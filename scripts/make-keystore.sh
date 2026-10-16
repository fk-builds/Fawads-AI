#!/usr/bin/env bash
#
# make-keystore.sh — create YOUR release signing key, on the phone.
#
# Why you need this: an APK installs on any device regardless of the key, but
# "update over the old version" only works when the new APK is signed with the
# SAME key. And Google Play refuses debug-signed uploads. So: create one key,
# reuse it forever, back it up.
#
#   bash scripts/make-keystore.sh
#   bash scripts/make-keystore.sh --pass "yourPassword" --alias fawads
#
# Creates:  fawads-release.keystore  +  keystore.properties   (both git-ignored)
#
set -uo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)" || exit 1

C_G="\033[32m"; C_Y="\033[33m"; C_0="\033[0m"
info() { printf '%s==>%s %s\n' "$C_G" "$C_0" "$*"; }
warn() { printf '%swarn%s %s\n' "$C_Y" "$C_0" "$*"; }

ALIAS="fawads"
KS="fawads-release.keystore"
PASS=""
DNAME="CN=Fawad Khan, OU=Fawads AI, O=Fawads, L=Mansehra, ST=KP, C=PK"
VALIDITY_DAYS=10000   # ~27 years — a release key must outlive the app

while [ $# -gt 0 ]; do
  case "$1" in
    --alias)   ALIAS="$2"; shift 2 ;;
    --keystore) KS="$2"; shift 2 ;;
    --pass)    PASS="$2"; shift 2 ;;
    --dname)   DNAME="$2"; shift 2 ;;
    *) warn "ignore: $1"; shift ;;
  esac
done

command -v keytool >/dev/null 2>&1 || { echo "keytool nahi mila — pehle: bash scripts/phone-setup.sh"; exit 1; }

if [ -f "$KS" ]; then
  info "$KS pehle se maujood — nayi key nahi banaoonga (purani key hi use hoti rahegi)."
else
  if [ -z "$PASS" ]; then
    printf 'Ek password sochein (APK update ke liye yaad rakhna hoga): '
    read -rs PASS; echo
    printf 'Dobara likhein: '
    read -rs PASS2; echo
    [ "$PASS" = "$PASS2" ] || { echo "Password match nahi kare."; exit 1; }
  fi
  [ ${#PASS} -ge 8 ] || warn "8+ characters behtar hai."

  info "2048-bit RSA key generate ho rahi hai…"
  if [ -n "$PASS" ]; then
    keytool -genkeypair -v -keystore "$KS" -alias "$ALIAS" \
      -keyalg RSA -keysize 2048 -validity "$VALIDITY_DAYS" \
      -storepass "$PASS" -keypass "$PASS" -dname "$DNAME" || exit 1
  else
    keytool -genkeypair -v -keystore "$KS" -alias "$ALIAS" \
      -keyalg RSA -keysize 2048 -validity "$VALIDITY_DAYS" -dname "$DNAME" || exit 1
  fi
fi

if [ -z "$PASS" ]; then
  printf 'Keystore ka password type karein (keystore.properties me save hoga): '
  read -rs PASS; echo
fi

info "keystore.properties likha ja raha hai…"
cat > keystore.properties <<EOF
# Local signing config — NEVER commit this file or the .keystore.
# Read by app/build.gradle for the release build type.
storeFile=$KS
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOF
chmod 600 keystore.properties 2>/dev/null || true

grep -q '^keystore.properties$' .gitignore 2>/dev/null || echo "keystore.properties" >> .gitignore

echo
info "Done ✅"
echo "  key:   $KS"
echo "  config: keystore.properties"
echo
warn "BAKUP ZAROORI: $KS + keystore.properties ka ek copy Google Drive/Email par rakh dein."
warn "Key kho gaya = aap ke purane installs par update install nahi hoga (uninstall karke dobara install karna parega)."
echo
info "Ab build: bash scripts/phone-build.sh"
