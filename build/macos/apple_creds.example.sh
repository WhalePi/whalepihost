# Credentials for signing and notarising WhalePi Host.
#
#   cp apple_creds.example.sh apple_creds.sh
#
# then fill in the values below. apple_creds.sh is git-ignored - never commit it.
# The same values work here as in PAMGuard/build/macos/apple_creds.sh.

# Exact name of the signing certificate as it appears in the keychain.
# Run ./utils/check_certs.sh to list what you have.
# It must be a "Developer ID Application" certificate - "Apple Development"
# and "Apple Distribution" certificates cannot be notarised.
CERT_NAME="Developer ID Application: Jamie Macaulay (7365S9DZ34)"

# The 10-character Team ID from the Apple developer portal.
export TEAM_ID="7365S9DZ34"

# --- Notarisation credentials: pick ONE of the two options below ---

# Option A (recommended): a keychain profile, so no password sits in a file.
# Create it once with:
#   xcrun notarytool store-credentials whalepihost \
#       --apple-id "you@example.com" --team-id "7365S9DZ34" --password "xxxx-xxxx-xxxx-xxxx"
# then uncomment:
# export KEYCHAIN_PROFILE="whalepihost"

# Option B: Apple ID plus an app-specific password
# (https://appleid.apple.com/account/manage -> App-Specific Passwords).
export APPLE_ID="your-apple-id@example.com"
export APPLE_PASSWORD="xxxx-xxxx-xxxx-xxxx"
