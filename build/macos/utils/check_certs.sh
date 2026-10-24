#!/bin/bash
#
# Sanity-check the signing setup before running build_and_sign.sh.
#   ./utils/check_certs.sh

HERE="$(cd "$(dirname "$0")/.." && pwd)"

echo "=========================================="
echo "WhalePi Host - signing setup check"
echo "=========================================="
echo ""

if [ ! -f "$HERE/apple_creds.sh" ]; then
    echo "FAIL  apple_creds.sh not found in $HERE"
    echo "      cp apple_creds.example.sh apple_creds.sh and fill it in."
    exit 1
fi
source "$HERE/apple_creds.sh"
echo "OK    apple_creds.sh found"

echo ""
echo "Signing identities in this keychain:"
security find-identity -v -p codesigning | sed 's/^/  /'
echo ""

ALL_OK=true

if security find-identity -v -p codesigning | grep -qF "$CERT_NAME"; then
    echo "OK    certificate found: $CERT_NAME"
else
    echo "FAIL  certificate not in keychain: $CERT_NAME"
    echo "      Fix CERT_NAME in apple_creds.sh, or install the certificate."
    ALL_OK=false
fi

# Only a Developer ID Application certificate can be notarised. Apple
# Development is for local runs, Apple Distribution is for the App Store.
if echo "$CERT_NAME" | grep -q "Developer ID Application"; then
    echo "OK    certificate type is Developer ID Application"
else
    echo "FAIL  certificate type cannot be notarised - you need a"
    echo "      \"Developer ID Application\" certificate. Create one at"
    echo "      https://developer.apple.com/account/resources/certificates/list"
    ALL_OK=false
fi

echo ""
if [ -n "$KEYCHAIN_PROFILE" ]; then
    echo "Notarisation: keychain profile \"$KEYCHAIN_PROFILE\""
    NOTARY_AUTH=(--keychain-profile "$KEYCHAIN_PROFILE")
elif [ -n "$APPLE_ID" ] && [ -n "$APPLE_PASSWORD" ] && [ -n "$TEAM_ID" ]; then
    echo "Notarisation: Apple ID $APPLE_ID, team $TEAM_ID, password ${APPLE_PASSWORD:0:4}****"
    NOTARY_AUTH=(--apple-id "$APPLE_ID" --password "$APPLE_PASSWORD" --team-id "$TEAM_ID")
else
    echo "FAIL  no notarisation credentials - set KEYCHAIN_PROFILE, or all of"
    echo "      APPLE_ID, APPLE_PASSWORD and TEAM_ID in apple_creds.sh"
    NOTARY_AUTH=()
    ALL_OK=false
fi

if [ ${#NOTARY_AUTH[@]} -gt 0 ]; then
    echo "      testing them against Apple..."
    if xcrun notarytool history "${NOTARY_AUTH[@]}" >/dev/null 2>&1; then
        echo "OK    Apple accepted the credentials"
    else
        echo "FAIL  Apple rejected the credentials"
        echo "      App-specific passwords come from https://appleid.apple.com/account/manage"
        ALL_OK=false
    fi
fi

echo ""
if [ "$ALL_OK" = true ]; then
    echo "All good - run ./build_and_sign.sh"
else
    echo "Fix the failures above before running ./build_and_sign.sh"
    exit 1
fi
