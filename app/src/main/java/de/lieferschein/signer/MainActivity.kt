package de.lieferschein.signer

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "settings"
        const val KEY_TREE = "tree_uri"
        const val KEY_MODE = "mode"
        const val MODE_LOCAL = "local"
        const val MODE_SMB = "smb"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var tvFolder: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etNumber: EditText
    private lateinit var progress: ProgressBar
    private lateinit var listHits: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private var hits: List<FileSearch.Hit> = emptyList()

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                toast("Keine dauerhafte Berechtigung für diesen Ordner: ${e.message}")
            }
            prefs.edit().putString(KEY_TREE, uri.toString()).putString(KEY_MODE, MODE_LOCAL).apply()
            updateFolderLabel()
        }
    }

    private val signLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            tvStatus.text = "✔ Unterschrieben und gespeichert. Bereit für den nächsten Lieferschein."
            etNumber.setText("")
            showHits(emptyList())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        tvFolder = findViewById(R.id.tvFolder)
        tvStatus = findViewById(R.id.tvStatus)
        etNumber = findViewById(R.id.etNumber)
        progress = findViewById(R.id.progress)
        listHits = findViewById(R.id.listHits)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listHits.adapter = adapter
        listHits.setOnItemClickListener { _, _, pos, _ -> openHit(hits[pos]) }

        findViewById<Button>(R.id.btnFolder).setOnClickListener { chooseSource() }
        findViewById<Button>(R.id.btnScan).setOnClickListener { startScan() }
        findViewById<Button>(R.id.btnSearch).setOnClickListener { search(etNumber.text.toString()) }
        etNumber.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(etNumber.text.toString()); true
            } else false
        }

        updateFolderLabel()
    }

    private fun mode() = prefs.getString(KEY_MODE, MODE_LOCAL) ?: MODE_LOCAL

    private fun currentTree(): Uri? = prefs.getString(KEY_TREE, null)?.let(Uri::parse)

    private fun isConfigured() = when (mode()) {
        MODE_SMB -> SmbStorage.load(this) != null
        else -> currentTree() != null
    }

    private fun updateFolderLabel() {
        tvFolder.text = when (mode()) {
            MODE_SMB -> SmbStorage.load(this)?.let { "Netzlaufwerk: ${it.display}" } ?: "Kein Ordner gewählt"
            else -> currentTree()?.let {
                DocumentsContract.getTreeDocumentId(it).replace("primary:", "Interner Speicher/")
            } ?: "Kein Ordner gewählt"
        }
    }

    // ---------- Ordnerauswahl ----------

    private fun chooseSource() {
        AlertDialog.Builder(this)
            .setTitle("Wo liegen die Lieferscheine?")
            .setItems(arrayOf("Lokaler Ordner / SD-Karte / USB", "Netzlaufwerk (Windows-Freigabe / NAS)")) { _, which ->
                if (which == 0) pickFolder.launch(currentTree()) else showSmbDialog()
            }
            .show()
    }

    private fun showSmbDialog() {
        val cur = SmbStorage.load(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setPadding(0, pad / 2, 0, 0)
            layout.addView(this)
        }

        fun field(title: String, hint: String, value: String?, password: Boolean = false): EditText {
            label(title)
            return EditText(this).apply {
                this.hint = hint
                setText(value ?: "")
                isSingleLine = true
                inputType = if (password) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
                layout.addView(this)
            }
        }

        val etHost = field("Server", "IP-Adresse, z. B. 192.168.1.10", cur?.host)
        val etShare = field("Freigabe (nur der erste Ordner nach der IP)", "z. B. daten", cur?.share)
        val etFolder = field("Unterordner (optional, mehrere Ebenen mit \\)", "z. B. bbm\\2024", cur?.folder)
        val etUser = field("Benutzer", "leer lassen für Gastzugang", cur?.user)
        val etPass = field("Passwort", "", cur?.password, password = true)
        val etDomain = field("Domäne / Arbeitsgruppe (optional)", "z. B. FIRMA", cur?.domain)
        val tvInfo = TextView(this).apply {
            setPadding(0, pad / 2, 0, pad / 2)
            text = "Tipp: Du kannst auch den kompletten Pfad wie \\\\192.168.1.10\\Lieferscheine ins Feld „Server“ eintragen."
            layout.addView(this)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Netzlaufwerk verbinden")
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Verbinden", null)
            .setNegativeButton("Abbrechen", null)
            .create()

        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                // Eingabe wie "\\server\freigabe\ordner" oder "smb://server/freigabe" zerlegen
                val raw = etHost.text.toString().trim()
                    .removePrefix("smb://").replace('/', '\\').trim('\\')
                val parts = raw.split('\\').filter { it.isNotBlank() }
                val host = parts.firstOrNull().orEmpty()
                var share = etShare.text.toString().trim().trim('\\', '/')
                var folder = SmbStorage.normalizeFolder(etFolder.text.toString())
                if (parts.size >= 2 && share.isEmpty()) share = parts[1]
                if (parts.size >= 3 && folder.isEmpty()) folder = parts.drop(2).joinToString("\\")

                // "daten\bbm" bei Freigabe: Freigabe = "daten", Rest wandert vor den Unterordner
                val shareParts = share.replace('/', '\\').split('\\').filter { it.isNotBlank() }
                if (shareParts.size > 1) {
                    share = shareParts[0]
                    folder = (shareParts.drop(1) + listOf(folder).filter { it.isNotEmpty() })
                        .joinToString("\\")
                    etShare.setText(share)
                    etFolder.setText(folder)
                }

                if (host.isEmpty() || share.isEmpty()) {
                    tvInfo.text = "Bitte Server und Freigabe angeben."
                    return@setOnClickListener
                }
                val s = SmbSettings(
                    host = host,
                    share = share,
                    folder = folder,
                    user = etUser.text.toString().trim(),
                    password = etPass.text.toString(),
                    domain = etDomain.text.toString().trim()
                )

                btn.isEnabled = false
                tvInfo.text = "Verbinde mit ${s.display} …"
                lifecycleScope.launch {
                    val error = withContext(Dispatchers.IO) {
                        try {
                            SmbStorage.test(s); null
                        } catch (e: Exception) {
                            e.message ?: e.toString()
                        }
                    }
                    btn.isEnabled = true
                    if (error == null) {
                        SmbStorage.save(this@MainActivity, s)
                        prefs.edit().putString(KEY_MODE, MODE_SMB).apply()
                        updateFolderLabel()
                        dialog.dismiss()
                        toast("Netzlaufwerk verbunden")
                    } else {
                        tvInfo.text = "Verbindung fehlgeschlagen:\n$error"
                    }
                }
            }
        }
        dialog.show()
    }

    // ---------- Scannen & Suchen ----------

    private fun startScan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue?.trim().orEmpty()
                if (value.isEmpty()) {
                    toast("Code ohne lesbaren Inhalt")
                } else {
                    etNumber.setText(value)
                    search(value)
                }
            }
            .addOnFailureListener { e ->
                toast("Scanner nicht verfügbar: ${e.message}")
            }
    }

    private fun search(rawNumber: String) {
        val number = rawNumber.trim()
        if (!isConfigured()) {
            toast("Bitte zuerst den Lieferschein-Ordner wählen")
            chooseSource()
            return
        }
        if (number.isEmpty()) {
            toast("Keine Nummer angegeben")
            return
        }
        progress.visibility = View.VISIBLE
        tvStatus.text = "Suche nach „$number“ …"
        showHits(emptyList())

        val currentMode = mode()
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    if (currentMode == MODE_SMB) {
                        SmbStorage.search(SmbStorage.load(this@MainActivity)!!, number)
                    } else {
                        FileSearch.searchLocal(this@MainActivity, currentTree()!!, number)
                    }
                }
            } catch (e: Exception) {
                progress.visibility = View.GONE
                tvStatus.text = "Fehler bei der Suche: ${e.message}\nOrdner bzw. Netzlaufwerk evtl. neu wählen."
                return@launch
            }
            progress.visibility = View.GONE
            when {
                result.isEmpty() -> tvStatus.text = "Kein Lieferschein mit der Nummer „$number“ gefunden."
                result.size == 1 -> {
                    tvStatus.text = "1 Treffer: ${result[0].path}"
                    showHits(result)
                    openHit(result[0])
                }
                else -> {
                    tvStatus.text = "${result.size} Treffer – bitte auswählen:"
                    showHits(result)
                }
            }
        }
    }

    private fun showHits(list: List<FileSearch.Hit>) {
        hits = list
        adapter.clear()
        adapter.addAll(list.map { it.path.trimStart('/') })
        adapter.notifyDataSetChanged()
    }

    private fun openHit(hit: FileSearch.Hit) {
        val intent = Intent(this, SignActivity::class.java)
            .putExtra(SignActivity.EXTRA_REF, hit.ref)
            .putExtra(SignActivity.EXTRA_NAME, hit.name)
        signLauncher.launch(intent)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
