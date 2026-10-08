-keep class fr.acinq.secp256k1.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-keep class com.journeyapps.barcodescanner.** { *; }

# WebRTC (rozmowy): kod natywny woła klasy Javy po nazwach
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# czytelne nazwy w dzienniku błędów (kod nadal jest odchudzany, tylko bez zmiany nazw)
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
