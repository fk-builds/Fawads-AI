#!/usr/bin/env bash
#
# make-update-feed.sh — write the tiny release.json the in-app updater reads.
#
#   bash scripts/make-update-feed.sh <apk-path> <out-json>
#
# Then upload BOTH files to any static host (see docs/AUTO-UPDATES.md) and put
# the release.json URL in the app: Settings → Updates → Update feed URL.
#
# Set the public download base URL of the APK like this:
#   APK_BASE_URL=https://files.example.com/fawads bash scripts/make-update-feed.sh ...
#
set -uo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)" || exit 1

APK="${1:-dist/FawadsAI-latest.apk}"
OUT="${2:-dist/release.json}"
BASE="${APK_BASE_URL:-https://REPLACE-ME.example.com/apk}"
BASE="${BASE%/}"

[ -f "$APK" ] || { echo "APK nahi mila: $APK (pehle bash scripts/phone-build.sh)"; exit 1; }

read_ver() {
  local v; v="$(grep -E "^$1=" version.properties 2>/dev/null | tail -1 | cut -d= -f2 | tr -d ' \r')"
  echo "${v:-$2}"
}
VC="$(read_ver VERSION_CODE 1)"
VN="$(read_ver VERSION_NAME 1.0.0)"
NOTES="${RELEASE_NOTES:-Naye bugs fix ho gaye.}"
SIZE=$(stat -c%s "$APK" 2>/dev/null || echo 0)
SHA=$(sha256sum "$APK" | awk '{print $1}')
NAME="$(basename "$APK")"

cat > "$OUT" <<EOF
{
  "versionCode": ${VC},
  "versionName": "${VN}",
  "apkUrl": "${BASE}/${NAME}",
  "fileName": "${NAME}",
  "sizeBytes": ${SIZE},
  "sha256": "${SHA}",
  "minSdkVersion": 26,
  "forceUpdate": false,
  "notes": "${NOTES}"
}
EOF

echo "wrote $OUT"
echo "  versionCode : $VC   versionName: $VN"
echo "  apk         : ${BASE}/${NAME}"
echo "  size        : $((SIZE/1024/1024))MB ($SIZE bytes)"
echo "  sha256      : $SHA"
echo
echo "Upload karna hai:  $APK   +   $OUT   (ek hi folder me, direct https links)"
