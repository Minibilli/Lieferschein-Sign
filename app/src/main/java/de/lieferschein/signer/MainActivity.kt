package de.lieferschein.signer

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
            prefs.edit().putString(KEY_TREE, uri.toString()).apply()
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

        findViewById<Button>(R.id.btnFolder).setOnClickListener { pickFolder.launch(currentTree()) }
        findViewById<Button>(R.id.btnScan).setOnClickListener { startScan() }
        findViewById<Button>(R.id.btnSearch).setOnClickListener { search(etNumber.text.toString()) }
        etNumber.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(etNumber.text.toString()); true
            } else false
        }

        updateFolderLabel()
    }

    private fun currentTree(): Uri? = prefs.getString(KEY_TREE, null)?.let(Uri::parse)

    private fun updateFolderLabel() {
        val tree = currentTree()
        tvFolder.text = if (tree == null) {
            "Kein Ordner gewählt"
        } else {
            DocumentsContract.getTreeDocumentId(tree).replace("primary:", "Interner Speicher/")
        }
    }

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
        val tree = currentTree()
        if (tree == null) {
            toast("Bitte zuerst den Lieferschein-Ordner wählen")
            pickFolder.launch(null)
            return
        }
        if (number.isEmpty()) {
            toast("Keine Nummer angegeben")
            return
        }
        progress.visibility = View.VISIBLE
        tvStatus.text = "Suche nach „$number“ …"
        showHits(emptyList())

        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { FileSearch.search(this@MainActivity, tree, number) }
            } catch (e: Exception) {
                progress.visibility = View.GONE
                tvStatus.text = "Fehler bei der Suche: ${e.message}\nOrdner evtl. neu wählen."
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
            .putExtra(SignActivity.EXTRA_URI, hit.uri.toString())
            .putExtra(SignActivity.EXTRA_NAME, hit.name)
        signLauncher.launch(intent)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
