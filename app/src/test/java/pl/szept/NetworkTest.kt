package pl.szept

import android.app.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.szept.core.Core
import pl.szept.core.Links
import pl.szept.crypto.Crypto
import pl.szept.crypto.hex
import pl.szept.data.GroupState
import pl.szept.data.Kind
import pl.szept.data.St
import pl.szept.data.XMode
import pl.szept.data.dmChatId
import java.io.File

/**
 * Test end-to-end na PRAWDZIWYCH publicznych przekaźnikach Nostr.
 * Uruchamianie: ./gradlew testDebugUnitTest -Dszept.network=1 --tests pl.szept.NetworkTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NetworkTest {

    private suspend fun waitFor(what: String, timeoutMs: Long = 60_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) { println("✔ $what"); return }
            delay(300)
        }
        throw AssertionError("Timeout: $what")
    }

    private fun core(name: String, foreground: Boolean = true): Core {
        val ctx = RuntimeEnvironment.getApplication()
        val c = Core(ctx, name + System.nanoTime())
        c.createIdentity(name)
        c.appVisible = foreground
        c.start()
        return c
    }

    private class CountingNotifier : pl.szept.core.Notifier {
        val chats = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun onMessage(chatId: String, chatName: String, sender: String, text: String, isGroup: Boolean) { chats.add(chatId) }
        override fun cancel(chatId: String) {}
    }

    @Test
    fun repliesRemovedMembersMute() = runBlocking {
        assumeTrue(System.getProperty("szept.network") == "1")
        val a = core("Admin"); val b = core("Basia"); val d = core("Damian")
        for (x in listOf(a, b, d)) waitFor("${x.myName} połączony", 40_000) { x.connectedRelays().size >= 3 }
        val gChat = a.createGroup("Test", listOf(b.myPub, d.myPub))
        val gid = gChat.removePrefix("g:")
        waitFor("B i D w grupie") { b.db.group(gid)?.state == GroupState.ACTIVE && d.db.group(gid)?.state == GroupState.ACTIVE }

        // odpowiedź (cytat)
        val q = a.sendText(gChat, "Kto idzie na pizzę?")
        waitFor("B ma pytanie") { b.db.message(q.id) != null }
        val ans = b.sendText(gChat, "Ja!", replyTo = b.db.message(q.id))
        waitFor("A dostał odpowiedź z cytatem") { a.db.message(ans.id)?.reply?.id == q.id }
        assertEquals("Kto idzie na pizzę?", a.db.message(ans.id)!!.reply!!.text)

        // znikanie tylko tej jednej wiadomości (czat bez timera)
        val once = a.sendText(gChat, "tylko ta znika", XMode.AFTER_SEND, 5)
        waitFor("B dostał znikającą") { b.db.message(once.id)?.xmode == XMode.AFTER_SEND }
        waitFor("znikła u A i B", 20_000) { a.db.message(once.id) == null && b.db.message(once.id) == null }
        assertEquals(XMode.OFF, a.db.chat(gChat)!!.xmode)

        // wyciszenie: brak powiadomień, ale wiadomość przychodzi
        val nb = CountingNotifier(); b.notifier = nb
        b.setMuted(gChat, true)
        val quiet = a.sendText(gChat, "cicha wiadomość")
        waitFor("B dostał cichą") { b.db.message(quiet.id) != null }
        assertTrue("wyciszony czat nie powiadamia", gChat !in nb.chats)
        b.setMuted(gChat, false)
        val loud = a.sendText(gChat, "głośna wiadomość")
        waitFor("B dostał powiadomienie") { gChat in nb.chats && b.db.message(loud.id) != null }

        // usunięcie członka; D „nie wie” o usunięciu (np. był offline) i nadal pisze
        val before = d.db.group(gid)!!
        a.removeMember(gid, d.myPub)
        waitFor("B wie o usunięciu D") { b.db.group(gid)?.removed?.contains(d.myPub) == true }
        waitFor("D wie o usunięciu") { d.db.group(gid)?.state == GroupState.REMOVED }
        d.db.saveGroup(before)  // symulacja: D ma starą listę członków
        val spam = d.sendText(gChat, "jestem usunięty, ale piszę")
        waitFor("D wysłał", 30_000) { (d.db.message(spam.id)?.status ?: 0) >= St.SENT }
        delay(5000)
        assertTrue("B odrzuca wiadomości od usuniętego", b.db.message(spam.id) == null)

        // usunięty nie wraca sam przez stary link zaproszenia
        val inv = Links.parse(Links.group(gid, before.token, before.admins, before.name)) as Links.Parsed.GroupInvite
        d.db.saveGroup(before.copy(state = GroupState.REMOVED))  // D wie, że go usunięto, i próbuje wrócić
        d.joinGroup(inv)
        waitFor("admin dostał prośbę od usuniętego") { a.db.message("sys:$gid:rejoin:${d.myPub}") != null }
        delay(3000)
        assertTrue("D nie został ponownie dodany", a.db.group(gid)?.isMember(d.myPub) == false)
        println("REPLIES/REMOVED/MUTE OK")
        listOf(a, b, d).forEach { it.stop() }
    }

    @Test
    fun backgroundBalancedMode() = runBlocking {
        assumeTrue(System.getProperty("szept.network") == "1")
        val ctx = RuntimeEnvironment.getApplication()
        val a = core("Nadawca")
        val b = Core(ctx, "bg" + System.nanoTime()).apply {
            createIdentity("Tło"); trimDelayMs = 2_000; appVisible = false; start()
        }
        waitFor("A połączony", 40_000) { a.connectedRelays().size >= 3 }
        waitFor("B przeszedł w tryb zrównoważony", 60_000) { b.trimmed.value }
        waitFor("B ma najwyżej 2 połączenia", 20_000) { b.connectedRelays().size in 1..2 }
        println("B na żywo: " + b.connectedRelays().map { it.url })
        a.addContact(b.myPub, "Tło")
        val m = a.sendText(dmChatId(b.myPub), "wiadomość do telefonu w tle")
        val t0 = System.currentTimeMillis()
        waitFor("B w tle odebrał od razu", 30_000) { b.db.message(m.id) != null }
        println("⏱ dostarczenie w trybie zrównoważonym: ${System.currentTimeMillis() - t0} ms")
        b.keepalive()  // alarm co 10 min: pełna synchronizacja
        waitFor("pełna synchronizacja łączy wszystkie", 30_000) { !b.trimmed.value && b.connectedRelays().size >= 4 }
        waitFor("po synchronizacji znów oszczędnie", 90_000) { b.trimmed.value && b.connectedRelays().size <= 2 }
        b.appVisible = true
        waitFor("otwarcie aplikacji = wszystkie połączenia", 30_000) { !b.trimmed.value && b.connectedRelays().size >= 4 }
        println("BACKGROUND OK")
        a.stop(); b.stop()
    }

    @Test
    fun endToEnd() = runBlocking {
        assumeTrue(System.getProperty("szept.network") == "1")
        val a = core("Ala"); val b = core("Bartek"); val c = core("Celina"); val d = core("Darek")
        for (x in listOf(a, b, c, d)) waitFor("${x.myName} połączony", 40_000) { x.connectedRelays().size >= 3 }
        println("Przekaźniki A: " + a.connectedRelays().map { it.url })

        // --- kontakt i wiadomość prywatna ---
        a.addContact(b.myPub, "Bartek")
        waitFor("B dostał kontakt od A") { b.db.contact(a.myPub)?.isContact == true && b.db.chat(dmChatId(a.myPub)) != null }
        assertEquals("Ala", b.db.contact(a.myPub)!!.name)

        val sent = a.sendText(dmChatId(b.myPub), "Cześć Bartek! Zażółć gęślą jaźń 😀")
        waitFor("B odebrał tekst") { b.db.message(sent.id) != null }
        assertEquals("Cześć Bartek! Zażółć gęślą jaźń 😀", b.db.message(sent.id)!!.body)
        waitFor("A widzi 'dostarczono'") { (a.db.message(sent.id)?.status ?: 0) >= St.DELIVERED }

        // --- zdjęcie (media w paczkach) ---
        val img = File(RuntimeEnvironment.getApplication().cacheDir, "foto.jpg")
        img.writeBytes(Crypto.randomBytes(150_000))
        val sha = Crypto.sha256(img.readBytes()).hex()
        b.addContact(a.myPub, "Ala")
        val photo = b.send(dmChatId(a.myPub), Kind.IMAGE, "fotka", Core.Attachment(img, "image/jpeg", "foto.jpg", null, 10, 10, 0))
        waitFor("A odebrał wiadomość ze zdjęciem", 120_000) { a.db.message(photo.id) != null }
        waitFor("A pobrał zdjęcie", 120_000) { a.db.message(photo.id)?.path != null }
        assertEquals(sha, Crypto.sha256(File(a.db.message(photo.id)!!.path!!).readBytes()).hex())
        waitFor("B: zdjęcie wysłane") { (b.db.message(photo.id)?.status ?: 0) >= St.SENT }

        // --- grupa ---
        a.addContact(c.myPub, "Celina")
        val gChat = a.createGroup("Paczka", listOf(b.myPub, c.myPub))
        val gid = gChat.removePrefix("g:")
        waitFor("B i C w grupie") { b.db.group(gid)?.state == GroupState.ACTIVE && c.db.group(gid)?.state == GroupState.ACTIVE }
        assertEquals(3, c.db.group(gid)!!.members.size)

        val gm = c.sendText(gChat, "Hej wszystkim z grupy")
        waitFor("A i B odebrali wiadomość grupową") { a.db.message(gm.id) != null && b.db.message(gm.id) != null }

        // --- dołączanie przez link ---
        val invite = Links.parse(a.groupInvite(gid)!!) as Links.Parsed.GroupInvite
        d.joinGroup(invite)
        waitFor("D dołączył przez link (admin A zaakceptował)") { d.db.group(gid)?.state == GroupState.ACTIVE }
        waitFor("B widzi D w grupie") { b.db.group(gid)?.isMember(d.myPub) == true }
        // każdy może pisać do każdego: D pisze prywatnie do C (znanego z grupy)
        val dChat = d.openDm(c.myPub)
        val dm = d.sendText(dChat, "Cześć Celina, jestem z grupy")
        waitFor("C odebrał wiadomość od D") { c.db.message(dm.id) != null }

        // --- usuwanie u wszystkich ---
        val toDel = a.sendText(gChat, "to zaraz zniknie")
        waitFor("wszyscy mają wiadomość do usunięcia") { listOf(b, c, d).all { it.db.message(toDel.id) != null } }
        a.deleteForEveryone(toDel.id)
        waitFor("usunięto u wszystkich") { listOf(a, b, c, d).all { it.db.message(toDel.id) == null } }

        // --- usuwanie u wybranej osoby ---
        val sel = a.sendText(gChat, "usuń tylko u Bartka")
        waitFor("wszyscy mają wiadomość") { listOf(b, c, d).all { it.db.message(sel.id) != null } }
        a.deleteFor(sel.id, listOf(b.myPub), false)
        waitFor("usunięto tylko u B") { b.db.message(sel.id) == null }
        assertNotNull(c.db.message(sel.id)); assertNotNull(a.db.message(sel.id))

        // --- znikająca po wysłaniu ---
        a.setChatTimer(dmChatId(b.myPub), XMode.AFTER_SEND, 6).join()
        val boom = a.sendText(dmChatId(b.myPub), "zniknę za 6 s")
        waitFor("B odebrał znikającą") { b.db.message(boom.id) != null }
        waitFor("znikająca usunięta u obu", 20_000) { a.db.message(boom.id) == null && b.db.message(boom.id) == null }

        // --- znikająca po otwarciu ---
        a.setChatTimer(dmChatId(b.myPub), XMode.AFTER_OPEN, 3).join()
        val once = a.sendText(dmChatId(b.myPub), "jednorazowa")
        waitFor("B odebrał jednorazową") { b.db.message(once.id) != null }
        delay(4000)
        assertNotNull("nieotwarta nie znika", b.db.message(once.id))
        assertTrue(b.db.message(once.id)!!.hiddenUntilOpened)
        b.openViewOnce(once.id)
        waitFor("jednorazowa znika po otwarciu", 15_000) { b.db.message(once.id) == null }
        waitFor("A widzi 'otwarto'", 30_000) { a.db.message(once.id)?.status == St.OPENED || a.db.message(once.id) == null }

        // --- admin usuwa członka ---
        a.removeMember(gid, d.myPub)
        waitFor("D usunięty z grupy") { d.db.group(gid)?.state == GroupState.REMOVED }
        waitFor("C widzi usunięcie D") { c.db.group(gid)?.isMember(d.myPub) == false }

        println("WSZYSTKIE TESTY SIECIOWE OK")
        listOf(a, b, c, d).forEach { it.stop() }
    }

    @Test
    fun offlineDelivery() = runBlocking {
        assumeTrue(System.getProperty("szept.network") == "1")
        val ctx = RuntimeEnvironment.getApplication()
        val a = core("Ala")
        val bProfile = "offline" + System.nanoTime()
        var b = Core(ctx, bProfile).apply { createIdentity("Bartek"); start() }
        waitFor("A i B połączeni", 40_000) { a.connectedRelays().size >= 3 && b.connectedRelays().size >= 3 }
        a.addContact(b.myPub, "Bartek")
        waitFor("B zna A") { b.db.contact(a.myPub) != null }
        val bPub = b.myPub

        // B wyłącza telefon
        b.stop()
        println("📴 B offline")
        val m1 = a.sendText(dmChatId(bPub), "Wiadomość 1 gdy B ma wyłączony telefon")
        val m2 = a.sendText(dmChatId(bPub), "Wiadomość 2")
        val big = File(ctx.cacheDir, "wideo.mp4").apply { writeBytes(Crypto.randomBytes(1_500_000)) }
        val t0 = System.currentTimeMillis()
        val vid = a.send(dmChatId(bPub), Kind.VIDEO, "", Core.Attachment(big, "video/mp4", "wideo.mp4", null, 0, 0, 1000))
        waitFor("A wysłał wszystko (1,5 MB)", 180_000) { listOf(m1, m2, vid).all { (a.db.message(it.id)?.status ?: 0) >= St.SENT } }
        println("⏱ wysyłka 1,5 MB: ${(System.currentTimeMillis() - t0) / 1000} s")
        delay(3000)

        // B włącza telefon
        val t1 = System.currentTimeMillis()
        b = Core(ctx, bProfile).apply { start() }
        println("📱 B online")
        waitFor("B odebrał zaległe wiadomości", 60_000) { b.db.message(m1.id) != null && b.db.message(m2.id) != null && b.db.message(vid.id) != null }
        waitFor("B pobrał wideo", 180_000) { b.db.message(vid.id)?.path != null }
        println("⏱ odbiór po włączeniu: ${(System.currentTimeMillis() - t1) / 1000} s")
        waitFor("A widzi 'dostarczono'", 60_000) { (a.db.message(m1.id)?.status ?: 0) >= St.DELIVERED }
        println("OFFLINE OK")
        a.stop(); b.stop()
    }

    @Test
    fun bigGroup() = runBlocking {
        assumeTrue(System.getProperty("szept.network") == "1")
        val a = core("Admin"); val b = core("Beata"); val c = core("Cezary")
        for (x in listOf(a, b, c)) waitFor("${x.myName} połączony", 40_000) { x.connectedRelays().size >= 3 }
        val fake = List(18) { Crypto.publicKey(Crypto.newPrivateKey()).hex() }
        fake.forEachIndexed { i, pk -> a.db.upsertContact(pk, "Osoba ${i + 4}", true) }
        val gChat = a.createGroup("Duża grupa", listOf(b.myPub, c.myPub) + fake)
        val gid = gChat.removePrefix("g:")
        waitFor("B i C w 21-osobowej grupie") { b.db.group(gid)?.members?.size == 21 && c.db.group(gid)?.members?.size == 21 }
        val t0 = System.currentTimeMillis()
        val msgs = (1..5).map { a.sendText(gChat, "Wiadomość grupowa $it") }
        waitFor("B i C odebrali 5 wiadomości", 90_000) { msgs.all { m -> b.db.message(m.id) != null && c.db.message(m.id) != null } }
        println("⏱ 5 wiadomości × 20 odbiorców: ${(System.currentTimeMillis() - t0)} ms")
        waitFor("status 'wysłano' dla wszystkich", 90_000) { msgs.all { (a.db.message(it.id)?.status ?: 0) >= St.SENT } }
        val img = File(RuntimeEnvironment.getApplication().cacheDir, "g.jpg").apply { writeBytes(Crypto.randomBytes(300_000)) }
        val photo = b.send(gChat, Kind.IMAGE, "", Core.Attachment(img, "image/jpeg", "g.jpg", null, 1, 1, 0))
        waitFor("A i C pobrali zdjęcie grupowe", 120_000) { a.db.message(photo.id)?.path != null && c.db.message(photo.id)?.path != null }
        // C pisze prywatnie do B (każdy do każdego)
        val pm = c.sendText(c.openDm(b.myPub), "Prywatnie do Beaty")
        waitFor("B odebrał prywatną od C") { b.db.message(pm.id) != null }
        println("BIG GROUP OK")
        listOf(a, b, c).forEach { it.stop() }
    }

    @Test
    fun clockCorrection() {
        val c = pl.szept.nostr.Clock
        c.reset()
        val now = System.currentTimeMillis()
        c.addSample(now + 2 * 3600_000L, now)
        assertEquals("jedna próbka nie wystarcza", 0L, c.offsetMs)
        c.addSample(now + 2 * 3600_000L + 500, now)
        c.addSample(now - 999_000_000L, now)  // pojedynczy przekaźnik z błędnym czasem nie psuje mediany
        assertTrue(kotlin.math.abs(c.offsetMs - 2 * 3600_000L) < 1000)
        val ev = pl.szept.nostr.Event.create(Crypto.newPrivateKey(), 1, emptyList(), "x")
        assertTrue("zdarzenia dostają skorygowany czas", kotlin.math.abs(ev.createdAt - (now / 1000 + 7200)) < 5)
        c.reset()
        c.addSample(now + 3000, now); c.addSample(now + 2000, now)
        assertEquals("małe różnice ignorowane", 0L, c.offsetMs)
        c.reset()
    }

    @Test
    fun inboxPaging() = runBlocking {
        assumeTrue(System.getProperty("szept.network") == "1")
        pl.szept.nostr.Clock.reset()
        val ctx = RuntimeEnvironment.getApplication()
        val a = core("Gaduła")
        val bProfile = "paging" + System.nanoTime()
        var b = Core(ctx, bProfile).apply { createIdentity("Odbiorca"); appVisible = true; start() }
        waitFor("A i B połączeni", 40_000) { a.connectedRelays().size >= 3 && b.connectedRelays().size >= 3 }
        assertTrue("czas z nagłówków Date przekaźników", pl.szept.nostr.Clock.sampleCount >= 2)
        assertEquals("zegar kontenera jest poprawny — brak korekty", 0L, pl.szept.nostr.Clock.offsetMs)
        a.addContact(b.myPub, "Odbiorca")
        waitFor("B zna A") { b.db.contact(a.myPub) != null }
        delay(2000)
        b.stop()
        val ids = (1..60).map { i -> a.sendText(dmChatId(b.myPub), "Wiadomość nr $i").id.also { delay(120) } }
        waitFor("A wysłał 60 wiadomości", 120_000) { ids.all { (a.db.message(it)?.status ?: 0) >= St.SENT } }
        b = Core(ctx, bProfile).apply { inboxLimit = 15; appVisible = true; start() }
        waitFor("B odebrał wszystkie 60 mimo strony po 15", 120_000) { ids.all { b.db.message(it) != null } }
        println("PAGING OK")
        a.stop(); b.stop()
    }

    @Test
    fun timedVoice() = runBlocking {
        assumeTrue(System.getProperty("szept.network") == "1")
        val a = core("Nadawca"); val b = core("Odbiorca")
        for (x in listOf(a, b)) waitFor("${x.myName} połączony", 40_000) { x.connectedRelays().size >= 3 }
        a.addContact(b.myPub, "Odbiorca")
        waitFor("B zna A") { b.db.contact(a.myPub) != null }
        val audio = File(RuntimeEnvironment.getApplication().cacheDir, "v.m4a").apply { writeBytes(Crypto.randomBytes(40_000)) }
        val wave = pl.szept.core.VoiceRecorder.encodeWave(List(120) { (it % 9) / 9f })
        val m = a.send(dmChatId(b.myPub), Kind.VOICE, "", Core.Attachment(audio, "audio/mp4", "v.m4a", null, 0, 0, 20_000, wave), XMode.AFTER_OPEN, 10)
        waitFor("B odebrał głosówkę czasową", 90_000) { b.db.message(m.id)?.path != null }
        val got = b.db.message(m.id)!!
        assertTrue("ukryta do dotknięcia", got.hiddenUntilOpened)
        assertEquals("przebieg dotarł", wave, got.media!!.wave)
        assertEquals("czas ≥ długość nagrania", 25, got.ttl)
        delay(4000)
        assertEquals("czas nie biegnie przed otwarciem", 0L, b.db.message(m.id)!!.expires)
        val t0 = System.currentTimeMillis()
        b.openViewOnce(m.id)
        waitFor("odliczanie ruszyło po dotknięciu") { (b.db.message(m.id)?.expires ?: 0) > 0 }
        val left = b.db.message(m.id)!!.expires - t0
        assertTrue("zniknie ok. 25 s po otwarciu (było $left ms)", left in 23_000..27_000)
        waitFor("A widzi 'otwarto'", 30_000) { a.db.message(m.id)?.status == pl.szept.data.St.OPENED }
        println("TIMED VOICE OK")
        a.stop(); b.stop()
    }

    @Test
    fun profileAndUpdate() = runBlocking {
        assumeTrue(System.getProperty("szept.network") == "1")
        val a = core("Ala"); val b = core("Bartek")
        for (x in listOf(a, b)) waitFor("${x.myName} połączony", 40_000) { x.connectedRelays().size >= 3 }
        a.addContact(b.myPub, "Bartek")
        waitFor("B zna A") { b.db.contact(a.myPub) != null }
        // profil: zdjęcie + status + opis
        a.avatarFile.writeBytes(Crypto.randomBytes(4000))
        a.setStatusBio("🎮 gram", "Cześć, tu Ala")
        waitFor("B dostał profil A", 40_000) {
            val c = b.db.contact(a.myPub); c?.status == "🎮 gram" && c.avatar.isNotEmpty() && c.bio == "Cześć, tu Ala"
        }
        assertEquals("Ala", b.db.contact(a.myPub)!!.name)
        // alias u B nie jest wysyłany, zostaje lokalnie
        b.setAlias(a.myPub, "Alka")
        assertEquals("Alka", b.db.nameOf(a.myPub))

        // grupa z ikoną
        val gChat = a.createGroup("Ekipa", listOf(b.myPub)); val gid = gChat.removePrefix("g:")
        waitFor("B w grupie") { b.db.group(gid)?.isMember(b.myPub) == true }
        a.setGroupLook(gid, "🎮", 7)
        waitFor("B widzi ikonę grupy", 40_000) { b.db.group(gid)?.icon == "🎮" }

        // aktualizacje może wysyłać tylko oficjalne konto — od zwykłej osoby są odrzucane
        val fakeApk = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "fake.apk").apply { writeBytes(Crypto.randomBytes(1000)) }
        try { a.send(pl.szept.data.groupChatId(gid), Kind.UPDATE, "9.9", Core.Attachment(fakeApk, "application/vnd.android.package-archive", "x.apk", null, 0, 0, 0)) } catch (_: Exception) {}
        delay(8000)
        assertTrue("aktualizacja od zwykłej osoby odrzucona", b.db.latestUpdate() == null && b.db.chats().none { ch -> b.db.messages(ch.id, 50).any { it.kind == Kind.UPDATE } })
        println("PROFILE/UPDATE OK")
        a.stop(); b.stop()
    }

    @Test
    fun features16() = runBlocking {
        assumeTrue(System.getProperty("szept.network") == "1")
        val a = core("Ala"); val b = core("Bartek"); val c = core("Celina")
        for (x in listOf(a, b, c)) waitFor("${x.myName} połączony", 40_000) { x.connectedRelays().size >= 3 }
        a.addContact(b.myPub, "Bartek"); a.addContact(c.myPub, "Celina")
        waitFor("B i C znają A") { b.db.contact(a.myPub)?.isContact == true && c.db.contact(a.myPub)?.isContact == true }
        val dmAB = dmChatId(b.myPub)

        // reakcja + edycja
        val m = a.sendText(dmAB, "Pierwsza wersja")
        waitFor("B ma wiadomość") { b.db.message(m.id) != null }
        b.react(m.id, "👍").join()
        waitFor("A widzi reakcję B") { a.db.reactionsForChat(dmAB)[m.id]?.any { it.pk == b.myPub && it.emoji == "👍" } == true }
        b.react(m.id, "👍").join()   // drugi raz = usunięcie
        waitFor("reakcja usunięta u A") { a.db.reactionsForChat(dmAB)[m.id].isNullOrEmpty() }
        a.editMessage(m.id, "Poprawiona wersja")
        waitFor("B widzi edycję") { b.db.message(m.id)?.let { it.body == "Poprawiona wersja" && it.edited > 0 } == true }

        // grupa: ankieta, odczyty, przypięcie, wzmianka w wyciszonej grupie, „pisze…”
        val g = a.createGroup("Ekipa", listOf(b.myPub, c.myPub)); val gid = g.removePrefix("g:")
        waitFor("B i C w grupie") { b.db.group(gid)?.state == GroupState.ACTIVE && c.db.group(gid)?.state == GroupState.ACTIVE }
        val poll = a.sendPoll(g, pl.szept.data.Poll("Pizza czy kebab?", listOf("Pizza", "Kebab"), false))
        waitFor("B i C mają ankietę") { b.db.message(poll.id) != null && c.db.message(poll.id) != null }
        b.vote(poll.id, listOf(0)).join(); c.vote(poll.id, listOf(1)).join()
        waitFor("A widzi 2 głosy") { a.db.votesForChat(g)[poll.id]?.size == 2 }
        waitFor("B widzi głos C") { b.db.votesForChat(g)[poll.id]?.any { it.pk == c.myPub && it.sel == listOf(1) } == true }
        val ann = a.sendText(g, "Ogłoszenie: spotkanie o 18")
        waitFor("B i C mają ogłoszenie") { b.db.message(ann.id) != null && c.db.message(ann.id) != null }
        b.markChatRead(g); c.markChatRead(g)
        waitFor("A: przeczytane przez B i C") { a.db.reads(ann.id).size == 2 && a.db.message(ann.id)?.status == St.READ }
        a.pinMessage(ann.id, true).join()
        waitFor("B widzi przypiętą") { b.db.pins(g).any { it.msg == ann.id } }
        b.pinMessage(ann.id, false).join()   // B nie jest adminem — nic się nie stanie
        delay(3000)
        assertTrue("nie-admin nie odpina", a.db.pins(g).any { it.msg == ann.id })
        val nc = CountingNotifier(); c.notifier = nc
        c.setMuted(g, true)
        val ment = b.sendText(g, "@Celina zerknij", extra = pl.szept.data.Extra(mentions = listOf(c.myPub)))
        waitFor("C dostał wzmiankę z powiadomieniem mimo wyciszenia") { c.db.message(ment.id) != null && g in nc.chats }
        b.sendTyping(g)
        waitFor("A widzi, że B pisze", 20_000) { (a.typing.value[g]?.get(b.myPub) ?: 0L) > System.currentTimeMillis() }

        // przekazanie dalej
        val n = b.forward(b.db.message(m.id)!!, listOf(b.openDm(c.myPub)))
        assertEquals(1, n)
        waitFor("C dostał przekazaną") { c.db.chats().any { ch -> c.db.messages(ch.id, 20).any { it.ex.fwd && it.body == "Poprawiona wersja" } } }

        // lokalizacja na żywo
        val loc = a.sendLocation(dmAB, 52.2297, 21.0122, 12f, 15 * 60_000L)
        waitFor("B ma lokalizację") { b.db.message(loc.id) != null }
        assertTrue(a.liveActive.value)
        a.db.setLiveLast(loc.id, 0, 52.2297, 21.0122)  // pozwól od razu wysłać aktualizację
        a.onLocation(52.2400, 21.0200, 8f)
        waitFor("B widzi nową pozycję") { pl.szept.data.Loc.parse(b.db.message(loc.id)!!.body)?.lat == 52.24 }
        a.stopLive(loc.id).join()
        waitFor("B widzi koniec udostępniania") { pl.szept.data.Loc.parse(b.db.message(loc.id)!!.body)?.ended == true }
        assertTrue(!a.liveActive.value)

        // relacja tekstowa i ze zdjęciem
        val st = a.postStory(Kind.TEXT, "Mój dzień", null, 3)
        waitFor("B i C widzą relację") { b.db.story(st.id) != null && c.db.story(st.id) != null }
        b.markStorySeen(st.id).join()
        waitFor("A wie, że B obejrzał") { a.db.storyViews(st.id).any { it.first == b.myPub } }
        val img = File(RuntimeEnvironment.getApplication().cacheDir, "s.jpg").apply { writeBytes(Crypto.randomBytes(60_000)) }
        val st2 = a.postStory(Kind.IMAGE, "", Core.Attachment(img, "image/jpeg", "s.jpg", null, 10, 10, 0), 0)
        waitFor("B pobrał zdjęcie relacji", 120_000) { b.db.story(st2.id)?.path != null }
        a.deleteStory(st.id).join()
        waitFor("relacja usunięta u B") { b.db.story(st.id) == null }

        // zaplanowana
        val at = System.currentTimeMillis() + 4000
        a.schedule(dmAB, at, Kind.TEXT, "Zaplanowana o czasie")
        assertEquals(1, a.db.scheduled(dmAB).size)
        waitFor("B dostał zaplanowaną", 60_000) { b.db.messages(dmChatId(a.myPub), 50).any { it.body == "Zaplanowana o czasie" } }
        assertTrue("nie wysłano przed czasem", System.currentTimeMillis() >= at)

        // grupa z zatwierdzaniem i linkiem jednorazowym
        val d = core("Damian"); val e = core("Ela")
        for (x in listOf(d, e)) waitFor("${x.myName} połączony", 40_000) { x.connectedRelays().size >= 3 }
        a.setApprove(gid, true)
        waitFor("B wie o zatwierdzaniu") { b.db.group(gid)?.approve == true }
        a.setGroupDescr(gid, "Paczka z podwórka")
        waitFor("C widzi opis") { c.db.group(gid)?.descr == "Paczka z podwórka" }
        val one = Links.parse(a.createInvite(gid, 3600_000, 1)!!) as Links.Parsed.GroupInvite
        d.joinGroup(one)
        waitFor("A ma prośbę D") { a.db.joinReqs(gid).any { it.pk == d.myPub } }
        assertTrue("D jeszcze nie w grupie", a.db.group(gid)?.isMember(d.myPub) == false)
        assertTrue(a.acceptJoin(gid, d.myPub))
        waitFor("D w grupie po zatwierdzeniu") { d.db.group(gid)?.state == GroupState.ACTIVE }
        assertEquals("link jednorazowy zużyty", 1, a.db.group(gid)!!.invites.first { it.token == one.token }.used)
        e.joinGroup(one)   // link już zużyty — admin nawet nie zobaczy prośby
        delay(6000)
        assertTrue("zużyty link nie działa", a.db.joinReqs(gid).none { it.pk == e.myPub })
        val main = Links.parse(a.groupInvite(gid)!!) as Links.Parsed.GroupInvite
        e.joinGroup(main)
        waitFor("A ma prośbę E") { a.db.joinReqs(gid).any { it.pk == e.myPub } }
        a.rejectJoin(gid, e.myPub).join()
        waitFor("E wie o odrzuceniu") { e.db.group(gid)?.state == GroupState.REMOVED }
        println("FEATURES 1.6 OK")
        listOf(a, b, c, d, e).forEach { it.stop() }
    }

    @Test
    fun links() {
        val pk = "a".repeat(64)
        val p = Links.parse("Dodaj mnie: " + Links.contact(pk, "Zoja Ż")) as Links.Parsed.ContactCode
        assertEquals(pk, p.pk); assertEquals("Zoja Ż", p.name)
        val g = Links.parse(Links.group("0123456789ab", "tok", listOf(pk), "Ekipa & co")) as Links.Parsed.GroupInvite
        assertEquals("Ekipa & co", g.name); assertEquals(listOf(pk), g.admins)
    }

    @Test
    fun crypto() {
        val a = Crypto.newPrivateKey(); val b = Crypto.newPrivateKey()
        val k1 = Crypto.conversationKey(a, Crypto.publicKey(b))
        val k2 = Crypto.conversationKey(b, Crypto.publicKey(a))
        assertTrue(k1.contentEquals(k2))
        val ev = pl.szept.nostr.Event.create(a, 1, listOf(listOf("p", "x\"y")), "linia\nnowa \u0001 ł")
        assertTrue(ev.verify())
        assertTrue(pl.szept.nostr.Event.fromJson(ev.toJson()).verify())
    }
}
