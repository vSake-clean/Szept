# Szept — notatki dla programisty AI

Komunikator na Androida bez własnego serwera (Nostr). Kotlin + Jetpack Compose, minSdk 26, pakiet `pl.szept.app`.
Z właścicielem rozmawiaj po polsku, prosto i krótko. Pytaj przed rzeczami nieodwracalnymi
(force push, zmiany w `main`, kasowanie danych, wydanie).

## Zasady commitów (ważne)
- **Nigdy nie dodawaj Claude jako autora ani współautora**: bez `Co-Authored-By: Claude…` i bez `Claude-Session:`
  w commitach i PR-ach. Autor commitów to właściciel (`vSake <149900710+vSake-clean@users.noreply.github.com>`).
- Przy scalaniu PR-ów z commitami autorstwa Claude: squash z własnym opisem.
- `secrets.properties` i `szept.keystore` nigdy nie trafiają do repozytorium (są w `.gitignore`).

## Budowanie w kontenerze
1. Android SDK (nie ma go w czystym kontenerze):
   ```
   mkdir -p /opt/android-sdk/cmdline-tools && cd /tmp
   curl -sSfLo clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
   unzip -qo clt.zip -d /opt/android-sdk/cmdline-tools && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
   yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
   /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
   echo "sdk.dir=/opt/android-sdk" > local.properties
   ```
2. Sekrety: właściciel wgrywa `secrets.properties` i `szept.keystore` — skopiuj je do katalogu głównego repo.
   Kontener jest tymczasowy, w nowej sesji trzeba je wgrać ponownie.
   Odcisk SHA-256 certyfikatu wydań: `1B:5B:8C:09:F6:DE:DF:27:92:BC:BA:BD:D0:36:AE:21:C2:94:92:8E:D1:03:87:16:63:1C:77:BC:06:0B:14:84`
   (sprawdzenie: `keytool -list -v -keystore szept.keystore`). Inny klucz = aktualizacja się nie zainstaluje.
3. Wydanie: `./gradlew assembleRelease` → `app/build/outputs/apk/release/Szept-<wersja>.apk`.
4. Testy (Robolectric): `./gradlew testDebugUnitTest`; z prawdziwymi przekaźnikami: `-Dszept.network=1`.
   `SafetyTest.builtInTurnServers` wymaga `secrets.properties`.

## Rozsyłanie aktualizacji
- APK musi zmieścić się w limitach przekaźników: dziś ok. 33 MB = ok. 750 paczek po 44 000 B (tylko ARM, bez x86).
- Publiczne przekaźniki limitują zapis: damus banuje IP po wielu „rate-limited”, nos.lol/oxtr każą zwolnić,
  oxtr bywa pełny („No space left on device”), wellorder bywa martwy. Wysyłka (od 3.4.2): jeden plik naraz na
  przekaźnik, okno 2–6 paczek, na „slow down” okno 1 + przerwa, po 5 skargach pauza 3 min; „banned”/pełny dysk =
  omijanie 6–24 h (`mc:<url>` w prefs) i przeniesienie części pliku. Nie przyspieszaj tego bez pomiaru.
- Starsze wersje (≤ 3.3.4) pobierają bez numerów paczek — dostaną komplet tylko, gdy żaden przekaźnik nie ma więcej
  niż ~500 paczek pliku (pilnuje tego `ChunkPlan.build`). Zapasowo: link do wydań na GitHubie (`Admin.RELEASES_URL`).

## Języki (od 3.4)
- Teksty w kodzie są po polsku i **zawsze** przez `tr("tekst {0}", wartość)` (`pl.szept.tr`, plik `I18n.kt`).
- Tłumaczenie angielskie: `app/src/main/assets/i18n/en.json` (klucz = tekst polski). `I18nTest` pilnuje,
  żeby każdy polski tekst miał tłumaczenie i te same `{0}` oraz spacje na brzegach.
- Teksty liczone raz przy starcie (listy, enumy, `val` w obiektach) zmieniają się po ponownym uruchomieniu —
  dlatego zmiana języka restartuje aplikację (`restartApp`). Nie tłumacz kodów protokołu, kluczy, SQL, nagłówków HTTP.
- Nowy język: plik `assets/i18n/<kod>.json`, wpis w `I18n.LANGS` i flaga w `LanguageFlag` (`ui/LanguageScreens.kt`).
- Kreator (`ui/style/Wizard.kt`): strona 1 = język. `WIZARD_VERSION` = kod wersji, od której każdy ma go zobaczyć ponownie.

## Nowa wersja
- `app/build.gradle.kts`: podnieś `versionCode` i `versionName`.
- Dopisz zmiany na górze `CHANGELOG.md` i w `CHANGELOG` w `ui/SettingsScreens.kt` (Ustawienia → Co nowego, przez `tr`, z tłumaczeniem w `en.json`) oraz sekcję „Nowe w …” w `README.md` i `README.en.md`.
- Wydanie na GitHubie: tag `v<wersja>`, plik `Szept-<wersja>.apk` (tylko po zgodzie właściciela).

## Mapa kodu
- `core/Core.kt` — serce: wysyłka i odbiór, koperty, grupy, panel administratora, PIN-y, gry, rozmowy.
- `core/ChunkPlan.kt` — rozkład paczek pliku na przekaźniki (od 3.3.5). Przekaźniki oddają max ~500 zdarzeń
  na zapytanie (relay.nostr.net 100), więc każdy przekaźnik dostaje ≤ limit paczek pliku; pobieranie pyta o numery (`#c`).
  Szybka wysyłka (`fastSendMode`: 0 auto = Wi-Fi tak / dane nie, 1 zawsze, 2 nigdy) = wszystkie przekaźniki, więcej równolegle.
- `core/SafetyCode.kt` — kod bezpieczeństwa rozmowy (60 cyfr + QR `szept://verify`).
- `crypto/Crypto.kt` — secp256k1 (Schnorr BIP-340), ECDH → HKDF-SHA256 → AES-256-GCM.
- `nostr/` — przekaźniki i zdarzenia. Koperta: kind 1059, w środku kind 14; paczki mediów: kind 4334 po 44 000 B; wygasanie 21 dni.
- `lan/` — własny przekaźnik (port 4869) i wykrywanie w Wi-Fi (NSD).
- `call/` — WebRTC (rozmowy 1:1 i grupowe), serwery TURN wbudowane zaszyfrowane przy budowaniu (`NetConfig.kt`).
- `data/` — baza SQLCipher (`Db.kt`, `Models.kt`).
- `service/` — działanie w tle, powiadomienia, WorkManager.
- `ui/` — ekrany; `ui/games/` — gry; `ui/style/` — 23 style, animacje, Liquid Glass, kreator wyglądu.
- `assets/pygame/` — gry w Pythonie (Pyodide).
