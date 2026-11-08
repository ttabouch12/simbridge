# Compatibility

Call audio is the part that depends on the SIM phone: Android lets a privileged process tap call
audio, but phone makers can restrict it. Texts, the dashboard and everything else work on any
Android 13+ phone with Shizuku.

**Test your phone:** main phone → Dashboard → Calls → *Does it work on my phone?* → Test. Then
tap *Share result* (or open a [compatibility report](../../../issues/new?template=compatibility.yml)).
Reports are collected under the [`compatibility`](../../../issues?q=label%3Acompatibility) label.

| SIM phone | Android | Call audio | Method | Source |
|---|---|---|---|---|
| Samsung Galaxy A56 (Exynos 1580) | 17 / One UI | ✅ both ways, HD | Direct telephony | SimBridge author |
| Google Pixel 11 Pro | 17 | ✅ | Direct telephony | upstream (voice-sip-agent) |
| Motorola (model not stated) | 16 | ✅ | — | upstream |
| Nubia NX769J | 16 | ✅ | — | upstream |

If calls connect with no sound: SIM phone → ⚙ → **Cellular audio** → try *Direct telephony*, then
*Call redirection*, run the test again, and report what worked.
