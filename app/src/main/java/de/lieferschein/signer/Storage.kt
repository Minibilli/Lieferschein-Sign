package de.lieferschein.signer

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/** Laden und Überschreiben – egal ob lokale Datei oder Netzlaufwerk. */
object Storage {

    fun download(ctx: Context, ref: String, dest: File) {
        if (ref.startsWith(SmbStorage.PREFIX)) {
            SmbStorage.download(ctx, ref.removePrefix(SmbStorage.PREFIX), dest)
        } else {
            val input = ctx.contentResolver.openInputStream(Uri.parse(ref))
                ?: throw IOException("Datei kann nicht geöffnet werden")
            input.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
        }
    }

    fun overwrite(ctx: Context, ref: String, src: File) {
        if (ref.startsWith(SmbStorage.PREFIX)) {
            SmbStorage.upload(ctx, ref.removePrefix(SmbStorage.PREFIX), src)
            return
        }
        val uri = Uri.parse(ref)
        val out = try {
            ctx.contentResolver.openOutputStream(uri, "rwt")
        } catch (e: Exception) {
            null
        } ?: ctx.contentResolver.openOutputStream(uri, "wt")
        ?: throw IOException("Datei kann nicht zum Schreiben geöffnet werden")
        out.use { os -> src.inputStream().use { it.copyTo(os) } }
    }
}
