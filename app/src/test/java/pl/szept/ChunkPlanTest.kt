package pl.szept

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.szept.core.ChunkPlan
import pl.szept.core.SafetyCode
import pl.szept.crypto.hex

/** Rozkład paczek na przekaźniki (3.3.5): limit odpowiedzi przekaźnika, kopie, pobieranie bez powtórek. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChunkPlanTest {
    private val relays = listOf("wss://a.pl", "wss://b.pl", "wss://c.pl", "wss://d.pl", "wss://e.pl", "wss://f.pl")

    private fun check(plan: ChunkPlan) {
        // każdy przekaźnik: kolejność bez powtórzeń, zgodna z „kto ma paczkę”
        for (u in plan.relays) {
            val order = plan.orderOf(u)
            assertEquals("powtórka w kolejności $u", order.size, order.toSet().size)
            assertEquals(plan.sizeOf(u), order.size)
            order.forEach { assertTrue("$u nie jest właścicielem $it", u in plan.holders(it)) }
        }
        // każda paczka ma tyle kopii, ile w planie
        for (i in 0 until plan.chunks) {
            assertEquals("kopie paczki $i", minOf(plan.copies, plan.n), plan.holders(i).size)
            plan.holders(i).forEach { assertTrue(i in plan.orderOf(it)) }
        }
        // po pierwszym odcinku każdego przekaźnika cały plik jest dostępny
        val first = plan.relays.associateWith { plan.segRange(plan.segmentsOf(it).first()).count() }
        assertEquals(plan.chunks, plan.covered { first[it] ?: 0 })
    }

    @Test
    fun updateFitsRelayLimits() {
        // APK 3.3.4: 1157 paczek; jeden przekaźnik oddaje tylko 100 na zapytanie
        val cap = { u: String -> if (u == "wss://f.pl") 100 else 500 }
        val p = ChunkPlan.build(1157, relays, 2, cap)
        assertEquals(listOf("wss://a.pl", "wss://b.pl", "wss://c.pl", "wss://d.pl", "wss://e.pl"), p.relays)
        assertEquals(2, p.copies)
        p.relays.forEach { assertTrue("${p.sizeOf(it)} > limit", p.sizeOf(it) <= cap(it)) }
        check(p)
        // 3.3.5 bez bibliotek x86: ok. 750 paczek — mieszczą się nawet 3 kopie
        val q = ChunkPlan.build(750, relays, 3, cap)
        assertEquals(3, q.copies)
        q.relays.forEach { assertTrue(q.sizeOf(it) <= cap(it)) }
        check(q)
    }

    @Test
    fun copiesGoFromTheEnd() {
        // 3.4.1: kopia idzie od końca odcinka — spotyka się w połowie z wolniejszym przekaźnikiem
        val p = ChunkPlan.build(120, relays.take(4), 2) { 500 }
        assertTrue(p.rev)
        val order = p.orderOf("wss://b.pl")
        assertEquals(p.segRange(1).toList(), order.take(30))
        assertEquals(p.segRange(0).reversed().toList(), order.drop(30))
        // stary plan (bez „o”) zachowuje dawną kolejność — wysyłki z 3.4 dokończą się poprawnie
        val old = ChunkPlan.fromJson(org.json.JSONObject(p.toJson().toString()).apply { remove("o") }, 120)!!
        assertEquals(p.segRange(0).toList(), old.orderOf("wss://b.pl").drop(30))
        // brakujące odcinki
        assertEquals(listOf(0, 1, 2, 3), p.uncoveredSegments { 0 })
        assertEquals(listOf(3), p.uncoveredSegments { if (it == "wss://d.pl") 0 else 30 })
        check(p)
    }

    @Test
    fun smallFileFullCopies() {
        // zwykłe zdjęcie: pełna kopia na 3 przekaźnikach, każdy zaczyna od innego kawałka
        val p = ChunkPlan.build(40, relays.take(3), 3) { 500 }
        assertEquals(3, p.copies)
        p.relays.forEach { assertEquals(40, p.sizeOf(it)) }
        assertEquals(listOf(0, 1, 2), p.relays.map { p.segmentsOf(it).first() })
        check(p)
        // plik mniejszy niż liczba przekaźników
        check(ChunkPlan.build(2, relays, 3) { 500 })
        check(ChunkPlan.build(1, relays, 3) { 500 })
    }

    @Test
    fun jsonRoundTripAndValidation() {
        val p = ChunkPlan.build(300, relays, 2) { 500 }.withExtra("wss://g.pl", listOf(1))
        val back = ChunkPlan.fromJson(p.toJson(), 300)!!
        assertEquals(p, back)
        // złe dane z sieci: zły adres i numery odcinków spoza zakresu odpadają
        val bad = org.json.JSONObject("""{"r":["http://zly", "wss://ok.pl"],"c":9,"x":{"wss://x.pl":[0,77,-1]}}""")
        val q = ChunkPlan.fromJson(bad, 10)!!
        assertEquals(listOf("wss://ok.pl"), q.relays)
        assertEquals(1, q.copies)
        assertEquals(listOf(0), q.extra["wss://x.pl"])
        assertEquals(null, ChunkPlan.fromJson(org.json.JSONObject("""{"r":[]}"""), 10))
    }

    @Test
    fun orphansMoveToHealthyRelay() {
        val p = ChunkPlan.build(120, relays.take(4), 2) { 500 }
        val failed = setOf("wss://a.pl", "wss://b.pl")
        val orph = p.orphans(failed)
        assertTrue(orph.isNotEmpty())
        val fixed = p.withExtra("wss://c.pl", orph)
        assertTrue(fixed.orphans(failed).isEmpty())
        // przeniesione odcinki są na końcu kolejki „c”, a jego postęp się nie zmienia
        assertEquals(p.orderOf("wss://c.pl"), fixed.orderOf("wss://c.pl").take(p.sizeOf("wss://c.pl")))
        // legacy: każdy ma wszystko, po kolei
        val l = ChunkPlan(listOf("wss://a.pl", "wss://b.pl"), 10, 2, legacy = true)
        assertEquals((0 until 10).toList(), l.orderOf("wss://b.pl"))
        assertEquals(7, l.covered { if (it == "wss://a.pl") 7 else 3 })
    }

    @Test
    fun assignAsksEachChunkOnce() {
        val p = ChunkPlan.build(500, relays, 3) { 500 }
        val missing = (0 until 500).toList()
        val first = ChunkPlan.assign(missing, 500, relays, p, emptyMap())
        // każda paczka dokładnie raz, zawsze do właściciela, mniej więcej po równo
        assertEquals(missing, first.values.flatten().sorted())
        first.forEach { (u, idx) -> idx.forEach { assertTrue(u in p.holders(it)) } }
        assertTrue(first.values.maxOf { it.size } <= 500 / relays.size + 2)
        // przekaźnik „a” niczego nie oddał — jego paczki idą do innych właścicieli
        val asked = mapOf("wss://a.pl" to first["wss://a.pl"]!!.toSet())
        val second = ChunkPlan.assign(first["wss://a.pl"]!!, 500, relays, p, asked)
        assertTrue("wss://a.pl" !in second)
        assertEquals(first["wss://a.pl"]!!.sorted(), second.values.flatten().sorted())
        // bez planu: po kawałku na przekaźnik, a gdy wszyscy zapytani — nic
        val noPlan = ChunkPlan.assign(missing, 500, relays, null, emptyMap())
        assertEquals(missing, noPlan.values.flatten().sorted())
        val all = relays.associateWith { missing.toSet() }
        assertTrue(ChunkPlan.assign(missing, 500, relays, null, all).isEmpty())
    }
}

/** Kod bezpieczeństwa rozmowy (3.3.5). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SafetyCodeTest {
    @Test
    fun sameForBothAndDetectsSwap() {
        val a = pl.szept.crypto.Crypto.publicKey(pl.szept.crypto.Crypto.newPrivateKey()).hex()
        val b = pl.szept.crypto.Crypto.publicKey(pl.szept.crypto.Crypto.newPrivateKey()).hex()
        val m = pl.szept.crypto.Crypto.publicKey(pl.szept.crypto.Crypto.newPrivateKey()).hex()
        val sc = pl.szept.core.SafetyCode
        // ten sam kod u obu osób, 12 grup po 5 cyfr
        assertEquals(sc.groups(a, b), sc.groups(b, a))
        assertEquals(12, sc.groups(a, b).size)
        sc.groups(a, b).forEach { assertTrue(it.length == 5 && it.all(Char::isDigit)) }
        // inna para — inny kod
        assertTrue(sc.groups(a, b) != sc.groups(a, m))
        // skan: B skanuje kod A
        assertEquals(SafetyCode.Result.MATCH, sc.check(sc.qr(a, b), b, a))
        // podmieniony klucz: B rozmawia „z A”, a naprawdę ma klucz M (ktoś podszywa się pod A)
        assertEquals(SafetyCode.Result.OTHER_PERSON, sc.check(sc.qr(a, b), b, m))
        // A ma u siebie podrobiony klucz B (M) — kod A się nie zgodzi u prawdziwego B
        assertEquals(SafetyCode.Result.MISMATCH, sc.check(sc.qr(a, m), b, a))
        assertEquals(SafetyCode.Result.INVALID, sc.check("szept://abc", b, a))
    }
}
