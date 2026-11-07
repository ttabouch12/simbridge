# Changelog

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
