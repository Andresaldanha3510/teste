package com.centrifugamais.midea

/** Status decoded from a 0xDA / 0xDB status frame body (body[0] is the body type). */
data class WasherStatus(
    val power: Boolean,
    val running: Boolean,
    val paused: Boolean,
    val statusText: String,
    val finished: Boolean,
    val programCode: Int,
    val programName: String,
    val stageName: String,
    val timeRemainingMin: Int?,
    val spinSpeedCode: Int,
    val spinSpeedName: String,
    val spinTimeValue: Int,
    /** Spin duration in minutes as reported by the machine, when it reports it. */
    val spinMinutes: Int?,
    /** Total cycle time remaining is in [timeRemainingMin]; this is the wash part only. */
    val washMinutes: Int?,
    val errorCode: Int,
    val rawBody: ByteArray,
)

data class Option(val code: Int, val label: String)

/**
 * Command builder / status parser for one washer family. Bodies returned here are
 * wrapped with [AaFrame.build] using [deviceType].
 */
sealed class WasherModel(val deviceType: Int) {
    abstract val familyName: String
    abstract val spinSpeeds: List<Option>
    abstract val maxSpinTime: Int
    abstract val spinTimeUnit: String
    abstract val defaultSpinTime: Int
    /** True when the machine has a dedicated spin-only program. */
    abstract val hasSpinOnlyProgram: Boolean

    fun queryBody(): ByteArray = bytesOf(0x03)
    abstract fun powerBody(on: Boolean): ByteArray
    fun pauseBody(): ByteArray = bytesOf(0x02, 0xFF, 0x00)
    abstract fun parse(body: ByteArray): WasherStatus?

    /** Starts the program currently selected on the machine, overriding spin settings. */
    abstract fun startCurrentProgramBody(status: WasherStatus, spinTime: Int, spinSpeed: Int): ByteArray

    /** Starts a spin-only cycle. */
    abstract fun spinOnlyBody(status: WasherStatus, spinTime: Int, spinSpeed: Int): ByteArray

    companion object {
        const val TYPE_TOP_LOAD = 0xDA
        const val TYPE_FRONT_LOAD = 0xDB

        fun forType(type: Int): WasherModel? = when (type) {
            TYPE_TOP_LOAD -> TopLoadWasher
            TYPE_FRONT_LOAD -> FrontLoadWasher
            else -> null
        }
    }
}

private fun ByteArray.at(i: Int): Int = if (i < size) u(i) else 0

private fun stageFromBits(bits: Int, firstBit: Int, lastBit: Int, offset: Int): Int {
    for (i in firstBit..lastBit) if (bits and (1 shl i) != 0) return i + offset
    return 0
}

/** Lavadora de abertura superior (tipo 0xDA). */
object TopLoadWasher : WasherModel(TYPE_TOP_LOAD) {
    override val familyName = "Abertura superior (0xDA)"
    override val spinSpeeds = listOf(Option(1, "Baixa"), Option(2, "Média"), Option(3, "Alta"))
    override val maxSpinTime = 15
    override val spinTimeUnit = "min"
    override val defaultSpinTime = 9
    override val hasSpinOnlyProgram = false

    private val programs = listOf(
        "Normal", "Rápido", "Edredom", "Lã", "Enxágue", "Memória", "Infantil", "Jaqueta",
        "Mexer", "Silencioso", "Limpeza do cesto", "Secagem a ar",
    )
    private val stages = listOf("Parada", "Centrifugando", "Enxaguando", "Lavando", "Pesando", "—", "Secando", "De molho")
    private val speeds = listOf("—", "Baixa", "Média", "Alta")

    override fun powerBody(on: Boolean) = bytesOf(0x02, if (on) 1 else 0, 0xFF)

    override fun parse(body: ByteArray): WasherStatus? {
        if (body.size < 19) return null
        val power = body.at(1) > 0
        val running = body.at(2) == 2 || body.at(2) == 6
        val program = body.at(4)
        val speed = (body.at(6) and 0xF0) shr 4
        val stage = stageFromBits(body.at(16), 1, 6, 0)
        return WasherStatus(
            power = power,
            running = running,
            paused = body.at(2) == 3,
            statusText = when {
                !power -> "Desligada"
                running -> "Funcionando"
                body.at(2) == 3 -> "Pausada"
                else -> "Ligada, parada"
            },
            finished = power && !running && stage == 0,
            programCode = program,
            programName = programs.getOrElse(program) { "Programa $program" },
            stageName = stages.getOrElse(stage) { "—" },
            timeRemainingMin = if (power) body.at(17) + body.at(18) * 60 else null,
            spinSpeedCode = speed,
            spinSpeedName = speeds.getOrElse(speed) { "$speed" },
            spinTimeValue = (body.at(10) and 0xF0) shr 4,
            spinMinutes = null,
            washMinutes = null,
            errorCode = body.at(24),
            rawBody = body,
        )
    }

    /** Washing data block echoed from the current status, as midea-local does. */
    private fun washingData(status: WasherStatus): ByteArray {
        val b = status.rawBody
        val d = ByteArray(12) { 0xFF.toByte() }
        d[1] = b.at(4).toByte()
        d[2] = b.at(5).toByte()
        d[3] = b.at(6).toByte()
        d[5] = b.at(8).toByte()
        d[6] = b.at(9).toByte()
        d[7] = b.at(10).toByte()
        d[9] = b.at(12).toByte()
        return d
    }

    private fun withSpin(d: ByteArray, spinTime: Int, spinSpeed: Int) {
        d[3] = (((spinSpeed and 0x0F) shl 4) or (d[3].toInt() and 0x0F)).toByte()
        d[7] = (((spinTime and 0x0F) shl 4) or (d[7].toInt() and 0x0F)).toByte()
    }

    override fun startCurrentProgramBody(status: WasherStatus, spinTime: Int, spinSpeed: Int): ByteArray {
        val d = washingData(status)
        withSpin(d, spinTime, spinSpeed)
        return bytesOf(0x02, 0xFF, 0x01) + d
    }

    override fun spinOnlyBody(status: WasherStatus, spinTime: Int, spinSpeed: Int): ByteArray {
        val d = washingData(status)
        withSpin(d, spinTime, spinSpeed)
        d[6] = 0 // wash time
        d[7] = (d[7].toInt() and 0xF0).toByte() // rinse count = 0
        d[9] = 0 // soak time
        return bytesOf(0x02, 0xFF, 0x01) + d
    }
}

/** Lavadora / lava e seca de abertura frontal (tipo 0xDB). */
object FrontLoadWasher : WasherModel(TYPE_FRONT_LOAD) {
    override val familyName = "Abertura frontal (0xDB)"
    override val spinSpeeds = listOf(
        Option(1, "400 rpm"), Option(2, "600 rpm"), Option(3, "800 rpm"), Option(4, "1000 rpm"),
        Option(5, "1200 rpm"), Option(6, "1400 rpm"),
    )
    // The spin-time field is a level, not minutes (confirmed on a real machine:
    // level 2 = 10 min). The machine reports the real minutes in body[28].
    override val maxSpinTime = 9
    override val spinTimeUnit = "nível"
    override val defaultSpinTime = 2
    override val hasSpinOnlyProgram = true

    private const val PROGRAM_SPIN_ONLY = 0x09

    private val programs = mapOf(
        0x00 to "Algodão", 0x01 to "Eco", 0x02 to "Rápido", 0x03 to "Misto", 0x04 to "Sintéticos",
        0x05 to "Lã", 0x09 to "Só centrifugar", 0x0A to "Enxágue + centrifugação", 0x0B to "Volumosos",
        0x0C to "Bebê", 0x0F to "Edredom/jaqueta", 0x10 to "Cores", 0x11 to "Inteligente",
        0x12 to "Rápido", 0x13 to "Infantil", 0x1C to "Camisas", 0x26 to "Limpeza do tambor",
        0x36 to "Jeans", 0x3F to "Toalhas", 0x66 to "Padrão", 0x73 to "Volumosos",
    )
    private val statuses = listOf("Desligada", "Em espera", "Funcionando", "Pausada", "Terminou", "Falha", "Início agendado")
    private val stages = listOf("Parada", "Centrifugando", "Enxaguando", "Lavando", "Pré-lavagem", "Secando", "Pesando", "Centrifugação alta", "—")
    private val speedNames = mapOf(0 to "Sem centrifugar", 1 to "400", 2 to "600", 3 to "800", 4 to "1000", 5 to "1200", 6 to "1400", 7 to "1600", 8 to "1300")

    override fun powerBody(on: Boolean): ByteArray = bytesOf(0x02, if (on) 1 else 0) + ByteArray(20) { 0xFF.toByte() }

    override fun parse(body: ByteArray): WasherStatus? {
        if (body.size < 19) return null
        val power = body.at(1) > 0
        val state = body.at(2)
        val running = state == 2 || state == 6
        val program = body.at(4)
        val speed = body.at(8)
        val stage = stageFromBits(body.at(16), 0, 6, 1)
        return WasherStatus(
            power = power,
            running = running,
            paused = state == 3,
            statusText = if (!power) "Desligada" else statuses.getOrElse(state) { "Estado $state" },
            finished = power && state == 4,
            programCode = program,
            programName = programs[program] ?: "Programa 0x%02X".format(program),
            stageName = stages.getOrElse(stage) { "—" },
            timeRemainingMin = if (power) body.at(17) + (body.at(18) shl 8) else null,
            spinSpeedCode = speed,
            spinSpeedName = speedNames[speed]?.let { if (it.all(Char::isDigit)) "$it rpm" else it } ?: "padrão",
            spinTimeValue = body.at(10),
            spinMinutes = if (body.size > 28 && body.u(28) != 0xFF) body.u(28) else null,
            washMinutes = if (body.size > 27 && body.u(27) != 0xFF) body.u(27) else null,
            errorCode = 0,
            rawBody = body,
        )
    }

    private fun washingData(status: WasherStatus): ByteArray {
        val b = status.rawBody
        val d = ByteArray(13) { 0xFF.toByte() }
        d[0] = b.at(3).toByte() // mode
        d[1] = b.at(4).toByte() // program
        d[2] = b.at(5).toByte() // water level
        d[4] = b.at(7).toByte() // temperature
        d[5] = b.at(8).toByte() // spin speed
        d[6] = b.at(9).toByte() // wash time
        d[7] = b.at(10).toByte() // spin time
        d[8] = b.at(11).toByte() // detergent
        d[9] = b.at(12).toByte() // softener
        return d
    }

    override fun startCurrentProgramBody(status: WasherStatus, spinTime: Int, spinSpeed: Int): ByteArray {
        val d = washingData(status)
        d[5] = spinSpeed.toByte()
        d[7] = spinTime.toByte()
        return bytesOf(0x02, 0xFF, 0x01) + d
    }

    override fun spinOnlyBody(status: WasherStatus, spinTime: Int, spinSpeed: Int): ByteArray {
        val d = ByteArray(13) { 0xFF.toByte() }
        d[0] = 0x00 // normal mode
        d[1] = PROGRAM_SPIN_ONLY.toByte()
        d[5] = spinSpeed.toByte()
        d[7] = spinTime.toByte()
        return bytesOf(0x02, 0xFF, 0x01) + d
    }
}
