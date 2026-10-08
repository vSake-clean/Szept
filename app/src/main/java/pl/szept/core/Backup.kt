package pl.szept.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import pl.szept.data.Db
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Zaszyfrowana kopia zapasowa całego konta: klucz konta, ustawienia, baza wiadomości oraz (opcjonalnie)
 * zdjęcia, filmy i pliki. Pozwala przenieść całą historię na nowy telefon.
 *
 * Format pliku: "SZEPTBK1" | wersja (1 B) | sól (16 B) | liczba iteracji (4 B) | nonce bazowy (8 B) |
 * ramki: długość (4 B) + AES-256-GCM (do 64 KB treści). Ostatnia ramka jest oznaczona (wykrywa obcięcie pliku).
 * W środku jest zwykły ZIP. Klucz z hasła: PBKDF2-HMAC-SHA256.
 */
object Backup {
    private val MAGIC = "SZEPTBK1".toByteArray(Charsets.US_ASCII)
    private const val FORMAT = 1
    const val ITERATIONS = 300_000
    private const val CHUNK = 64 * 1024

    class BadPassword : IOException("Błędne hasło albo to nie jest kopia Szepta")
    data class Info(val name: String, val created: Long, val app: String, val files: Int)

    private fun deriveKey(password: CharArray, salt: ByteArray, iter: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, iter, 256)).encoded

    private fun nonce(base: ByteArray, counter: Int) = ByteArray(12).also {
        System.arraycopy(base, 0, it, 0, 8)
        it[8] = (counter ushr 24).toByte(); it[9] = (counter ushr 16).toByte(); it[10] = (counter ushr 8).toByte(); it[11] = counter.toByte()
    }

    private fun aad(counter: Int, last: Boolean) = byteArrayOf(
        (counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte(), if (last) 1 else 0,
    )

    /** Strumień szyfrujący w ramkach po 64 KB. */
    private class EncOut(private val out: OutputStream, private val key: ByteArray, private val base: ByteArray) : OutputStream() {
        private val buf = ByteArray(CHUNK)
        private var n = 0
        private var counter = 0
        private val data = DataOutputStream(out)
        override fun write(b: Int) { buf[n++] = b.toByte(); if (n == CHUNK) frame(false) }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off; var l = len
            while (l > 0) {
                val k = min(l, CHUNK - n)
                System.arraycopy(b, o, buf, n, k); n += k; o += k; l -= k
                if (n == CHUNK) frame(false)
            }
        }
        private fun frame(last: Boolean) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(base, counter)))
            c.updateAAD(aad(counter, last))
            val ct = c.doFinal(buf, 0, n)
            data.writeInt(ct.size); data.write(ct)
            counter++; n = 0
        }
        override fun flush() = data.flush()
        override fun close() { frame(true); data.flush(); out.close() }
    }

    /** Strumień odszyfrowujący; błędne hasło wykrywa już pierwsza ramka. */
    private class DecIn(inp: InputStream, private val key: ByteArray, private val base: ByteArray) : InputStream() {
        private val data = DataInputStream(inp)
        private var buf = ByteArray(0)
        private var pos = 0
        private var counter = 0
        private var done = false

        private fun dec(ct: ByteArray, last: Boolean): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(base, counter)))
            c.updateAAD(aad(counter, last))
            return c.doFinal(ct)
        }

        private fun next(): Boolean {
            if (done) return false
            val len = try { data.readInt() } catch (e: EOFException) { throw IOException("Plik kopii jest niekompletny") }
            if (len < 16 || len > CHUNK + 16) throw IOException("Uszkodzony plik kopii")
            val ct = ByteArray(len)
            try { data.readFully(ct) } catch (e: EOFException) { throw IOException("Plik kopii jest niekompletny") }
            buf = try { dec(ct, false) } catch (e: AEADBadTagException) {
                try { dec(ct, true).also { done = true } } catch (e2: AEADBadTagException) {
                    if (counter == 0) throw BadPassword() else throw IOException("Uszkodzony plik kopii")
                }
            }
            counter++
            pos = 0
            return true
        }

        override fun read(): Int {
            while (pos >= buf.size) if (!next()) return -1
            return buf[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= buf.size) if (!next()) return -1
            val k = min(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, k)
            pos += k
            return k
        }
    }

    // ---------------- zapis ustawień ----------------

    private fun prefsToJson(p: SharedPreferences): JSONObject {
        val o = JSONObject()
        for ((k, v) in p.all) {
            val t = when (v) {
                is String -> JSONObject().put("t", "s").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, t)
        }
        return o
    }

    private fun jsonToPrefs(o: JSONObject, p: SharedPreferences) {
        val e = p.edit().clear()
        for (k in o.keys()) {
            val t = o.getJSONObject(k)
            when (t.optString("t")) {
                "s" -> e.putString(k, t.getString("v"))
                "b" -> e.putBoolean(k, t.getBoolean("v"))
                "i" -> e.putInt(k, t.getInt("v"))
                "l" -> e.putLong(k, t.getLong("v"))
                "f" -> e.putFloat(k, t.getDouble("v").toFloat())
                "ss" -> e.putStringSet(k, t.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
            }
        }
        e.commit()
    }

    // ---------------- eksport ----------------

    /** Pliki konta do skopiowania: (nazwa w archiwum, plik). */
    private fun accountFiles(core: Core, withMedia: Boolean): List<Pair<String, File>> {
        val ctx = core.ctx
        val out = ArrayList<Pair<String, File>>()
        if (withMedia) core.mediaDir.listFiles()?.filter { it.isFile }?.forEach { out += "media/${it.name}" to it }
        core.schedulePathFiles().forEach { out += "scheduled/${it.name}" to it }
        listOf(core.avatarFile, core.bannerFile, File(ctx.filesDir, "wallpaper.jpg")).filter { it.exists() }.forEach { out += "files/${it.name}" to it }
        return out
    }

    /** Przybliżony rozmiar kopii (do pokazania przed zapisem). */
    fun estimateSize(core: Core, withMedia: Boolean): Long =
        accountFiles(core, withMedia).sumOf { it.second.length() } + core.ctx.getDatabasePath(core.dbName).length()

    /**
     * Zapisuje kopię do [out]. [progress] dostaje 0..100.
     * Hasło musi mieć co najmniej 8 znaków — chroni klucz Twojego konta.
     */
    fun export(core: Core, out: OutputStream, password: CharArray, withMedia: Boolean, appVersion: String, progress: (Int) -> Unit = {}) {
        require(password.size >= 8) { "Hasło musi mieć co najmniej 8 znaków" }
        val ctx = core.ctx
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val base = ByteArray(8).also { rnd.nextBytes(it) }
        val key = deriveKey(password, salt, ITERATIONS)
        val header = DataOutputStream(out)
        header.write(MAGIC); header.writeByte(FORMAT); header.write(salt); header.writeInt(ITERATIONS); header.write(base); header.flush()

        // spójna kopia bazy do zwykłego pliku SQLite
        val tmpName = "backup_export_${System.nanoTime()}.db"
        val tmpDb = ctx.getDatabasePath(tmpName)
        try {
            Db(ctx, tmpName, encrypt = false).apply { dbVersion; close() }
            core.db.copyInto(tmpDb.absolutePath)
            flatten(tmpDb)
            val files = accountFiles(core, withMedia)
            val total = (files.sumOf { it.second.length() } + tmpDb.length()).coerceAtLeast(1)
            var doneBytes = 0L
            ZipOutputStream(EncOut(out, key, base)).use { zip ->
                zip.setLevel(1)   // zdjęcia i filmy i tak są skompresowane
                fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                val meta = JSONObject().put("v", 1).put("app", appVersion).put("created", System.currentTimeMillis())
                    .put("name", core.myName).put("pub", core.myPub).put("mediaDir", core.mediaDir.absolutePath)
                    .put("schedDir", core.schedulePath().absolutePath).put("profile", core.profile)
                put("meta.json", meta.toString().toByteArray())
                put("prefs.json", prefsToJson(core.prefs).toString().toByteArray())
                put("ui.json", prefsToJson(ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)).toString().toByteArray())
                fun putFile(name: String, f: File) {
                    zip.putNextEntry(ZipEntry(name))
                    f.inputStream().use { ins ->
                        val b = ByteArray(64 * 1024)
                        while (true) {
                            val r = ins.read(b); if (r <= 0) break
                            zip.write(b, 0, r); doneBytes += r
                            progress((doneBytes * 100 / total).toInt().coerceIn(0, 99))
                        }
                    }
                    zip.closeEntry()
                }
                putFile("db.sqlite", tmpDb)
                files.forEach { (name, f) -> if (f.exists()) putFile(name, f) }
            }
            progress(100)
        } finally {
            ctx.deleteDatabase(tmpName)
        }
    }

    /** Wszystko z dziennika WAL do głównego pliku — kopia musi być jednym, samodzielnym plikiem. */
    private fun flatten(f: File) {
        try {
            android.database.sqlite.SQLiteDatabase.openDatabase(f.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { d ->
                d.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                d.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            }
        } catch (_: Exception) { }
    }

    // ---------------- odczyt ----------------

    private fun openArchive(input: InputStream, password: CharArray): ZipInputStream {
        val d = DataInputStream(input)
        val magic = ByteArray(8)
        try { d.readFully(magic) } catch (e: EOFException) { throw BadPassword() }
        if (!magic.contentEquals(MAGIC)) throw BadPassword()
        val ver = d.readUnsignedByte()
        if (ver != FORMAT) throw IOException("Kopia z nowszej wersji Szepta — zaktualizuj aplikację")
        val salt = ByteArray(16).also { d.readFully(it) }
        val iter = d.readInt()
        if (iter !in 10_000..10_000_000) throw IOException("Uszkodzony plik kopii")
        val base = ByteArray(8).also { d.readFully(it) }
        return ZipInputStream(DecIn(input, deriveKey(password, salt, iter), base))
    }

    /**
     * Przywraca kopię do bieżącego profilu. Najpierw wszystko jest rozpakowywane do katalogu tymczasowego —
     * błędne hasło albo uszkodzony plik niczego nie psują. Po sukcesie trzeba utworzyć nowy rdzeń (App.resetCore).
     */
    fun restore(core: Core, input: InputStream, password: CharArray, progress: (Int) -> Unit = {}): Info {
        val ctx = core.ctx
        val stage = File(ctx.filesDir, "restore_tmp").apply { deleteRecursively(); mkdirs() }
        try {
            var meta: JSONObject? = null
            var files = 0
            openArchive(input, password).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    // ochrona przed ścieżkami typu „../”
                    if (name.contains("..") || name.startsWith("/")) throw IOException("Uszkodzony plik kopii")
                    val dest = File(stage, name)
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { zip.copyTo(it) }
                    if (name == "meta.json") meta = JSONObject(dest.readText())
                    files++
                    if (files % 20 == 0) progress(min(90, files / 20))
                }
            }
            val m = meta ?: throw IOException("Uszkodzony plik kopii (brak opisu)")
            val dbFile = File(stage, "db.sqlite")
            if (!dbFile.exists()) throw IOException("Uszkodzony plik kopii (brak bazy)")
            val prefsJson = JSONObject(File(stage, "prefs.json").readText())
            if (!prefsJson.has("priv")) throw IOException("Kopia nie zawiera klucza konta")

            // --- podmiana danych (od tej chwili stare dane są zastępowane) ---
            core.stop()
            core.db.close()
            ctx.deleteDatabase(core.dbName)
            pl.szept.data.DbCrypto.forgetKey(ctx, core.dbName)
            dbFile.copyTo(ctx.getDatabasePath(core.dbName).apply { parentFile?.mkdirs() }, overwrite = true)
            // ten telefon to nowe urządzenie: własny identyfikator, a gdy konto działa na kilku — przedstawi się im
            prefsJson.remove("device_id")
            if (prefsJson.optJSONObject("multi")?.optBoolean("v") == true) prefsJson.put("announce", JSONObject().put("t", "b").put("v", true))
            jsonToPrefs(prefsJson, core.prefs)
            File(stage, "ui.json").takeIf { it.exists() }?.let { jsonToPrefs(JSONObject(it.readText()), ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)) }
            core.mediaDir.deleteRecursively(); core.mediaDir.mkdirs()
            File(stage, "media").listFiles()?.forEach { it.renameTo(File(core.mediaDir, it.name)) || run { it.copyTo(File(core.mediaDir, it.name), true); true } }
            val sched = core.schedulePath().apply { deleteRecursively(); mkdirs() }
            File(stage, "scheduled").listFiles()?.forEach { it.copyTo(File(sched, it.name), true) }
            File(stage, "files").listFiles()?.forEach { f ->
                // zdjęcie profilowe/baner mają w nazwie profil — dopasuj do bieżącego
                val target = when {
                    f.name.startsWith("me_avatar_") -> core.avatarFile
                    f.name.startsWith("me_banner_") -> core.bannerFile
                    else -> File(ctx.filesDir, f.name)
                }
                f.copyTo(target, true)
            }
            // ścieżki plików w bazie wskazują na stary telefon — poprawiamy je
            Db(ctx, core.dbName, encrypt = false).apply {
                fixPaths(m.optString("mediaDir"), core.mediaDir.absolutePath, m.optString("schedDir"), core.schedulePath().absolutePath)
                close()
            }
            progress(100)
            return Info(m.optString("name"), m.optLong("created"), m.optString("app"), files)
        } finally {
            stage.deleteRecursively()
        }
    }

    /** Odczyt samego opisu kopii (sprawdzenie hasła bez przywracania). */
    fun peek(input: InputStream, password: CharArray): Info {
        openArchive(input, password).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "meta.json") {
                    val m = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    return Info(m.optString("name"), m.optLong("created"), m.optString("app"), 0)
                }
            }
        }
        throw IOException("Uszkodzony plik kopii")
    }
}
