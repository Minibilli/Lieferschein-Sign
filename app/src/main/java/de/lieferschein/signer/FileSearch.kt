package de.lieferschein.signer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * Durchsucht den gewählten Ordner inkl. aller Unterordner nach PDF-Dateien,
 * deren Dateiname die gescannte Nummer enthält.
 *
 * Treffer-Regel: Die Nummer muss im Dateinamen vorkommen und darf nicht direkt
 * von weiteren Ziffern umgeben sein (Suche "4711" findet "LS_4711.pdf" und
 * "4711_Mueller.pdf", aber nicht "47110.pdf").
 */
object FileSearch {

    data class Hit(val uri: Uri, val name: String, val path: String, val exact: Boolean)

    fun search(context: Context, treeUri: Uri, number: String): List<Hit> {
        val needle = number.trim()
        if (needle.isEmpty()) return emptyList()
        val pattern = Regex("(?<!\\d)" + Regex.escape(needle) + "(?!\\d)", RegexOption.IGNORE_CASE)

        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val hits = mutableListOf<Hit>()
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(rootId to "")

        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE
        )

        while (stack.isNotEmpty()) {
            val (docId, path) = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (mime == Document.MIME_TYPE_DIR) {
                        stack.addLast(id to "$path/$name")
                    } else if (name.endsWith(".pdf", ignoreCase = true) || mime == "application/pdf") {
                        val base = name.substringBeforeLast('.')
                        if (pattern.containsMatchIn(base)) {
                            hits.add(
                                Hit(
                                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                                    name = name,
                                    path = "$path/$name",
                                    exact = base.equals(needle, ignoreCase = true)
                                )
                            )
                        }
                    }
                }
            }
        }
        return hits.sortedWith(compareByDescending<Hit> { it.exact }.thenBy { it.path.lowercase() })
    }
}
