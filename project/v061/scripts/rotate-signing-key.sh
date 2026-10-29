#!/usr/bin/env bash
# Create a private release key and a v3 signing lineage, or sign an unsigned release APK for migration.
set -euo pipefail
umask 077
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK" ]]; then
  echo "Set ANDROID_SDK_ROOT (or ANDROID_HOME) to your Android SDK." >&2; exit 2
fi
APKSIGNER="${APKSIGNER:-$(find "$SDK/build-tools" -type f -name apksigner 2>/dev/null | sort -V | tail -1)}"
ZIPALIGN="${ZIPALIGN:-$(find "$SDK/build-tools" -type f -name zipalign 2>/dev/null | sort -V | tail -1)}"
KEYTOOL="${KEYTOOL:-$(command -v keytool || true)}"
[[ -x "$APKSIGNER" ]] || { echo "apksigner not found under $SDK/build-tools." >&2; exit 2; }
[[ -n "$KEYTOOL" && -x "$KEYTOOL" ]] || { echo "keytool (JDK 17+) not found." >&2; exit 2; }

MODE="${1:-help}"
DEFAULT_NEW_KEY="$ROOT/release-key.jks"
NEW_KEY="${VOICEDOCK_KEYSTORE:-${VOICEDOCK_KEYSTORE_PATH:-$DEFAULT_NEW_KEY}}"
NEW_ALIAS="${VOICEDOCK_KEY_ALIAS:-voicedock_release}"
OLD_KEY="${VOICEDOCK_OLD_KEYSTORE:-$ROOT/keystore/debug.keystore}"
OLD_ALIAS="${VOICEDOCK_OLD_KEY_ALIAS:-androiddebugkey}"
LINEAGE="${VOICEDOCK_SIGNING_LINEAGE:-$ROOT/signing-lineage.bin}"
OLD_STORE_PASS="${VOICEDOCK_OLD_STORE_PASSWORD:-android}"
OLD_KEY_PASS="${VOICEDOCK_OLD_KEY_PASSWORD:-android}"

read_secret() {
  local prompt="$1" var_name="$2" value
  read -r -s -p "$prompt" value </dev/tty
  printf '\n' >/dev/tty
  printf -v "$var_name" '%s' "$value"
}

load_new_passwords() {
  NEW_STORE_PASS="${VOICEDOCK_STORE_PASSWORD:-${VOICEDOCK_KEYSTORE_PASSWORD:-}}"
  if [[ -z "$NEW_STORE_PASS" ]]; then read_secret "New release keystore password: " NEW_STORE_PASS; fi
  NEW_KEY_PASS="${VOICEDOCK_KEY_PASSWORD:-$NEW_STORE_PASS}"
}

write_gradle_properties() {
  local path="$1" store="$2" alias="$3" storepass="$4" keypass="$5"
  python3 - "$path" "$store" "$alias" "$storepass" "$keypass" <<'PY'
import os, sys
path, store, alias, storepass, keypass = sys.argv[1:]
def esc(value):
    out=[]
    for i,c in enumerate(value):
        if c == '\\': out.append('\\\\')
        elif c in '=:#!': out.append('\\' + c)
        elif c == ' ': out.append('\\ ' if i == 0 else ' ')
        elif c == '\t': out.append('\\t')
        elif c == '\n': out.append('\\n')
        elif c == '\r': out.append('\\r')
        else: out.append(c)
    return ''.join(out)
with open(path, 'w', encoding='utf-8') as f:
    f.write('storeFile=' + esc(store) + '\n')
    f.write('storePassword=' + esc(storepass) + '\n')
    f.write('keyAlias=' + esc(alias) + '\n')
    f.write('keyPassword=' + esc(keypass) + '\n')
os.chmod(path, 0o600)
PY
}

create_lineage() {
  [[ -f "$OLD_KEY" ]] || { echo "Old signing key not found: $OLD_KEY" >&2; exit 2; }
  [[ -f "$NEW_KEY" ]] || { echo "New signing key not found: $NEW_KEY" >&2; exit 2; }
  export VOICEDOCK_ROTATE_OLD_STORE="$OLD_STORE_PASS" VOICEDOCK_ROTATE_OLD_KEY="$OLD_KEY_PASS"
  export VOICEDOCK_ROTATE_NEW_STORE="$NEW_STORE_PASS" VOICEDOCK_ROTATE_NEW_KEY="$NEW_KEY_PASS"
  "$APKSIGNER" rotate --out "$LINEAGE" \
    --old-signer --ks "$OLD_KEY" --ks-key-alias "$OLD_ALIAS" \
      --ks-pass env:VOICEDOCK_ROTATE_OLD_STORE --key-pass env:VOICEDOCK_ROTATE_OLD_KEY \
      --set-installed-data true --set-shared-uid false --set-permission false --set-rollback false --set-auth false \
    --new-signer --ks "$NEW_KEY" --ks-key-alias "$NEW_ALIAS" \
      --ks-pass env:VOICEDOCK_ROTATE_NEW_STORE --key-pass env:VOICEDOCK_ROTATE_NEW_KEY
  chmod 600 "$LINEAGE"
}

fingerprint() {
  local key="$1" alias="$2" storepass="$3" var="$4"
  export "$var=$storepass"
  "$KEYTOOL" -list -v -keystore "$key" -alias "$alias" "-storepass:env" "$var" 2>/dev/null |
    awk -F': ' '/SHA1:/{print $2; exit}'
}

sign_rotated() {
  local input="$1" output="$2"
  [[ -f "$input" ]] || { echo "Unsigned APK not found: $input" >&2; exit 2; }
  [[ -f "$LINEAGE" ]] || { echo "Signing lineage not found; run '$0 init' first." >&2; exit 2; }
  [[ -f "$OLD_KEY" && -f "$NEW_KEY" ]] || { echo "Old/new keystore missing." >&2; exit 2; }
  mkdir -p "$(dirname "$output")"
  export VOICEDOCK_ROTATE_OLD_STORE="$OLD_STORE_PASS" VOICEDOCK_ROTATE_OLD_KEY="$OLD_KEY_PASS"
  export VOICEDOCK_ROTATE_NEW_STORE="$NEW_STORE_PASS" VOICEDOCK_ROTATE_NEW_KEY="$NEW_KEY_PASS"
  "$APKSIGNER" sign \
    --ks "$OLD_KEY" --ks-key-alias "$OLD_ALIAS" \
      --ks-pass env:VOICEDOCK_ROTATE_OLD_STORE --key-pass env:VOICEDOCK_ROTATE_OLD_KEY \
    --next-signer --ks "$NEW_KEY" --ks-key-alias "$NEW_ALIAS" \
      --ks-pass env:VOICEDOCK_ROTATE_NEW_STORE --key-pass env:VOICEDOCK_ROTATE_NEW_KEY \
    --lineage "$LINEAGE" --rotation-min-sdk-version 28 --min-sdk-version 26 \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$output" "$input"
  "$APKSIGNER" verify --verbose --print-certs "$output"
  if [[ -x "$ZIPALIGN" ]]; then "$ZIPALIGN" -c -P 16 -v 4 "$output"; fi
  echo "Rotated APK written to: $output"
}

case "$MODE" in
  init)
    load_new_passwords
    export VOICEDOCK_ROTATE_NEW_STORE="$NEW_STORE_PASS" VOICEDOCK_ROTATE_NEW_KEY="$NEW_KEY_PASS"
    if [[ ! -f "$NEW_KEY" ]]; then
      mkdir -p "$(dirname "$NEW_KEY")"
      "$KEYTOOL" -genkeypair -v -keystore "$NEW_KEY" -storetype PKCS12 \
        -storepass:env VOICEDOCK_ROTATE_NEW_STORE -keypass:env VOICEDOCK_ROTATE_NEW_KEY \
        -alias "$NEW_ALIAS" -keyalg RSA -keysize 3072 -validity 10000 \
        -dname "CN=VoiceDock AI Release, OU=Android, O=VoiceDock, C=BD" \
        >/dev/null
    fi
    if [[ "$NEW_KEY" == "$DEFAULT_NEW_KEY" ]]; then
      write_gradle_properties "$ROOT/keystore.properties" "$NEW_KEY" "$NEW_ALIAS" "$NEW_STORE_PASS" "$NEW_KEY_PASS"
    fi
    create_lineage
    sha1="$(fingerprint "$NEW_KEY" "$NEW_ALIAS" "$NEW_STORE_PASS" VOICEDOCK_FP_PASS)"
    echo "Private release key created/loaded: $NEW_KEY"
    echo "New certificate SHA-1: ${sha1:-unavailable}"
    echo "Signing lineage: $LINEAGE"
    echo "For migration, run: $0 sign app/build/outputs/apk/release/app-release-unsigned.apk"
    echo "Keep the keystore and passwords in separate secure backups. Installed-data capability is retained for migration; shared-UID, signature-permission, rollback and authenticator capabilities are disabled."
    ;;
  sign)
    load_new_passwords
    input="${2:-$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk}"
    output="${3:-$ROOT/app/build/outputs/apk/release/VoiceDock-AI-v0.6.1-release-rotated.apk}"
    sign_rotated "$input" "$output"
    ;;
  help|-h|--help)
    cat <<EOF
Usage:
  $0 init [-- not needed]
      Generate/load a private release key, write local keystore.properties (default key only),
      and create a v3 lineage from the v0.6.0 debug certificate.
  $0 sign [unsigned.apk] [output.apk]
      Sign an unsigned release APK with a migration lineage; Android 9+ uses the new key,
      Android 8.x uses the old debug key.

Environment: VOICEDOCK_KEYSTORE, VOICEDOCK_STORE_PASSWORD, VOICEDOCK_KEY_ALIAS,
             VOICEDOCK_KEY_PASSWORD; optional VOICEDOCK_OLD_KEYSTORE and old-key env values.
EOF
    ;;
  *) echo "Unknown command: $MODE (use init, sign, or help)." >&2; exit 2 ;;
esac
