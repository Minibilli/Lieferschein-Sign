package de.lieferschein.signer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

class SignActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        private const val KEY_STYLUS_ONLY = "stylus_only"
        private const val MAX_BITMAP_SIDE = 3000
    }

    private lateinit var docUri: Uri
    private lateinit var workFile: File
    private lateinit var canvas: SignatureCanvasView
    private lateinit var tvPage: TextView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnSave: Button

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageIndex = 0
    private var busy = false
    private val strokes = mutableMapOf<Int, MutableList<Stroke>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign)
        title = "Unterschreiben"

        docUri = Uri.parse(intent.getStringExtra(EXTRA_URI))
        workFile = File(cacheDir, "work.pdf")

        canvas = findViewById(R.id.canvas)
        tvPage = findViewById(R.id.tvPage)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnSave = findViewById(R.id.btnSave)
        findViewById<TextView>(R.id.tvName).text = intent.getStringExtra(EXTRA_NAME) ?: ""

        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val cbStylus = findViewById<CheckBox>(R.id.cbStylus)
        cbStylus.isChecked = prefs.getBoolean(KEY_STYLUS_ONLY, true)
        canvas.stylusOnly = cbStylus.isChecked
        cbStylus.setOnCheckedChangeListener { _, checked ->
            canvas.stylusOnly = checked
            prefs.edit().putBoolean(KEY_STYLUS_ONLY, checked).apply()
        }

        btnPrev.setOnClickListener { showPage(pageIndex - 1) }
        btnNext.setOnClickListener { showPage(pageIndex + 1) }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { canvas.undo() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { canvas.clearPage() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { confirmLeave() }
        btnSave.setOnClickListener { save() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })

        loadDocument()
    }

    private fun loadDocument() {
        btnSave.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(docUri)
                        ?: throw IOException("Datei kann nicht geöffnet werden")
                    input.use { inp -> workFile.outputStream().use { inp.copyTo(it) } }
                }
                pfd = ParcelFileDescriptor.open(workFile, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(pfd!!)
                btnSave.isEnabled = true
                canvas.doOnLayout { showPage(0) }
            } catch (e: Exception) {
                showErrorAndFinish("PDF konnte nicht geladen werden:\n${e.message}")
            }
        }
    }

    private fun showPage(index: Int) {
        val r = renderer ?: return
        if (index < 0 || index >= r.pageCount) return
        pageIndex = index

        r.openPage(index).use { page ->
            val pw = page.width.toFloat()
            val ph = page.height.toFloat()
            val viewW = max(canvas.width, 1).toFloat()
            val viewH = max(canvas.height, 1).toFloat()
            // etwas höher rendern als nötig, damit es auch nach Drehen scharf bleibt
            var scale = min(viewW / pw, viewH / ph) * 1.5f
            scale = min(scale, MAX_BITMAP_SIDE / max(pw, ph))
            val bw = max((pw * scale).toInt(), 1)
            val bh = max((ph * scale).toInt(), 1)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            canvas.setPage(bmp, pw, strokes.getOrPut(index) { mutableListOf() })
        }

        tvPage.text = "Seite ${index + 1} / ${r.pageCount}"
        btnPrev.isEnabled = index > 0
        btnNext.isEnabled = index < r.pageCount - 1
    }

    private fun hasInk() = strokes.values.any { it.isNotEmpty() }

    private fun save() {
        if (busy) return
        if (!hasInk()) {
            Toast.makeText(this, "Bitte zuerst unterschreiben", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        btnSave.isEnabled = false
        btnSave.text = "Speichere …"

        val snapshot: Map<Int, List<Stroke>> = strokes
            .filterValues { it.isNotEmpty() }
            .mapValues { (_, list) -> list.map { Stroke(it.points.toMutableList()) } }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val signed = File(cacheDir, "signed.pdf")
                    PdfSigner.sign(workFile, snapshot, signed)
                    overwriteOriginal(signed)
                    signed.delete()
                }
                Toast.makeText(this@SignActivity, "Gespeichert", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                busy = false
                btnSave.isEnabled = true
                btnSave.text = "Speichern"
                AlertDialog.Builder(this@SignActivity)
                    .setTitle("Speichern fehlgeschlagen")
                    .setMessage(e.message ?: e.toString())
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /** Überschreibt die geladene Datei. "rwt" = schreiben + vorher kürzen. */
    private fun overwriteOriginal(signed: File) {
        val out = try {
            contentResolver.openOutputStream(docUri, "rwt")
        } catch (e: Exception) {
            null
        } ?: contentResolver.openOutputStream(docUri, "wt")
        ?: throw IOException("Datei kann nicht zum Schreiben geöffnet werden")

        out.use { os -> signed.inputStream().use { it.copyTo(os) } }
    }

    private fun confirmLeave() {
        if (busy) return
        if (!hasInk()) {
            finish(); return
        }
        AlertDialog.Builder(this)
            .setTitle("Unterschrift verwerfen?")
            .setMessage("Die Unterschrift wurde noch nicht gespeichert.")
            .setPositiveButton("Verwerfen") { _, _ -> finish() }
            .setNegativeButton("Zurück", null)
            .show()
    }

    private fun showErrorAndFinish(msg: String) {
        AlertDialog.Builder(this)
            .setTitle("Fehler")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> finish() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
    }
}
