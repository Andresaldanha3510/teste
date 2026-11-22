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

/**
 * Keeps one connection to the washer, polls its status and runs the
 * "extra spin" automation. All public methods are safe to call from the UI thread.
 */
object WasherController {

    enum class Conn { DISCONNECTED, CONNECTING, CONNECTED }

    @Volatile var conn = Conn.DISCONNECTED; private set
    @Volatile var status: WasherStatus? = null; private set
    @Volatile var device: SavedDevice? = null; private set
    val model: WasherModel? get() = device?.let { WasherModel.forType(it.type) }

    // Automation ("centrifugar de novo quando o ciclo terminar")
    @Volatile var autoEnabled = false; private set
    @Volatile var autoRepeatsLeft = 0; private set
    @Volatile var autoText = ""; private set
    @Volatile private var autoSpinTime = 0
    @Volatile private var autoSpinSpeed = 0
    @Volatile private var sawRunning = false
    @Volatile private var autoPending = false

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
        setAutomation(false, 0, 0, 0)
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
        // Whatever level the machine reports together with its minutes is valid.
        s.spinMinutes?.let { if (s.spinTimeValue in 1..15 && it in 1..120) learn(s.spinTimeValue, it) }
        onStatusForAutomation(s)
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

    private fun ensureReadyToStart(): WasherStatus {
        val m = model ?: throw Exception("Modelo desconhecido")
        if (connection == null) throw Exception("Sem conexão com a lavadora")
        var s = status
        if (s == null && !waitFor(5000) { true }) throw Exception("A máquina não respondeu ao pedido de status")
        s = status!!
        if (s.running) throw Exception("A máquina já está funcionando. Pause ou espere terminar.")
        if (!s.power) {
            send(m.powerBody(true))
            if (!waitFor(8000) { it.power }) throw Exception("A máquina não ligou pelo app")
            Thread.sleep(1500)
        }
        return status!!
    }

    private fun startAndConfirm(body: ByteArray, requestedLevel: Int): String {
        send(body)
        if (!waitFor(10_000) { it.running }) {
            return "Comando enviado, mas a máquina não começou. Confira se a porta/tampa está fechada e se o " +
                "controle remoto (Wi-Fi) está ativado no painel."
        }
        // Give the machine a moment to report the settings it actually applied.
        Thread.sleep(2000)
        val s = status ?: return "A máquina começou! ✔"
        val minutes = s.spinMinutes?.let { " = $it min" } ?: ""
        return if (s.spinTimeValue == requestedLevel) {
            s.spinMinutes?.let { learn(requestedLevel, it) }
            "A máquina começou! ✔ Centrifugação nível ${s.spinTimeValue}$minutes"
        } else {
            if (s.spinMinutes != null) learn(requestedLevel, -1)
            "A máquina começou, mas não aceitou o tempo $requestedLevel: ficou em ${s.spinTimeValue}$minutes."
        }
    }

    /** Remembers how many minutes a spin-time level gives on this machine (-1 = refused). */
    private fun learn(level: Int, minutes: Int) {
        val ctx = appContext ?: return
        val prefs = Prefs(ctx)
        val known = prefs.spinLevels.toMutableMap()
        if (known[level] == minutes) return
        known[level] = minutes
        prefs.spinLevels = known
        log(if (minutes < 0) "Aprendi: nível $level não é aceito" else "Aprendi: nível $level = $minutes min")
    }

    fun spinLevels(): Map<Int, Int> = appContext?.let { Prefs(it).spinLevels } ?: emptyMap()

    fun spinNow(spinTime: Int, spinSpeed: Int, done: (String) -> Unit) = runCommand(done) {
        val m = model ?: throw Exception("Modelo desconhecido")
        val s = ensureReadyToStart()
        log("Centrifugar: tempo=$spinTime velocidade=$spinSpeed")
        startAndConfirm(m.spinOnlyBody(s, spinTime, spinSpeed), spinTime)
    }

    fun startProgramBoosted(spinTime: Int, spinSpeed: Int, done: (String) -> Unit) = runCommand(done) {
        val m = model ?: throw Exception("Modelo desconhecido")
        val s = ensureReadyToStart()
        log("Programa ${s.programName} com centrifugação tempo=$spinTime velocidade=$spinSpeed")
        startAndConfirm(m.startCurrentProgramBody(s, spinTime, spinSpeed), spinTime)
    }

    fun power(on: Boolean, done: (String) -> Unit) = runCommand(done) {
        val m = model ?: throw Exception("Modelo desconhecido")
        send(m.powerBody(on))
        if (waitFor(8000) { it.power == on }) (if (on) "Ligada" else "Desligada") else "A máquina não respondeu"
    }

    fun pause(done: (String) -> Unit) = runCommand(done) {
        val m = model ?: throw Exception("Modelo desconhecido")
        send(m.pauseBody())
        if (waitFor(8000) { !it.running }) "Pausada" else "A máquina não respondeu"
    }

    fun refreshNow() = commands.execute { runCatching { model?.let { send(it.queryBody(), AaFrame.TYPE_QUERY) } } }

    // ---------------------------------------------------------------- automation

    fun setAutomation(enabled: Boolean, repeats: Int, spinTime: Int, spinSpeed: Int) {
        autoEnabled = enabled
        autoRepeatsLeft = if (enabled) repeats else 0
        autoSpinTime = spinTime
        autoSpinSpeed = spinSpeed
        autoPending = false
        sawRunning = status?.running == true
        autoText = if (enabled) {
            if (sawRunning) "Aguardando o ciclo atual terminar…" else "Aguardando um ciclo começar e terminar…"
        } else {
            ""
        }
        notifyListeners()
    }

    private fun onStatusForAutomation(s: WasherStatus) {
        if (!autoEnabled || autoRepeatsLeft <= 0 || autoPending) return
        if (s.running) {
            if (!sawRunning) {
                sawRunning = true
                autoText = "Ciclo em andamento. Centrifugação extra quando terminar (${autoRepeatsLeft}x restante)."
            }
            return
        }
        if (!sawRunning || s.paused) return
        // The cycle that was running has finished.
        sawRunning = false
        autoPending = true
        autoText = "Ciclo terminou. Centrifugação extra começa em 20 s…"
        log(autoText)
        appContext?.let { WasherService.alert(it, "Ciclo terminou", "Centrifugação extra começa em 20 s.") }
        commands.schedule({
            try {
                if (!autoEnabled) return@schedule
                val m = model ?: return@schedule
                val ready = ensureReadyToStart()
                val result = startAndConfirm(m.spinOnlyBody(ready, autoSpinTime, autoSpinSpeed), autoSpinTime)
                autoRepeatsLeft--
                autoText = if (autoRepeatsLeft > 0) {
                    "Centrifugação extra iniciada. Faltam $autoRepeatsLeft."
                } else {
                    "Última centrifugação extra iniciada."
                }
                log("Automação: $result")
                if (status?.running == true) sawRunning = true
                if (autoRepeatsLeft <= 0) autoEnabled = false
            } catch (e: Exception) {
                autoText = "Falhou ao iniciar a centrifugação extra: ${e.message}"
                log(autoText)
                appContext?.let { WasherService.alert(it, "Centrifugação extra falhou", e.message ?: "") }
            } finally {
                autoPending = false
                notifyListeners()
            }
        }, 20, TimeUnit.SECONDS)
    }
}
