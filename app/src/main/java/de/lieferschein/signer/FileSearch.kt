package de.lieferschein.signer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * Suche nach PDF-Dateien, deren Dateiname die gescannte Nummer enthält.
 *
 * Treffer-Regel: Die Nummer muss im Dateinamen vorkommen und darf nicht direkt
 * von weiteren Ziffern umgeben sein (Suche "4711" findet "LS_4711.pdf" und
 * "4711_Mueller.pdf", aber nicht "47110.pdf").
 */
object FileSearch {

    /** ref = content://-URI (lokal) oder "smb:" + Pfad in der Freigabe (Netzlaufwerk) */
    data class Hit(val ref: String, val name: String, val path: String, val exact: Boolean)

    class Matcher(number: String) {
        private val needle = number.trim()
        private val pattern = Regex("(?<!\\d)" + Regex.escape(needle) + "(?!\\d)", RegexOption.IGNORE_CASE)

        /** null = kein Treffer, sonst true bei exaktem Treffer */
        fun match(fileName: String): Boolean? {
            if (!fileName.endsWith(".pdf", ignoreCase = true)) return null
            val base = fileName.substringBeforeLast('.')
            if (!pattern.containsMatchIn(base)) return null
            return base.equals(needle, ignoreCase = true)
        }
    }

    fun sort(hits: List<Hit>) =
        hits.sortedWith(compareByDescending<Hit> { it.exact }.thenBy { it.path.lowercase() })

    /** Lokale Ordner, SD-Karte, USB (Storage Access Framework) */
    fun searchLocal(context: Context, treeUri: Uri, number: String): List<Hit> {
        val matcher = Matcher(number)
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
                    } else {
                        val exact = matcher.match(name) ?: continue
                        hits.add(
                            Hit(
                                ref = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                                name = name,
                                path = "$path/$name",
                                exact = exact
                            )
                        )
                    }
                }
            }
        }
        return sort(hits)
    }
}
