package pl.szept.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Rozłożenie paczek jednego pliku na przekaźniki.
 *
 * Plik dzielimy na n odcinków (n = liczba przekaźników). Odcinek s leży na przekaźnikach
 * s, s+1, …, s+copies-1 (mod n). Przekaźnik j wysyła najpierw „swój” odcinek j, potem kopie
 * sąsiednich — gdy każdy przekaźnik skończy pierwszy odcinek, cały plik jest już do pobrania,
 * a kolejne kopie dochodzą w tle.
 *
 * Każdy przekaźnik dostaje najwyżej copies·⌈chunks/n⌉ paczek. Przekaźniki oddają na jedno
 * zapytanie zwykle najwyżej 500 zdarzeń, więc przy dużym pliku (np. APK aktualizacji) tylko
 * taki podział pozwala starszym wersjom Szepta (pytają bez numerów paczek) pobrać komplet.
 *
 * [extra] — dodatkowe odcinki dla przekaźnika (gdy któryś odmówił i trzeba było przenieść jego część).
 * [legacy] — stary sposób sprzed 3.3.5: każdy przekaźnik ma cały plik po kolei od paczki 0.
 */
data class ChunkPlan(
    val relays: List<String>,
    val chunks: Int,
    val copies: Int,
    val extra: Map<String, List<Int>> = emptyMap(),
    val legacy: Boolean = false,
) {
    val n: Int get() = relays.size.coerceAtLeast(1)
    /** Długość odcinka. */
    val seg: Int get() = ((chunks + n - 1) / n).coerceAtLeast(1)
    private val c: Int get() = copies.coerceIn(1, n)

    fun segRange(s: Int): IntRange = (s * seg) until minOf(chunks, (s + 1) * seg)

    /** Odcinki przekaźnika w kolejności wysyłania. */
    fun segmentsOf(url: String): List<Int> {
        val j = relays.indexOf(url)
        val base = when {
            j < 0 -> emptyList()
            legacy -> (0 until n).toList()
            else -> (0 until c).map { (j - it).mod(n) }
        }
        return (base + (extra[url] ?: emptyList())).distinct()
    }

    /** Numery paczek, które ma dostać przekaźnik, w kolejności wysyłania. */
    fun orderOf(url: String): List<Int> =
        if (legacy && url in relays) (0 until chunks).toList()
        else segmentsOf(url).flatMap { segRange(it).toList() }

    /** Ile paczek ma dostać przekaźnik. */
    fun sizeOf(url: String): Int = if (legacy && url in relays) chunks else segmentsOf(url).sumOf { segRange(it).count() }

    /** Przekaźniki, które mają (albo będą miały) paczkę [idx]; pierwszy — ten, który wysyła ją najwcześniej. */
    fun holders(idx: Int): List<String> {
        if (idx < 0 || idx >= chunks) return emptyList()
        if (legacy) return relays
        val s = idx / seg
        val base = (0 until c).map { relays[(s + it) % n] }
        return (base + extra.filterValues { s in it }.keys).distinct()
    }

    /**
     * Ile różnych paczek jest już na przekaźnikach przy postępie [done] (url → ile paczek z [orderOf] wysłano;
     * ujemne = przekaźnik odmówił).
     */
    fun covered(done: (String) -> Int): Int {
        if (chunks <= 0) return 0
        if (legacy) return relays.maxOfOrNull { done(it).coerceIn(0, chunks) } ?: 0
        val have = BooleanArray(chunks)
        for (url in (relays + extra.keys).distinct()) {
            val d = done(url)
            if (d <= 0) continue
            val order = orderOf(url)
            for (k in 0 until minOf(d, order.size)) have[order[k]] = true
        }
        return have.count { it }
    }

    /** Odcinki, których nie ma już żaden sprawny przekaźnik (wszystkie ich przekaźniki odmówiły). */
    fun orphans(failed: Set<String>): List<Int> =
        if (legacy) emptyList()
        else (0 until n).filter { s -> !segRange(s).isEmpty() && holders(s * seg).all { it in failed } }

    /** Nowy plan z odcinkami [segs] przeniesionymi na przekaźnik [url]. */
    fun withExtra(url: String, segs: List<Int>): ChunkPlan =
        copy(extra = extra + (url to ((extra[url] ?: emptyList()) + segs).distinct()))

    fun toJson(): JSONObject = JSONObject().apply {
        put("r", JSONArray(relays)); put("c", copies)
        if (legacy) put("l", 1)
        if (extra.isNotEmpty()) put("x", JSONObject().apply { extra.forEach { (k, v) -> put(k, JSONArray(v)) } })
    }

    companion object {
        /** Typowy limit odpowiedzi przekaźnika (NIP-11 max_limit), gdy przekaźnik go nie podaje. */
        const val DEFAULT_CAP = 500

        /** Plan z sieci albo z bazy — sprawdzany, bo pochodzi od innej osoby. */
        fun fromJson(o: JSONObject?, chunks: Int): ChunkPlan? {
            if (o == null || chunks <= 0) return null
            return try {
                val r = o.optJSONArray("r") ?: return null
                val relays = List(r.length()) { r.optString(it) }
                    .filter { pl.szept.nostr.isValidRelayUrl(it) && it.length <= 200 }.distinct().take(20)
                if (relays.isEmpty()) return null
                val n = relays.size
                val extra = HashMap<String, List<Int>>()
                o.optJSONObject("x")?.let { x ->
                    for (k in x.keys()) {
                        if (!pl.szept.nostr.isValidRelayUrl(k) || extra.size >= 20) continue
                        val a = x.optJSONArray(k) ?: continue
                        extra[k] = List(a.length()) { a.optInt(it, -1) }.filter { it in 0 until n }.distinct().take(n)
                    }
                }
                ChunkPlan(relays, chunks, o.optInt("c", 1).coerceIn(1, n), extra, o.optInt("l") == 1)
            } catch (e: Exception) { null }
        }

        /**
         * Wybiera przekaźniki i liczbę kopii dla pliku z [chunks] paczek.
         * [relays] — kandydaci w kolejności preferencji, [cap] — limit odpowiedzi przekaźnika,
         * [want] — ile kopii chcemy. Każdy przekaźnik ma dostać najwyżej cap(url) paczek tego pliku
         * (wtedy także stara wersja pobierze komplet). Jeśli się nie da — jedna kopia rozłożona na wszystkich.
         */
        fun build(chunks: Int, relays: List<String>, want: Int, cap: (String) -> Int): ChunkPlan {
            require(relays.isNotEmpty())
            val byCap = relays.withIndex().sortedWith(compareByDescending<IndexedValue<String>> { cap(it.value) }.thenBy { it.index }).map { it.value }
            var best: Pair<Int, Int>? = null   // (liczba przekaźników, kopie)
            for (k in relays.size downTo 1) {
                val chosen = byCap.take(k)
                val seg = (chunks + k - 1) / k
                val minCap = chosen.minOf { cap(it) }
                val copies = minOf(want, k, if (seg == 0) k else minCap / seg)
                if (copies < 1) continue
                if (best == null || copies > best.second) best = k to copies
                if (copies >= minOf(want, relays.size)) break
            }
            val (k, copies) = best ?: (relays.size to 1)
            // zachowaj kolejność preferencji wśród wybranych
            val keep = byCap.take(k).toSet()
            return ChunkPlan(relays.filter { it in keep }, chunks, copies)
        }

        /**
         * Komu zadać pytanie o brakujące paczki w tej rundzie: każda paczka do jednego przekaźnika
         * (bez pobierania tego samego kilka razy), najpierw do tych, które według [plan] ją mają,
         * po równo między nimi; bez planu — po kawałku pliku na przekaźnik.
         * [asked] — o co już pytaliśmy dany przekaźnik (i nic nie przyszło).
         */
        fun assign(
            missing: Collection<Int>, chunks: Int, relays: List<String>, plan: ChunkPlan?, asked: Map<String, Set<Int>>,
        ): Map<String, List<Int>> {
            if (relays.isEmpty()) return emptyMap()
            val load = HashMap<String, Int>()
            val out = LinkedHashMap<String, MutableList<Int>>()
            val n = relays.size
            for (idx in missing.sorted()) {
                fun free(u: String) = idx !in (asked[u] ?: emptySet())
                val owners = plan?.holders(idx) ?: emptyList()
                val holders = owners.filter { it in relays && free(it) }
                val pick = if (holders.isNotEmpty()) {
                    // „swój” przekaźnik paczki (każdy oddaje inny kawałek, równolegle); gdy go nie ma —
                    // najmniej obciążony z pozostałych właścicieli
                    if (holders.first() == owners.first()) holders.first() else holders.minByOrNull { load[it] ?: 0 }
                } else {
                    val start = ((idx.toLong() * n) / chunks.coerceAtLeast(1)).toInt().coerceIn(0, n - 1)
                    (0 until n).map { relays[(start + it) % n] }.firstOrNull { free(it) && (plan == null || it !in plan.relays) }
                        ?: (0 until n).map { relays[(start + it) % n] }.firstOrNull { free(it) }
                } ?: continue
                out.getOrPut(pick) { ArrayList() }.add(idx)
                load[pick] = (load[pick] ?: 0) + 1
            }
            return out
        }
    }
}
