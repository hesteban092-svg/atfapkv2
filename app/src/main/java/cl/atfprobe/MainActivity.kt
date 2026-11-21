package cl.atfprobe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/**
 * Registrador en vivo (solo lectura): refrigerante (0105), aire de admisión (010F) y una lista
 * de identificadores UDS, mostrando el valor crudo (1 a 4 bytes) y guardando un CSV.
 * Úsalo desde el arranque en frío: el ATF se calienta más lento que el refrigerante.
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private val elm = Elm()
    private var devices: List<BluetoothDevice> = emptyList()

    private lateinit var spinner: Spinner
    private lateinit var header: EditText
    private lateinit var didsEt: EditText
    private lateinit var out: TextView

    @Volatile private var stop = false
    @Volatile private var busy = false
    private val csv = StringBuilder()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        spinner = Spinner(this)
        root.addView(spinner)

        header = EditText(this).apply { setText("7E1"); setSingleLine() }
        root.addView(label("Cabecera del módulo (TCM = 7E1):"))
        root.addView(header)

        didsEt = EditText(this).apply {
            setText("1314,1315,1316,1317,1274,1275,1276,1277,1278,1279,10A1,10AF,10B0,10B2,10DE,10DF,1134,1289,128A,128B,128D,1298,1323,1327")
        }
        root.addView(label("Identificadores a registrar (hex, coma):"))
        root.addView(didsEt)

        root.addView(row(
            btn("Conectar") { connect() },
            btn("Iniciar") { start() },
            btn("Detener") { stop = true }
        ))

        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            text = "1) Conectar  2) Iniciar (desde el arranque en frío)  3) Detener al llegar  4) Compartir CSV"
        }
        val sc = ScrollView(this).apply { addView(out) }
        root.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(btn("Compartir registro (CSV)") { share() })
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        } else {
            loadDevices()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        loadDevices()
    }

    override fun onDestroy() {
        stop = true
        elm.close()
        super.onDestroy()
    }

    private fun loadDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        devices = adapter?.bondedDevices?.toList() ?: emptyList()
        val names = devices.map { "${it.name} (${it.address})" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val i = devices.indexOfFirst { (it.name ?: "").contains("OBD", ignoreCase = true) }
        if (i >= 0) spinner.setSelection(i)
    }

    private fun label(t: String) = TextView(this).apply { text = t; textSize = 12f }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg v: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }

    private fun show(s: String) = runOnUiThread { out.text = s }

    private fun work(block: () -> Unit) {
        if (busy) { show("Ocupado. Pulsa Detener y espera un segundo."); return }
        busy = true
        stop = false
        Thread {
            try { block() } catch (e: Exception) { show("ERROR: ${e.message}") } finally { busy = false }
        }.start()
    }

    private fun connect() = work {
        val d = devices.getOrNull(spinner.selectedItemPosition)
        if (d == null) { show("Sin dispositivo seleccionado."); return@work }
        show("Conectando a ${d.name}...")
        elm.close()
        elm.connect(d)
        elm.init(header.text.toString().trim().uppercase())
        show("Conectado. Pulsa Iniciar.")
    }

    /** Devuelve los datos en hex (sin la cabecera de respuesta) o null. */
    private fun readData(req: String, tag: String): String? {
        val hex = Elm.parse(elm.send(req, 600))
        return if (hex != null && hex.startsWith(tag) && hex.length >= tag.length + 2)
            hex.substring(tag.length) else null
    }

    private fun num(h: String?): Long? =
        if (h == null) null else h.substring(0, minOf(h.length, 8)).toLong(16)

    private fun fmtDid(h: String?): String = when {
        h == null -> "--"
        h.length == 2 -> "%d (dato-40 = %d C)".format(h.toInt(16), h.toInt(16) - 40)
        h.length == 4 -> "%d (0x%s)".format(h.toInt(16), h)
        else -> h
    }

    private fun start() = work {
        val dids = didsEt.text.toString().split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        csv.setLength(0)
        csv.append("t_s,refrigerante,aire_adm,${dids.joinToString(",")}\n")
        val t0 = System.currentTimeMillis()
        while (!stop) {
            val cool = readData("0105", "4105")?.substring(0, 2)?.toInt(16)?.minus(40)
            val iat = readData("010F", "410F")?.substring(0, 2)?.toInt(16)?.minus(40)
            val vals = dids.map { readData("22$it", "62$it") }
            val t = (System.currentTimeMillis() - t0) / 1000.0
            val sb = StringBuilder()
            sb.append("t = %.0f s\n".format(t))
            sb.append("Refrigerante  ${cool ?: "--"} C\n")
            sb.append("Aire admisión ${iat ?: "--"} C\n\n")
            dids.forEachIndexed { i, d -> sb.append("$d  ${fmtDid(vals[i])}\n") }
            show(sb.toString())
            if (csv.length < 400000) {
                csv.append("%.1f,%s,%s,%s\n".format(
                    t, cool?.toString() ?: "", iat?.toString() ?: "",
                    vals.joinToString(",") { v -> num(v)?.toString() ?: "" }
                ))
            }
        }
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, csv.toString())
        }
        startActivity(Intent.createChooser(i, "Compartir registro"))
    }
}
