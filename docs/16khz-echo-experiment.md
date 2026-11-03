# 16 kHz Direct telephony echo experiment

This experimental build uses 16 kHz mono PCM for loopback tests. Normal SIP calls remain at 8 kHz because the built-in SIP stack supports G.711 only. The separate application ID `.wideband` and label `Voice SIP 16k Test` allow installation alongside the upstream app; stop takeover in the other app before testing.

Configure Shizuku access, Direct telephony (`audio_method 2`), and default-dialer call access (`call_access 2`). The ADB receiver is `io.github.sparkcyf.voicesipagent.wideband/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver`. Enable `--es op loopback --ez on true`, then call the SIM from another phone. Inspect `--es op status` and `VsaTap` logs for `method=direct rate=16000`.

Device validation: moto g52j 5G, Android 12 / API 31. Incoming calls auto-answered in default-dialer mode; the caller heard the echo. Logs showed rate=16000, frameBytes=640, nonzero audio in both directions and no backend failures. Subjectively clearer than the 8 kHz baseline, but roughness remained. This does not establish native VoLTE fidelity or wideband SIP support.

Validation: `:app:assembleDebug :gateway:test :voice-api:test` passed. The upstream `:app:testDebugUnitTest` cannot compile because GatewayRuntimeLifecycleTest references pjsua2 classes absent from the default build.

Loopback is temporary runtime state and must be re-enabled after an app restart. Turn it off after testing (`--es op loopback --ez on false`); disable takeover to restore the previous dialer. Shizuku must be restarted after a non-root device reboot.
