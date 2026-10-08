# Szept — komunikator na Androida bez własnego serwera

## Instalacja
1. Skopiuj plik `Szept-<wersja>.apk` (np. `Szept-2.2.apk`) na telefon (Android 8 lub nowszy) i otwórz go. Zezwól na instalację z nieznanych źródeł.
2. Wpisz swoje imię i wybierz „Zaczynamy”. To cała konfiguracja — nie trzeba podawać numeru telefonu, e-maila ani adresu serwera.
3. Zezwól na powiadomienia i na działanie **bez ograniczeń baterii** (baner na ekranie głównym). Na telefonach Xiaomi, Huawei, Oppo i Vivo włącz też „Autostart”.

## Jak dodać znajomych
- **Kontakt:** ikona QR → „Mój kod”. Znajomy wybiera „Dodaj kontakt” → „Skanuj kod QR” albo wkleja wysłany mu link `szept://…`.
- **Grupa:** „Nowa grupa” → Informacje o grupie → „Link zaproszenia”. Wyślij link np. na WhatsAppie lub SMS-em. Każdy, kto go otworzy, dołącza automatycznie, gdy którykolwiek administrator jest online — albo czeka na zatwierdzenie, jeśli admin włączył „Zatwierdzaj nowe osoby”. Można też utworzyć link ważny np. 1 dzień albo dla 1 osoby.
- Wszyscy członkowie grupy widzą się nawzajem, więc **każdy może napisać prywatnie do każdego**.

## Aktualizacja
Każda wersja ma numer w nazwie pliku (`Szept-1.9.apk`, potem `Szept-2.2.apk`…). Numer zainstalowanej wersji widać w Ustawieniach → „O aplikacji” i na ekranie powitalnym. Nowszą wersję instalujesz tak samo, otwierając plik na telefonie. Zainstaluje się „na” starą wersję i zachowa konto, kontakty, grupy i wiadomości.

## Funkcje
### Nowe w 2.7
- **Moderacja**: administrator aplikacji może wyciszyć, zablokować lub usunąć konto (podpisana lista sankcji, której pilnuje każda kopia aplikacji), podejrzeć hasło panelu i tworzyć **kody jednorazowe**; administratorzy grup mogą **wyciszać i banować** członków.

### Nowe w 2.6
- Poprawki stabilności i bezpieczeństwa (awaria kolejki wysyłki, walidacja plików i kluczy z sieci, wysyłanie nie ginie po wyjściu z czatu) oraz **dziennik błędów** w Ustawieniach → Pomoc.

### Nowe w 2.5
- **GIF-y**: animowane, z podglądem przed wysłaniem; **Moje GIF-y** zapisane lokalnie (dodaj z telefonu lub zapisz z rozmowy); wyszukiwarka **KLIPY** (klucz z klipy.com — w oknie GIF-ów, w panelu administratora albo w pliku `klipy.key` przy budowaniu).
- Edytor zdjęć tylko dla zdjęcia profilowego i banera.

### Nowe w 2.4
- **Rozmowy prywatne naprawione** i w nowym wyglądzie; **dodawanie osób do trwającej rozmowy** (dzwoni do nich, także ponownie).
- Edytor zdjęć: poprawione kadrowanie i rozmieszczenie przycisków; **Usuń ze znajomych** w profilu osoby.

### Nowe w 2.3
- **Statusy**: aktywny / w tle / zaraz wracam / nieaktywny — kropka przy awatarze, opis w czacie i profilu; wybór w Mój profil → „Mój status”.
- **Odpowiedź gestem**: przesuń wiadomość w prawo = odpowiedz, przytrzymaj = reakcja.
- **Skan QR** prosto z ekranu „Mój kod”.

### Nowe w 2.2
Pełna lista zmian: w aplikacji **Ustawienia → Co nowego** oraz w pliku `CHANGELOG.md`.
- Prostsze ustawienia, PIN i kod awaryjny z ikonką kosza, oznaczenie „usunięte u rozmówcy”, zakaz zrzutów ekranu, lokalizacja na żywo do 8 h.
- **Oficjalne konto „Szept” ✔** i **panel administratora** (Ustawienia → na dole „Panel administratora”, tylko z hasłem):
  - wysyłanie aktualizacji i wiadomości do wybranych osób (także spoza znajomych — po kodzie/linku), z licznikiem odbiorców; u nadawcy nic nie zostaje w historii, odbiorcy nie mogą odpowiadać;
  - nadawanie i odbieranie znaczka ✔ (podpisany kluczem administratora — nie do podrobienia);
  - wymuszanie aktualizacji: wybierasz najstarszą dozwoloną wersję, starsze pokażą ekran „Musisz zaktualizować” z przyciskiem pobrania.
- Aktualizacje (pliki aplikacji) przyjmowane są wyłącznie od konta oficjalnego.

### Nowe w 2.1
- Zdjęcie profilowe i baner można **przyciąć** przy wyborze (kadr 1:1 dla zdjęcia, 3:1 dla baneru) i zapisują się od razu — bez wychodzenia z profilu.
- Wybór zdjęcia nie wywołuje już blokady PIN-em po powrocie z galerii.
- Wiadomość czasowa „od otwarcia” nie pokazuje u nadawcy mylącego licznika 1 h — odliczanie startuje dopiero po otwarciu.

### Nowe w 2.0
- **Rozmowy grupowe** (ikona słuchawki w czacie grupy; wideo w menu ⋮): dzwonią wszyscy członkowie, a kto nie zdążył, dołącza później z paska „Trwa rozmowa grupowa” w czacie. Każdy łączy się bezpośrednio z każdym, więc bez serwera mieści się do **6 osób** (głos) albo **4 osób** (wideo). Wyjście jednej osoby nie kończy rozmowy pozostałym.

### Nowe w 1.9
- **Rozmowy głosowe i wideo** (ikona słuchawki w rozmowie prywatnej; wideo w menu ⋮): szyfrowane end-to-end (WebRTC, DTLS-SRTP), dźwięk i obraz płyną bezpośrednio między telefonami. Dzwonek na pełnym ekranie (także na zablokowanym telefonie), wyciszanie, głośnik, przełączanie kamery, wygaszanie ekranu przy uchu, powrót do czatów w trakcie rozmowy, „Nieodebrane połączenie” w historii.
- Jeśli używasz Szepta na kilku urządzeniach, dzwonią wszystkie — odbierasz na jednym.
- Działa też bez internetu w tej samej sieci Wi-Fi (tryb „Bez internetu”).
- Uczciwie: w części sieci komórkowych połączenie bezpośrednie jest blokowane — wtedy pomaga Wi-Fi albo własny serwer TURN (Ustawienia → Rozmowy). Rozmówca może poznać Twój adres IP.

### Nowe w 1.8
- **Bez internetu** (Ustawienia → Bez internetu): telefony w tej samej sieci Wi-Fi piszą do siebie bezpośrednio — każdy telefon z włączoną opcją działa jak mały przekaźnik i znajduje pozostałe sam. Nie ma routera? Jeden telefon tworzy sieć Wi-Fi („Utwórz sieć Wi-Fi”), a inni skanują jego kod. Działają tekst, zdjęcia, głosówki i grupy; wiadomość dla kogoś nieobecnego czeka w telefonie, aż ta osoba się pojawi.
- **Kurier**: koperty zostawione w Twoim telefonie przez innych wysyłają się do internetu, gdy złapiesz zasięg. Opcjonalnie telefon zabiera też z internetu koperty dla znajomych i oddaje im je, gdy spotkacie się bez internetu (treści nie widzisz — są zaszyfrowane).
- **Jedno konto na kilku urządzeniach** (Ustawienia → Moje urządzenia): zaloguj się kodem konta na drugim telefonie lub tablecie — urządzenia same się odnajdą. Wysłane wiadomości, przeczytanie, reakcje, edycje, usuwanie, wyciszenie, nowe kontakty i grupy synchronizują się.
- **Rotacja kluczy (forward secrecy)** (Prywatność i blokada): wiadomości do Ciebie są szyfrowane kluczem, który telefon zmienia co tydzień i kasuje po 5 tygodniach. Działa z nadawcami z wersją 1.8+, starsze wersje dalej mogą pisać.

### Nowe w 1.7
- **Edytor zdjęć** (✏️ Edytuj w podglądzie przed wysłaniem, w albumie i w relacji): kadrowanie, obrót, rysowanie palcem, zamazywanie fragmentu i **automatyczne zamazanie twarzy** — wszystko w telefonie, przed zaszyfrowaniem.
- **Okrągłe notatki wideo** (📎 → Notatka wideo): przednia kamera (można przełączyć), do 1 minuty — dotknij czerwony przycisk, aby nagrywać, i jeszcze raz, aby wysłać. W rozmowie odtwarzają się w kółku.
- **Głosówka na tekst** (przytrzymaj głosówkę → 📝 Zamień na tekst): rozpoznawanie mowy **w telefonie**, bez wysyłania nagrania nigdzie. Wymaga Androida 13+ i pakietu mowy polskiej (przy pierwszym użyciu telefon może poprosić o jego pobranie). Tekst widzisz tylko Ty.
- **Kopia zapasowa** (Ustawienia → Kopia zapasowa): konto, kontakty, grupy, cała historia i — jeśli chcesz — zdjęcia i filmy w jednym pliku `.szept` zaszyfrowanym Twoim hasłem (AES-256, PBKDF2). Na nowym telefonie: ekran powitalny → „Przywróć z kopii zapasowej”.
- **Zaszyfrowana baza wiadomości** w telefonie (SQLCipher, AES-256): klucz siedzi w sprzętowym magazynie kluczy Androida. Istniejąca baza zostaje zaszyfrowana automatycznie przy pierwszym uruchomieniu 1.7. Stan widać w Prywatność i blokada → „Dane w telefonie”.
- Zgodność z telefonami z pamięcią 16 KB (Android 15+) i nowsze biblioteki.

### Nowe w 1.6
- **Reakcje** (przytrzymaj wiadomość → 👍 ❤️ 😂 😮 😢 🙏 lub „+”) i **edycja** własnych wiadomości („edytowano”).
- **Ankiety** (📎 → Ankieta): jedna lub kilka odpowiedzi, wyniki na żywo, „Kto głosował”.
- **„Przeczytane przez…”** w grupach (przytrzymaj swoją wiadomość → Informacje) i wskaźnik **„pisze…”** (w rozmowach do 16 osób; można wyłączyć).
- **Przekazywanie** wiadomości do innych rozmów, **wzmianki @imię** w grupach (powiadamiają nawet w wyciszonej grupie; admin może użyć @wszyscy).
- **Zaplanowane wiadomości**: przytrzymaj Wyślij → „🕓 Zaplanuj wysłanie…” (także zdjęcia z podglądu). Telefon wyśle je sam o wybranej godzinie.
- **Przypięte wiadomości / ogłoszenia** (w grupie przypina admin) — pasek na górze rozmowy.
- **Lokalizacja** (📎 → Lokalizacja): jednorazowo albo **na żywo przez 15 min lub 1 h**. Bez usług Google; mapa otwiera się w aplikacji map.
- **Relacje 24 h** (pasek na górze listy rozmów): zdjęcie lub tekst na kolorowym tle, z listą wyświetleń.
- **Wiadomość do wielu** (menu ⋮): każdy dostaje ją osobno.
- **Kilka zdjęć naraz** (album do 10 zdjęć, wyświetlany jako siatka), przeglądanie zdjęć rozmowy palcem, **zapis do galerii** i **galeria rozmowy** (media, pliki, linki).
- **Grupy:** opis, ręczne zatwierdzanie nowych osób, linki z limitem czasu i liczby użyć.
- **Ukryte czaty** (przytrzymaj rozmowę → Ukryj): znikają z listy, a pokazujesz je, wpisując osobny PIN w wyszukiwarce.
- **Treść powiadomień:** pełna, tylko nadawca albo nic (Prywatność i blokada). Na Androidzie 14+ uczestnicy dostają informację o zrzucie ekranu rozmowy.
- **Wyszukiwarka** (lupa na górze): rozmowy i wiadomości, z przejściem do znalezionej wiadomości; wyszukiwanie także w rozmowie (⋮ → Szukaj).
- **Przypinanie i archiwum rozmów** (przytrzymaj rozmowę), **szkice** (niewysłany tekst czeka w polu).
- **Wygląd** (Ustawienia): motyw jasny/ciemny/czarny, 8 kolorów, rozmiar tekstu, tapety rozmów, dźwięk powiadomień — także osobny dla wybranej rozmowy.
- **Własny przekaźnik** (Ustawienia): instrukcja dla Raspberry Pi/komputera i link, którym znajomi dodają Twoje przekaźniki jednym dotknięciem.

### Wcześniej
- **Profil jak na Discordzie** (menu ⋮ → Mój profil): zdjęcie profilowe, baner, status, opis. Znajomi widzą je po dotknięciu Twojego imienia.
- **Stały ID** (np. `SZPT-7QF3-K9A2`) — wynika z klucza konta i nie da się go zmienić, nawet gdy zmienisz nazwę.
- **Nazwy:** swoją zmieniasz w profilu, nazwę grupy — admin w Informacjach o grupie, a każdą osobę możesz nazwać po swojemu (✏️ w profilu osoby — widzisz to tylko Ty).
- **Ikona grupy** (zdjęcie albo emoji) — zmienia admin, widzą wszyscy.
- **Streak 🔥** — liczba kolejnych dni, w których oboje do siebie piszecie (w nagłówku czatu i w profilu).
- **Osoby** (menu ⋮): wszyscy, których zna Twój telefon (kontakty + osoby z Twoich grup), z wyszukiwaniem po nazwie lub ID i blokowaniem.
- **Prywatność i blokada** (Ustawienia): PIN przy wejściu, odblokowanie odciskiem palca/twarzą oraz **kod awaryjny (wipe)** — wpisany zamiast PIN-u po cichu kasuje wszystkie albo wybrane czaty, może zablokować wszystkich lub wybranych i opcjonalnie usunąć konto.
- **Kod dostępu do konta** (Ustawienia): Twój klucz w postaci kodu i QR — wpisany w „Mam już konto” na innym telefonie przenosi konto.
- **Aktualizacje od znajomych** (Ustawienia → „Wyślij aktualizację wszystkim”): rozsyła zainstalowaną wersję do grup i kontaktów; u nich pojawia się „Dostępna nowa wersja — Zainstaluj”.
- Wiadomości prywatne i grupowe (20+ osób, bez limitu), potwierdzenia ✓ wysłano, ✓✓ dostarczono i przeczytano.
- Odpowiedzi z cytatem: przytrzymaj wiadomość → „Odpowiedz”. Dotknięcie cytatu przewija do oryginału. Czat ma separatory dni („Dzisiaj”, „Wczoraj”…).
- Zdjęcia (z galerii i aparatu), wideo (automatyczna kompresja do ok. 480p, maksymalnie 16 MB), pliki.
- **Głosówki jednym palcem** (przycisk mikrofonu):
  - przytrzymaj — nagrywasz, puść — wysłane,
  - przesuń w lewo — anulujesz,
  - przesuń w górę albo krótko dotknij — nagrywasz bez trzymania (potem „Wyślij” lub kosz),
  - podczas nagrywania widać poziom głosu, a w dymku przebieg nagrania. Dotknięcie przebiegu przewija, przycisk 1×/1,5×/2× zmienia prędkość.
- Usuwanie: u mnie, u rozmówcy, u wybranych osób w grupie albo u wszystkich (także z przekaźników).
- **Wiadomości czasowe** (tekst, zdjęcie, wideo, głosówka, plik):
  - u odbiorcy widać tylko „⏱ Wiadomość czasowa — dotknij, aby przeczytać”. Treści nie ma ani w powiadomieniu, ani na liście czatów; zdjęcie nie ma nawet miniatury,
  - **czas liczy się dopiero od dotknięcia**. Wtedy pojawia się treść i odliczanie (np. 10 s), potem wiadomość znika. Nieotwarta czeka,
  - głosówka czasowa od razu się odtwarza, a wideo i głosówka nie znikną w trakcie odtwarzania,
  - nadawca widzi „Czeka na otwarcie” lub „Otwarto”. Zrzuty ekranu są blokowane.
  - Jak wysłać: tekst — przytrzymaj przycisk Wyślij i wybierz czas; zdjęcie lub wideo — „⏱ Czasowe” w podglądzie; głosówka — „⏱ Czasowa” podczas nagrywania bez trzymania; cały czat — ikona ⏱ → „Wiadomości czasowe”.
- **Automatyczne usuwanie** (ikona ⏱ w czacie): wiadomości widać normalnie, a znikają u wszystkich po 5 s … 7 dni od wysłania.
- Wyciszanie czatów (Informacje o grupie lub kontakcie). Na liście czatów widać wtedy ikonę 🔕.
- Odpowiedź prosto z powiadomienia oraz przycisk „Przeczytane”.
- Administrator grupy może zmienić jej nazwę, dodawać i usuwać członków, nadawać uprawnienia admina, włączyć tryb „tylko admini piszą”, unieważnić link zaproszenia i rozwiązać grupę.
- Blokowanie kontaktów, odcisk klucza do weryfikacji tożsamości, kopia klucza do przeniesienia konta na nowy telefon.
- „Wyślij aplikację znajomym” (menu ⋮ albo Ustawienia) — udostępnia plik APK przez Quick Share, Bluetooth, komunikator lub e-mail.
- Odporność na źle ustawiony zegar w telefonie: aplikacja koryguje czas według przekaźników. Bez tego telefon ze spieszącym się zegarem nie mógłby wysyłać, a ze spóźniającym — byłby słabo odbierany.

## Jak to działa bez serwera?
Aplikacja nie ma własnego serwera ani firmy, przez którą wszystko przechodzi. Konto to para kluczy wygenerowana w telefonie. Do przekazywania wiadomości służą **publiczne przekaźniki Nostr** — domyślnie 8, prowadzonych przez różne niezależne osoby i firmy. Działają jak skrzynki pocztowe:

```
Twój telefon ──🔒 koperta──►  przekaźnik 1 ┐
             ──🔒 koperta──►  przekaźnik 2 ├──► telefon odbiorcy pobiera „swoje” koperty,
             ──🔒 koperta──►  …  (8 naraz) ┘    gdy tylko ma internet (także po włączeniu)
```


1. Każda wiadomość jest szyfrowana end-to-end **osobno dla każdego odbiorcy** (ECDH secp256k1 → HKDF → AES-256-GCM) i podpisana kluczem nadawcy.
2. Zaszyfrowana wiadomość trafia do „koperty” podpisanej jednorazowym kluczem. Przekaźnik nie widzi więc ani treści, ani nadawcy.
3. Koperta jest wysyłana na wszystkie przekaźniki naraz, więc awaria kilku z nich nie przeszkadza.
4. **Odbiorca nieaktywny albo z wyłączonym telefonem:** koperty czekają na przekaźnikach do 21 dni. Po włączeniu telefonu usługa startuje sama i pobiera zaległe wiadomości. Jeśli nadawca był offline, wiadomość czeka w jego kolejce i wychodzi, gdy złapie internet.
5. Media są dzielone na zaszyfrowane paczki po ~44 KB i wysyłane raz, także w grupie. Klucz do nich trafia w kopertach.
6. Wszystkie połączenia są wychodzące (WebSocket/TLS), więc aplikacja działa z każdej sieci i każdego adresu IP, przez Wi-Fi i dane komórkowe, bez przekierowywania portów.
7. **Bez internetu** telefony same stają się przekaźnikami: każdy z włączoną opcją uruchamia mały przekaźnik Nostr (port 4869) i ogłasza się w sieci Wi-Fi (mDNS/NSD). Te same zaszyfrowane koperty krążą wtedy między telefonami, a gdy któryś złapie internet — trafiają też na zwykłe przekaźniki.
8. Przy włączonej **rotacji kluczy** każde urządzenie co 7 dni tworzy nowy klucz i ogłasza go znajomym w zaszyfrowanej kopercie; koperty do niego szyfrowane są tym kluczem, a stare klucze telefon kasuje.

W tle działa usługa z cichym powiadomieniem „Szept odbiera wiadomości”. Dodatkowo co 10 minut alarm, a co 15 minut WorkManager sprawdzają połączenie.

**Tryb pracy w tle** (Ustawienia):
- **Zrównoważony** (domyślny) — gdy aplikacja jest zamknięta, telefon trzyma na żywo tylko 2 połączenia, więc wiadomości przychodzą od razu (w teście ok. 0,3 s), a pozostałe przekaźniki są synchronizowane co ok. 10 minut. Zużywa wyraźnie mniej baterii niż 8 stałych połączeń.
- **Pełny** — wszystkie przekaźniki na żywo.
- **Oszczędny** — bez stałego połączenia, sprawdzanie co ok. 10–15 minut.

Listę przekaźników można zmienić w Ustawieniach. Każdy może też dodać własny przekaźnik, ale nie jest to wymagane.

## Budowanie ze źródeł
```
./gradlew assembleRelease          # wynik: app/build/outputs/apk/release/Szept-<wersja>.apk
./gradlew testDebugUnitTest -Dszept.network=1   # testy end-to-end na prawdziwych przekaźnikach
```
Aktualizacje podpisuj zawsze tym samym kluczem (plik `szept.keystore`), inaczej Android nie zainstaluje nowej wersji na starszą.
Klucz, hasła i zaszyfrowany klucz administratora **nie są w repozytorium** — skopiuj `secrets.properties.example` do `secrets.properties`
(albo ustaw zmienne środowiskowe `SZEPT_KEYSTORE`, `SZEPT_STORE_PASS`, `SZEPT_KEY_ALIAS`, `SZEPT_KEY_PASS`, `SZEPT_ADMIN_ENC`, `KLIPY_KEY`).
Bez nich aplikacja buduje się normalnie, tylko bez podpisu wydania i bez panelu administratora.
