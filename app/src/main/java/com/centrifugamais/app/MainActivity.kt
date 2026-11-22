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
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.centrifugamais.midea.WasherModel

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val listener: () -> Unit = { renderStatus() }

    // Control screen views (null while on the setup screen)
    private var connText: TextView? = null
    private var statusText: TextView? = null
    private var autoStatusText: TextView? = null
    private var autoSwitch: Switch? = null
    private var logText: TextView? = null
    private var timeLabel: TextView? = null

    private var selectedSpeed = 0
    private var selectedTime = 0
    private var selectedRepeats = 1

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
        connText = null; statusText = null; autoStatusText = null; autoSwitch = null; logText = null
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

        val root = page()
        root.text(dev.name, 24f, bold = true, color = "#1565C0")
        root.text(model.familyName + " · " + dev.ip, 13f, color = "#666666")
        connText = root.text("", 14f)

        val st = root.card("Agora")
        statusText = st.text("Aguardando status…", 15f)

        // ---- spin settings
        val spin = root.card("Centrifugação")
        if (selectedSpeed == 0) selectedSpeed = prefs.spinSpeed.takeIf { s -> model.spinSpeeds.any { it.code == s } }
            ?: model.spinSpeeds.last().code
        if (selectedTime <= 0) selectedTime = prefs.spinTime.takeIf { it in 1..model.maxSpinTime }
            ?: model.defaultSpinTime
        spin.text("Velocidade", 13f, color = "#666666")
        spin.spinner(model.spinSpeeds.map { it.label }, model.spinSpeeds.indexOfFirst { it.code == selectedSpeed }) {
            selectedSpeed = model.spinSpeeds[it].code
            prefs.spinSpeed = selectedSpeed
        }
        timeLabel = spin.text("", 13f, color = "#666666")
        val seek = SeekBar(this).apply {
            max = model.maxSpinTime - 1
            progress = selectedTime - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    selectedTime = p + 1
                    prefs.spinTime = selectedTime
                    updateTimeLabel(model)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        spin.addView(seek, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        updateTimeLabel(model)

        spin.button("Centrifugar agora" + if (model.hasSpinOnlyProgram) "" else " (experimental)") {
            confirm("Centrifugar agora?", "A máquina vai ligar e centrifugar: ${describeTime(model)}.") {
                WasherController.spinNow(selectedTime, selectedSpeed) { toast(it) }
            }
        }
        spin.button("Iniciar o programa escolhido no painel com esta centrifugação", primary = false) {
            val prog = WasherController.status?.programName ?: "atual"
            confirm("Iniciar programa $prog?", "A lavagem completa começa com centrifugação: ${describeTime(model)}.") {
                WasherController.startProgramBoosted(selectedTime, selectedSpeed) { toast(it) }
            }
        }
        if (!model.hasSpinOnlyProgram) {
            spin.text(
                "Sua máquina não tem um programa \"só centrifugar\" no protocolo. O app tenta zerar lavagem e " +
                    "enxágue e deixar só a centrifugação. Fique de olho na primeira vez.",
                12f, color = "#AA6600",
            )
        }

        // ---- automation
        val auto = root.card("Centrifugação extra automática")
        auto.text(
            "Quando o ciclo terminar, o app manda a máquina centrifugar de novo com as configurações acima. " +
                "Deixe o app conectado (pode bloquear a tela).",
            13f, color = "#666666",
        )
        auto.text("Quantas vezes", 13f, color = "#666666")
        auto.spinner(listOf("1 vez", "2 vezes", "3 vezes"), selectedRepeats - 1) { selectedRepeats = it + 1 }
        autoSwitch = Switch(this).apply {
            text = "Ativar"
            textSize = 16f
            isChecked = WasherController.autoEnabled
            setOnCheckedChangeListener { _, checked ->
                if (checked != WasherController.autoEnabled) {
                    WasherController.setAutomation(checked, selectedRepeats, selectedTime, selectedSpeed)
                }
            }
        }
        auto.addView(autoSwitch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        autoStatusText = auto.text("", 14f, color = "#1565C0")

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
        rowButton("Desligar") { confirm("Desligar a máquina?", "O ciclo atual será interrompido.") { WasherController.power(false) { toast(it) } } }
        ctl.addView(row)
        ctl.text(
            "Se a máquina não obedecer, ative o controle remoto/Wi-Fi no painel dela (em muitos modelos é " +
                "segurar o botão de Wi-Fi por 3 s).",
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

    private fun describeTime(model: WasherModel): String {
        if (model.spinTimeUnit != "nível") return "$selectedTime ${model.spinTimeUnit}"
        return when (val m = WasherController.spinLevels()[selectedTime]) {
            null -> "nível $selectedTime (minutos ainda desconhecidos)"
            -1 -> "nível $selectedTime (a máquina recusou este nível)"
            else -> "nível $selectedTime = $m min"
        }
    }

    private fun updateTimeLabel(model: WasherModel) {
        val label = timeLabel ?: return
        var text = "Tempo de centrifugação: ${describeTime(model)}"
        if (model.spinTimeUnit == "nível") {
            val known = WasherController.spinLevels().toSortedMap()
            text += "\nA máquina usa níveis, não minutos. O app aprende quantos minutos cada nível dá " +
                "quando você usa." +
                if (known.isEmpty()) "" else "\nJá conhecidos: " + known.entries.joinToString(" · ") {
                    if (it.value < 0) "${it.key} = recusado" else "${it.key} = ${it.value} min"
                }
        }
        label.text = text
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
                val minutes = s.spinMinutes?.let { "$it min (nível ${s.spinTimeValue})" } ?: "tempo ${s.spinTimeValue}"
                appendLine("Centrifugação: ${s.spinSpeedName}, $minutes")
                if (s.errorCode != 0) appendLine("Código de erro: ${s.errorCode}")
            }.trimEnd()
        }
        autoStatusText?.text = c.autoText
        autoSwitch?.let { if (it.isChecked != c.autoEnabled) it.isChecked = c.autoEnabled }
        logText?.text = c.logText().lines().takeLast(25).joinToString("\n")
        c.model?.let { updateTimeLabel(it) }
    }
}
