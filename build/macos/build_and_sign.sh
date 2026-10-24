#!/bin/bash
#
# Build, sign, notarise and staple WhalePi Host for distribution to other Macs.
#
# Same shape as PAMGuard/build/macos/build_and_sign.sh, but adapted to this
# project: jpackage builds the .app for us, so the script asks Maven for an
# APP_IMAGE, signs everything inside it (including the dylibs that live inside
# the JavaFX jars), then wraps it in a DMG and sends that to Apple.
#
# Usage:
#   ./build_and_sign.sh                 # build, sign, notarise, staple
#   ./build_and_sign.sh --no-build      # sign whatever .app is already in target/installer
#   ./build_and_sign.sh --no-notarize   # sign only, skip the trip to Apple
#
# We deliberately do not use "set -e": a single library that refuses to sign
# should not abandon the run, as long as the app bundle itself signs cleanly.

HERE="$(cd "$(dirname "$0")" && pwd)"
BASE_DIR="$(cd "$HERE/../.." && pwd)"
TARGET_DIR="$BASE_DIR/target"
INSTALLER_DIR="$TARGET_DIR/installer"
ENTITLEMENTS="$HERE/entitlements.plist"

SHOULD_BUILD=true
SHOULD_NOTARIZE=true

for ARG in "$@"; do
    case "$ARG" in
        --no-build)    SHOULD_BUILD=false ;;
        --no-notarize) SHOULD_NOTARIZE=false ;;
        -h|--help)
            sed -n '3,17p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "Unknown option: $ARG (try --help)"
            exit 1
            ;;
    esac
done

# --- CREDENTIALS ---
if [ ! -f "$HERE/apple_creds.sh" ]; then
    echo "Error: $HERE/apple_creds.sh not found."
    echo "       cp apple_creds.example.sh apple_creds.sh and fill it in."
    exit 1
fi
source "$HERE/apple_creds.sh"

if [ -z "$CERT_NAME" ]; then
    echo "Error: CERT_NAME is not set in apple_creds.sh"
    exit 1
fi

if ! security find-identity -v -p codesigning | grep -qF "$CERT_NAME"; then
    echo "Error: no signing identity called:"
    echo "       $CERT_NAME"
    echo ""
    echo "Identities in this keychain:"
    security find-identity -v -p codesigning | sed 's/^/       /'
    echo ""
    echo "Run ./utils/check_certs.sh for help. Notarisation needs a"
    echo "\"Developer ID Application\" certificate - the Apple Development and"
    echo "Apple Distribution ones will not do."
    exit 1
fi

echo "=========================================="
echo "WhalePi Host - sign and notarise"
echo "Certificate: $CERT_NAME"
echo "=========================================="

# --- STEP 0: BUILD THE APP IMAGE ---
if [ "$SHOULD_BUILD" = true ]; then
    echo ""
    echo "=========================================="
    echo "STEP 0: Building app image with jpackage"
    echo "=========================================="
    # mac.package.type is a property in pom.xml; overriding it to APP_IMAGE
    # gives us an unsigned .app to work on instead of a finished DMG.
    (cd "$BASE_DIR" && mvn -q -Pinstaller-mac -Dmac.package.type=APP_IMAGE clean verify)
    if [ $? -ne 0 ]; then
        echo "Maven build failed."
        exit 1
    fi
fi

APP_PATH=$(ls -d "$INSTALLER_DIR"/*.app 2>/dev/null | head -n 1)

if [ -z "$APP_PATH" ]; then
    echo "Error: no .app found in $INSTALLER_DIR"
    echo "       Run without --no-build, or build it yourself with:"
    echo "       mvn -Pinstaller-mac -Dmac.package.type=APP_IMAGE clean verify"
    exit 1
fi

APP_NAME=$(basename "$APP_PATH")
INFO_PLIST="$APP_PATH/Contents/Info.plist"
APP_VERSION=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$INFO_PLIST" 2>/dev/null)
[ -z "$APP_VERSION" ] && APP_VERSION="1.0.0"
DMG_NAME="WhalePi-Host-${APP_VERSION}"
DMG_PATH="$INSTALLER_DIR/${DMG_NAME}-signed.dmg"
BUNDLE_DIR="$TARGET_DIR/dmg-staging"

echo ""
echo "App:     $APP_PATH"
echo "Version: $APP_VERSION"

# --- STEP 0b: USAGE DESCRIPTIONS ---
# The app reads SD cards under /Volumes and copies recordings into folders the
# user picks. On macOS 13+ those are TCC-protected, and the prompt reads badly
# (or is refused outright) when the bundle carries no explanation. These keys
# have to go in before signing or the signature will not match the bundle.
echo ""
echo "--- Adding usage descriptions to Info.plist ---"
set_plist_string() {
    local KEY="$1"
    local VALUE="$2"
    /usr/libexec/PlistBuddy -c "Add :$KEY string $VALUE" "$INFO_PLIST" 2>/dev/null || \
    /usr/libexec/PlistBuddy -c "Set :$KEY $VALUE" "$INFO_PLIST" 2>/dev/null
    echo "  $KEY"
}
set_plist_string NSRemovableVolumesUsageDescription \
    "WhalePi Host needs access to removable volumes to read WhalePi SD cards."
set_plist_string NSDesktopFolderUsageDescription \
    "WhalePi Host needs access so it can copy recordings to a folder on your Desktop."
set_plist_string NSDocumentsFolderUsageDescription \
    "WhalePi Host needs access so it can copy recordings to a folder in Documents."
set_plist_string NSDownloadsFolderUsageDescription \
    "WhalePi Host needs access so it can copy recordings to a folder in Downloads."

# Sign one Mach-O binary with the hardened runtime and an Apple timestamp.
# Returns 0 if the file was signed, so callers know whether to rebuild a jar.
sign_native_lib() {
    local LIB="$1"

    [ -f "$LIB" ] || return 1
    file "$LIB" | grep -q "Mach-O" || return 1

    echo "    Signing: $(basename "$LIB")"
    local OUTPUT
    OUTPUT=$(codesign --force --options runtime --timestamp --sign "$CERT_NAME" "$LIB" 2>&1)
    if [ $? -eq 0 ]; then
        return 0
    fi
    echo "    WARNING: failed to sign $(basename "$LIB")"
    echo "$OUTPUT" | grep -v "replacing existing signature" | sed 's/^/        /'
    return 1
}

# Every Mach-O file under a directory, one per line.
#
# We test each file with file(1) rather than matching *.dylib, because the
# runtime also ships plain executables with no extension - jspawnhelper is the
# one that matters here, since the app shells out to diskutil. Apple rejects a
# bundle that contains even one unsigned binary, so nothing may be missed.
find_macho() {
    local ROOT="$1"
    shift
    while IFS= read -r F; do
        if file "$F" 2>/dev/null | grep -q "Mach-O"; then
            echo "$F"
        fi
    done < <(find "$ROOT" -type f "$@")
}

# --- STEP 1: NATIVE LIBRARIES INSIDE JARS ---
# Apple's notary service looks inside jars, so the JavaFX dylibs shipped in
# javafx-graphics-*-mac*.jar have to be signed too. Each jar is unpacked, its
# binaries signed, and the jar rebuilt.
echo ""
echo "=========================================="
echo "STEP 1: Signing native libraries inside jars"
echo "=========================================="

while IFS= read -r JAR_PATH; do
    if ! unzip -l "$JAR_PATH" 2>/dev/null | grep -qE "\.dylib|\.jnilib|\.so$"; then
        continue
    fi

    echo ""
    echo "  Processing: $(basename "$JAR_PATH")"

    TMP_DIR=$(mktemp -d)
    if ! unzip -q "$JAR_PATH" -d "$TMP_DIR" 2>/dev/null; then
        echo "    WARNING: could not unpack, leaving it alone"
        rm -rf "$TMP_DIR"
        continue
    fi

    MODIFIED=false
    while IFS= read -r LIB; do
        if sign_native_lib "$LIB"; then
            MODIFIED=true
        fi
    done < <(find "$TMP_DIR" -type f \( -name "*.dylib" -o -name "*.jnilib" -o -name "*.so" \))

    if [ "$MODIFIED" = true ]; then
        echo "    Rebuilding jar..."
        rm -f "$JAR_PATH"
        (cd "$TMP_DIR" && zip -r -q "$JAR_PATH" .)
    else
        echo "    Nothing to sign"
    fi

    rm -rf "$TMP_DIR"
done < <(find "$APP_PATH/Contents/app" -name "*.jar" -type f)

# --- STEP 2: LOOSE BINARIES ---
echo ""
echo "=========================================="
echo "STEP 2: Signing loose binaries"
echo "=========================================="

# Quarantine and other extended attributes make codesign fail; clear them and
# make sure everything is writable first.
xattr -cr "$APP_PATH" 2>/dev/null || true
chmod -R u+w "$APP_PATH" 2>/dev/null || true

# The runtime is handled separately below, and the main launcher in
# Contents/MacOS is covered when the bundle itself is signed in step 4.
while IFS= read -r LIB; do
    sign_native_lib "$LIB" || true
done < <(find_macho "$APP_PATH" -not -path "*/Contents/runtime/*" -not -path "*/Contents/MacOS/*")

# --- STEP 3: THE BUNDLED JAVA RUNTIME ---
echo ""
echo "=========================================="
echo "STEP 3: Signing the bundled Java runtime"
echo "=========================================="

RUNTIME="$APP_PATH/Contents/runtime"
if [ -d "$RUNTIME" ]; then
    echo "  Libraries and executables..."
    while IFS= read -r BIN; do
        sign_native_lib "$BIN" || true
    done < <(find_macho "$RUNTIME")

    # The runtime is a bundle in its own right and has to be sealed after its
    # contents, or its signature will not match.
    echo "  Runtime bundle..."
    codesign --force --options runtime --timestamp --sign "$CERT_NAME" "$RUNTIME"
else
    echo "  No embedded runtime found - skipping"
fi

# --- STEP 4: THE APP BUNDLE ---
echo ""
echo "=========================================="
echo "STEP 4: Signing $APP_NAME"
echo "=========================================="
codesign --force --options runtime --timestamp \
    --entitlements "$ENTITLEMENTS" --sign "$CERT_NAME" "$APP_PATH"
if [ $? -ne 0 ]; then
    echo "Signing the app bundle failed."
    exit 1
fi

echo ""
echo "Verifying..."
if codesign --verify --deep --strict --verbose=2 "$APP_PATH" 2>&1; then
    echo "Signature verified"
else
    echo "Signature verification failed"
    exit 1
fi

# --- STEP 5: DMG ---
echo ""
echo "=========================================="
echo "STEP 5: Building the DMG"
echo "=========================================="
rm -rf "$BUNDLE_DIR"
mkdir -p "$BUNDLE_DIR"

# rsync rather than cp: it preserves the signed bundle's permissions and
# symlinks without tripping over read-only files in the runtime.
rsync -a --chmod=u+w "$APP_PATH/" "$BUNDLE_DIR/$APP_NAME/"
ln -s /Applications "$BUNDLE_DIR/Applications"
rm -f "$DMG_PATH"

hdiutil create -srcfolder "$BUNDLE_DIR" -volname "WhalePi Host" -fs HFS+ -o "$DMG_PATH"
if [ $? -ne 0 ]; then
    echo "DMG creation failed."
    rm -rf "$BUNDLE_DIR"
    exit 1
fi
rm -rf "$BUNDLE_DIR"

echo ""
echo "Signing the DMG..."
codesign --force --timestamp --sign "$CERT_NAME" "$DMG_PATH"
if codesign --verify --verbose "$DMG_PATH" 2>&1; then
    echo "DMG signature verified"
else
    echo "DMG signature verification failed"
    exit 1
fi

# --- STEP 6: NOTARISATION ---
echo ""
echo "=========================================="
echo "STEP 6: Notarisation"
echo "=========================================="

if [ "$SHOULD_NOTARIZE" != true ]; then
    echo "Skipped (--no-notarize)."
    echo ""
    echo "The DMG is signed but not notarised, so other Macs will still refuse"
    echo "to open it. Re-run without --no-notarize before distributing."
    echo ""
    echo "Generated: $DMG_PATH"
    exit 0
fi

# Either a stored keychain profile or an Apple ID plus app-specific password.
if [ -n "$KEYCHAIN_PROFILE" ]; then
    NOTARY_AUTH=(--keychain-profile "$KEYCHAIN_PROFILE")
    LOG_AUTH="--keychain-profile \"$KEYCHAIN_PROFILE\""
elif [ -n "$APPLE_ID" ] && [ -n "$APPLE_PASSWORD" ] && [ -n "$TEAM_ID" ]; then
    NOTARY_AUTH=(--apple-id "$APPLE_ID" --password "$APPLE_PASSWORD" --team-id "$TEAM_ID")
    LOG_AUTH="--apple-id \"$APPLE_ID\" --password \"\$APPLE_PASSWORD\" --team-id \"$TEAM_ID\""
else
    echo "Error: no notarisation credentials in apple_creds.sh."
    echo "       Set KEYCHAIN_PROFILE, or all of APPLE_ID, APPLE_PASSWORD and TEAM_ID."
    exit 1
fi

echo "Submitting to Apple (usually 5-15 minutes)..."
SUBMISSION_OUTPUT=$(xcrun notarytool submit "$DMG_PATH" "${NOTARY_AUTH[@]}" --wait)
echo "$SUBMISSION_OUTPUT"

SUBMISSION_ID=$(echo "$SUBMISSION_OUTPUT" | grep "id:" | head -1 | awk '{print $2}')

if ! echo "$SUBMISSION_OUTPUT" | grep -q "status: Accepted"; then
    echo ""
    echo "Notarisation failed. For the full log, run:"
    echo "  xcrun notarytool log $SUBMISSION_ID $LOG_AUTH"
    exit 1
fi

echo "Notarisation accepted"

echo ""
echo "Stapling the ticket..."
# The ticket can take a minute to propagate through Apple's CDN, so retry.
for i in {1..6}; do
    if xcrun stapler staple "$DMG_PATH"; then
        echo "Ticket stapled"
        break
    fi
    echo "  waiting for the ticket to propagate... ($i/6)"
    sleep 30
done

# --- STEP 7: GATEKEEPER ---
echo ""
echo "=========================================="
echo "STEP 7: Gatekeeper check"
echo "=========================================="
if spctl --assess --type install --verbose "$DMG_PATH" 2>&1; then
    echo "Gatekeeper accepted the DMG"
else
    echo "Gatekeeper returned warnings - check the output above"
fi

echo ""
echo "=========================================="
echo "Done"
echo "=========================================="
echo "Generated: $DMG_PATH"
echo "This DMG can be opened on any Mac without a security warning."
echo ""
