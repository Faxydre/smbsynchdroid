package com.faxydre.smbsynchdroid

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Calendar
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Connexion ouverte vers un partage SMB. */
class SmbTarget private constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
) : Closeable {

    private val knownDirs = HashSet<String>()

    /** Crée le dossier (et ses parents) s'il n'existe pas. */
    fun ensureDir(path: String) {
        if (path.isEmpty() || path in knownDirs) return
        var current = ""
        for (part in path.split('\\')) {
            if (part.isEmpty()) continue
            current = if (current.isEmpty()) part else "$current\\$part"
            if (current in knownDirs) continue
            if (!share.folderExists(current)) share.mkdir(current)
            knownDirs += current
        }
    }

    /** Sous-dossiers de [path] (vide = racine du partage), triés par nom. */
    fun listDirs(path: String): List<String> =
        share.list(path)
            .filter { (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L }
            .map { it.fileName }
            .filter { it != "." && it != ".." && !it.startsWith(".") }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** Taille du fichier distant, ou null s'il n'existe pas. */
    fun remoteSize(path: String): Long? =
        if (share.fileExists(path)) share.getFileInformation(path).standardInformation.endOfFile else null

    /** Écrit dans "nom.part" puis renomme : jamais de photo à moitié copiée sous son vrai nom. */
    fun upload(input: InputStream, path: String, replace: Boolean = false): Long {
        val tmp = "$path.part"
        var written = 0L
        try {
            share.openFile(
                tmp,
                EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.noneOf(SMB2CreateOptions::class.java),
            ).use { file ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        file.write(buf, written, 0, n)
                        written += n
                    }
                }
                file.rename(path, replace)
            }
        } catch (e: Exception) {
            runCatching { if (share.fileExists(tmp)) share.rm(tmp) }
            throw e
        }
        return written
    }

    /** Vérifie qu'on peut écrire dans le dossier de destination. */
    fun testWrite(dir: String) {
        ensureDir(dir)
        val path = RemotePaths.join(dir, ".smbsynchdroid-test")
        upload("test".byteInputStream(), path)
        share.rm(path)
    }

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    companion object {
        fun open(s: SmbSettings): SmbTarget {
            val config = SmbConfig.builder()
                .withSecurityProvider(BCSecurityProvider())
                .withTimeout(60, TimeUnit.SECONDS)
                .withSoTimeout(60, TimeUnit.SECONDS)
                .build()
            val client = SMBClient(config)
            try {
                val (host, port) = parseHost(s.host)
                val connection = client.connect(host, port)
                val auth = if (s.username.isBlank()) {
                    AuthenticationContext.guest()
                } else {
                    AuthenticationContext(s.username.trim(), s.password.toCharArray(), s.domain.trim().ifEmpty { null })
                }
                val session = connection.authenticate(auth)
                val share = session.connectShare(s.share.trim()) as? DiskShare
                    ?: throw IOException("« ${s.share} » n'est pas un partage de fichiers")
                return SmbTarget(client, connection, session, share)
            } catch (e: Exception) {
                runCatching { client.close() }
                throw e
            }
        }

        private fun parseHost(raw: String): Pair<String, Int> {
            val h = raw.trim().removePrefix("smb://").trim('/', '\\')
            val i = h.lastIndexOf(':')
            if (i > 0 && h.indexOf(':') == i) {
                h.substring(i + 1).toIntOrNull()?.let { return h.substring(0, i) to it }
            }
            return h to SMBClient.DEFAULT_PORT
        }
    }
}

object RemotePaths {
    fun join(vararg parts: String): String =
        parts.flatMap { it.replace('/', '\\').split('\\') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\\")

    fun dirFor(s: SmbSettings, takenMs: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = takenMs }
        val y = c.get(Calendar.YEAR).toString()
        val m = "%02d".format(c.get(Calendar.MONTH) + 1)
        val d = "%02d".format(c.get(Calendar.DAY_OF_MONTH))
        return when (s.layout) {
            Layout.FLAT -> join(s.remoteDir)
            Layout.YEAR -> join(s.remoteDir, y)
            Layout.YEAR_MONTH -> join(s.remoteDir, y, m)
            Layout.YEAR_MONTH_DAY -> join(s.remoteDir, y, m, d)
        }
    }
}

object Errors {
    /** Message lisible pour l'utilisateur. */
    fun friendly(e: Throwable): String {
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is SMBApiException -> return when (t.status) {
                    NtStatus.STATUS_LOGON_FAILURE -> "identifiant ou mot de passe refusé"
                    NtStatus.STATUS_BAD_NETWORK_NAME -> "partage introuvable sur le NAS"
                    NtStatus.STATUS_ACCESS_DENIED -> "accès refusé (droits d'écriture sur le partage ?)"
                    NtStatus.STATUS_DISK_FULL -> "le NAS est plein"
                    else -> "erreur SMB ${t.status}"
                }
                is UnknownHostException -> return "nom du NAS inconnu sur ce réseau"
                is ConnectException, is NoRouteToHostException -> return "NAS injoignable (pas sur le bon réseau ?)"
                is SocketTimeoutException -> return "le NAS ne répond pas (délai dépassé)"
            }
            t = t.cause
        }
        return e.message ?: e.javaClass.simpleName
    }
}
