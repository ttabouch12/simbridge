# ADB Direct PCM transport

Experimental companion for [android-call-bridge](https://github.com/yyano/android-call-bridge/tree/feature/direct-telephony); see its DIRECT_TELEPHONY.md for host commands and protocol.

AdbControlReceiver (DUMP-protected) adds direct-ready/direct-start/direct-stop. Start only after disabling takeover and loopback. The non-exported DirectPcmService owns an independent ShizukuConnector tag, rather than the SIP runtime's helper, so turning off SIP does not terminate audio. The `.wideband` test app ID is retained.

One ADB shell/root peer may connect to abstract socket `${applicationId}.pcm`. No network listener is opened on Android. On an active cellular audio mode, openCallAudio(2,16000) provides raw 20ms/640-byte mono PCM16LE packets. The stream header is DTP1 plus two big-endian uint32 fields (16000,640); subsequent frames are fixed-size. Host EOF, phone tap close, 5s uplink inactivity, explicit stop, or 180s service lifetime closes the session and restores the locally muted call output. The service is non-sticky and does not dial or answer calls. The local host forward must be trusted.

Validation: app assembleDebug and gateway/voice-api unit tests passed. Installed on moto g52j 5G Android 12. ADB connection and independent Shizuku helper confirmed; an idle-phone connection was rejected with audio mode=0, and the service/helper/forward were released. Live outgoing PCM transport and audio quality remain unverified. Earlier in-device 16 kHz echo success is not evidence of host transport quality. No call was placed for this smoke test.

The upstream app-wide test task still has missing pjsua2 class references in GatewayRuntimeLifecycleTest. The external host transport has seven offline tests, including sample preservation at low amplitudes. G.722 and AI integration are not added here.

## 2026-11-03 saved baseline

Subsequent outgoing host tests completed at 16 kHz. TCP_NODELAY and a 120ms host playback lead reduced intermittent cuts subjectively; both changes were applied together. Lower-level human speech was reported clear enough that distortion/cuts were not bothersome. A 20s session had 1000 frames each way, no backend failure, and final IDLE confirmation. Eight tones from 125Hz through 6kHz were audible, but this is not a calibrated response or cellular codec identification. Hiss reported once was not reproduced in a normal/zero-PCM/normal comparison; no denoising was added. Full details and the remaining AI integration work are in android-call-bridge/DIRECT_TELEPHONY.md. No recordings, destination numbers, or external sample WAVs are committed.
