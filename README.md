# SimBridge

**Use your phone's SIM on another phone, over Wi-Fi. No root, no server, no cloud.**

SimBridge turns an Android phone that holds a SIM card (the **SIM phone**) into a pocket gateway for a
second Android phone without a SIM (the **main phone**). Calls and texts on the SIM's number are relayed
to the main phone, and the main phone makes calls and sends texts through the SIM, either over the SIM
phone's own hotspot or over any Wi-Fi network the two phones share.

On the main phone it feels native. Calls ring in the **system Phone app**, with its call screen, call
history, contact names and keypad. Texts appear in a Google Messages-style app, and can optionally be
copied into your **default Messages app** too.

[**⬇ Download the latest release**](../../releases/latest) ·
[How it works](#how-it-works) · [Setup](#setup) · [FAQ & troubleshooting](#troubleshooting) ·
[Disclaimer](DISCLAIMER.md)

<p align="center">
  <img src="docs/images/simbridge-gateway.png" width="280" alt="SimBridge Gateway on the SIM phone">
  &nbsp;&nbsp;
  <img src="docs/images/simbridge-settings.png" width="280" alt="SimBridge settings on the main phone">
</p>

---

## Why?

Some people want to carry one phone but can't, or don't want to, put their SIM in it. For example:

- **Your main phone can't use your local mobile network.** It may be an imported or travel phone that
  isn't registered for use in your country, or one that lacks your carrier's bands. Keep the SIM in a
  cheap, compatible phone in your bag. That phone shares its mobile data as a hotspot and relays calls
  and texts. Your main phone uses only Wi-Fi.
- **Two numbers, one phone in hand.** Leave a second line (work, home country, a local tourist SIM) in
  a spare phone and still answer it from your main phone.
- **A home line that follows you around the house.** Leave the SIM phone on a charger where reception
  is best, and use the main phone anywhere on your home Wi-Fi.

> **The motivating setup:** a SIM phone (a Samsung Galaxy A56) lives in a bag, plugged into a power
> bank, with its hotspot on. A Pixel 9 with **no SIM** joins that hotspot for internet. SimBridge rings
> the Pixel's own Phone app for calls to the SIM's number, sends texts both ways, and the A56's screen
> never turns on.

SimBridge does **not** modify IMEIs, clone SIMs, or connect the main phone to any cellular network. The
main phone only ever uses Wi-Fi. See the [disclaimer](DISCLAIMER.md).

## Features

| | |
|---|---|
| 📞 **Calls in the system Phone app** | Incoming calls ring with the normal call screen, contact name and ringtone. Outgoing calls go from the Phone app's keypad or contacts. Calls appear in the call history. |
| 🔢 **Keypad tones (DTMF)** | Press numbers during a call to navigate phone menus (language selection, "press 1 for…"). |
| 💬 **Texts (SMS)** | A Google Messages-style app: conversations, contact photos, bubbles, notifications with **Reply** and **Mark as read**, and send status ("Sent" / "Not sent · tap to retry"). |
| 📥 **System SMS integration (optional)** | Make SimBridge the **default SMS app**: "Message" buttons in Contacts and other apps open it, the Phone app's *Reply with message* sends through the SIM, and texts are saved to Android's SMS database. Or keep Google Messages as default and just copy relayed texts into it (read-only, one adb command). |
| 🔍 **Finds the SIM phone automatically** | Uses mDNS/DNS-SD (`_simbridge._udp`), plus the hotspot's gateway address. Works on home Wi-Fi and on the SIM phone's hotspot. No IP addresses to type, and it reconnects after network changes. |
| 🔒 **Password protected** | SIP digest authentication, IP-locked calls and texts, and brute-force lockout. Only your main phone can use the SIM. |
| 🌙 **Quiet SIM phone** | While the main phone is connected, the SIM phone shows no call screen, its screen stays off, its call audio is muted, and the local keypad beeps it would play for your key presses are silenced. |
| 🔋 **Ultimate power saver** | One switch on the SIM phone turns off Bluetooth, location and scanning, and puts every other app to sleep. Optional extras: **no sounds**, **no vibration** and **keep the screen off** (Do Not Disturb, haptics, Always On Display and wake gestures). Turning it off restores every setting exactly. |
| 🔁 **Survives reboots** | Starts by itself. Status messages ("SIM phone restarted", "Shizuku stopped", "battery low", "unplugged") arrive as texts from "SimBridge Gateway". |
| 🚫 **No root, no server** | Everything runs on the two phones. Nothing goes through the internet unless you set that up yourself. |

## How it works

```
┌────────────────────────────┐        Wi-Fi / SIM phone's hotspot        ┌─────────────────────────────┐
│  SIM phone                 │ ◄──────────────────────────────────────► │  Main phone (no SIM)        │
│  "SimBridge Gateway"       │     SIP over UDP (port 5062) + RTP audio  │  "SimBridge"                │
│                            │     mDNS: _simbridge._udp                 │                             │
│  cellular call ◄──► call   │                                           │  Telecom ConnectionService  │
│  audio tap (via Shizuku)   │     INVITE / BYE / RFC 4733 DTMF          │   → system Phone app UI     │
│  SMS in/out (SmsManager)   │     SIP MESSAGE (texts, status alerts)    │  Messages-style app         │
│  built-in SIP registrar    │                                           │   (+ optional copy to the   │
│                            │                                           │    system SMS database)     │
└────────────────────────────┘                                           └─────────────────────────────┘
```

- **SimBridge Gateway** (SIM phone) is a fork of
  [voice-sip-agent](https://github.com/sparkcyf/android-voice-sip-agent) in its "device-in-itself"
  configuration ([selfhost fork](https://github.com/yyano/voice-sip-agent-selfhost)). It answers and
  places cellular calls, and bridges their audio to a built-in SIP server. Android 13+ lets a
  privileged process capture and inject call audio (`CALL_AUDIO_INTERCEPTION` /
  `CAPTURE_AUDIO_OUTPUT`). [Shizuku](https://github.com/RikkaApps/Shizuku) gives the app that
  privilege, **without root**.
- **SimBridge** (main phone) registers to the gateway with the same pure-Kotlin SIP stack. It
  registers a calling account with Android's Telecom framework, so the system Phone app handles the
  call UI. Audio goes through the main phone's microphone and earpiece, with echo cancellation.

## Requirements

**SIM phone (gateway)**
- Android **13 or newer**, with a SIM, **no root needed**.
- [Shizuku](#downloads) running (wireless debugging; set once).
- The phone's audio system must allow call audio capture. Many modern phones do. It works on a
  **Samsung Galaxy A56 (Exynos 1580, Android 17, One UI)**. The upstream projects report it working
  on a Google Pixel 11 Pro (Android 17), a Motorola (Android 16) and a Nubia NX769J (Android 16). If calls connect with no sound, see
  [troubleshooting](#troubleshooting).

**Main phone**
- Android **14 or newer**. Tested on a **Google Pixel 9 (Android 17)**. The system Phone app must
  support third-party calling accounts (Google Phone does).
- Same Wi-Fi network as the SIM phone, **or** connected to the SIM phone's hotspot.

## Downloads

From the [latest release](../../releases/latest):

| File | Install on |
|---|---|
| `SimBridge-Gateway-<version>.apk` | the **SIM phone** |
| `SimBridge-<version>.apk` | the **main phone** |

Also needed on the **SIM phone**: **Shizuku**, either of:
- [Shizuku by thedjchi](https://github.com/thedjchi/Shizuku/releases) (**recommended**: adds *start on
  boot*, a *watchdog* and TCP mode, so it restarts by itself after a reboot), or
- the official [Shizuku by RikkaApps](https://github.com/RikkaApps/Shizuku/releases) /
  [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api).

Optional, for setup by cable: [Android platform-tools (adb)](https://developer.android.com/tools/releases/platform-tools).

## Setup

### 1. SIM phone

1. **Enable Developer options:** Settings → About phone → Software information → tap *Build number*
   7 times.
2. **Install Shizuku** and start it with **Wireless debugging** (Shizuku shows the steps: Developer
   options → Wireless debugging → *Pair device with pairing code*, then type the code into Shizuku's
   notification). In thedjchi's Shizuku, also turn on **Start on boot** and **Watchdog**.
   - On Android 17, allow Shizuku's *notifications* and *nearby devices / local network* prompts, or
     pairing silently fails.
3. **Install `SimBridge-Gateway.apk`** and open it. Tap **Allow Shizuku access → Allow all the time**.
4. Allow every permission it asks for (phone, SMS, contacts, notifications).
5. **Make it the default phone app** when asked, or later in Settings → Apps → Default apps → Phone
   app → *SimBridge Gateway*. This lets it answer calls without the SIM phone's own call screen.
   While the main phone is not connected, it shows a simple call screen on the SIM phone.
6. Under **Security**, set a **password**. Use something long; the main phone needs it once.
7. Leave **Forward calls & texts** on and **Keep this phone silent during calls** on. Optional:
   **Companion power saver**, with its *No sounds*, *No vibration* and *Keep the screen off* options,
   for a phone that only bridges calls, texts and data.
8. Turn on **Mobile hotspot** if the main phone will use it for internet. If both phones are on the
   same Wi-Fi, you don't need the hotspot.

Recommended: Settings → Battery → set SimBridge Gateway and Shizuku to **Unrestricted**. On Samsung,
also add both to *Never sleeping apps*.

### 2. Main phone

1. **Install `SimBridge.apk`** and open it. Allow microphone, contacts, phone, notifications and
   *nearby devices / local network*.
2. Open **Settings** (⚙ in the search bar):
   - **Gateway password:** enter the password from step 1.6 → *Save*. The status chip changes to
     **Connected via Wi-Fi** or **Connected via hotspot**.
   - **Calls in the Phone app:** tap *Open calling accounts* and turn on **SimBridge**. In Google
     Phone this is Settings → Calling accounts → All calling accounts. If the main phone has no SIM of
     its own, set SimBridge as the account to **make calls with**.
   - **Stay connected in the background:** tap *Allow* (battery optimization exemption).
3. That's it. Call from the Phone app as usual, and text from SimBridge.

#### Optional: make SimBridge the default SMS app (recommended)

Settings → **Default SMS app** → *Make default SMS app*. Then:
- every "Message" / "Send SMS" button (Contacts, Phone app, other apps) opens SimBridge;
- the Phone app's **Reply with message** on an incoming call sends the text through your SIM;
- texts are saved to Android's SMS database, and Google Messages can still show them read-only.

Why not reply from Google Messages directly? On a phone without a SIM, Google Messages refuses to
send at all ("Could not start conversation"). Android offers no way for another app to send on its
behalf, so the default SMS app has to be SimBridge for replies to work everywhere.

#### Optional: keep Google Messages as default and copy texts into it

If you'd rather keep Google Messages as the default SMS app, SimBridge can still copy relayed texts
into it so you see them there (read-only). Android only lets the default SMS app write there, so run
this once from a computer with the main phone connected over USB:

```sh
adb shell appops set io.github.simbridge WRITE_SMS allow
```

Then turn on *Settings → Show texts in your Messages app → Copy texts to Messages*. Texts you
receive, and texts you send from SimBridge, then also appear in Google Messages or your default SMS
app. Deleting a conversation in SimBridge removes its copies too.

> **Replying from Google Messages doesn't work.** The main phone has no SIM, so the Messages app
> has nothing to send through. Reply from SimBridge, or from its notification.

#### Optional: set everything up over adb

Useful for scripting or headless setups. The receivers only accept broadcasts from adb (they require
the `DUMP` permission).

```sh
# SIM phone
G=io.github.simbridge.gateway/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver
adb shell am broadcast -n $G --es op reg-password --es password 'YOUR-LONG-PASSWORD'
adb shell am broadcast -n $G --es op takeover --ez on true
adb shell cmd role add-role-holder android.app.role.DIALER io.github.simbridge.gateway 0
adb shell am broadcast -n $G --es op provision --ei audio_method 2   # only if calls have no audio (see below)
adb shell am broadcast -n $G --es op quiet --ei flags 7              # power-saver extras: 1 sound, 2 vibration, 4 screen
adb shell am broadcast -n $G --es op power --ez on true

# Main phone
P=io.github.simbridge
adb shell am broadcast -n $P/.ControlReceiver --es op password --es password 'YOUR-LONG-PASSWORD'
adb shell telecom set-phone-account-enabled $P/$P.GatewayConnectionService simbridge 0
adb shell telecom set-user-selected-outgoing-phone-account $P/$P.GatewayConnectionService simbridge 0
adb shell dumpsys deviceidle whitelist +$P
```

## Everyday use

- **Calls:** dial from the Phone app, contacts, or the call button in a SimBridge chat. Incoming
  calls ring like normal calls. Mute, speaker, Bluetooth and the keypad all work from the call
  screen.
- **Texts:** open SimBridge. *Start chat* picks a contact or takes a number. Reply straight from
  notifications.
- **Status:** the chip at the top of SimBridge shows the connection. The SIM phone texts you (as
  *SimBridge Gateway*) if it restarted, Shizuku stopped, the battery is low or it was unplugged.
- **After restarting the main phone**, open SimBridge once. Android only grants background
  microphone access to an app that was opened since boot.
- **After restarting the SIM phone**, everything restarts by itself, including Shizuku with
  thedjchi's version (it needs Wi-Fi on once). You get a text when it's back.

## Limitations

- **Voice and SMS only.** No MMS, RCS, picture messages, or USSD codes.
- **Google Messages can't send** without a SIM in the main phone. Reply from SimBridge (or make
  it the default SMS app).
- **One main phone at a time.** The most recent phone to connect receives calls.
- **Narrowband audio** (G.711, 8 kHz). It's normal phone-call quality, not HD voice.
- **No call waiting or hold** for relayed calls.
- **Local network only by default.** The phones must share a Wi-Fi network or the hotspot. Reaching
  the gateway over the internet needs your own VPN (e.g. WireGuard/Tailscale). Don't expose port 5062
  to the internet.
- **Emergency calls** should be made from a phone with a SIM. Most phones can still place emergency
  calls without a SIM. SimBridge never handles emergency numbers.
- The SIM phone must stay **powered and in range**: it is the phone line.

## Troubleshooting

| Problem | Fix |
|---|---|
| Main phone says *Looking for your SIM phone…* | Both phones on the same Wi-Fi (or main phone on the hotspot)? Is SimBridge Gateway open, with **Forward calls & texts** on? Check that the password matches. Some routers block mDNS or isolate devices: use the hotspot, or turn off "AP/client isolation". |
| *The SIM phone rejected the password* | Re-enter the same password on both phones. After 5 wrong tries the gateway ignores that device for 10 minutes. |
| Calls connect but **no sound** | On the SIM phone: SimBridge Gateway → ⚙ (top right) → **Cellular audio** → try *Direct telephony*, then *Call redirection*. Check that Shizuku is running. |
| Calls don't ring on the main phone | Turn on the **SimBridge** calling account (main phone Settings → Calls in the Phone app). |
| No microphone after a main-phone reboot | Open SimBridge once. |
| Shizuku isn't running after a SIM-phone reboot | Use thedjchi's Shizuku with *Start on boot* + *Watchdog*, and keep Wi-Fi on. Otherwise open Shizuku and tap *Start*. |
| SIM phone screen lights up during calls | Make SimBridge Gateway the **default phone app** on the SIM phone. |
| A faint voice is still audible from the SIM phone's earpiece | Android lets the gateway mute the call-volume channel, but some phones (e.g. Samsung on Android 17) keep a minimum earpiece level the modem still drives. It's very quiet and inaudible in a bag or pocket. For total silence, plug in a wired earphone or a USB-C audio adapter with an earphone: calls then route there instead of the earpiece. The catch is that a USB-C adapter blocks charging. |
| Turning the power saver on takes a minute | It puts every other app to sleep one at a time. Wait until the switch settles. |
| Texts don't show in Google Messages | Run the `appops` command above and turn the option on. Only new texts are copied. |

## Security & privacy

- All traffic stays on your local network. SimBridge has no servers, accounts, analytics or ads.
- SIP signalling and audio are **not encrypted** (plain SIP/RTP on the LAN). The password is never
  sent in clear (digest auth). Only use SimBridge on networks you trust, such as your home Wi-Fi or
  your own hotspot. Use a strong hotspot password.
- Only the device that registered with the password can place calls or send texts, checked by IP.
  Repeated wrong passwords are blocked.
- Shizuku grants powerful system access to apps you approve. Only approve apps you trust.

## Building from source

Requirements: JDK 17, Android SDK (platform 37), and an internet connection for Gradle.

```sh
git clone https://github.com/ttabouch12/simbridge.git
cd simbridge
./gradlew :app:assembleDebug :phone:assembleDebug   # debug APKs
./gradlew :app:assembleRelease :phone:assembleRelease  # release APKs (unsigned unless configured)
```

Modules: `app` (SimBridge Gateway), `phone` (SimBridge), `sip` (pure-Kotlin SIP/RTP stack, shared),
`voice-api`, `telephony`, `privileged` (Shizuku user service), `gateway`. Release signing reads a
git-ignored `signing.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) in the
project root.

## Credits

SimBridge stands on the work of others. Thank you to:

- **[sparkcyf/android-voice-sip-agent](https://github.com/sparkcyf/android-voice-sip-agent)** (sparkcyf,
  csalinezh and contributors): the cellular ↔ SIP gateway, Shizuku privileged service, call-audio
  capture/injection, and the pure-Kotlin SIP stack used on both phones.
- **[yyano/voice-sip-agent-selfhost](https://github.com/yyano/voice-sip-agent-selfhost)** (YANO
  Yasuhiro, Hndcdr): the "device-in-itself" mode with a built-in SIP registrar, SMS relay, registrar
  authentication, and the 16 kHz/echo work.
- **[Shizuku](https://github.com/RikkaApps/Shizuku)** by RikkaApps, and the
  **[thedjchi fork](https://github.com/thedjchi/Shizuku)** (start on boot, watchdog). They're used
  through the Apache-2.0 [Shizuku-API](https://github.com/RikkaApps/Shizuku-API).
- **[baresip](https://github.com/baresip/baresip) / [baresip-studio](https://github.com/juha-h/baresip-studio)**:
  the softphone used while developing and testing the gateway.
- Android Jetpack (Compose, Material 3, Material Icons) and Kotlin coroutines.

The full upstream commit history is kept in this repository. Upstream READMEs and docs are in
[`docs/upstream/`](docs/upstream) and [`docs/`](docs). See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)
for licenses.

## License

**GPL-2.0-or-later** with the upstream Apache-2.0 linking exception, the same as the project it's
forked from. See [`LICENSE`](LICENSE). The software comes with **no warranty**. Read the
[disclaimer](DISCLAIMER.md).
