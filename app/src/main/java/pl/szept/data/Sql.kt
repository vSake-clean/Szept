package pl.szept.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimalny interfejs bazy SQLite. Ta sama logika działa na zwykłym SQLite (testy, telefony x86)
 * i na SQLCipher (zaszyfrowana baza na telefonach ARM).
 */
interface Sql {
    fun execSQL(sql: String)
    fun execSQL(sql: String, args: Array<Any?>)
    fun rawQuery(sql: String, args: Array<String?>): Cursor
    fun insert(table: String, nullColumnHack: String?, values: ContentValues): Long
    fun insertWithOnConflict(table: String, nullColumnHack: String?, values: ContentValues, conflict: Int): Long
    fun update(table: String, values: ContentValues, where: String?, args: Array<String>?): Int
    fun delete(table: String, where: String?, args: Array<String>?): Int
    fun beginTransaction()
    fun setTransactionSuccessful()
    fun endTransaction()
    val version: Int
}

private class PlainSql(val d: android.database.sqlite.SQLiteDatabase) : Sql {
    override fun execSQL(sql: String) = d.execSQL(sql)
    override fun execSQL(sql: String, args: Array<Any?>) = d.execSQL(sql, args)
    override fun rawQuery(sql: String, args: Array<String?>): Cursor = d.rawQuery(sql, args)
    override fun insert(table: String, nullColumnHack: String?, values: ContentValues) = d.insert(table, nullColumnHack, values)
    override fun insertWithOnConflict(table: String, nullColumnHack: String?, values: ContentValues, conflict: Int) =
        d.insertWithOnConflict(table, nullColumnHack, values, conflict)
    override fun update(table: String, values: ContentValues, where: String?, args: Array<String>?) = d.update(table, values, where, args)
    override fun delete(table: String, where: String?, args: Array<String>?) = d.delete(table, where, args)
    override fun beginTransaction() = d.beginTransaction()
    override fun setTransactionSuccessful() = d.setTransactionSuccessful()
    override fun endTransaction() = d.endTransaction()
    override val version: Int get() = d.version
}

private class CipherSql(val d: net.zetetic.database.sqlcipher.SQLiteDatabase) : Sql {
    override fun execSQL(sql: String) = d.execSQL(sql)
    override fun execSQL(sql: String, args: Array<Any?>) = d.execSQL(sql, args)
    override fun rawQuery(sql: String, args: Array<String?>): Cursor = d.rawQuery(sql, args)
    override fun insert(table: String, nullColumnHack: String?, values: ContentValues) = d.insertWithOnConflict(table, nullColumnHack, values, 0)
    override fun insertWithOnConflict(table: String, nullColumnHack: String?, values: ContentValues, conflict: Int) =
        d.insertWithOnConflict(table, nullColumnHack, values, conflict)
    override fun update(table: String, values: ContentValues, where: String?, args: Array<String>?) = d.update(table, values, where, args)
    override fun delete(table: String, where: String?, args: Array<String>?) = d.delete(table, where, args)
    override fun beginTransaction() = d.beginTransaction()
    override fun setTransactionSuccessful() = d.setTransactionSuccessful()
    override fun endTransaction() = d.endTransaction()
    override val version: Int get() = d.version
}

/** Schemat bazy — tworzenie i aktualizacje (wspólne dla obu silników). */
interface Schema {
    val version: Int
    fun create(db: Sql)
    fun upgrade(db: Sql, oldVersion: Int, newVersion: Int)
}

/** Połączenie z bazą (otwierane leniwie przy pierwszym użyciu). */
interface DbHelper {
    val writable: Sql
    val readable: Sql
    fun close()
}

private class PlainHelper(ctx: Context, name: String, private val schema: Schema) :
    android.database.sqlite.SQLiteOpenHelper(ctx, name, null, schema.version), DbHelper {
    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) = schema.create(PlainSql(db))
    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) = schema.upgrade(PlainSql(db), oldVersion, newVersion)
    private var w: Sql? = null
    override val writable: Sql get() = w ?: PlainSql(writableDatabase).also { w = it }
    override val readable: Sql get() = writable
    override fun close() { super.close(); w = null }
}

private class CipherHelper(ctx: Context, name: String, key: ByteArray, private val schema: Schema) :
    net.zetetic.database.sqlcipher.SQLiteOpenHelper(ctx, name, key, null, schema.version, 0, null, null, true), DbHelper {
    override fun onCreate(db: net.zetetic.database.sqlcipher.SQLiteDatabase) = schema.create(CipherSql(db))
    override fun onUpgrade(db: net.zetetic.database.sqlcipher.SQLiteDatabase, oldVersion: Int, newVersion: Int) = schema.upgrade(CipherSql(db), oldVersion, newVersion)
    private var w: Sql? = null
    override val writable: Sql get() = w ?: CipherSql(writableDatabase).also { w = it }
    override val readable: Sql get() = writable
    override fun close() { super.close(); w = null }
}

/**
 * Szyfrowanie bazy w telefonie (SQLCipher, AES-256). Losowy klucz bazy jest zapisany zaszyfrowany kluczem
 * z Android Keystore — nie da się go odczytać z kopii plików aplikacji.
 * Gdy szyfrowanie jest niedostępne (np. telefon x86 albo testy), baza działa zwyczajnie.
 */
object DbCrypto {
    private const val TAG = "Szept"
    private const val ALIAS = "szept_db_key"

    @Volatile private var libState = 0   // 0 = nie sprawdzano, 1 = jest, -1 = brak

    fun libraryAvailable(): Boolean {
        if (libState == 0) libState = try {
            System.loadLibrary("sqlcipher")
            // domyślne okno odczytu SQLCipher to 16 KB — większe wiersze (np. koperta ze zdjęciem profilowym i banerem)
            // wracały jako null i wywracały aplikację; 4 MB mieści każdy wiersz, który zapisujemy
            net.zetetic.database.sqlcipher.SQLiteCursor.setCursorWindowSize(4 * 1024 * 1024)
            1
        } catch (t: Throwable) { -1 }
        return libState == 1
    }

    /** Czy plik jest zwykłą (niezaszyfrowaną) bazą SQLite. */
    fun isPlaintext(f: File): Boolean {
        if (!f.exists() || f.length() < 16) return false
        val head = ByteArray(16)
        f.inputStream().use { if (it.read(head) != 16) return false }
        return String(head, Charsets.US_ASCII) == "SQLite format 3\u0000"
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build(),
        )
        return gen.generateKey()
    }

    /** Wynik pobrania klucza bazy. */
    sealed class KeyResult {
        class Ok(val key: ByteArray) : KeyResult()
        /** Klucza nie ma i nie będzie (np. magazyn kluczy wyczyszczony) — zaszyfrowanej bazy nie da się odczytać. */
        data object Lost : KeyResult()
        /** Magazyn kluczy chwilowo nie odpowiada — nie wolno niczego kasować. */
        data object Unavailable : KeyResult()
    }

    /** Klucz bazy (32 B) — tworzony przy pierwszym użyciu. */
    @Synchronized
    fun dbKey(ctx: Context, dbName: String): KeyResult {
        val prefs = ctx.getSharedPreferences("szept_keys", Context.MODE_PRIVATE)
        val stored = prefs.getString("db:$dbName", null)
        var attempt = 0
        while (true) {
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (stored != null) {
                    val sk = ks.getKey(ALIAS, null) as? SecretKey ?: return KeyResult.Lost
                    val raw = Base64.decode(stored, Base64.NO_WRAP)
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, sk, GCMParameterSpec(128, raw, 0, 12))
                    return try { KeyResult.Ok(c.doFinal(raw, 12, raw.size - 12)) } catch (e: javax.crypto.AEADBadTagException) { KeyResult.Lost }
                }
                val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, keystoreKey())
                val out = c.iv + c.doFinal(key)
                prefs.edit().putString("db:$dbName", Base64.encodeToString(out, Base64.NO_WRAP)).commit()
                return KeyResult.Ok(key)
            } catch (t: Throwable) {
                if (++attempt >= 4) {
                    Log.w(TAG, "Keystore niedostępny", t)
                    return KeyResult.Unavailable
                }
                Thread.sleep(250L * attempt)
            }
        }
    }

    /** Szyfruje małą tajemnicę kluczem z magazynu kluczy Androida (np. zapamiętany klucz administratora). */
    fun seal(data: ByteArray): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreKey())
        Base64.encodeToString(c.iv + c.doFinal(data), Base64.NO_WRAP)
    } catch (t: Throwable) { null }

    fun open(sealed: String): ByteArray? = try {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, raw, 0, 12))
        c.doFinal(raw, 12, raw.size - 12)
    } catch (t: Throwable) { null }

    fun forgetKey(ctx: Context, dbName: String) {
        ctx.getSharedPreferences("szept_keys", Context.MODE_PRIVATE).edit().remove("db:$dbName").commit()
    }

    /** Hasło SQLCipher w postaci surowego klucza (bez wolnego wyprowadzania z hasła). */
    fun passphrase(key: ByteArray): ByteArray = ("x'" + key.joinToString("") { "%02x".format(it) } + "'").toByteArray(Charsets.US_ASCII)

    /**
     * Szyfruje istniejącą, zwykłą bazę (np. po aktualizacji z wersji 1.6): eksport do nowego pliku,
     * sprawdzenie i dopiero wtedy podmiana. Przy błędzie stara baza zostaje nietknięta.
     */
    fun encryptExisting(ctx: Context, dbName: String, key: ByteArray): Boolean {
        val file = ctx.getDatabasePath(dbName)
        if (!isPlaintext(file)) return true
        val tmp = File(file.path + ".enc")
        tmp.delete(); File(tmp.path + "-journal").delete()
        return try {
            val pass = passphrase(key)
            val plain = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(file.path, "", null,
                net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null, null)
            val ver = plain.version
            plain.rawExecSQL("ATTACH DATABASE ? AS enc KEY ?", tmp.path, String(pass, Charsets.US_ASCII))
            plain.rawQuery("SELECT sqlcipher_export('enc')", *arrayOf<Any>()).use { it.moveToFirst() }
            plain.rawExecSQL("PRAGMA enc.user_version = $ver")
            plain.rawExecSQL("DETACH DATABASE enc")
            plain.close()
            // sprawdzenie nowego pliku
            val check = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(tmp.path, pass, null,
                net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null, null)
            val ok = check.rawQuery("SELECT count(*) FROM sqlite_master", *arrayOf<Any>()).use { it.moveToFirst() && it.getInt(0) > 0 } && check.version == ver
            check.close()
            if (!ok) { tmp.delete(); return false }
            for (suffix in listOf("-wal", "-shm", "-journal")) File(file.path + suffix).delete()
            val bak = File(file.path + ".plain")
            bak.delete()
            if (!file.renameTo(bak)) { tmp.delete(); return false }
            if (!tmp.renameTo(file)) { bak.renameTo(file); return false }
            bak.delete()
            Log.i(TAG, "baza zaszyfrowana")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "nie udało się zaszyfrować bazy", t)
            tmp.delete()
            false
        }
    }

    /**
     * Otwiera bazę: zaszyfrowaną, gdy to możliwe, inaczej zwykłą.
     * [allowCipher] = false wymusza zwykłe SQLite (testy).
     */
    fun helper(ctx: Context, dbName: String, schema: Schema, allowCipher: Boolean): Pair<DbHelper, Boolean> {
        val file = ctx.getDatabasePath(dbName)
        val encryptedFile = file.exists() && file.length() > 0 && !isPlaintext(file)
        if (allowCipher && libraryAvailable()) {
            when (val k = dbKey(ctx, dbName)) {
                is KeyResult.Ok -> {
                    if (!file.exists() || encryptExisting(ctx, dbName, k.key)) return CipherHelper(ctx, dbName, passphrase(k.key), schema) to true
                    if (isPlaintext(file)) return PlainHelper(ctx, dbName, schema) to false
                }
                // lepiej nie uruchomić się teraz niż skasować wiadomości — przy następnym starcie klucz zwykle już jest
                KeyResult.Unavailable -> if (encryptedFile) throw IllegalStateException("Magazyn kluczy Androida chwilowo niedostępny")
                KeyResult.Lost -> {}
            }
        }
        // zaszyfrowanej bazy nie da się otworzyć bez klucza — odkładamy ją na bok, żeby aplikacja działała
        if (encryptedFile) {
            Log.w(TAG, "baza zaszyfrowana, ale klucz utracony — zaczynam od nowa")
            file.renameTo(File(file.path + ".unreadable"))
            for (suffix in listOf("-wal", "-shm", "-journal")) File(file.path + suffix).delete()
        }
        return PlainHelper(ctx, dbName, schema) to false
    }
}
