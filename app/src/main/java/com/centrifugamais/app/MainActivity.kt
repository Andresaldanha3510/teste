package com.centrifugamais.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.centrifugamais.midea.FrontLoadWasher
import com.centrifugamais.midea.WasherModel

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val listener: () -> Unit = { renderStatus() }

    // Control screen views (null while on the setup screen)
    private var connText: TextView? = null
    private var statusText: TextView? = null
    private var planText: TextView? = null
    private var cancelPlanButton: Button? = null
    private var programsBox: LinearLayout? = null
    private var programsSignature = ""
    private var logText: TextView? = null

    private var selectedSpeed = 0
    private var followRepeats = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val dev = prefs.device
        if (dev == null || WasherModel.forType(dev.type) == null) showSetup() else showControl(dev)
    }

    override fun onResume() {
        super.onResume()
        WasherController.addListener(listener)
        renderStatus()
    }

    override fun onPause() {
        WasherController.removeListener(listener)
        super.onPause()
    }

    // ------------------------------------------------------------------ UI helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun page(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F2F4F8"))
            addView(root)
        })
        return root
    }

    private fun LinearLayout.card(title: String? = null): LinearLayout {
        val c = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
            }
            elevation = dp(2).toFloat()
        }
        addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
        if (title != null) c.text(title, 17f, bold = true)
        return c
    }

    private fun LinearLayout.text(s: String, size: Float = 15f, bold: Boolean = false, color: String = "#222222"): TextView {
        val t = TextView(this@MainActivity).apply {
            text = s
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }
        addView(t)
        return t
    }

    private fun LinearLayout.button(label: String, primary: Boolean = true, onClick: () -> Unit): Button {
        val b = Button(this@MainActivity).apply {
            text = label
            isAllCaps = false
            if (primary) {
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1565C0"))
                    cornerRadius = dp(10).toFloat()
                }
            }
            setOnClickListener { onClick() }
        }
        addView(b, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        return b
    }

    private fun LinearLayout.input(hint: String, type: Int, value: String = ""): EditText {
        val e = EditText(this@MainActivity).apply {
            this.hint = hint
            inputType = type
            setText(value)
        }
        addView(e, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return e
    }

    private fun LinearLayout.spinner(items: List<String>, selected: Int, onSelect: (Int) -> Unit): Spinner {
        val sp = Spinner(this@MainActivity)
        sp.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        sp.setSelection(selected.coerceIn(0, items.size - 1))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onSelect(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        addView(sp, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return sp
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ setup screen

    private fun showSetup() {
        connText = null; statusText = null; planText = null; cancelPlanButton = null
        programsBox = null; programsSignature = ""; logText = null
        val root = page()
        root.text("Centrifuga+", 26f, bold = true, color = "#1565C0")
        root.text("Controle a centrifugação da sua lavadora Midea pelo Wi-Fi.", 15f, color = "#555555")

        val c = root.card("1. Sua conta SmartHome")
        c.text(
            "Use o e-mail e a senha do app SmartHome. Se você entra com Google, crie uma senha na conta " +
                "(Perfil → Conta) ou crie uma conta nova com e-mail e compartilhe a lavadora com ela.",
            13f, color = "#666666",
        )
        val email = c.input("E-mail", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, prefs.email)
        val password = c.input("Senha", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        val c2 = root.card("2. Rede")
        c2.text("O celular precisa estar no mesmo Wi-Fi da lavadora.", 13f, color = "#666666")
        val ip = c2.input("IP da lavadora (opcional)", InputType.TYPE_CLASS_TEXT)

        val progress = root.text("", 14f, color = "#1565C0")
        lateinit var go: Button
        go = root.button("Conectar") {
            val e = email.text.toString().trim()
            val pw = password.text.toString()
            if (e.isEmpty() || pw.isEmpty()) {
                toast("Preencha e-mail e senha")
                return@button
            }
            prefs.email = e
            go.isEnabled = false
            progress.text = "Começando…"
            Thread {
                try {
                    val dev = Setup.run(this, e, pw, ip.text.toString().ifBlank { null }) { msg ->
                        runOnUiThread { progress.text = msg }
                    }
                    prefs.device = dev
                    runOnUiThread { showControl(dev) }
                } catch (ex: Exception) {
                    runOnUiThread {
                        go.isEnabled = true
                        progress.text = "❌ " + (ex.message ?: ex.javaClass.simpleName)
                    }
                }
            }.start()
        }
    }

    // ------------------------------------------------------------------ control screen

    private fun showControl(dev: SavedDevice) {
        val model = WasherModel.forType(dev.type) ?: return showSetup()
        WasherController.start(this, dev)
        if (selectedSpeed == 0) {
            selectedSpeed = prefs.spinSpeed.takeIf { s -> model.spinSpeeds.any { it.code == s } }
                ?: model.spinSpeeds.last().code
        }

        val root = page()
        root.text(dev.name, 24f, bold = true, color = "#1565C0")
        root.text(model.familyName + " · " + dev.ip, 13f, color = "#666666")
        connText = root.text("", 14f)

        // ---- status + running sequence
        val st = root.card("Agora")
        statusText = st.text("Aguardando status…", 15f)
        planText = st.text("", 14f, color = "#1565C0")
        cancelPlanButton = st.button("Cancelar sequência", primary = false) { WasherController.cancelPlan() }

        // ---- spin / rinse specials
        val sp = root.card("Centrifugar e enxaguar")
        sp.text("Velocidade da centrifugação", 13f, color = "#666666")
        sp.spinner(model.spinSpeeds.map { it.label }, model.spinSpeeds.indexOfFirst { it.code == selectedSpeed }) {
            selectedSpeed = model.spinSpeeds[it].code
            prefs.spinSpeed = selectedSpeed
        }
        val spinLabel = if (model.hasSpinOnlyProgram) "Centrifugar" else "Centrifugar (experimental)"
        fun spin(n: Int) = List(n) { i -> Step(if (n == 1) spinLabel else "$spinLabel ${i + 1}/$n", spinOnly = true, spinSpeed = selectedSpeed) }
        sp.button(spinLabel) { askStart(spinLabel, spin(1)) }
        sp.button("$spinLabel 2 vezes") { askStart("$spinLabel 2 vezes", spin(2)) }
        sp.button("$spinLabel 3 vezes") { askStart("$spinLabel 3 vezes", spin(3)) }
        model.rinseSpinProgram?.let { rinse ->
            val rinseStep = { Step("Enxágue + centrifugação", spinOnly = false, program = rinse, spinSpeed = selectedSpeed) }
            sp.button("Enxágue + centrifugação") { askStart("Enxágue + centrifugação", listOf(rinseStep())) }
            sp.button("Enxágue + centrifugação + centrifugar de novo") {
                askStart("Enxágue + centrifugação + centrifugar de novo", listOf(rinseStep()) + spin(1).map { it.copy(label = "Centrifugação extra") })
            }
            sp.text(
                "Para o enxágue com amaciante, coloque amaciante no compartimento antes de começar.",
                12f, color = "#666666",
            )
        }
        if (model == FrontLoadWasher) {
            sp.text("Nesta máquina cada centrifugação dura cerca de 10 min (tempo fixo do programa).", 12f, color = "#666666")
        }

        // ---- program catalog
        val pc = root.card("Programas")
        pc.text(
            "✓ = já visto na sua máquina · ✗ = a máquina recusou. Com o app aberto, gire o seletor do painel " +
                "pelos programas para o app aprender quais existem.",
            12f, color = "#666666",
        )
        programsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pc.addView(programsBox)
        renderPrograms(model)

        // ---- extra spins after a cycle started on the panel
        val fc = root.card("Ciclo iniciado no painel")
        fc.text("Quando o ciclo que você iniciou no painel terminar, centrifugar mais:", 13f, color = "#666666")
        fc.spinner(listOf("1 vez", "2 vezes", "3 vezes"), followRepeats - 1) { followRepeats = it + 1 }
        fc.button("Armar centrifugação extra", primary = false) {
            val steps = List(followRepeats) { i -> Step("Centrifugação extra ${i + 1}/$followRepeats", spinOnly = true, spinSpeed = selectedSpeed) }
            WasherController.followCurrentCycle("Centrifugação extra", steps)
            toast("Armado: o app centrifuga quando o ciclo terminar")
        }

        // ---- basic controls
        val ctl = root.card("Controles")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun rowButton(label: String, action: () -> Unit) {
            row.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        rowButton("Ligar") { WasherController.power(true) { toast(it) } }
        rowButton("Pausar") { WasherController.pause { toast(it) } }
        rowButton("Desligar") { confirm("Desligar a máquina?", "O ciclo atual e a sequência serão interrompidos.") { WasherController.power(false) { toast(it) } } }
        ctl.addView(row)
        ctl.text(
            "Antes de iniciar pelo app, ative o controle remoto (Wi-Fi) no painel da máquina.",
            12f, color = "#666666",
        )

        // ---- log
        val lg = root.card("Registro técnico")
        lg.text("Se algo não funcionar, copie isto e me mande.", 12f, color = "#666666")
        logText = lg.text("", 11f, color = "#444444").apply { typeface = Typeface.MONOSPACE }
        lg.button("Copiar registro", primary = false) {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("registro", WasherController.logText()))
            toast("Registro copiado")
        }
        lg.button("Esquecer esta lavadora", primary = false) {
            confirm("Esquecer a lavadora?", "Você vai precisar entrar de novo com a conta SmartHome.") {
                WasherController.stop(this)
                prefs.device = null
                showSetup()
            }
        }
        lg.button("Desconectar e fechar", primary = false) {
            WasherController.stop(this)
            finish()
        }
        renderStatus()
    }

    /** Program grid, two per row; machine-confirmed programs first. Rebuilt only when what we know changes. */
    private fun renderPrograms(model: WasherModel) {
        val box = programsBox ?: return
        val seen = Programs.seen(this) + Programs.worked(this)
        val failed = Programs.failed(this)
        val signature = "$seen|$failed"
        if (signature == programsSignature) return
        programsSignature = signature

        val catalog = model.programs.toMutableList()
        // Programs reported by the machine that are not in the catalog.
        seen.filter { code -> catalog.none { it.code == code } && code != model.spinOnlyProgram && code != model.rinseSpinProgram }
            .forEach { catalog.add(com.centrifugamais.midea.Option(it, model.programName(it))) }
        val ordered = catalog.sortedBy { if (it.code in seen) 0 else if (it.code in failed) 2 else 1 }

        box.removeAllViews()
        ordered.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { opt ->
                val mark = when (opt.code) { in seen -> " ✓"; in failed -> " ✗"; else -> "" }
                row.addView(Button(this).apply {
                    text = opt.label + mark
                    isAllCaps = false
                    textSize = 14f
                    alpha = if (opt.code in failed) 0.5f else 1f
                    setOnClickListener { showProgramDialog(model, opt.code, opt.label) }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            box.addView(row)
        }
    }

    private fun showProgramDialog(model: WasherModel, program: Int, name: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        var temp: Int? = null
        if (model.temperatures.isNotEmpty()) {
            box.text("Temperatura", 13f, color = "#666666")
            box.spinner(listOf("Padrão do programa") + model.temperatures.map { it.label }, 0) {
                temp = if (it == 0) null else model.temperatures[it - 1].code
            }
        }
        var speed: Int? = null
        box.text("Velocidade da centrifugação", 13f, color = "#666666")
        box.spinner(listOf("Padrão do programa") + model.spinSpeeds.map { it.label }, 0) {
            speed = if (it == 0) null else model.spinSpeeds[it - 1].code
        }
        var extra = 0
        box.text("Centrifugação extra no final", 13f, color = "#666666")
        box.spinner(listOf("Nenhuma", "+1 centrifugação", "+2 centrifugações"), 0) { extra = it }

        AlertDialog.Builder(this)
            .setTitle(name)
            .setView(box)
            .setPositiveButton("Iniciar") { _, _ ->
                val steps = listOf(Step(name, spinOnly = false, program = program, spinSpeed = speed, temperature = temp)) +
                    List(extra) { i -> Step("Centrifugação extra ${i + 1}/$extra", spinOnly = true, spinSpeed = selectedSpeed) }
                val title = if (extra > 0) "$name + $extra centrifugação(ões)" else name
                WasherController.runSequence(title, steps) { toast(it) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun askStart(title: String, steps: List<Step>) {
        val desc = steps.joinToString(" → ") { it.label }
        confirm("$title?", "A máquina vai ligar e fazer: $desc.") {
            WasherController.runSequence(title, steps) { toast(it) }
        }
    }

    private fun confirm(title: String, msg: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Sim") { _, _ -> action() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun renderStatus() {
        val c = WasherController
        connText?.text = when (c.conn) {
            WasherController.Conn.CONNECTED -> "🟢 Conectado"
            WasherController.Conn.CONNECTING -> "🟡 Conectando…"
            WasherController.Conn.DISCONNECTED -> "🔴 Desconectado — tentando de novo"
        }
        val s = c.status
        statusText?.text = if (s == null) {
            "Aguardando status…"
        } else {
            buildString {
                appendLine("Estado: ${s.statusText}")
                appendLine("Programa: ${s.programName}")
                appendLine("Etapa: ${s.stageName}")
                if (s.running && s.timeRemainingMin != null) appendLine("Tempo restante: ${s.timeRemainingMin} min")
                val minutes = s.spinMinutes?.let { ", $it min" } ?: ""
                appendLine("Centrifugação: ${s.spinSpeedName}$minutes")
                if (s.errorCode != 0) appendLine("Código de erro: ${s.errorCode}")
            }.trimEnd()
        }
        val title = c.planTitle
        planText?.text = when {
            c.planText.isEmpty() -> ""
            title.isEmpty() -> c.planText
            else -> "Sequência \"$title\"\n${c.planText}"
        }
        cancelPlanButton?.visibility = if (c.planActive) View.VISIBLE else View.GONE
        logText?.text = c.logText().lines().takeLast(25).joinToString("\n")
        c.model?.let { renderPrograms(it) }
    }
}
