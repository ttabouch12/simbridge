# Changelog

## SimBridge 2.4.0

Update both apps. Pairing is kept.

### Main phone
- **Messages work like Google Messages / Gmail:**
  - **Swipe** a conversation: read / unread, archive, delete or pin. Choose what each direction does
    in Settings > Messages. Delete shows **Undo**.
  - **Select several** (long-press, then tap): pin, archive, read / unread, delete, block, select all.
  - **Pinned** conversations stay on top; **Archived** ones leave the list until a new text arrives.
  - **Filters:** All, Unread, Archived, Starred. **Search** also looks inside messages; a
    conversation has its own search too.
  - **Links, e-mail addresses and phone numbers** in texts open when tapped; one-time codes copy.
  - **Long-press a message:** copy, star, forward, details, delete (several at once).
  - **Send later:** hold Send and pick a time. Waiting texts show above the keyboard and can be
    cancelled; they go out when the SIM phone is reachable.
- **Faster call translation:**
  - Words appear **while they are spoken** (cloud speech), and the translation is written out as it
    arrives instead of all at once.
  - The next sentence is recognized while the previous one is translated.
  - Long sentences end on a shorter pause; the default pause is now "Fast".
- **Battery time left** for the SIM phone on the dashboard, from its real drain (or time to full).

### SIM phone (gateway)
- **Hotspot comes on by itself when Wi-Fi is lost** (with home/work Wi-Fi set up and its switch on),
  also after a restart away from home, so the main phone can always reach it.
- Time-left estimate from the measured drain of the current discharge.
- The 20 % low-battery alert was sent twice; now once at 20 % and once at 10 %.

## SimBridge 2.3.0

Update both apps. Pairing is kept.

### Main phone
- **Dashboard is the home screen.** Swipe between Messages, Overview, Controls, USSD, Data, Places
  and Speed; the tabs sit at the bottom, within thumb reach. Settings also swipes between its tabs.
- **Control the SIM phone's screen**, scrcpy-style: hardware H.264 video and direct touch input over
  one encrypted, tamper-proof (AES-GCM) connection on the LAN.
  - The SIM phone's screen can stay dark while you control it.
  - Quality presets (Fast / Balanced / Sharp), optional sound, screenshots, full screen.
  - Buttons for back, home, recent apps, volume, power and typing.
  - Blind PIN unlock: the lock screen is hidden from screen capture, so the PIN is typed for you.
- **USSD like the phone's own dialer:** the network's answer appears in a popup-style card, and menus
  can be answered (Reply / Cancel). Works on networks that refuse background USSD ("-1").
- **Home and work Wi-Fi:** pick networks saved on the SIM phone or nearby, edit them, hidden password.
  When the main phone sees one, the SIM phone joins it and turns its hotspot off; when it leaves, the
  hotspot comes back.
- **Speed test:** ping, download and upload between the phones, and to the internet.
- **Missed calls** on the Overview with call back; **home-screen widget** with link state, battery
  and signal.
- **One-time codes** in texts are copied for your keyboard to paste, with a *Copy* button in the
  notification.
- **Mobile data details:** 30 days per day and the apps that used most.
- Switches show a spinner and confirm at once; colored on/off badges.
- Fixed: the same text could arrive twice after a reconnect.

### SIM phone (gateway)
- **Remote hotspot fixed** (it was refused as "no permission"); a clear message when the phone can't
  host a hotspot while it is on Wi-Fi.
- **Low-battery alerts** at 20 % and 10 %, sent to the main phone.
- New control operations for the features above (screen stream, USSD menus, saved Wi-Fi networks,
  data detail, speed test).

## SimBridge 2.2.0

### Main phone
- **Live call translation** for relayed calls (Settings > Call translation):
  - **Captions:** the caller's words appear translated in a floating window over the call screen
    (or tap Translate in the call notification for full screen).
  - **Captions + voice:** your words are also spoken to the caller in their language.
  - 19 languages (Turkish, English, Arabic, Persian, Russian, Ukrainian, French, German, Spanish and more).
  - Free engines: Groq GPT-OSS 120B / 20B, Cerebras GPT-OSS 120B, Google Gemini 2.5 Flash-Lite,
    or on-device (ML Kit, full build only). Speech recognition: Groq Whisper or on-device.
  - Options: pause length, read the caller's translation to you, large text, on-device only.

## SimBridge 2.1.0

Updating from 2.0 keeps the pairing. Coming from 1.x? Pair once with the QR code (see 2.0.0 below,
which 2.1 includes).

### Main phone
- **New layout:** Messages, Dashboard and Settings tabs.
- **Dashboard** with a live picture of the SIM phone: every SIM (number, carrier, eSIM, signal),
  data used today / this month, devices on its hotspot, **network mode** per SIM (5G / 4G / 3G / 2G),
  **APN** list, choice and new APNs (experimental), **find my SIM phone** (location → Maps).
- **Carrier voicemail:** unanswered calls go back to the carrier after 15 / 25 / 40 s.
- **"Does it work on my phone?"** audio test call, with a one-tap compatibility report.
- **Texts in a browser** on your computer (HTTPS on the LAN, 6-digit code, session cookie).
- **Caller names on the SIM phone** (contact names sent from the main phone; can be turned off).
- **Away from home:** optional VPN address of the SIM phone (Tailscale / WireGuard).
- **Update check** once a day (off in the FOSS build).
- **Languages:** Arabic, Farsi, French, Russian, Spanish, Turkish (and English), with right-to-left
  layout and a per-app language setting (Android settings → Apps → SimBridge → Language).
- **FOSS build** (`SimBridge-FOSS`) without Google code, for F-Droid: pair by scanning the code with the
  camera app or by pasting the link.
- **Contacts & remote access** and **Updates** cards in Settings.
- Backups now include pictures.

### SIM phone (gateway)
- **Any SIP app** (Linphone on iPhone/Mac/Windows/Linux, Acrobits, Zoiper): QR setup for Linphone,
  account details for others. Calls ring on every registered app and phone.
- **OLED saver** in the power saver: dark theme and 60 Hz.
- Remote network mode, APN, location and hotspot-device list for the dashboard.
- Usage access and location are granted through Shizuku automatically.
- Shows caller names from the main phone's contacts in its own alerts.

### Docs and distribution
- Guides: [Shizuku setup by phone maker](docs/shizuku-setup.md), [remote access](docs/remote-access.md),
  [compatibility list](docs/COMPATIBILITY.md) with a GitHub report template.
- Store listings (fastlane) and [store guide](docs/stores/README.md): Obtainium, IzzyOnDroid,
  F-Droid, Yuki, Orion Store.

### Fixes
- Texts and missed-call alerts are no longer lost while the main phone is away: anything a phone
  didn't confirm is kept and resent when it reconnects.
- A phone that disconnects is dropped at once instead of up to 20 minutes later.
- Connections that don't log in within 30 s are closed, and large messages are only accepted after
  login (a device on the Wi-Fi could block the phones before).
- Old SIP stack, transport and SRTP settings that no longer did anything were removed; the unused
  native pjsua2 module was deleted.
- Replies (status, USSD) go only to the phone that asked.
- OLED saver: early test builds also switched to black & white and a lower resolution, which could
  leave the screen unreadable. It is now dark theme and 60 Hz only, and turning it off undoes the
  older changes.
- Tests for the MMS encoder/decoder, phone-number matching, keep-alives and dropped connections.


## SimBridge 2.0.0

### Security (breaking: pair the phones again once)
- **QR pairing.** The SIM phone shows a code with its key fingerprint and a random password; the main
  phone scans it (Google code scanner, or paste the `simbridge://pair` link). The password field is gone.
- **SIP over TLS** (port 5063, TLS 1.3) with the gateway's EC key **pinned** on the main phone. The
  gateway key lives in the Android Keystore; the main phone keeps its pairing encrypted with its own
  Keystore key.
- **SRTP** (AES_CM_128_HMAC_SHA1_80, SDES keying) on every call; unencrypted audio is refused.
- **Digest on every call and text** with single-use nonces. In-call requests are only accepted from the
  phone that owns the call. Forged UDP requests are challenged, not executed.
- **LAN only:** the gateway refuses public addresses and anything arriving over mobile data.
  Plain SIP/UDP (port 5062) is off unless *Allow other SIP apps* is turned on.
- Generic mDNS name (`SimBridge-xxxxxx`); *New key* unpairs every phone at once.

### New
- **HD voice:** 16 kHz linear PCM (L16) between the phones, resampled to the cellular call; G.711 fallback.
- **Picture messages and group texts (MMS)**, with SimBridge Gateway as the SIM phone's default SMS app.
  Pictures are resized to fit carrier limits; group chats show who said what.
- **Delivery reports:** *Sent* and *Delivered* under each text.
- **Missed calls while offline** are added to the Phone app's call history, with a *Call back* notification.
- **Call blocking** at the SIM phone, plus hidden numbers; blocked calls are logged as blocked.
- **SIM phone dashboard:** signal, network, battery, data, hotspot, uptime.
- **USSD codes** (balance, data left) from the main phone.
- **Remote control:** battery saver, mobile data, hotspot (experimental), *Ring it* (find the SIM
  phone), *Restart link*.
- **Call recording** on the main phone: a *Record* button in the call notification and an *always
  record* option. AAC files in Recordings/SimBridge.
- **Dual SIM:** per-SIM calling accounts (*SIM 1*, *SIM 2*), SIM labels on texts, replies on the
  line a conversation came in on.
- **Several main phones** (up to four): calls ring on all of them, the first to answer wins and the
  others show *answered elsewhere*; texts reach every phone.
- **Wear OS / Android Auto:** conversation notifications (long-lived shortcuts) and car messaging
  support.
- **Backup:** export / import texts, blocked numbers and settings as JSON.

### Faster and lighter
- Registration every 10 minutes over TLS, with a CRLF keep-alive every 2 minutes (was every
  2 minutes over UDP). Fixed keep-alive answers being held back, which made the link reconnect every
  few minutes.
- The power saver restricts apps in one batched shell run, eight at a time (seconds instead of a
  minute).
- Startup tries the last working address before the router, and gives up on a refused address at
  once instead of waiting.
- First call after a restart or update: if Android kept the microphone from SimBridge, a
  notification turns it on with one tap.


## SimBridge 1.1.0

- **SimBridge can be the default SMS app** on the main phone: "Message" buttons in other apps open
  it (with the text pre-filled), the Phone app's *Reply with message* sends through the SIM, and texts
  are stored in the system SMS database with no adb step. (Google Messages can't send on a SIM-less
  phone, so it can't be used to reply.)
- **Ultimate power saver** options on the SIM phone: *No sounds*, *No vibration*, *Keep the screen
  off*. Each setting is restored exactly when the power saver is turned off.
- **Quieter calls on the SIM phone**: local keypad beeps for your key presses are silenced during
  bridged calls. Where Android allows it, the modem's voice volume is set to 0 too.
- **Instant reconnection after a gateway restart**: a call or text from a phone the gateway doesn't
  know yet gets a password challenge instead of being refused, so there's no wait for the next
  check-in.
- Deleting a conversation in SimBridge also deletes its copies in the system SMS database.

## SimBridge 1.0.0

First release of SimBridge, based on voice-sip-agent-selfhost (see [`docs/upstream/CHANGELOG.selfhost.md`](docs/upstream/CHANGELOG.selfhost.md)
for earlier history).

### New: SimBridge app for the main phone (`phone` module)
- Calls ring in the **system Phone app** through a Telecom calling account (incoming and outgoing,
  call history, contact names, keypad DTMF, mute/speaker/Bluetooth).
- **Messages-style texting**: conversations, contact photos, bubbles, notifications with Reply and
  Mark as read, send status with retry, new chat from contacts.
- Optional copy of relayed texts into the **system SMS database**, so they show in Google Messages or
  the default SMS app (needs one `adb shell appops set io.github.simbridge WRITE_SMS allow`).
- **Automatic discovery** of the gateway over mDNS (`_simbridge._udp`), with a fallback to the
  hotspot's gateway address and the last address that worked. Reconnects after network changes.

### Gateway (SIM phone)
- Advertises itself over mDNS. No IP addresses needed.
- **Fixed silent calls over the SIM phone's hotspot**: SDP/Contact now use the local address on the
  main phone's network instead of the default route's.
- SIP `MESSAGE` relay of texts from the main phone, with contact names on incoming texts and calls.
- Status alerts as texts: reboot, Shizuku down/back, battery low, unplugged.
- No call screen and no full-screen wake-ups while the main phone is connected (as default phone app).
- Companion power-saver profile, new Material 3 interface, registrar password editor.
- New app ID `io.github.simbridge.gateway`, so it installs alongside upstream builds.
