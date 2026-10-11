import java.util.Properties
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** Prywatne wartości: zmienna środowiskowa albo plik secrets.properties (nie trafia do repozytorium). */
val secrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(name: String): String? = (System.getenv(name) ?: secrets.getProperty(name))?.trim()?.ifEmpty { null }

/**
 * Konfiguracja sieci (serwery TURN, API Metered) z secrets.properties → JSON zaszyfrowany AES-256-GCM.
 * Klucz jest losowy dla każdej kompilacji i zapisany w aplikacji w postaci zamaskowanej (XOR ze stałą w kodzie).
 */
fun sealedNetConfig(): Pair<String, String> {
    fun esc(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
    val turns = (1..4).mapNotNull { i ->
        val urls = secret("TURN${i}_URLS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return@mapNotNull null
        "{\"u\":[" + urls.joinToString(",") { "\"" + esc(it) + "\"" } + "],\"n\":\"" + esc(secret("TURN${i}_USER") ?: "") + "\",\"p\":\"" + esc(secret("TURN${i}_PASS") ?: "") + "\"}"
    }
    val json = "{\"turns\":[" + turns.joinToString(",") + "],\"mu\":\"" + esc(secret("METERED_API_URL") ?: "") + "\",\"mk\":\"" + esc(secret("METERED_API_KEY") ?: "") + "\"}"
    val rnd = SecureRandom()
    val key = ByteArray(32).also { rnd.nextBytes(it) }
    val iv = ByteArray(12).also { rnd.nextBytes(it) }
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    c.updateAAD("szept-net".toByteArray())
    val ct = iv + c.doFinal(json.toByteArray(Charsets.UTF_8))
    val pad = MessageDigest.getInstance("SHA-256").digest("szept/siec/2.9".toByteArray())
    val masked = ByteArray(32) { (key[it].toInt() xor pad[it].toInt()).toByte() }
    val b64 = Base64.getEncoder()
    return b64.encodeToString(ct) to b64.encodeToString(masked)
}

android {
    namespace = "pl.szept"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.szept.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "2.9"
        // klucz KLIPY (GIF-y) — można też wpisać w aplikacji lub rozesłać z panelu administratora
        val klipy = rootProject.file("klipy.key").takeIf { it.exists() }?.readText()?.trim() ?: secret("KLIPY_KEY") ?: ""
        buildConfigField("String", "KLIPY_KEY", "\"" + klipy.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + "\"")
        // zaszyfrowany klucz panelu administratora (bez niego panel jest niedostępny)
        val adminEnc = secret("SZEPT_ADMIN_ENC") ?: ""
        buildConfigField("String", "ADMIN_ENC", "\"" + adminEnc.filter { it.isLetterOrDigit() || it in "+/=" } + "\"")
        // wbudowane serwery TURN i klucz API Metered — zaszyfrowane AES-GCM losowym kluczem tej kompilacji
        val (blob, mask) = sealedNetConfig()
        buildConfigField("String", "NET_CFG", "\"" + blob + "\"")
        buildConfigField("String", "NET_K", "\"" + mask + "\"")
    }

    // podpis wydania: plik klucza i hasła z secrets.properties albo zmiennych środowiskowych (poza repozytorium)
    val keystore = rootProject.file(secret("SZEPT_KEYSTORE") ?: "szept.keystore")
    val canSign = keystore.exists() && secret("SZEPT_STORE_PASS") != null
    signingConfigs {
        if (canSign) create("release") {
            storeFile = keystore
            storePassword = secret("SZEPT_STORE_PASS")
            keyAlias = secret("SZEPT_KEY_ALIAS") ?: "szept"
            keyPassword = secret("SZEPT_KEY_PASS") ?: secret("SZEPT_STORE_PASS")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // plik wynikowy z numerem wersji w nazwie, np. Szept-1.4.apk
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "Szept-${variant.versionName}" + (if (variant.buildType.name == "release") "" else "-${variant.buildType.name}") + ".apk"
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // biblioteki natywne spakowane (mniejszy plik APK)
            useLegacyPackaging = true
            // SQLCipher tylko dla procesorów ARM (telefony); na x86 baza działa bez szyfrowania
            excludes += listOf("lib/x86/libsqlcipher.so", "lib/x86_64/libsqlcipher.so")
            // WebRTC (rozmowy) tylko dla telefonów ARM — ok. 25 MB mniej
            excludes += listOf("lib/x86/libjingle_peerconnection_so.so", "lib/x86_64/libjingle_peerconnection_so.so")
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("szept.network", System.getProperty("szept.network") ?: "")
                it.systemProperty("szept.adminpw", System.getProperty("szept.adminpw") ?: "")
                it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
                it.systemProperty("robolectric.dependency.repo.id", "gcs-central")
                it.testLogging { showStandardStreams = true; events("passed", "failed", "skipped") }
            }
        }
    }
}

// W testach JVM używamy natywnej biblioteki secp256k1 dla Linuksa zamiast androidowej.
configurations.matching { it.name.contains("UnitTest", ignoreCase = true) }.configureEach {
    exclude(group = "fr.acinq.secp256k1", module = "secp256k1-kmp-jni-android")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.20.0")
    // szyfrowanie bazy w telefonie
    implementation("net.zetetic:sqlcipher-android:4.16.0@aar")
    implementation("androidx.sqlite:sqlite:2.6.2")
    // okrągłe notatki wideo (kamera w aplikacji)
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // rozmowy głosowe i wideo
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.20.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}
