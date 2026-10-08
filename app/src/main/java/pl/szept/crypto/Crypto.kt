package pl.szept.crypto

import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cała kryptografia aplikacji:
 *  - tożsamość = klucz secp256k1 (zgodny z Nostr, podpisy Schnorr BIP-340),
 *  - szyfrowanie = ECDH (współrzędna x) -> HKDF-SHA256 -> AES-256-GCM.
 */
object Crypto {
    private val secp: Secp256k1 by lazy { Secp256k1.get() }
    val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun newPrivateKey(): ByteArray {
        while (true) {
            val k = randomBytes(32)
            if (secp.secKeyVerify(k)) return k
        }
    }

    fun isValidPrivateKey(k: ByteArray) = k.size == 32 && secp.secKeyVerify(k)

    /** Klucz publiczny x-only (32 bajty), tak jak w Nostr. */
    fun publicKey(priv: ByteArray): ByteArray = secp.pubkeyCreate(priv).copyOfRange(1, 33)

    fun sign(hash32: ByteArray, priv: ByteArray): ByteArray = secp.signSchnorr(hash32, priv, randomBytes(32))

    fun verify(sig: ByteArray, hash32: ByteArray, pubX: ByteArray): Boolean =
        try { secp.verifySchnorr(sig, hash32, pubX) } catch (e: Throwable) { false }

    private val validPubs = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 2000
    })

    /** Czy 64 znaki hex to prawdziwy klucz publiczny (punkt na krzywej) — ok. połowa losowych ciągów nim nie jest. */
    fun isValidPub(hex: String): Boolean = validPubs.getOrPut(hex) {
        try { secp.pubkeyParse(byteArrayOf(2) + hex.unhex()); true } catch (e: Throwable) { false }
    }

    /** Wspólny sekret ECDH: współrzędna x punktu priv * lift_x(pubX) (niezależna od parzystości y). */
    fun sharedX(priv: ByteArray, pubX: ByteArray): ByteArray {
        val full = secp.pubkeyParse(byteArrayOf(2) + pubX)
        val point = secp.pubKeyTweakMul(full, priv)
        val uncompressed = if (point.size == 33) secp.pubkeyParse(point) else point
        return uncompressed.copyOfRange(1, 33)
    }

    /** Deterministyczny klucz prywatny wyprowadzony z sekretu (np. klucz jednorazowy koperty). */
    fun deriveKey(secret: ByteArray, label: String): ByteArray {
        var k = hmac(secret, label.toByteArray())
        var i = 0
        while (!secp.secKeyVerify(k)) { k = hmac(secret, (label + "#" + (++i)).toByteArray()) }
        return k
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int = 32): ByteArray {
        val prk = hmac(salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var c = 1
        while (out.size() < len) {
            t = hmac(prk, t + info + byteArrayOf(c.toByte()))
            out.write(t); c++
        }
        return out.toByteArray().copyOf(len)
    }

    /** Klucz AES dla pary (mój klucz prywatny, czyjś klucz publiczny). */
    fun conversationKey(priv: ByteArray, pubX: ByteArray): ByteArray =
        hkdf(sharedX(priv, pubX), "szept/1".toByteArray(), "wrap".toByteArray())

    /** AES-256-GCM: wynik = iv(12) || szyfrogram || tag(16). */
    fun encrypt(key: ByteArray, plain: ByteArray, iv: ByteArray = randomBytes(12), aad: ByteArray? = null): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) c.updateAAD(aad)
        return iv + c.doFinal(plain)
    }

    fun decrypt(key: ByteArray, data: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(data, 12, data.size - 12)
    }
}

fun ByteArray.hex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0xf])
    }
    return sb.toString()
}

fun String.unhex(): ByteArray {
    require(length % 2 == 0) { "zła długość hex" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

fun String.isHex64() = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
/** Poprawny klucz publiczny (hex i punkt na krzywej) — dla kluczy od innych osób. */
fun String.isPubKey() = isHex64() && Crypto.isValidPub(this)

object B64 {
    fun enc(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    fun dec(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
}
