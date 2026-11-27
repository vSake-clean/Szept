package pl.szept.core

import pl.szept.tr

/** Słowa, pytania i zadania do gier (wszystko w aplikacji — bez internetu). */
object GameData {

    /** Pięcioliterowe słowa do „Literaków”. */
    val WORDS5: List<String> = """
        AKTOR ALBUM ANIOŁ ARBUZ AUTOR BALET BANAN BATON BERET BIURO BLASK BLUZA BOMBA BRAMA BRODA BUDKA BURZA CEGŁA CHATA CHLEB
        DESKA DRZWI DUSZA DYNIA DZBAN DZIÓB FARBA FOTEL GÓRAL HASŁO HOTEL KABEL KAJAK KANAŁ KAPEĆ KARTA KASZA KLASA KOBRA KOGUT
        KOMAR KONIK KOTEK KREDA KROWA KRZYŻ KUBEK KWIAT LALKA LAMPA LISEK ŁAPKA ŁAWKA ŁÓDKA ŁYŻKA MASŁO MATKA MEBEL METRO MIARA
        MIECZ MISKA MLEKO MOTYL MUCHA NOTES OBIAD OGIEŃ OPERA ORZEŁ OSIOŁ PALEC PANDA PASEK PIŁKA PIÓRO PIZZA PLAŻA POMPA POWÓZ
        PUDŁO RADIO RAMKA ROBOT ROWER RYBAK RZEKA SANKI SEREK SKAŁA SKARB SŁOIK STRUŚ SZAFA SZKŁO SZOPA SZYJA ŚWIAT TEATR TEKST
        TORBA TRAWA ULICA WAFEL WAGON WANNA WAZON WIDOK WIEŻA WINDA WOJNA WÓZEK WYSPA ZAMEK ZEBRA ZEGAR ŻABKA ŻUREK ŻYCIE AGENT
        AKCJA BAGNO BAJKA BALON BARAN BEKON DROGA FLAGA GARAŻ GLINA GRZYB HAMAK HUMOR KAKAO KAWKA KLUCZ KOLEJ LASER MAGIA MORZE
        NAPÓJ NIEBO NUTKA OCEAN OKRĘT OSOBA PAJĄK PAŁAC POKÓJ RĘKAW ROZUM SALON SAMBA SERCE SIANO SOSNA SPORT STRAŻ TAJGA TANGO
        TĘCZA TUNEL TUBKA UCZEŃ URLOP WIATR WIRUS WOREK ZAJĄC ZORZA AKORD ALARM ARENA ATLAS BARWA BITWA BRZEG CYTAT DESER DIETA
        DOBRO DOLAR DOMEK EKRAN FAJKA FORMA FRAZA GALOP GRUPA GUZIK HONOR IKONA IMBIR JAZDA KAMYK KLAUN KOCYK KOMIN KONTO KOPIA
        LIANA LILIA LINKA LOTOS ŁOSOŚ MAMUT MANGO MEDAL METAL MOTOR NAUKA OBRAZ PASJA PAUZA PERŁA PIRAT POTOK RANEK RUINA SAPER
        SŁOWO STADO SUSZA ŚNIEG TRASA TWARZ WALEC WĄTEK WIDEO ŻETON
    """.trim().split(Regex("\\s+"))

    /** Dłuższe słowa: wisielec, anagramy, „kto pierwszy napisze”. */
    val WORDS: List<String> = """
        KOMPUTER TELEFON CZEKOLADA PARASOL MOTOCYKL SAMOLOT BIEDRONKA ŻYRAFA KANGUR WIEWIÓRKA TRUSKAWKA POMARAŃCZA HELIKOPTER
        PINGWIN KROKODYL HIPOPOTAM DINOZAUR BIBLIOTEKA PIEKARNIA LATARNIA LODÓWKA PRALKA ODKURZACZ KALENDARZ OKULARY RĘKAWICZKI
        SKARPETKI PIŻAMA ŚNIADANIE KOLACJA PIEROGI BIGOS NALEŚNIKI KANAPKA HERBATA KAWIARNIA RESTAURACJA LOTNISKO DWORZEC TRAMWAJ
        AUTOBUS ROWERZYSTA KIEROWCA STRAŻAK POLICJANT NAUCZYCIEL LEKARZ PIELĘGNIARKA OGRODNIK MALARZ MUZYKANT GITARA PERKUSJA
        TRĄBKA SKRZYPCE FORTEPIAN KONCERT FESTIWAL WAKACJE PLECAK NAMIOT OGNISKO JEZIORO WODOSPAD WULKAN PUSTYNIA DŻUNGLA LODOWIEC
        ATLANTYK KONTYNENT PLANETA GALAKTYKA ASTRONAUTA RAKIETA SATELITA TELESKOP MIKROSKOP LABORATORIUM CHEMIA FIZYKA GEOGRAFIA
        HISTORIA MATEMATYKA KALKULATOR LINIJKA KREDKI PLASTELINA NOŻYCZKI ZESZYT PODRĘCZNIK TABLICA SZKOŁA PRZERWA ZAKŁADKA
        KOSZYKÓWKA SIATKÓWKA PIŁKARZ BRAMKARZ STADION OLIMPIADA MEDALISTA MARATON PŁYWALNIA ŁYŻWY NARCIARZ SNOWBOARD HULAJNOGA
        DESKOROLKA TRAMPOLINA HUŚTAWKA PIASKOWNICA ZJEŻDŻALNIA KARUZELA CYRKOWIEC ILUZJONISTA CZARODZIEJ KSIĘŻNICZKA
        RYCERZ ZAMCZYSKO SKARBIEC KOMPAS LATAWIEC BALONIK CUKIERNIA TORCIK PĄCZEK SERNIK SZARLOTKA MANDARYNKA
        CYTRYNA BANANOWIEC ANANAS KOKOS AWOKADO BRZOSKWINIA MORELA WINOGRONO POMIDOR OGÓREK MARCHEWKA ZIEMNIAK KALAFIOR BROKUŁ
        PIETRUSZKA CEBULKA CZOSNEK RZODKIEWKA SAŁATA KUKURYDZA FASOLKA GROSZEK SŁONECZNIK TULIPAN STOKROTKA KONWALIA SASANKA
        PAPROĆ KAKTUS STORCZYK DĄB BRZOZA KASZTAN JARZĘBINA ŻOŁĄDŹ MUCHOMOR BOROWIK KURKI BOCIAN JASKÓŁKA SKOWRONEK DZIĘCIOŁ
        SOKÓŁ JASTRZĄB SOWA PAPUGA FLAMING DELFIN WIELORYB REKIN OŚMIORNICA MEDUZA ROZGWIAZDA HOMAR KRAB ŻÓŁW JASZCZURKA
        KAMELEON WĄŻ PYTON TYGRYS LEOPARD GEPARD NOSOROŻEC SŁOŃ WIELBŁĄD ZEBRA LAMA ALPAKA BÓBR WYDRA BORSUK JEŻOZWIERZ NIEDŹWIEDŹ
    """.trim().split(Regex("\\s+")).filter { it.length in 4..13 && it.all { c -> c.isLetter() } }

    /** Zdania do szybkiego pisania. */
    val SENTENCES = listOf(
        tr("Szybki brązowy lis przeskoczył nad leniwym psem."),
        tr("Zażółć gęślą jaźń."),
        tr("W Szczebrzeszynie chrząszcz brzmi w trzcinie."),
        tr("Kto rano wstaje, temu Pan Bóg daje."),
        tr("Nie ma róży bez kolców."),
        tr("Lepszy wróbel w garści niż gołąb na dachu."),
        tr("Co ma wisieć, nie utonie."),
        tr("Gdzie kucharek sześć, tam nie ma co jeść."),
        tr("Darowanemu koniowi w zęby się nie zagląda."),
        tr("Kto pod kim dołki kopie, ten sam w nie wpada."),
        tr("Apetyt rośnie w miarę jedzenia."),
        tr("Nie wszystko złoto, co się świeci."),
        tr("Prawdziwych przyjaciół poznaje się w biedzie."),
        tr("Bez pracy nie ma kołaczy."),
        tr("Mądry Polak po szkodzie."),
        tr("Jak sobie pościelesz, tak się wyśpisz."),
        tr("Fortuna kołem się toczy."),
        tr("Wszystkie drogi prowadzą do Rzymu."),
        tr("Raz na wozie, raz pod wozem."),
        tr("Niedaleko pada jabłko od jabłoni."),
        tr("Kropla drąży skałę."),
        tr("Czego Jaś się nie nauczy, tego Jan nie będzie umiał."),
        tr("Dzisiaj gramy w Szepcie i świetnie się bawimy."),
        tr("Wysłałem wiadomość, która dotarła bez żadnego serwera."),
        tr("Mój telefon potrafi uruchomić grę w Pythonie."),
    )

    /** Pytanie: treść, cztery odpowiedzi, numer dobrej. */
    data class Question(val q: String, val o: List<String>, val c: Int)

    val QUIZ: List<Question> = listOf(
        Question(tr("Stolica Australii to:"), listOf(tr("Sydney"), tr("Canberra"), tr("Melbourne"), tr("Perth")), 1),
        Question(tr("Ile nóg ma pająk?"), listOf("6", "8", "10", "12"), 1),
        Question(tr("Najdłuższa rzeka w Polsce to:"), listOf(tr("Odra"), tr("Warta"), tr("Wisła"), "Bug"), 2),
        Question(tr("Kto napisał „Pana Tadeusza”?"), listOf(tr("Juliusz Słowacki"), tr("Adam Mickiewicz"), tr("Henryk Sienkiewicz"), tr("Bolesław Prus")), 1),
        Question(tr("Ile wynosi 7 × 8?"), listOf("54", "56", "58", "64"), 1),
        Question(tr("Najwyższy szczyt Polski to:"), listOf(tr("Śnieżka"), tr("Giewont"), tr("Rysy"), tr("Kasprowy Wierch")), 2),
        Question(tr("Symbol chemiczny złota to:"), listOf("Ag", "Au", "Fe", "Zn"), 1),
        Question(tr("Która planeta jest najbliżej Słońca?"), listOf(tr("Wenus"), tr("Mars"), tr("Merkury"), tr("Ziemia")), 2),
        Question(tr("W którym roku Polska wstąpiła do Unii Europejskiej?"), listOf("1999", "2004", "2007", "2010"), 1),
        Question(tr("Ile kontynentów jest na Ziemi (w polskiej szkole)?"), listOf("5", "6", "7", "8"), 2),
        Question(tr("Kto namalował „Mona Lisę”?"), listOf(tr("Michał Anioł"), tr("Leonardo da Vinci"), tr("Rafael"), tr("Rembrandt")), 1),
        Question(tr("Największy ocean na Ziemi to:"), listOf(tr("Atlantycki"), tr("Indyjski"), tr("Spokojny"), tr("Arktyczny")), 2),
        Question(tr("Ile minut ma doba?"), listOf("1240", "1440", "1640", "2400"), 1),
        Question(tr("Stolica Kanady to:"), listOf(tr("Toronto"), tr("Vancouver"), tr("Ottawa"), tr("Montreal")), 2),
        Question(tr("Ile kości ma dorosły człowiek?"), listOf("186", "206", "226", "256"), 1),
        Question(tr("Kto odkrył polon i rad?"), listOf(tr("Maria Skłodowska-Curie"), tr("Mikołaj Kopernik"), tr("Albert Einstein"), tr("Isaac Newton")), 0),
        Question(tr("Ile boków ma sześciokąt?"), listOf("5", "6", "7", "8"), 1),
        Question(tr("Który pierwiastek ma symbol O?"), listOf(tr("Złoto"), "Osm", tr("Tlen"), tr("Ołów")), 2),
        Question(tr("Stolica Hiszpanii to:"), listOf(tr("Barcelona"), tr("Madryt"), tr("Sewilla"), tr("Walencja")), 1),
        Question(tr("Ile wynosi pierwiastek kwadratowy ze 144?"), listOf("11", "12", "13", "14"), 1),
        Question(tr("Największe zwierzę na świecie to:"), listOf(tr("Słoń afrykański"), tr("Płetwal błękitny"), tr("Rekin wielorybi"), tr("Żyrafa")), 1),
        Question(tr("Kto był pierwszym królem Polski?"), listOf(tr("Mieszko I"), tr("Bolesław Chrobry"), tr("Kazimierz Wielki"), tr("Władysław Łokietek")), 1),
        Question(tr("W którym roku wybuchła II wojna światowa?"), listOf("1914", "1939", "1945", "1920"), 1),
        Question(tr("Ile serc ma ośmiornica?"), listOf("1", "2", "3", "8"), 2),
        Question(tr("Jak nazywa się waluta Japonii?"), listOf(tr("Juan"), "Won", "Jen", tr("Rupia")), 2),
        Question(tr("Który kraj ma kształt buta?"), listOf(tr("Hiszpania"), tr("Grecja"), tr("Włochy"), tr("Portugalia")), 2),
        Question(tr("Ile graczy jednej drużyny jest na boisku w piłce nożnej?"), listOf("9", "10", "11", "12"), 2),
        Question(tr("Stolica Norwegii to:"), listOf(tr("Sztokholm"), tr("Oslo"), tr("Helsinki"), tr("Kopenhaga")), 1),
        Question(tr("Ile wynosi 15% ze 200?"), listOf("20", "25", "30", "35"), 2),
        Question(tr("Mikołaj Kopernik urodził się w:"), listOf(tr("Krakowie"), tr("Toruniu"), tr("Fromborku"), tr("Gdańsku")), 1),
        Question(tr("Najmniejsze państwo świata to:"), listOf(tr("Monako"), tr("San Marino"), tr("Watykan"), tr("Liechtenstein")), 2),
        Question(tr("Który gaz rośliny pobierają do fotosyntezy?"), listOf(tr("Tlen"), tr("Azot"), tr("Dwutlenek węgla"), tr("Wodór")), 2),
        Question(tr("Ile województw ma Polska?"), listOf("14", "16", "18", "49"), 1),
        Question(tr("Autor „Quo vadis” to:"), listOf(tr("Henryk Sienkiewicz"), tr("Stefan Żeromski"), tr("Władysław Reymont"), tr("Eliza Orzeszkowa")), 0),
        Question(tr("Stolica Egiptu to:"), listOf(tr("Aleksandria"), tr("Kair"), tr("Luksor"), tr("Giza")), 1),
        Question(tr("Ile zer ma milion?"), listOf("5", "6", "7", "9"), 1),
        Question(tr("Jaki kolor powstaje z połączenia niebieskiego i żółtego?"), listOf(tr("Fioletowy"), tr("Pomarańczowy"), tr("Zielony"), tr("Brązowy")), 2),
        Question(tr("Najszybsze zwierzę lądowe to:"), listOf("Lew", tr("Gepard"), tr("Antylopa"), tr("Koń")), 1),
        Question(tr("Kto skomponował „Polonez As-dur” op. 53?"), listOf(tr("Stanisław Moniuszko"), tr("Fryderyk Chopin"), tr("Karol Szymanowski"), tr("Wojciech Kilar")), 1),
        Question(tr("Ile dni ma rok przestępny?"), listOf("364", "365", "366", "367"), 2),
        Question(tr("Stolica Portugalii to:"), listOf(tr("Porto"), tr("Lizbona"), tr("Faro"), tr("Coimbra")), 1),
        Question(tr("Z ilu pól składa się szachownica?"), listOf("36", "49", "64", "81"), 2),
        Question(tr("W jakim mieście stoi Wawel?"), listOf(tr("Warszawa"), tr("Kraków"), tr("Gniezno"), tr("Poznań")), 1),
        Question(tr("Ile wynosi suma kątów w trójkącie?"), listOf("90°", "180°", "270°", "360°"), 1),
        Question(tr("Który ptak jest symbolem Polski?"), listOf(tr("Sokół"), tr("Bocian"), tr("Orzeł biały"), tr("Jastrząb")), 2),
        Question(tr("Ile planet ma Układ Słoneczny?"), listOf("7", "8", "9", "10"), 1),
        Question(tr("Największa pustynia gorąca na świecie to:"), listOf(tr("Gobi"), tr("Kalahari"), tr("Sahara"), tr("Atakama")), 2),
        Question(tr("Stolica Grecji to:"), listOf(tr("Saloniki"), tr("Ateny"), tr("Sparta"), tr("Korynt")), 1),
        Question(tr("Ile strun ma klasyczna gitara?"), listOf("4", "5", "6", "7"), 2),
        Question(tr("Kto wynalazł żarówkę (najbardziej znany wynalazca)?"), listOf(tr("Nikola Tesla"), tr("Thomas Edison"), tr("Graham Bell"), tr("James Watt")), 1),
        Question(tr("Ile wynosi 2 do potęgi 10?"), listOf("512", "1000", "1024", "2048"), 2),
        Question(tr("Morze, nad którym leży Polska, to:"), listOf(tr("Morze Północne"), tr("Morze Bałtyckie"), tr("Morze Czarne"), tr("Morze Śródziemne")), 1),
        Question(tr("Ile godzin ma tydzień?"), listOf("148", "158", "168", "178"), 2),
        Question(tr("Stolica Węgier to:"), listOf(tr("Praga"), tr("Budapeszt"), tr("Bukareszt"), tr("Wiedeń")), 1),
        Question(tr("Kto napisał „Romea i Julię”?"), listOf(tr("Charles Dickens"), tr("William Szekspir"), tr("Johann Goethe"), tr("Victor Hugo")), 1),
        Question(tr("Najtwardszy naturalny minerał to:"), listOf(tr("Kwarc"), tr("Diament"), tr("Topaz"), tr("Grafit")), 1),
        Question(tr("Ile wynosi 9 × 9?"), listOf("72", "81", "90", "99"), 1),
        Question(tr("Stolica Ukrainy to:"), listOf(tr("Lwów"), tr("Kijów"), tr("Odessa"), tr("Charków")), 1),
        Question(tr("Który narząd pompuje krew?"), listOf(tr("Płuca"), tr("Wątroba"), tr("Serce"), tr("Nerki")), 2),
        Question(tr("Ile pól ma plansza do kółka i krzyżyka?"), listOf("6", "8", "9", "12"), 2),
        Question(tr("Wisła wpada do:"), listOf(tr("Morza Bałtyckiego"), tr("Odry"), tr("Morza Czarnego"), tr("Zalewu Wiślanego tylko")), 0),
        Question(tr("Stolica Czech to:"), listOf(tr("Brno"), tr("Bratysława"), tr("Praga"), tr("Ostrawa")), 2),
        Question(tr("Ile nóg ma owad?"), listOf("4", "6", "8", "10"), 1),
        Question(tr("Który miesiąc ma najmniej dni?"), listOf(tr("Kwiecień"), tr("Luty"), tr("Listopad"), tr("Czerwiec")), 1),
        Question(tr("Język programowania z logo węża to:"), listOf(tr("Java"), tr("Python"), tr("Kotlin"), tr("Ruby")), 1),
        Question(tr("Ile wynosi liczba π w przybliżeniu?"), listOf("2,14", "3,14", "3,41", "4,13"), 1),
        Question(tr("Kto był pierwszym człowiekiem na Księżycu?"), listOf(tr("Jurij Gagarin"), tr("Neil Armstrong"), tr("Buzz Aldrin"), tr("Mirosław Hermaszewski")), 1),
        Question(tr("Stolica Litwy to:"), listOf(tr("Ryga"), tr("Wilno"), tr("Tallin"), tr("Kowno")), 1),
        Question(tr("Ile zębów ma dorosły człowiek (z zębami mądrości)?"), listOf("28", "30", "32", "36"), 2),
        Question(tr("Z czego robi się tradycyjny oscypek?"), listOf(tr("Z mleka krowiego"), tr("Z mleka owczego"), tr("Z mleka koziego"), tr("Z soi")), 1),
        Question(tr("Najdłuższa rzeka świata (według większości źródeł) to:"), listOf(tr("Amazonka"), "Nil", tr("Jangcy"), tr("Missisipi")), 1),
        Question(tr("Ile wynosi 1000 metrów?"), listOf(tr("1 dm"), tr("1 km"), tr("10 km"), "100 m"), 1),
        Question(tr("Stolica Słowacji to:"), listOf(tr("Koszyce"), tr("Bratysława"), tr("Żylina"), tr("Preszów")), 1),
        Question(tr("Kto namalował „Bitwę pod Grunwaldem”?"), listOf(tr("Jan Matejko"), tr("Józef Chełmoński"), tr("Stanisław Wyspiański"), tr("Jacek Malczewski")), 0),
        Question(tr("Który metal jest płynny w temperaturze pokojowej?"), listOf(tr("Ołów"), tr("Rtęć"), tr("Cyna"), tr("Sód")), 1),
        Question(tr("Ile sekund ma godzina?"), listOf("360", "3600", "6000", "36000"), 1),
        Question(tr("Stolica Holandii to:"), listOf(tr("Rotterdam"), tr("Haga"), tr("Amsterdam"), tr("Utrecht")), 2),
        Question(tr("Jak nazywa się młode konia?"), listOf(tr("Cielę"), tr("Źrebię"), tr("Koźlę"), tr("Prosię")), 1),
        Question(tr("Ile kolorów ma tęcza (tradycyjnie)?"), listOf("5", "6", "7", "8"), 2),
        Question(tr("Polskie miasto nazywane „miastem Kopernika” to:"), listOf(tr("Olsztyn"), tr("Toruń"), tr("Bydgoszcz"), tr("Płock")), 1),
        Question(tr("W jakim kraju leży Machu Picchu?"), listOf(tr("Meksyk"), tr("Peru"), tr("Chile"), tr("Boliwia")), 1),
        Question(tr("Ile wynosi 100 − 37?"), listOf("53", "63", "67", "73"), 1),
        Question(tr("Stolica Szwecji to:"), listOf(tr("Göteborg"), tr("Sztokholm"), tr("Malmö"), tr("Uppsala")), 1),
        Question(tr("Który instrument ma klawisze i pedały?"), listOf(tr("Skrzypce"), tr("Fortepian"), tr("Flet"), tr("Bęben")), 1),
        Question(tr("Ile lat trwa wiek?"), listOf("10", "50", "100", "1000"), 2),
        Question(tr("Największe jezioro w Polsce to:"), listOf(tr("Mamry"), tr("Śniardwy"), tr("Hańcza"), tr("Wigry")), 1),
        Question(tr("Który ptak nie lata?"), listOf(tr("Bocian"), tr("Pingwin"), tr("Wróbel"), tr("Jaskółka")), 1),
        Question(tr("Ile boków ma kwadrat?"), listOf("3", "4", "5", "6"), 1),
        Question(tr("Stolica Francji to:"), listOf(tr("Lyon"), tr("Marsylia"), tr("Paryż"), tr("Nicea")), 2),
        Question(tr("Twórca teorii względności to:"), listOf(tr("Isaac Newton"), tr("Albert Einstein"), tr("Niels Bohr"), tr("Stephen Hawking")), 1),
        Question(tr("Ile wynosi 12 × 12?"), listOf("124", "144", "156", "164"), 1),
        Question(tr("Która z tych gier pochodzi z Rosji?"), listOf(tr("Tetris"), tr("Pac-Man"), tr("Mario"), tr("Pong")), 0),
        Question(tr("Ile oczek ma kostka do gry łącznie?"), listOf("18", "21", "24", "36"), 1),
        Question(tr("Stolica Austrii to:"), listOf(tr("Salzburg"), tr("Graz"), tr("Wiedeń"), tr("Innsbruck")), 2),
        Question(tr("Ile wynosi temperatura wrzenia wody na poziomie morza?"), listOf("90°C", "100°C", "110°C", "120°C"), 1),
        Question(tr("Smok Wawelski mieszkał w:"), listOf(tr("Gdańsku"), tr("Krakowie"), tr("Warszawie"), tr("Wrocławiu")), 1),
        Question(tr("Ile kart jest w standardowej talii (bez jokerów)?"), listOf("32", "48", "52", "54"), 2),
        Question(tr("Stolica Niemiec to:"), listOf(tr("Monachium"), tr("Hamburg"), tr("Berlin"), tr("Frankfurt")), 2),
        Question(tr("Ile nut jest w gamie (bez powtórzenia oktawy)?"), listOf("5", "7", "8", "12"), 1),
        Question(tr("Który kontynent jest największy?"), listOf(tr("Afryka"), tr("Azja"), tr("Ameryka Północna"), tr("Europa")), 1),
    )

    /** Zdania do „Prawda czy fałsz” (zdanie, czy prawdziwe). */
    val TRUE_FALSE: List<Pair<String, Boolean>> = listOf(
        tr("Wieloryb jest ssakiem.") to true,
        tr("Pająk jest owadem.") to false,
        tr("Warszawa leży nad Wisłą.") to true,
        tr("Słońce jest gwiazdą.") to true,
        tr("Pingwiny żyją na biegunie północnym.") to false,
        tr("Woda wrze w 100°C na poziomie morza.") to true,
        tr("Mount Everest to najwyższa góra na Ziemi.") to true,
        tr("Delfin jest rybą.") to false,
        tr("Kraków był kiedyś stolicą Polski.") to true,
        tr("Ludzie mają 4 płuca.") to false,
        tr("Księżyc świeci własnym światłem.") to false,
        tr("Diament jest odmianą węgla.") to true,
        tr("Pomidor botanicznie jest owocem.") to true,
        tr("Wenus jest najgorętszą planetą Układu Słonecznego.") to true,
        tr("Struś potrafi latać.") to false,
        tr("Chopin urodził się w Żelazowej Woli.") to true,
        tr("Nietoperz jest ptakiem.") to false,
        tr("Bałtyk jest morzem słonym jak ocean.") to false,
        tr("W roku jest 12 miesięcy.") to true,
        tr("Złoto przewodzi prąd.") to true,
        tr("Koala jest niedźwiedziem.") to false,
        tr("Ośmiornica ma osiem ramion.") to true,
        tr("Szklanka wody zamarza w 10°C.") to false,
        tr("Tlen jest gazem.") to true,
        tr("Pierwszy człowiek w kosmosie to Jurij Gagarin.") to true,
        tr("Australia jest kontynentem i państwem.") to true,
        tr("Rekiny są ssakami.") to false,
        tr("Trójkąt może mieć dwa kąty proste.") to false,
        tr("Liczba 17 jest liczbą pierwszą.") to true,
        tr("Liczba 21 jest liczbą pierwszą.") to false,
        tr("Sahara jest największą pustynią gorącą.") to true,
        tr("Bocian odlatuje na zimę do Afryki.") to true,
        tr("Pszczoły robią miód z nektaru.") to true,
        tr("Ludzkie serce ma dwa przedsionki i dwie komory.") to true,
        tr("Ryby oddychają skrzelami.") to true,
        tr("Kangur pochodzi z Ameryki Południowej.") to false,
        tr("Mars nazywany jest Czerwoną Planetą.") to true,
        tr("Morze Martwe jest bardzo słone.") to true,
        tr("Nil płynie przez Egipt.") to true,
        tr("Tatry leżą w Polsce i na Słowacji.") to true,
        tr("Banany rosną na drzewach liściastych jak jabłonie.") to false,
        tr("Igrzyska olimpijskie pochodzą ze starożytnej Grecji.") to true,
        tr("Ślimak ma szkielet wewnętrzny.") to false,
        tr("Żaba jest płazem.") to true,
        tr("Wąż ma powieki.") to false,
        tr("Jedna godzina ma 3600 sekund.") to true,
        tr("Kwadrat jest prostokątem.") to true,
        tr("Prostokąt zawsze jest kwadratem.") to false,
        tr("Na Antarktydzie żyją niedźwiedzie polarne.") to false,
        tr("Kopernik twierdził, że Ziemia krąży wokół Słońca.") to true,
        tr("Rtęć jest metalem.") to true,
        tr("Hel jest cięższy od powietrza.") to false,
        tr("Polska graniczy z Węgrami.") to false,
        tr("Polska graniczy z Litwą.") to true,
        tr("Kot ma zwykle 5 palców w przednich łapach.") to true,
        tr("Pająki mają osiem oczu (większość gatunków).") to true,
        tr("Liczba 0 jest liczbą parzystą.") to true,
        tr("Wieża Eiffla stoi w Londynie.") to false,
        tr("Mleko krowie jest źródłem wapnia.") to true,
        tr("Dźwięk rozchodzi się w próżni.") to false,
    )

    /** Emoji-zagadki: emoji, odpowiedź, podpowiedź. */
    val EMOJI: List<Triple<String, String, String>> = listOf(
        Triple("🦁👑", tr("Król Lew"), tr("film")),
        Triple("❄️👸", tr("Kraina Lodu"), tr("film")),
        Triple("🕷️🧑", tr("Spider-Man"), tr("film")),
        Triple("🧙‍♂️💍🌋", tr("Władca Pierścieni"), tr("film")),
        Triple("🚢🧊💔", tr("Titanic"), tr("film")),
        Triple("🦖🏝️", tr("Park Jurajski"), tr("film")),
        Triple("👻🚫", tr("Pogromcy duchów"), tr("film")),
        Triple("🐠🔍", tr("Gdzie jest Nemo"), tr("film")),
        Triple("🧸🍯", tr("Kubuś Puchatek"), tr("bajka")),
        Triple("🐉🏰🐑", tr("Smok Wawelski"), tr("legenda")),
        Triple("🧜‍♀️🗡️🌊", tr("Syrenka Warszawska"), tr("legenda")),
        Triple("🐻🐻🐻👧", tr("Złotowłosa i trzy niedźwiadki"), tr("bajka")),
        Triple("🍎👸💤", tr("Królewna Śnieżka"), tr("bajka")),
        Triple("👠🎃🕛", tr("Kopciuszek"), tr("bajka")),
        Triple("🐺👵🧺", tr("Czerwony Kapturek"), tr("bajka")),
        Triple("🤥👃", tr("Pinokio"), tr("bajka")),
        Triple("🧑‍🚀🌕", tr("Lądowanie na Księżycu"), tr("wydarzenie")),
        Triple("⚡🧙‍♂️👓", tr("Harry Potter"), tr("film")),
        Triple("🦇🧑", tr("Batman"), tr("film")),
        Triple("🐼🥋", tr("Kung Fu Panda"), tr("film")),
        Triple("🚗⚡", tr("Auta"), tr("film")),
        Triple("🐀👨‍🍳", tr("Ratatuj"), tr("film")),
        Triple("🎈🏠👴", tr("Odlot"), tr("film")),
        Triple("🤖🌱", "WALL-E", tr("film")),
        Triple("🐦🌅", tr("Kto rano wstaje, temu Pan Bóg daje"), tr("przysłowie")),
        Triple("🐴🎁🦷", tr("Darowanemu koniowi w zęby się nie zagląda"), tr("przysłowie")),
        Triple("🍎🌳", tr("Niedaleko pada jabłko od jabłoni"), tr("przysłowie")),
        Triple("🌹🌵", tr("Nie ma róży bez kolców"), tr("przysłowie")),
        Triple("💧🪨", tr("Kropla drąży skałę"), tr("przysłowie")),
        Triple("🐦✋🕊️🏠", tr("Lepszy wróbel w garści niż gołąb na dachu"), tr("przysłowie")),
        Triple("🍕🇮🇹", tr("Pizza"), tr("jedzenie")),
        Triple("🥟🥟🥟", tr("Pierogi"), tr("jedzenie")),
        Triple("🍫🏭", tr("Charlie i fabryka czekolady"), tr("film")),
        Triple("🎄🏠👦", tr("Kevin sam w domu"), tr("film")),
        Triple("🦈🌊🏖️", tr("Szczęki"), tr("film")),
        Triple("🐒🏙️", tr("King Kong"), tr("film")),
        Triple("🧟‍♂️🌍", tr("Apokalipsa zombie"), tr("film")),
        Triple("🕵️‍♂️🔍🎻", tr("Sherlock Holmes"), tr("postać")),
        Triple("🏴‍☠️🌊💀", tr("Piraci z Karaibów"), tr("film")),
        Triple("🐝🎬", tr("Pszczółka Maja"), tr("bajka")),
        Triple("👨‍🔬⏰🚗", tr("Powrót do przyszłości"), tr("film")),
        Triple("🦸‍♂️🛡️🇺🇸", tr("Kapitan Ameryka"), tr("film")),
        Triple("🔨⚡", tr("Thor"), tr("film")),
        Triple("🐸👑💋", tr("Żabi książę"), tr("bajka")),
        Triple("🍀🌈💰", tr("Szczęście"), tr("rzecz")),
        Triple("☔🎶", tr("Deszczowa piosenka"), tr("film")),
        Triple("🧀🐭", tr("Mysz i ser"), tr("rzecz")),
        Triple("🎅🦌🎁", tr("Święty Mikołaj"), tr("postać")),
        Triple("🐣🥚🐰", tr("Wielkanoc"), tr("święto")),
        Triple("🎃👻🍬", tr("Halloween"), tr("święto")),
    )

    /** „Co wolisz?” */
    val WYR: List<Pair<String, String>> = listOf(
        tr("Umieć latać") to tr("Być niewidzialnym"),
        tr("Mieszkać nad morzem") to tr("Mieszkać w górach"),
        tr("Zawsze lato") to tr("Zawsze zima"),
        tr("Pizza do końca życia") to tr("Lody do końca życia"),
        tr("Czytać w myślach") to tr("Przewidywać przyszłość"),
        tr("Podróż w przeszłość") to tr("Podróż w przyszłość"),
        tr("Mieć smoka") to tr("Mieć jednorożca"),
        tr("Nigdy nie spać") to tr("Nigdy nie jeść"),
        tr("Mówić wszystkimi językami") to tr("Rozmawiać ze zwierzętami"),
        tr("Rok bez telefonu") to tr("Rok bez słodyczy"),
        tr("Wakacje w namiocie") to tr("Wakacje w hotelu"),
        tr("Być bardzo mądrym") to tr("Być bardzo zabawnym"),
        tr("Mieszkać w mieście") to tr("Mieszkać na wsi"),
        "Kot" to tr("Pies"),
        tr("Kawa") to tr("Herbata"),
        tr("Książka") to tr("Film"),
        tr("Zawsze się spóźniać o 10 minut") to tr("Zawsze być godzinę za wcześnie"),
        tr("Teleportacja") to tr("Superszybkość"),
        tr("Śpiewać zamiast mówić") to tr("Tańczyć zamiast chodzić"),
        tr("Wygrać na loterii") to tr("Żyć dwa razy dłużej"),
        tr("Być sławnym") to tr("Być bogatym"),
        tr("Zjeść cytrynę") to tr("Zjeść łyżkę musztardy"),
        tr("Mieć własną wyspę") to tr("Mieć własny samolot"),
        tr("Pracować w nocy") to tr("Pracować bardzo wcześnie rano"),
        tr("Góry") to tr("Jeziora"),
        tr("Słodkie") to tr("Słone"),
        tr("Być kapitanem statku kosmicznego") to tr("Być kapitanem pirackiego statku"),
        tr("Nie móc się śmiać") to tr("Nie móc płakać"),
        tr("Mieć pamięć absolutną") to tr("Umieć zapominać, co się chce"),
        tr("Mieszkać w zamku") to tr("Mieszkać w domku na drzewie"),
        tr("Dzień bez internetu") to tr("Dzień bez prądu"),
        tr("Rower") to tr("Hulajnoga"),
        tr("Biegać maraton") to tr("Przepłynąć jezioro"),
        tr("Mieć supermoc na jeden dzień") to tr("Mieć milion złotych na jeden dzień"),
        tr("Zawsze mówić prawdę") to tr("Zawsze kłamać"),
        tr("Żyć bez muzyki") to tr("Żyć bez filmów"),
        tr("Jeść tylko na zimno") to tr("Jeść tylko na ciepło"),
        tr("Mieć rację") to tr("Mieć spokój"),
        tr("Spotkać kosmitę") to tr("Spotkać dinozaura"),
        tr("Być najlepszym graczem w drużynie") to tr("Być w najlepszej drużynie"),
    )

    /** „Nigdy przenigdy…” */
    val NEVER: List<String> = listOf(
        tr("…nie zasnąłem/zasnęłam w kinie."), tr("…nie zgubiłem/zgubiłam kluczy."), tr("…nie śpiewałem/śpiewałam pod prysznicem."),
        tr("…nie jadłem/jadłam pizzy z ananasem."), tr("…nie wysłałem/wysłałam wiadomości do złej osoby."), tr("…nie spóźniłem/spóźniłam się na pociąg."),
        tr("…nie skakałem/skakałam na bungee."), tr("…nie byłem/byłam za granicą."), tr("…nie płakałem/płakałam na bajce."),
        tr("…nie udawałem/udawałam, że mnie nie ma w domu."), tr("…nie zjadłem/zjadłam czegoś, co spadło na podłogę."),
        tr("…nie zapomniałem/zapomniałam czyichś urodzin."), tr("…nie grałem/grałam w grę całą noc."), tr("…nie tańczyłem/tańczyłam na stole."),
        tr("…nie przewróciłem/przewróciłam się na lodzie."), tr("…nie mówiłem/mówiłam przez sen."), tr("…nie gotowałem/gotowałam dla więcej niż 10 osób."),
        tr("…nie widziałem/widziałam zorzy polarnej."), tr("…nie spałem/spałam pod namiotem."), tr("…nie złamałem/złamałam kości."),
        tr("…nie byłem/byłam w telewizji."), tr("…nie wygrałem/wygrałam żadnego konkursu."), tr("…nie zgubiłem/zgubiłam się w obcym mieście."),
        tr("…nie jechałem/jechałam na koniu."), tr("…nie pływałem/pływałam w morzu nocą."), tr("…nie zrobiłem/zrobiłam sobie tatuażu."),
        tr("…nie przeczytałem/przeczytałam książki w jeden dzień."), tr("…nie oglądałem/oglądałam serialu do rana."),
        tr("…nie wysłałem/wysłałam głosówki dłuższej niż 5 minut."), tr("…nie zapomniałem/zapomniałam hasła do telefonu."),
        tr("…nie śmiałem/śmiałam się w nieodpowiednim momencie."), tr("…nie farbowałem/farbowałam włosów."),
        tr("…nie byłem/byłam na koncercie."), tr("…nie latałem/latałam samolotem."), tr("…nie wspinałem/wspinałam się na górę powyżej 2000 m."),
        tr("…nie zjadłem/zjadłam całej pizzy sam/sama."), tr("…nie dostałem/dostałam mandatu."), tr("…nie zrobiłem/zrobiłam nikomu dowcipu na prima aprilis."),
        tr("…nie zapomniałem/zapomniałam, po co wszedłem/weszłam do pokoju."), tr("…nie pisałem/pisałam wiersza."),
    )

    val TRUTHS: List<String> = listOf(
        tr("Jaka jest Twoja ulubiona piosenka, do której wstyd się przyznać?"), tr("Co najdziwniejszego zdarzyło Ci się zjeść?"),
        tr("Kim chciałeś/chciałaś zostać jako dziecko?"), tr("Jaki był Twój najgorszy prezent?"), tr("Czego boisz się najbardziej?"),
        tr("Jakie jest Twoje najśmieszniejsze wspomnienie ze szkoły?"), tr("Jaką supermoc chciałbyś/chciałabyś mieć?"),
        tr("Gdybyś mógł/mogła zjeść tylko jedną potrawę do końca życia, co by to było?"), tr("Jaki jest Twój ukryty talent?"),
        tr("Komu z tej rozmowy powierzyłbyś/powierzyłabyś sekret?"), tr("Co robisz, gdy nikt nie patrzy?"), tr("Jaki film widziałeś/widziałaś najwięcej razy?"),
        tr("Jaki był Twój najbardziej żenujący moment?"), tr("Gdzie chciałbyś/chciałabyś pojechać w podróż marzeń?"),
        tr("Co ostatnio Cię naprawdę rozśmieszyło?"), tr("Jakie jest Twoje najdziwniejsze przyzwyczajenie?"), tr("Za co jesteś dziś wdzięczny/wdzięczna?"),
        tr("Jaką grę lubisz najbardziej?"), tr("Czego nauczyłeś/nauczyłaś się ostatnio?"), tr("Jaki jest Twój ulubiony emoji i dlaczego?"),
        tr("Opowiedz o swojej pierwszej pracy albo pierwszym zarobionym pieniądzu."), tr("Jaki był najlepszy dzień w tym roku?"),
        tr("Z jaką postacią z bajki się utożsamiasz?"), tr("Jaką aplikację otwierasz najczęściej?"), tr("Co zrobiłbyś/zrobiłabyś z milionem złotych?"),
    )

    val DARES: List<String> = listOf(
        tr("Wyślij głosówkę, w której śpiewasz refren dowolnej piosenki."), tr("Napisz następną wiadomość tylko emoji."),
        tr("Wyślij zdjęcie rzeczy, która stoi najbliżej Ciebie."), tr("Opowiedz kawał (najlepiej suchy)."), tr("Napisz wierszyk o osobie powyżej."),
        tr("Zrób 10 przysiadów i daj znać, że skończyłeś/skończyłaś."), tr("Przez 10 minut kończ każdą wiadomość słowem „ziemniak”."),
        tr("Wyślij zdjęcie swojej najśmieszniejszej miny."), tr("Powiedz komplement każdej osobie w tej rozmowie."),
        tr("Napisz zdanie od tyłu."), tr("Wyślij głosówkę z najlepszą imitacją zwierzęcia."), tr("Ustaw sobie status „🐧 jestem pingwinem” na godzinę."),
        tr("Opisz swój dzień w trzech słowach."), tr("Wyślij zdjęcie widoku z okna."), tr("Zaproponuj grę, w którą wszyscy zagrają w tej rozmowie."),
        tr("Napisz wiadomość, używając tylko słów na literę „P”."), tr("Wyślij GIF, który najlepiej opisuje Twój nastrój."),
        tr("Udawaj przez 5 minut, że jesteś prezenterem pogody."), tr("Wymyśl nowe imię dla każdego w rozmowie."), tr("Zanuć melodię w głosówce — reszta zgaduje."),
    )

    /** „Kto z nas…?” */
    val WHO: List<String> = listOf(
        tr("…najczęściej się spóźnia?"), tr("…pierwszy przeżyłby apokalipsę zombie?"), tr("…najlepiej gotuje?"), tr("…zostanie milionerem?"),
        tr("…najszybciej odpisuje?"), tr("…najczęściej zapomina o urodzinach?"), tr("…byłby najlepszym prezydentem?"), tr("…ma najlepsze poczucie humoru?"),
        tr("…najdłużej śpi w weekend?"), tr("…najchętniej pojechałby w kosmos?"), tr("…ma najwięcej zdjęć w telefonie?"), tr("…najgłośniej się śmieje?"),
        tr("…zje najwięcej pierogów?"), tr("…najlepiej tańczy?"), tr("…zgubi telefon na imprezie?"), tr("…pierwszy zasypia na filmie?"),
        tr("…najlepiej zna się na grach?"), tr("…napisałby najlepszą książkę?"), tr("…najczęściej wysyła głosówki?"), tr("…byłby najlepszym detektywem?"),
        tr("…wygrałby w teleturnieju?"), tr("…ma najlepszy gust muzyczny?"), tr("…najbardziej lubi zwierzęta?"), tr("…najszybciej wyda wypłatę?"),
        tr("…jest największym optymistą?"), tr("…pierwszy pobiegłby maraton?"), tr("…ma najwięcej nieprzeczytanych wiadomości?"),
        tr("…zostałby gwiazdą internetu?"), tr("…najlepiej dotrzymuje tajemnic?"), tr("…najczęściej wymyśla zabawy?"),
    )

    val BALL8: List<String> = listOf(
        tr("Zdecydowanie tak."), tr("Bez wątpienia."), tr("Tak — na pewno."), tr("Możesz na to liczyć."), tr("Wygląda na to, że tak."),
        tr("Najprawdopodobniej."), tr("Perspektywy są dobre."), tr("Tak."), tr("Znaki wskazują, że tak."), tr("Spróbuj zapytać później."),
        tr("Lepiej teraz nie mówić."), tr("Nie mogę tego teraz przewidzieć."), tr("Skup się i zapytaj ponownie."), tr("Nie licz na to."),
        tr("Moja odpowiedź brzmi: nie."), tr("Moje źródła mówią, że nie."), tr("Perspektywy nie są zbyt dobre."), tr("Bardzo wątpliwe."),
        tr("Gwiazdy mówią: zdecydowanie!"), tr("Hmm… zapytaj kota."),
    )
}
