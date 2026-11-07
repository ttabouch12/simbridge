# Third-party notices

## SimBridge

SimBridge is a fork of **Voice SIP Agent** ([sparkcyf/android-voice-sip-agent](https://github.com/sparkcyf/android-voice-sip-agent))
via its "selfhost" fork ([yyano/voice-sip-agent-selfhost](https://github.com/yyano/voice-sip-agent-selfhost)), and is
distributed under the same license: **GPL-2.0-or-later with an Apache-2.0 linking exception**. The upstream git
history, authorship and copyright notices are preserved in this repository.

SimBridge's own additions: the `phone` module (main-phone app: Telecom calling account, call audio,
mDNS discovery, Messages-style UI, system SMS copy), the gateway's mDNS advertiser, client-side SIP
MESSAGE support, contact lookup, status alerts, power-saver profile, the new gateway UI, and
interface-aware SDP addressing. These are licensed under the same terms.

**SimBridge release APKs do not include the native pjsua2 stack** (the `sip-pjsua2` module is not part of
the build). Its notices below apply only if you build that module yourself.

SimBridge release APKs include: Kotlin standard library and kotlinx.coroutines (Apache-2.0), AndroidX
core / activity / Compose UI / Material 3 / Material Icons (Apache-2.0), and the Shizuku API/provider
libraries (Apache-2.0, gateway only).

**Shizuku itself is not bundled.** Install it separately from
[RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) (Apache-2.0) or
[thedjchi/Shizuku](https://github.com/thedjchi/Shizuku).

---

## Upstream notices (Voice SIP Agent)

Voice SIP Agent is licensed under the GNU General Public License v2 or later with an Apache-2.0 linking
exception.
Release APKs include the following third-party software.

## Native code (statically linked into `libpjsua2.so`)

| Component | Version | License | Source |
|---|---|---|---|
| pjproject (pjsip, pjmedia, pjlib, pjsua2 SWIG binding) | 2.17 | GPL-2.0-or-later | https://github.com/pjsip/pjproject |
| Third-party code bundled with pjproject (e.g. libsrtp) | as shipped with pjproject 2.17 | see each component's license in pjproject's `third_party/` directory (libsrtp: BSD-3-Clause) | https://github.com/pjsip/pjproject/tree/2.17/third_party |
| OpenSSL | 3.5.8 | Apache-2.0 | https://github.com/openssl/openssl |
| libopus | 1.6.1 | BSD-3-Clause (see upstream COPYING for patent notices) | https://opus-codec.org/ |
| LLVM libc++ (`libc++_shared.so`, from the Android NDK) | NDK r28c | Apache-2.0 WITH LLVM-exception | https://github.com/llvm/llvm-project |

pjproject is built by [`sip-pjsua2/native/build-pjsua2.sh`](sip-pjsua2/native/build-pjsua2.sh) from the
SHA-256-pinned upstream release tarballs, with the modifications in [`sip-pjsua2/native/`](sip-pjsua2/native/)
(`config_site.h`, `pjsua2-java-ext.i`, `pjsip-tls-wildcard.patch`). The [pjsip third-party license notice](https://docs.pjsip.org/en/latest/overview/license_3rd_party.html)
gives explicit permission to link pjproject with OpenSSL.

## Java / Kotlin libraries

| Component | License | Source |
|---|---|---|
| Shizuku API / provider (`dev.rikka.shizuku`) | Apache-2.0 | https://github.com/RikkaApps/Shizuku-API |
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 | https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines |
| AndroidX (core, activity, Compose UI, Material 3) | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support |

The full text of the Apache License 2.0 is available at https://www.apache.org/licenses/LICENSE-2.0 and the
GNU GPL at https://www.gnu.org/licenses/.

## Corresponding source

For each release, the corresponding source is the tagged commit of this repository together with the upstream
tarballs listed above (exact versions and SHA-256 sums are in `build-pjsua2.sh`).
