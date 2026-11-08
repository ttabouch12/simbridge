# Getting SimBridge into app stores

Each release on GitHub has three APKs: `SimBridge-Gateway-<v>.apk` (SIM phone), `SimBridge-<v>.apk`
(main phone, with Google's QR scanner) and `SimBridge-FOSS-<v>.apk` (main phone, no Google code:
scan the pairing code with the camera app). Store listings come from `app/fastlane` and
`phone/fastlane`.

## Obtainium (works now)
Users add `https://github.com/ttabouch12/simbridge`. Two apps share one repo, so each needs an APK
filter (Obtainium → Additional options → *Filter APKs by regular expression*):
- SimBridge: `^SimBridge-\d` (or `^SimBridge-FOSS-` for the Google-free build)
- SimBridge Gateway: `^SimBridge-Gateway-`

One-tap links for the README: `obtainium://add/https://github.com/ttabouch12/simbridge`.

## IzzyOnDroid (accepts APKs from GitHub releases)
Open an issue at https://gitlab.com/IzzyOnDroid/repo/-/issues with the template *Request for
inclusion*, giving the repo URL and both package names (`io.github.simbridge.gateway`,
`io.github.simbridge`). Mention the FOSS APK for the main phone. Izzy reads the fastlane metadata
from the repo. Expect anti-features: none for the FOSS build; *NonFreeDep* for the scanner build.

## F-Droid (builds from source)
Submit a merge request to https://gitlab.com/fdroid/fdroiddata adding the two files in
[`fdroid/`](fdroid). F-Droid builds the `foss` flavor (no Google code, no update check) and the
gateway. Builds must be reproducible from the tag, so tag each release (`v2.1.0`).

## Yuki (Shizuku app catalog)
Nothing to submit: Yuki indexes GitHub repos that declare `rikka.shizuku.ShizukuProvider` (the
gateway does) and ship an APK in releases. Add the GitHub topic **`shizuku`** to the repo (and
`android`, `sip`, `sms`) so it ranks well; it uses the fastlane icon and README screenshots.

## Orion Store
Open Orion Store → Submit app (or the repository submission form linked from
https://github.com/RookieEnough/Orion-Store) and enter `https://github.com/ttabouch12/simbridge`.
Once approved, Developer Mode (tap the title 7 times) gives a badge for the README.
