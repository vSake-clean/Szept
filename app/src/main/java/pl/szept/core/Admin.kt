package pl.szept.core

import pl.szept.crypto.Crypto
import pl.szept.crypto.hex
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Oficjalne konto „Szept” (administrator). Klucz publiczny jest wbudowany w aplikację, więc nikt nie podrobi
 * jego podpisu (znaczek ✔, aktualizacje, wymuszanie aktualizacji). Klucz prywatny jest w aplikacji tylko
 * zaszyfrowany hasłem administratora (PBKDF2-SHA256 + AES-256-GCM).
 */
object Admin {
    const val PUB = "e3ad5df0c05fb4f7d5c95a82cb1de7fc184d967547550db61b12f4bb134a97d9"
    /** Zaszyfrowany klucz administratora — wstawiany przy budowaniu (secrets.properties / SZEPT_ADMIN_ENC), nie ma go w repozytorium. */
    private val ENC: String get() = pl.szept.BuildConfig.ADMIN_ENC
    private const val ITER = 310_000
    const val NAME = "Szept"
    /** Zdarzenie Nostr z polityką wersji (zastępowalne: najnowsze wygrywa). */
    const val POLICY_KIND = 30078
    const val POLICY_D = "szept-policy"

    fun isAdmin(pk: String?) = pk == PUB

    /** Odszyfrowuje klucz administratora; null = złe hasło. */
    /** Czy ta kompilacja ma panel administratora. */
    val available: Boolean get() = ENC.isNotEmpty()

    fun unlock(password: CharArray): ByteArray? = if (ENC.isEmpty()) null else try {
        val raw = Base64.getDecoder().decode(ENC)
        val salt = raw.copyOfRange(0, 16)
        val iv = raw.copyOfRange(16, 28)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, ITER, 256)).encoded
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD("szept-admin".toByteArray())
        val priv = c.doFinal(raw, 28, raw.size - 28)
        if (Crypto.publicKey(priv).hex() == PUB) priv else null
    } catch (e: Exception) { null }

    /** Znane wersje aplikacji (nazwa → numer), do wyboru najstarszej dozwolonej. */
    val VERSIONS = listOf(
        "1.0" to 1, "1.1" to 2, "1.2" to 3, "1.3" to 4, "1.4" to 5, "1.5" to 6, "1.6" to 7, "1.7" to 8,
        "1.8" to 9, "1.9" to 10, "2.0" to 11, "2.1" to 12, "2.2" to 13, "2.3" to 14, "2.4" to 15, "2.5" to 16, "2.6" to 17, "2.7" to 18, "2.8" to 19, "2.9" to 20, "3.0" to 21,
    )

    fun versionName(code: Int) = VERSIONS.firstOrNull { it.second == code }?.first ?: code.toString()

    /** Usuwa z cudzych nazw znaki „ptaszka”, żeby nikt nie udawał zweryfikowanego konta. */
    fun cleanName(name: String): String = name.replace(Regex("[\u2713\u2714\u2705\u2611\uFE0F]|\uD83D\uDDF8"), "").trim()
}
