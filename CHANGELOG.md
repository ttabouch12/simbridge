# Changelog

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
