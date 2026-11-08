# Shizuku setup by phone maker

SimBridge Gateway needs [Shizuku](https://shizuku.rikka.app/) for call audio and the remote
switches. Start it with **Wireless debugging** (no computer needed), then allow SimBridge Gateway in
Shizuku. Use [thedjchi's Shizuku](https://github.com/thedjchi/Shizuku/releases) with *Start on
boot* + *Watchdog* so it survives reboots.

**Everywhere:** Settings → About phone → tap *Build number* 7 times → Developer options → turn on
*Wireless debugging* → in Shizuku tap *Pairing* and enter the code from *Pair device with pairing
code*. On Android 17 allow Shizuku's notification and nearby-devices prompts first.

| Maker | Extra steps |
|---|---|
| **Samsung** (One UI) | Build number is in About phone → Software information. Battery → Background usage limits → add Shizuku and SimBridge Gateway to *Never sleeping apps*. |
| **Google Pixel** | Nothing extra. Set both apps' battery use to *Unrestricted*. |
| **Xiaomi / Redmi / POCO** (HyperOS, MIUI) | Developer options → turn on *USB debugging (Security settings)* (needs a Mi account) and turn off *MIUI optimization* if pairing fails. Autostart: allow both apps. Battery saver: *No restrictions*. |
| **OnePlus / OPPO / realme** (ColorOS, OxygenOS) | Developer options → turn off *Disable permission monitoring* only if grants fail. Battery → allow background activity and auto-launch for both apps. |
| **vivo / iQOO** (Funtouch, OriginOS) | Allow *High background power consumption* and auto-start. Some builds block call-audio capture: run the audio test. |
| **Motorola** | Nothing extra; set battery to *Unrestricted*. |
| **Huawei / Honor** without Google | Wireless debugging works on Android 11+ builds; app launch → *Manage manually* with all three switches on. |

If Shizuku keeps stopping, connect once by USB and run `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`
(rikka) or use thedjchi's TCP mode.
