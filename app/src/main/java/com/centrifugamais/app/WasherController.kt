package com.centrifugamais.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.centrifugamais.midea.AaFrame
import com.centrifugamais.midea.LanConnection
import com.centrifugamais.midea.WasherModel
import com.centrifugamais.midea.WasherStatus
import com.centrifugamais.midea.hexToBytes
import com.centrifugamais.midea.toHex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One cycle of a sequence: a program (or the spin-only cycle) with its settings. */
data class Step(
    val label: String,
    val spinOnly: Boolean,
    val program: Int = 0,
    val spinSpeed: Int? = null,
    val temperature: Int? = null,
)

/**
 * Keeps one connection to the washer, polls its status and runs sequences of
 * cycles ("lavar, depois centrifugar de novo…"). Public methods are safe to call
 * from the UI thread.
 */
object WasherController {

    enum class Conn { DISCONNECTED, CONNECTING, CONNECTED }

    @Volatile var conn = Conn.DISCONNECTED; private set
    @Volatile var status: WasherStatus? = null; private set
    @Volatile var device: SavedDevice? = null; private set
    val model: WasherModel? get() = device?.let { WasherModel.forType(it.type) }

    // Sequence: the steps still to run after the current cycle ends.
    private val pending = ArrayDeque<Step>()
    @Volatile var planTitle = ""; private set
    @Volatile var planText = ""; private set
    @Volatile private var sawRunning = false
    @Volatile private var stepScheduled = false
    val planActive: Boolean get() = synchronized(pending) { pending.isNotEmpty() } || stepScheduled

    private val logLines = ArrayDeque<String>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val commands = Executors.newSingleThreadScheduledExecutor()
    private var loopThread: Thread? = null
    @Volatile private var keepRunning = false
    @Volatile private var connection: LanConnection? = null
    @Volatile private var protocolVersion = 0
    @Volatile private var lastStatusAt = 0L
    private var lastRawBody = ""
    private var appContext: Context? = null

    // ---------------------------------------------------------------- listeners / log

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    private fun notifyListeners() {
        main.post {
            val copy = synchronized(listeners) { listeners.toList() }
            copy.forEach { it() }
            appContext?.let { WasherService.refresh(it) }
        }
    }

    fun log(msg: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + msg
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 300) logLines.removeFirst()
        }
        notifyListeners()
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    // ---------------------------------------------------------------- connection

    fun start(context: Context, dev: SavedDevice) {
        appContext = context.applicationContext
        if (keepRunning && device == dev) return
        stop(context, keepService = true)
        device = dev
        keepRunning = true
        context.startForegroundService(Intent(context, WasherService::class.java))
        loopThread = Thread({ connectionLoop(dev) }, "washer-connection").apply { isDaemon = true; start() }
    }

    fun stop(context: Context, keepService: Boolean = false) {
        keepRunning = false
        connection?.close()
        loopThread?.interrupt()
        loopThread = null
        connection = null
        conn = Conn.DISCONNECTED
        status = null
        device = null
        cancelPlan(silent = true)
        if (!keepService) context.stopService(Intent(context, WasherService::class.java))
        notifyListeners()
    }

    private fun connectionLoop(dev: SavedDevice) {
        val model = WasherModel.forType(dev.type) ?: return
        var failures = 0
        while (keepRunning) {
            var c: LanConnection? = null
            try {
                conn = Conn.CONNECTING
                notifyListeners()
                c = LanConnection(
                    dev.ip, dev.port, dev.id, dev.protocol,
                    dev.token.takeIf { it.isNotEmpty() }?.hexToBytes(),
                    dev.key.takeIf { it.isNotEmpty() }?.hexToBytes(),
                )
                c.connect()
                connection = c
                conn = Conn.CONNECTED
                failures = 0
                log("Conectado em ${dev.ip}:${dev.port}")
                c.sendFrame(AaFrame.queryAppliance(model.deviceType))
                var lastQuery = 0L
                var lastBeat = System.currentTimeMillis()
                while (keepRunning) {
                    val now = System.currentTimeMillis()
                    if (now - lastQuery >= 5000) {
                        c.sendFrame(AaFrame.build(model.deviceType, protocolVersion, AaFrame.TYPE_QUERY, model.queryBody()))
                        lastQuery = now
                    }
                    if (now - lastBeat >= 10_000) {
                        c.sendHeartbeat()
                        lastBeat = now
                    }
                    for (frame in c.readFrames()) handleFrame(model, frame)
                    if (lastStatusAt > 0 && now - lastStatusAt > 60_000) {
                        throw Exception("Sem resposta da máquina há 1 minuto")
                    }
                }
            } catch (e: Exception) {
                if (!keepRunning) break
                failures++
                log("Conexão perdida: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                c?.close()
                connection = null
                lastStatusAt = 0
            }
            if (!keepRunning) break
            conn = Conn.DISCONNECTED
            notifyListeners()
            try {
                Thread.sleep(minOf(5_000L * failures, 30_000L))
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun handleFrame(model: WasherModel, frame: ByteArray) {
        val p = AaFrame.parse(frame) ?: return
        if (p.messageType == AaFrame.TYPE_QUERY_APPLIANCE) {
            protocolVersion = p.protocolVersion
            log("Versão do protocolo da máquina: $protocolVersion")
            return
        }
        if (p.deviceType != model.deviceType || p.body.isEmpty()) return
        val isStatus = p.messageType == AaFrame.TYPE_QUERY || p.messageType == AaFrame.TYPE_SET ||
            (p.messageType == AaFrame.TYPE_NOTIFY1 && p.body[0].toInt() == 0x04)
        if (!isStatus) return
        val s = model.parse(p.body) ?: return
        lastStatusAt = System.currentTimeMillis()
        val hex = p.body.toHex()
        if (hex != lastRawBody) {
            lastRawBody = hex
            log("Status: ${s.statusText} · ${s.programName} · ${s.stageName} · raw=$hex")
        }
        status = s
        // Any program the machine reports while on exists on this machine.
        if (s.power) appContext?.let { Programs.markSeen(it, s.programCode) }
        onStatusForPlan(s)
        notifyListeners()
    }

    // ---------------------------------------------------------------- commands

    private fun send(body: ByteArray, type: Int = AaFrame.TYPE_SET) {
        val c = connection ?: throw Exception("Sem conexão com a lavadora")
        val m = model ?: throw Exception("Modelo desconhecido")
        val frame = AaFrame.build(m.deviceType, protocolVersion, type, body)
        log("Enviando: ${frame.toHex()}")
        c.sendFrame(frame)
    }

    private fun waitFor(timeoutMs: Long, cond: (WasherStatus) -> Boolean): Boolean {
        val m = model ?: return false
        val end = System.currentTimeMillis() + timeoutMs
        var lastQuery = 0L
        while (System.currentTimeMillis() < end) {
            status?.let { if (cond(it)) return true }
            if (System.currentTimeMillis() - lastQuery > 1500) {
                runCatching { send(m.queryBody(), AaFrame.TYPE_QUERY) }
                lastQuery = System.currentTimeMillis()
            }
            Thread.sleep(300)
        }
        return status?.let(cond) ?: false
    }

    /** Runs [block] on the command thread and reports the result message on the UI thread. */
    private fun runCommand(done: (String) -> Unit, block: () -> String) {
        commands.execute {
            val msg = try {
                block()
            } catch (e: Exception) {
                "Erro: ${e.message ?: e.javaClass.simpleName}"
            }
            log(msg)
            main.post { done(msg) }
        }
    }

    private fun logCurrentStatus() {
        val s = status ?: return log("Sem status da máquina")
        log("Status atual: ${s.statusText} · ${s.programName} · raw=${s.rawBody.toHex()}")
    }

    private fun ensureReadyToStart(): WasherStatus {
        val m = model ?: throw Exception("Modelo desconhecido")
        if (connection == null) throw Exception("Sem conexão com a lavadora")
        if (status == null && !waitFor(5000) { true }) throw Exception("A máquina não respondeu ao pedido de status")
        val s = status!!
        if (s.running) throw Exception("A máquina já está funcionando. Pause ou espere terminar.")
        if (s.paused) {
            throw Exception(
                "A máquina está pausada. Pausada, ela só retoma o ciclo antigo e ignora configurações " +
                    "novas. Toque em Desligar (ou cancele no painel) e tente de novo.",
            )
        }
        if (!s.power) {
            send(m.powerBody(true))
            if (!waitFor(10_000) { it.power }) throw Exception("A máquina não ligou pelo app")
            Thread.sleep(1500)
        }
        return status!!
    }

    /** Starts one step and waits for confirmation. Throws if the machine did not start. */
    private fun startStep(step: Step): String {
        val m = model ?: throw Exception("Modelo desconhecido")
        val ready = ensureReadyToStart()
        val program = if (step.spinOnly) m.spinOnlyProgram else step.program
        val body = if (step.spinOnly) {
            m.spinOnlyBody(ready, step.spinSpeed ?: m.spinSpeeds.last().code)
        } else {
            m.programBody(ready, step.program, step.spinSpeed, step.temperature)
        }
        log("Iniciando: ${step.label}")
        send(body)
        // The machine takes ~10 s to report that it started (seen on a real 0xDB).
        if (!waitFor(25_000) { it.running }) {
            logCurrentStatus()
            program?.let { p ->
                if (status?.programCode != p) appContext?.let { Programs.markFailed(it, p) }
            }
            throw Exception(
                "A máquina não começou \"${step.label}\". Confira a porta, o controle remoto (Wi-Fi) no " +
                    "painel e se este programa existe na sua máquina.",
            )
        }
        Thread.sleep(2000)
        val s = status
        if (program != null && s != null && s.programCode != program) {
            appContext?.let { Programs.markFailed(it, program) }
            return "A máquina começou, mas com o programa ${s.programName} em vez de ${step.label}."
        }
        program?.let { p -> appContext?.let { Programs.markWorked(it, p) } }
        val minutes = s?.timeRemainingMin?.let { " (≈ $it min)" } ?: ""
        return "Começou: ${step.label}$minutes ✔"
    }

    /** Starts [steps] now: the first one immediately, the rest each time a cycle ends. */
    fun runSequence(title: String, steps: List<Step>, done: (String) -> Unit) = runCommand(done) {
        require(steps.isNotEmpty())
        cancelPlan(silent = true)
        val result = startStep(steps.first())
        if (steps.size > 1) {
            synchronized(pending) { pending.addAll(steps.drop(1)) }
            planTitle = title
            sawRunning = true
            updatePlanText("Em andamento: ${steps.first().label}.")
        }
        result
    }

    /** Queues [steps] to run after the cycle that is running now (or the next one) ends. */
    fun followCurrentCycle(title: String, steps: List<Step>) {
        cancelPlan(silent = true)
        synchronized(pending) { pending.addAll(steps) }
        planTitle = title
        sawRunning = status?.running == true
        updatePlanText(if (sawRunning) "Aguardando o ciclo atual terminar." else "Aguardando um ciclo começar e terminar.")
        log("Sequência \"$title\" armada")
    }

    fun cancelPlan(silent: Boolean = false) {
        val had = planActive
        synchronized(pending) { pending.clear() }
        stepScheduled = false
        planTitle = ""
        planText = ""
        if (had && !silent) log("Sequência cancelada")
        notifyListeners()
    }

    private fun updatePlanText(now: String) {
        val next = synchronized(pending) { pending.toList() }
        planText = if (next.isEmpty()) now else "$now\nDepois: " + next.joinToString(" → ") { it.label }
        notifyListeners()
    }

    private fun onStatusForPlan(s: WasherStatus) {
        if (stepScheduled || synchronized(pending) { pending.isEmpty() }) return
        if (s.running) {
            sawRunning = true
            return
        }
        if (!sawRunning || s.paused) return
        // The cycle that was running has finished: start the next step in 20 s.
        sawRunning = false
        stepScheduled = true
        val next = synchronized(pending) { pending.first() }
        updatePlanText("Ciclo terminou. \"${next.label}\" começa em 20 s.")
        log("Ciclo terminou; próxima etapa: ${next.label}")
        appContext?.let { WasherService.alert(it, "Ciclo terminou", "\"${next.label}\" começa em 20 s.") }
        commands.schedule({
            try {
                if (!stepScheduled) return@schedule
                val step = synchronized(pending) { pending.removeFirstOrNull() } ?: return@schedule
                val result = startStep(step)
                log("Sequência: $result")
                sawRunning = status?.running == true
                stepScheduled = false
                if (synchronized(pending) { pending.isEmpty() }) {
                    planText = "Última etapa em andamento: ${step.label}."
                    planTitle = ""
                    notifyListeners()
                } else {
                    updatePlanText("Em andamento: ${step.label}.")
                }
            } catch (e: Exception) {
                log("Sequência interrompida: ${e.message}")
                appContext?.let { WasherService.alert(it, "Sequência interrompida", e.message ?: "") }
                synchronized(pending) { pending.clear() }
                stepScheduled = false
                planTitle = ""
                planText = "Sequência interrompida: ${e.message}"
                notifyListeners()
            }
        }, 20, TimeUnit.SECONDS)
    }

    fun power(on: Boolean, done: (String) -> Unit) = runCommand(done) {
        val m = model ?: throw Exception("Modelo desconhecido")
        if (!on) cancelPlan()
        send(m.powerBody(on))
        if (waitFor(10_000) { it.power == on }) {
            if (on) "Ligada" else "Desligada"
        } else {
            logCurrentStatus()
            "A máquina não obedeceu"
        }
    }

    fun pause(done: (String) -> Unit) = runCommand(done) {
        val m = model ?: throw Exception("Modelo desconhecido")
        send(m.pauseBody())
        if (waitFor(10_000) { !it.running }) {
            "Pausada"
        } else {
            logCurrentStatus()
            "A máquina continuou funcionando (ela recusou a pausa remota)"
        }
    }
}
