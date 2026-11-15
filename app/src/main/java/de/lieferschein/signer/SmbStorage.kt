package de.lieferschein.signer

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

data class SmbSettings(
    val host: String,
    val share: String,
    val folder: String,
    val user: String,
    val password: String,
    val domain: String
) {
    val display: String
        get() = "\\\\$host\\$share" + if (folder.isNotEmpty()) "\\$folder" else ""
}

/** Zugriff auf Windows-Freigaben / NAS über SMB2/SMB3. */
object SmbStorage {

    const val PREFIX = "smb:"
    private const val PREFS = "smb"

    fun load(ctx: Context): SmbSettings? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = p.getString("host", null) ?: return null
        return SmbSettings(
            host = host,
            share = p.getString("share", "") ?: "",
            folder = p.getString("folder", "") ?: "",
            user = p.getString("user", "") ?: "",
            password = p.getString("password", "") ?: "",
            domain = p.getString("domain", "") ?: ""
        )
    }

    fun save(ctx: Context, s: SmbSettings) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("host", s.host)
            .putString("share", s.share)
            .putString("folder", s.folder)
            .putString("user", s.user)
            .putString("password", s.password)
            .putString("domain", s.domain)
            .apply()
    }

    fun normalizeFolder(f: String) = f.replace('/', '\\').trim('\\', ' ')

    private fun <T> withShare(s: SmbSettings, block: (DiskShare) -> T): T {
        val config = SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()

        val hostPart = s.host.substringBefore(':')
        val port = s.host.substringAfter(':', "").toIntOrNull() ?: 445

        val client = SMBClient(config)
        try {
            val conn = client.connect(hostPart, port)
            try {
                val auth = if (s.user.isBlank()) {
                    AuthenticationContext.guest()
                } else {
                    AuthenticationContext(s.user, s.password.toCharArray(), s.domain.ifBlank { null })
                }
                val session = conn.authenticate(auth)
                try {
                    val share = session.connectShare(s.share) as? DiskShare
                        ?: throw IOException("„${s.share}“ ist keine Datei-Freigabe")
                    try {
                        return block(share)
                    } finally {
                        share.close()
                    }
                } finally {
                    session.close()
                }
            } finally {
                conn.close()
            }
        } finally {
            client.close()
        }
    }

    /** Prüft Verbindung, Anmeldung und ob der Ordner lesbar ist. */
    fun test(s: SmbSettings) {
        withShare(s) { share -> share.list(normalizeFolder(s.folder)) }
    }

    fun search(s: SmbSettings, number: String): List<FileSearch.Hit> = withShare(s) { share ->
        val matcher = FileSearch.Matcher(number)
        val root = normalizeFolder(s.folder)
        val hits = mutableListOf<FileSearch.Hit>()
        val stack = ArrayDeque<String>()
        stack.addLast(root)

        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val entries = try {
                share.list(dir)
            } catch (e: Exception) {
                if (dir == root) throw e else continue // Unterordner ohne Rechte überspringen
            }
            for (e in entries) {
                val name = e.fileName
                if (name == "." || name == "..") continue
                val full = if (dir.isEmpty()) name else "$dir\\$name"
                val isDir = (e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                if (isDir) {
                    stack.addLast(full)
                } else {
                    val exact = matcher.match(name) ?: continue
                    hits.add(FileSearch.Hit(PREFIX + full, name, "/" + full.replace('\\', '/'), exact))
                }
            }
        }
        FileSearch.sort(hits)
    }

    private fun settings(ctx: Context) =
        load(ctx) ?: throw IOException("Netzlaufwerk ist nicht eingerichtet")

    fun download(ctx: Context, path: String, dest: File) {
        withShare(settings(ctx)) { share ->
            val f = share.openFile(
                path, EnumSet.of(AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null
            )
            try {
                f.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            } finally {
                f.close()
            }
        }
    }

    fun upload(ctx: Context, path: String, src: File) {
        withShare(settings(ctx)) { share ->
            val f = share.openFile(
                path, EnumSet.of(AccessMask.GENERIC_WRITE), null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OVERWRITE_IF, null
            )
            try {
                f.outputStream.use { out -> src.inputStream().use { it.copyTo(out) } }
            } finally {
                f.close()
            }
        }
    }
}
