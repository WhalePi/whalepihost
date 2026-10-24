# Signing WhalePi Host for other Macs

An unsigned DMG works on the machine that built it and nowhere else: on any
other Mac, Gatekeeper refuses to open the app because it was downloaded and
carries no Developer ID signature. `build_and_sign.sh` produces a DMG that
opens anywhere, with no right-click-Open and no `xattr` incantation.

This is the same approach as `PAMGuard/build/macos/build_and_sign.sh`, adapted
to this project.

---

## Quick start

```bash
cp build/macos/apple_creds.example.sh build/macos/apple_creds.sh
```

Fill in the credentials, then:

```bash
cd build/macos && ./utils/check_certs.sh && ./build_and_sign.sh
```

The result is `target/installer/WhalePi-Host-<version>-signed.dmg`, ready to
send to anyone. The whole run takes roughly 10-20 minutes, most of it waiting
on Apple.

---

## What you need first

**A "Developer ID Application" certificate.** This is the only kind Apple will
notarise. "Apple Development" is for running on your own machines, and
"Apple Distribution" is for the Mac App Store; neither works here.

At the time of writing this keychain has only those two, so you will need to
create the Developer ID certificate at
<https://developer.apple.com/account/resources/certificates/list> and install
it before the script will run. It is the same certificate PAMGuard is signed
with, so if you have it on another machine, export it as a `.p12` from there
instead of creating a second one.

**Notarisation credentials.** Either a stored keychain profile (preferred - no
password sitting in a file):

```bash
xcrun notarytool store-credentials whalepihost --apple-id "you@example.com" --team-id "7365S9DZ34" --password "xxxx-xxxx-xxxx-xxxx"
```

or an Apple ID plus an app-specific password from
<https://appleid.apple.com/account/manage>. `apple_creds.sh` takes either;
it is git-ignored.

---

## What the script does

**Step 0 - build.** Runs Maven with `-Dmac.package.type=APP_IMAGE`, so jpackage
hands back a bare `WhalePi Host.app` instead of a finished DMG. The script
builds and signs the DMG itself later, because everything inside the app has to
be signed before the DMG is sealed around it. `--no-build` skips this and signs
whatever `.app` is already in `target/installer`.

**Step 0b - usage descriptions.** Adds `NSRemovableVolumesUsageDescription` and
the Desktop/Documents/Downloads equivalents to `Info.plist`. macOS 13 and later
gate the SD card under `/Volumes` and the folders people copy recordings into
behind these prompts, and a bundle with no explanation string gets a confusing
prompt at best. This has to happen before signing or the signature will not
match the bundle.

**Step 1 - binaries inside jars.** Apple's notary service unpacks jars and
rejects the whole submission if it finds an unsigned Mach-O inside one.
`javafx-graphics-*-mac-*.jar` carries seven dylibs, so each jar containing
native code is unpacked, its binaries signed, and the jar rebuilt.

**Step 2 - loose binaries.** Anything Mach-O sitting in the bundle outside the
runtime.

**Step 3 - the bundled Java runtime.** Every Mach-O file under
`Contents/runtime`, then the runtime bundle itself. Note this is found by
testing each file with `file`, not by matching `*.dylib`: the runtime also
ships `lib/jspawnhelper`, an executable with no extension and not in a `bin`
directory, which the app genuinely uses when it shells out to `diskutil`.
Missing it fails notarisation.

**Step 4 - the app bundle**, with `entitlements.plist`. The JVM needs
`allow-jit` and `allow-unsigned-executable-memory`; JavaFX needs
`disable-library-validation`, because it unpacks and loads its dylibs from the
class path at run time.

**Step 5 - the DMG**, with an Applications symlink, then signed.

**Step 6 - notarisation.** Uploads the DMG, waits for Apple, then staples the
ticket so the app opens even on a Mac that is offline. Stapling is retried,
because the ticket takes a minute to propagate.

**Step 7 - Gatekeeper**, a final `spctl` check that the DMG will be accepted.

---

## Files here

```
build/macos/
  build_and_sign.sh          the script
  apple_creds.example.sh     copy to apple_creds.sh and fill in
  entitlements.plist         hardened runtime exceptions for the JVM and JavaFX
  utils/check_certs.sh       checks the certificate and credentials before a run
```

---

## Troubleshooting

**"no signing identity called ..."** - `CERT_NAME` has to match the keychain
exactly. `./utils/check_certs.sh` lists what is there.

**Notarisation rejected** - the script prints the exact `xcrun notarytool log`
command for the submission. The usual cause is a binary that was missed; the
log names the file.

**"The timestamp service is not available"** - a transient Apple outage.
Re-run.

**Only need a local test build?** `./build_and_sign.sh --no-notarize` signs
everything but skips Apple. The DMG will still be refused on other Macs -
signing alone is not enough, it has to be notarised too.

**No Developer ID certificate yet?** Until you have one, `mvn clean verify`
still produces an unsigned DMG, and recipients can open it with right-click →
Open, or `xattr -dr com.apple.quarantine "/Applications/WhalePi Host.app"`.
