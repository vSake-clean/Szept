# Szept — an Android messenger with no server of its own

🇵🇱 [Polski](README.md) · 🇬🇧 English

*Szept* is Polish for “whisper”. The app's interface is in Polish.

## Installation
1. Download the latest `Szept-<version>.apk` from the [releases](https://github.com/vSake-clean/Szept/releases/latest), copy it to your phone (Android 8 or newer) and open it. Allow installing from unknown sources.
2. Enter your name and tap “Zaczynamy” (“Let's start”). That is the whole setup — no phone number, e-mail or server address needed.
3. Allow notifications and running **without battery restrictions** (banner on the home screen). On Xiaomi, Huawei, Oppo and Vivo phones also enable “Autostart”.

## Adding friends
- **Contact:** QR icon → “My code”. Your friend picks “Add contact” → “Scan QR code”, or pastes the `szept://…` link you sent them.
- **Group:** “New group” → Group info → “Invite link”. Send the link e.g. over WhatsApp or SMS. Anyone who opens it joins automatically as soon as any admin is online — or waits for approval if the admin turned on “Approve new members”. You can also create a link that is valid e.g. for 1 day or for 1 person.
- All group members can see each other, so **anyone can message anyone privately**.

## Updating
Every version has its number in the file name (`Szept-1.9.apk`, then `Szept-2.2.apk`…). The installed version is shown in Settings → “About” and on the welcome screen. You install a newer version the same way, by opening the file on the phone. It installs “over” the old version and keeps your account, contacts, groups and messages.

## Features
### New in 3.1
- **Almost 80 games** (paperclip → Game or menu ⋮ → Games): arcade, puzzle, memory & reflex, words & quizzes, cards & dice, board and party games. A new game picker with search and icons matching the rest of the menu.
- **Solo, two players or a group**: full-screen single-player games, **chat challenges** (same layout for everyone, ranking in the bubble), turn-based board games (chess, checkers, connect four, battleship…, also vs. the computer) and party games for groups.
- **Score and “Total” (Łącznie)**: after a game you first see the current score; “Total” shows a table of all results, your best, the average and who won how many times in the chat.
- **Python (pygame) games** run on the phone (Pyodide; the runtime is downloaded once, ~18 MB, verified with SHA-256; games have no internet access). Built-in **Wheelie Simulator 2D**; the administrator distributes more games in the admin panel → “Games” (a `.py` file or a `.zip` with `main.py`). Reporting a score: `import szept; szept.score(value)`.

### New in 3.0
- **Send when they're online**, **on-device message translation** (nothing is sent to the internet), **formatting** (*bold*, _italic_, ~strikethrough~, `code`, ||spoiler||).
- **Decoy PIN** that opens an empty Szept, **disguised icon** (a working calculator that opens Szept when you type a code), **auto-deletion** of old messages set separately per chat, a hidden preview in the recent-apps list.
- **Games in chat**: tic-tac-toe, dice, “Who's faster?” — played over messages, with no server.

### New in 2.9
- **Built-in (encrypted) TURN servers** — calls work right away over mobile data, too.

### New in 2.8
- **Calls** no longer hang on “Connecting…”; the admin can distribute a **TURN server** to everyone (needed on mobile networks).

### New in 2.7
- **Moderation**: the app admin can mute, block or delete an account (a signed sanctions list that every copy of the app enforces), view the panel password and create **one-time codes**; group admins can **mute and ban** members.

### New in 2.6
- Stability and security fixes (send-queue crash, validation of files and keys received from the network, sending no longer gets lost after leaving a chat) and an **error log** in Settings → Help.

### New in 2.5
- **GIFs**: animated, with a preview before sending; **My GIFs** saved locally (add from the phone or save from a chat); **KLIPY** search (key from klipy.com — in the GIF window, in the admin panel or in a `klipy.key` file at build time).
- Photo editor only for the profile picture and banner.

### New in 2.4
- **Private calls fixed** and redesigned; **adding people to an ongoing call** (it rings them, also again).
- Photo editor: improved cropping and button layout; **Remove from friends** in a person's profile.

### New in 2.3
- **Statuses**: active / in background / be right back / inactive — a dot on the avatar, a description in the chat and profile; set in My profile → “My status”.
- **Swipe to reply**: swipe a message right = reply, long-press = reaction.
- **QR scan** straight from the “My code” screen.

### New in 2.2
Full list of changes: in the app under **Settings → What's new** and in `CHANGELOG.md`.
- Simpler settings, PIN and emergency code with a trash icon, a “deleted for recipient” marker, screenshot blocking, live location for up to 8 h.
- **Official “Szept” ✔ account** and an **admin panel** (Settings → “Admin panel” at the bottom, password only):
  - sending updates and messages to selected people (also outside your friends — by code/link), with a recipient counter; nothing stays in the sender's history and recipients cannot reply;
  - granting and revoking the ✔ badge (signed with the admin key — cannot be forged);
  - forcing updates: you pick the oldest allowed version, older ones show a “You must update” screen with a download button.
- Updates (app files) are accepted only from the official account.

### New in 2.1
- The profile picture and banner can be **cropped** when picked (1:1 for the picture, 3:1 for the banner) and are saved right away — without leaving the profile.
- Picking a photo no longer triggers the PIN lock after returning from the gallery.
- A disappearing message “from opening” no longer shows a misleading 1 h counter to the sender — the countdown only starts once it is opened.

### New in 2.0
- **Group calls** (handset icon in a group chat; video in the ⋮ menu): all members ring, and anyone who missed it can join later from the “Group call in progress” bar in the chat. Everyone connects directly to everyone, so without a server it fits up to **6 people** (voice) or **4 people** (video). One person leaving does not end the call for the others.

### New in 1.9
- **Voice and video calls** (handset icon in a private chat; video in the ⋮ menu): end-to-end encrypted (WebRTC, DTLS-SRTP), audio and video flow directly between the phones. Full-screen ringing (also on a locked phone), mute, speaker, camera switching, screen off near the ear, going back to chats during a call, “Missed call” in the history.
- If you use Szept on several devices, all of them ring — you answer on one.
- Also works without internet on the same Wi-Fi network (“No internet” mode).
- To be honest: some mobile networks block direct connections — then Wi-Fi or your own TURN server helps (Settings → Calls). The other person can learn your IP address.

### New in 1.8
- **No internet** (Settings → No internet): phones on the same Wi-Fi network message each other directly — every phone with the option on acts as a small relay and finds the others by itself. No router? One phone creates a Wi-Fi network (“Create Wi-Fi network”) and the others scan its code. Text, photos, voice messages and groups work; a message for someone who isn't around waits on the phone until that person shows up.
- **Courier**: envelopes that others left on your phone are sent to the internet once you get a signal. Optionally your phone also picks up envelopes for your friends from the internet and hands them over when you meet without internet (you can't see the content — it's encrypted).
- **One account on several devices** (Settings → My devices): sign in with your account code on a second phone or tablet — the devices find each other. Sent messages, read receipts, reactions, edits, deletions, mutes, new contacts and groups are synchronized.
- **Key rotation (forward secrecy)** (Privacy & lock): messages to you are encrypted with a key that your phone changes every week and deletes after 5 weeks. Works with senders on 1.8+, older versions can still message you.

### New in 1.7
- **Photo editor** (✏️ Edit in the preview before sending, in an album and in a story): cropping, rotation, finger drawing, blurring part of the image and **automatic face blurring** — all on the phone, before encryption.
- **Round video notes** (📎 → Video note): front camera (switchable), up to 1 minute — tap the red button to record and again to send. They play in a circle in the chat.
- **Voice message to text** (long-press a voice message → 📝 Convert to text): speech recognition **on the phone**, the recording is not sent anywhere. Requires Android 13+ and the Polish speech pack (the phone may ask to download it the first time). Only you see the text.
- **Backup** (Settings → Backup): account, contacts, groups, the whole history and — if you want — photos and videos in a single `.szept` file encrypted with your password (AES-256, PBKDF2). On a new phone: welcome screen → “Restore from backup”.
- **Encrypted message database** on the phone (SQLCipher, AES-256): the key lives in Android's hardware keystore. An existing database is encrypted automatically on the first launch of 1.7. The status is shown in Privacy & lock → “Data on the phone”.
- Compatibility with 16 KB page-size phones (Android 15+) and newer libraries.

### New in 1.6
- **Reactions** (long-press a message → 👍 ❤️ 😂 😮 😢 🙏 or “+”) and **editing** your own messages (“edited”).
- **Polls** (📎 → Poll): single or multiple choice, live results, “Who voted”.
- **“Read by…”** in groups (long-press your message → Info) and a **“typing…”** indicator (in chats of up to 16 people; can be turned off).
- **Forwarding** messages to other chats, **@name mentions** in groups (they notify even in a muted group; an admin can use @everyone).
- **Scheduled messages**: long-press Send → “🕓 Schedule send…” (also photos from the preview). The phone sends them by itself at the chosen time.
- **Pinned messages / announcements** (in a group the admin pins) — a bar at the top of the chat.
- **Location** (📎 → Location): one-time or **live for 15 min or 1 h**. No Google services; the map opens in your maps app.
- **24 h stories** (bar at the top of the chat list): a photo or text on a colored background, with a list of viewers.
- **Broadcast message** (⋮ menu): everyone receives it separately.
- **Several photos at once** (an album of up to 10 photos, shown as a grid), swiping through a chat's photos, **saving to the gallery** and a **chat gallery** (media, files, links).
- **Groups:** description, manual approval of new members, links limited by time and number of uses.
- **Hidden chats** (long-press a chat → Hide): they disappear from the list, and you reveal them by typing a separate PIN in the search box.
- **Notification content:** full, sender only or nothing (Privacy & lock). On Android 14+ participants are notified about a screenshot of the chat.
- **Search** (magnifier at the top): chats and messages, jumping to the found message; search inside a chat as well (⋮ → Search).
- **Pinning and archiving chats** (long-press a chat), **drafts** (unsent text waits in the input field).
- **Appearance** (Settings): light/dark/black theme, 8 colors, text size, chat wallpapers, notification sound — also per chat.
- **Your own relay** (Settings): instructions for a Raspberry Pi/computer and a link your friends use to add your relays with one tap.

### Earlier
- **Discord-style profile** (⋮ menu → My profile): profile picture, banner, status, bio. Friends see it when they tap your name.
- **Permanent ID** (e.g. `SZPT-7QF3-K9A2`) — derived from the account key and cannot be changed, even when you change your name.
- **Names:** you change yours in your profile, a group's name is changed by an admin in Group info, and you can give anyone your own nickname (✏️ in a person's profile — only you see it).
- **Group icon** (a photo or an emoji) — changed by an admin, visible to everyone.
- **Streak 🔥** — the number of consecutive days you have both messaged each other (in the chat header and the profile).
- **People** (⋮ menu): everyone your phone knows (contacts + people from your groups), with search by name or ID and blocking.
- **Privacy & lock** (Settings): PIN on entry, fingerprint/face unlock and an **emergency (wipe) code** — typed instead of the PIN, it silently deletes all or selected chats, can block everyone or selected people and optionally delete the account.
- **Account access code** (Settings): your key as a code and a QR code — entered under “I already have an account” on another phone, it moves the account.
- **Updates from friends** (Settings → “Send update to everyone”): sends the installed version to groups and contacts; they see “New version available — Install”.
- Private and group messages (20+ people, no limit), ✓ sent, ✓✓ delivered and read receipts.
- Quoted replies: long-press a message → “Reply”. Tapping the quote scrolls to the original. The chat has day separators (“Today”, “Yesterday”…).
- Photos (from the gallery and the camera), video (automatically compressed to about 480p, max 16 MB), files.
- **One-finger voice messages** (microphone button):
  - hold — recording, release — sent,
  - slide left — cancel,
  - slide up or tap briefly — hands-free recording (then “Send” or the trash can),
  - while recording you see the voice level, and the bubble shows the waveform. Tapping the waveform seeks, the 1×/1.5×/2× button changes the speed.
- Deleting: for me, for the recipient, for selected people in a group or for everyone (also from the relays).
- **Disappearing messages** (text, photo, video, voice message, file):
  - the recipient only sees “⏱ Disappearing message — tap to read”. The content is neither in the notification nor in the chat list; a photo doesn't even have a thumbnail,
  - **the timer starts only when it is tapped**. Then the content and a countdown appear (e.g. 10 s), after which the message disappears. An unopened one waits,
  - a disappearing voice message plays right away, and video and voice messages don't disappear while playing,
  - the sender sees “Waiting to be opened” or “Opened”. Screenshots are blocked.
  - How to send: text — long-press Send and pick a time; photo or video — “⏱ Disappearing” in the preview; voice message — “⏱ Disappearing” during hands-free recording; a whole chat — ⏱ icon → “Disappearing messages”.
- **Auto-delete** (⏱ icon in a chat): messages are visible normally, and disappear for everyone 5 s … 7 days after sending.
- Muting chats (Group or contact info). The chat list then shows a 🔕 icon.
- Replying straight from the notification and a “Mark as read” button.
- A group admin can rename it, add and remove members, grant admin rights, turn on “only admins can write”, revoke the invite link and dissolve the group.
- Blocking contacts, a key fingerprint for verifying identity, a key backup for moving the account to a new phone.
- “Send the app to friends” (⋮ menu or Settings) — shares the APK file via Quick Share, Bluetooth, a messenger or e-mail.
- Resilience to a wrongly set phone clock: the app corrects the time based on the relays. Without this, a phone with a fast clock could not send, and one with a slow clock would receive poorly.

## How does it work without a server?
The app has no server of its own and no company that everything passes through. An account is a key pair generated on the phone. Messages are carried by **public Nostr relays** — 8 by default, run by different independent people and companies. They work like mailboxes:

```
Your phone ──🔒 envelope──►  relay 1 ┐
           ──🔒 envelope──►  relay 2 ├──► the recipient's phone fetches “its” envelopes
           ──🔒 envelope──►  … (8 at once) ┘   whenever it has internet (also after power-on)
```

1. Every message is end-to-end encrypted **separately for each recipient** (ECDH secp256k1 → HKDF → AES-256-GCM) and signed with the sender's key.
2. The encrypted message goes into an “envelope” signed with a one-time key. So the relay sees neither the content nor the sender.
3. The envelope is sent to all relays at once, so a few of them failing doesn't matter.
4. **Recipient inactive or with the phone off:** envelopes wait on the relays for up to 21 days. When the phone is turned on, the service starts by itself and fetches the pending messages. If the sender was offline, the message waits in their queue and goes out once they get internet.
5. Media is split into encrypted chunks of ~44 KB and sent once, also in a group. Their key travels in the envelopes.
6. All connections are outgoing (WebSocket/TLS), so the app works from any network and any IP address, over Wi-Fi and mobile data, with no port forwarding.
7. **Without internet** the phones become relays themselves: each one with the option on runs a small Nostr relay (port 4869) and announces itself on the Wi-Fi network (mDNS/NSD). The same encrypted envelopes then travel between phones, and once one of them gets internet — they also reach the regular relays.
8. With **key rotation** on, every device creates a new key every 7 days and announces it to friends in an encrypted envelope; envelopes to it are encrypted with that key, and the phone deletes old keys.

A background service runs with a quiet “Szept is receiving messages” notification. On top of that, an alarm every 10 minutes and WorkManager every 15 minutes check the connection.

**Background mode** (Settings):
- **Balanced** (default) — while the app is closed, the phone keeps only 2 live connections, so messages arrive instantly (about 0.3 s in testing), and the remaining relays are synchronized about every 10 minutes. Uses noticeably less battery than 8 permanent connections.
- **Full** — all relays live.
- **Saver** — no permanent connection, checking every 10–15 minutes.

The relay list can be changed in Settings. Anyone can also add their own relay, but it is not required.

## Building from source
```
./gradlew assembleRelease          # output: app/build/outputs/apk/release/Szept-<version>.apk
./gradlew testDebugUnitTest -Dszept.network=1   # end-to-end tests against real relays
```
Always sign updates with the same key (the `szept.keystore` file), otherwise Android won't install the new version over the old one.
The key, passwords and the encrypted admin key **are not in the repository** — copy `secrets.properties.example` to `secrets.properties`
(or set the environment variables `SZEPT_KEYSTORE`, `SZEPT_STORE_PASS`, `SZEPT_KEY_ALIAS`, `SZEPT_KEY_PASS`, `SZEPT_ADMIN_ENC`, `KLIPY_KEY`).
Without them the app builds normally, just without the release signature and without the admin panel.

---

## More projects by vSake

- **[Fih](https://github.com/vSake-clean/Fih)** — programming language with a compiler written in pure x86-64 assembly, no libc
- **[LunarDraw](https://github.com/vSake-clean/LunarDraw)** — GTK3/Cairo drawing app with layers
- **[LuminaView](https://github.com/vSake-clean/LuminaView)** — fast, lightweight Qt5 image viewer for Linux
- **[StatusCycler](https://github.com/vSake-clean/StatusCycler)** — Vencord plugin that cycles your Discord custom status
- **[vencord-AutoReply](https://github.com/vSake-clean/vencord-AutoReply)** — Vencord plugin for automatic replies with triggers and filters
- **[vencord-StickyNotes](https://github.com/vSake-clean/vencord-StickyNotes)** — Vencord plugin for sticky notes on Discord users
- **[vencord-friendgroups](https://github.com/vSake-clean/vencord-friendgroups)** — Vencord plugin to group friends in the DM sidebar

If you like this project, consider leaving a ⭐ — it really helps!
