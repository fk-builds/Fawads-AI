#!/usr/bin/env bash
#
# phone-build.sh — build the Fawad's AI APK (and Play-ready AAB) from the phone.
#
# Runs inside Termux after `bash scripts/phone-setup.sh`.
#
#   bash scripts/phone-build.sh                 # signed universal release APK
#   bash scripts/phone-build.sh --debug         # debug APK (faster, no minify)
#   bash scripts/phone-build.sh --both          # debug + release
#   bash scripts/phone-build.sh --play          # release + AAB for Play Console
#   bash scripts/phone-build.sh --bump          # +1 VERSION_CODE, then build
#   bash scripts/phone-build.sh --low-memory    # small heap (4GB-RAM phones)
#   bash scripts/phone-build.sh doctor          # only check the environment
#
# Output: /sdcard/Download/FawadsAI-v<version>.apk  (+ dist/release.json for auto-updates)
#
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-$HOME/android-sdk}"
BUILD_TOOLS_VERSION="34.0.4"
VERSION_FILE="$REPO/version.properties"
DIST="$REPO/dist"

TASKS="assembleRelease"
BUMP=0
HEAP_MB="${HEAP_MB:-2048}"
PLAY=0
COPIES=()

C_G="\033[32m"; C_R="\033[31m"; C_Y="\033[33m"; C_D="\033[2m"; C_0="\033[0m"
info() { printf '%b==>%b %s\n' "$C_G" "$C_0" "$*"; }
warn() { printf '%bwarn%b %s\n' "$C_Y" "$C_0" "$*"; }
die()  { printf '%bFAIL%b %s\n' "$C_R" "$C_0" "$*" >&2; exit 1; }

for arg in "$@"; do
  case "$arg" in
    --debug)      TASKS="assembleDebug" ;;
    --both)       TASKS="assembleDebug assembleRelease" ;;
    --play)       TASKS="assembleRelease bundleRelease"; PLAY=1 ;;
    --bump)       BUMP=1 ;;
    --low-memory) HEAP_MB=1280 ;;
    doctor)       TASKS="doctor" ;;
    -h|--help)    sed -n '1,20p' "$0"; exit 0 ;;
    *)            die "Unknown option: $arg (see --help)" ;;
  esac
done

cd "$REPO" || die "repo dir nahi mila: $REPO"

# ───────────────────────── env ─────────────────────────
[ -n "${PREFIX:-}" ] || warn "Termux ke bahar chal raha hai — phone par Termux ke andar hi chalayein."

if [ ! -d "$SDK_DIR/platforms/android-34" ] || [ ! -x "$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION/aapt2" ]; then
  info "Android SDK missing hai — setup chala raha hoon."
  bash "$REPO/scripts/phone-setup.sh" || die "SDK setup fail hua"
fi
if ! command -v java >/dev/null 2>&1; then
  die "Java nahi mila. Pehle: bash scripts/phone-setup.sh"
fi
if [ ! -d "$SDK_DIR/platforms/android-34" ]; then
  die "Android SDK platform android-34 nahi hai ($SDK_DIR). Pehle: bash scripts/phone-setup.sh"
fi
if [ ! -x "$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION/aapt2" ]; then
  die "arm64 build-tools (aapt2) nahi mile. Pehle: bash scripts/phone-setup.sh"
fi
[ -f "$REPO/gradlew" ] || die "gradlew missing — repo poora clone hua hai?"

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="$PATH:$SDK_DIR/platform-tools:$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dkotlin.compiler.execution.strategy=in-process"

TOTAL_MEM_MB=$(grep MemTotal /proc/meminfo 2>/dev/null | awk '{printf "%d", $2/1024}')
if [ -n "${TOTAL_MEM_MB:-}" ] && [ "$TOTAL_MEM_MB" -lt 4000 ] && [ "$HEAP_MB" -gt 1536 ]; then
  warn "Phone me ${TOTAL_MEM_MB}MB RAM hai — heap 2048m OOM kar sakta hai. --low-memory try karein."
fi

# ───────────────────────── version ─────────────────────────
read_ver() { # read_ver <KEY> <fallback>
  [ -f "$VERSION_FILE" ] || { echo "$2"; return; }
  local v; v="$(grep -E "^$1=" "$VERSION_FILE" | tail -1 | cut -d= -f2 | tr -d ' \r')"
  echo "${v:-$2}"
}

VC="$(read_ver VERSION_CODE 1)"
VN="$(read_ver VERSION_NAME 1.0.0)"

if [ "$BUMP" -eq 1 ]; then
  [ -f "$VERSION_FILE" ] || die "version.properties missing — bump nahi kar saka"
  NEWVC=$((VC + 1))
  sed -i.bak "s/^VERSION_CODE=.*/VERSION_CODE=${NEWVC}/" "$VERSION_FILE" && rm -f "$VERSION_FILE.bak"
  info "VERSION_CODE bumped: $VC → $NEWVC"
  VC="$NEWVC"
  git add version.properties 2>/dev/null && git commit -q -m "release: v$VN (build $VC)" 2>/dev/null \
    && info "version.properties commit ho gaya (tag bana lein: git tag v${VN}-${VC})" \
    || warn "commit nahi ho saka — version.properties khud commit kar lena"
fi

# ───────────────────────── keystore ─────────────────────────
if [ ! -f "$REPO/keystore.properties" ] && [ "$TASKS" != "doctor" ]; then
  case "$TASKS" in
    *Release*)
      warn "keystore.properties nahi mila → release build DEBUG key se sign hoga."
      warn "Install to karega (har device par), lekin Play Store upload + 'same key se update' nahi hoga."
      warn "Apni key banane ke liye:  bash scripts/make-keystore.sh"
      ;;
  esac
fi

# ───────────────────────── doctor ─────────────────────────
if [ "$TASKS" = "doctor" ]; then
  info "doctor — environment check"
  echo "  java:      $(java -version 2>&1 | head -1)"
  echo "  JAVA_HOME: ${JAVA_HOME:-<unset, Gradle will find it>}"
  echo "  ANDROID_HOME: $ANDROID_HOME"
  echo "  aapt2:     $("$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION/aapt2" version 2>&1 | head -1)"
  echo "  zipalign:  $([ -x "$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION/zipalign" ] && echo present || echo MISSING)"
  echo "  keystore:  $([ -f "$REPO/keystore.properties" ] && echo present || echo 'MISSING (release = debug-signed)')"
  echo "  version:   $VN (code $VC)"
  echo "  ~/.gradle/gradle.properties aapt2 override: $(grep -c 'aapt2FromMavenOverride' "$HOME/.gradle/gradle.properties" 2>/dev/null || echo 0) line(s)"
  echo "  free space in \$HOME: $(df -Pk "$HOME" | awk 'NR==2{print $4}')MB"
  echo
  info "Sab theek lag raha hai. Build: bash scripts/phone-build.sh"
  exit 0
fi

# ───────────────────────── build ─────────────────────────
mkdir -p "$DIST"
LOG="$DIST/build.log"
info "Building $TASKS (v$VN / code $VC) — phone par pehli baar me 15-40 min lag sakte hain (dependency downloads)"

set -x
./gradlew --no-daemon --max-workers="${MAX_WORKERS:-1}" \
  -Dorg.gradle.jvmargs="-Xmx${HEAP_MB}m -XX:MaxMetaspaceSize=512m" \
  -Dkotlin.compiler.execution.strategy=in-process \
  -PVERSION_CODE="$VC" -PVERSION_NAME="$VN" -PUPDATE_FEED_URL="${UPDATE_FEED_URL:-}" \
  $TASKS --stacktrace 2>&1 | tee "$LOG"
STATUS=${PIPESTATUS[0]}
set +x

[ "$STATUS" -eq 0 ] || { echo; die "Gradle fail (exit $STATUS). Puri log: $LOG — upar se 'What went wrong' section dekhein."; }

# ───────────────────────── collect outputs ─────────────────────────
find_apk() { # find_apk <debug|release>
  local t="$1"
  local p="$REPO/app/build/outputs/apk/$t/app-$t.apk"
  [ -f "$p" ] || p="$(find "$REPO/app/build/outputs/apk/$t" -name '*.apk' 2>/dev/null | head -1)"
  echo "$p"
}

DEST="${EXTERNAL_STORAGE:-/sdcard}"
[ -d "$DEST/Download" ] || DEST="$HOME/storage/shared"
if [ ! -d "$DEST/Download" ]; then
  warn "Shared storage nahi mila — 'termux-setup-storage' chalayein (ek baar). Filhal dist/ me rakh rahe hain."
  DEST=""
fi

copy_out() { # copy_out <src> <basename>
  local src="$1" base="$2"
  [ -n "$src" ] && [ -f "$src" ] || return 0
  cp -f "$src" "$DIST/$base"
  COPIES+=("$DIST/$base")
  if [ -n "$DEST" ]; then
    cp -f "$DIST/$base" "$DEST/Download/$base"
    COPIES+=("$DEST/Download/$base")
  fi
}

RELEASE_APK="$(find_apk release)"
DEBUG_APK="$(find_apk debug)"
copy_out "$RELEASE_APK" "FawadsAI-v${VN}-${VC}.apk"
copy_out "$RELEASE_APK" "FawadsAI-latest.apk"
copy_out "$DEBUG_APK"   "FawadsAI-debug-latest.apk"

if [ "$PLAY" -eq 1 ]; then
  AAB="$(find "$REPO/app/build/outputs/bundle/release" -name '*.aab' 2>/dev/null | head -1)"
  copy_out "$AAB" "FawadsAI-v${VN}-${VC}.aab"
fi

# ───────────────────────── update feed ─────────────────────────
if [ -n "$RELEASE_APK" ] && [ -f "$RELEASE_APK" ]; then
  bash "$REPO/scripts/make-update-feed.sh" "$DIST/FawadsAI-latest.apk" "$DIST/release.json" || warn "release.json generate nahi hua"
fi

echo
info "BUILD OK ✅"
for f in "${COPIES[@]}"; do
  [ -f "$f" ] && printf '   %s%s%s  (%sMB)\n' "$C_G" "$f" "$C_0" "$(du -m "$f" | cut -f1)"
done
if [ -n "$DEST" ]; then
  echo
  info "Phone par install karne ke liye: Files app → Download → $C_G FawadsAI-latest.apk $C_0 → Install"
  info "Kisi aur ko bhejne ke liye: wahi file WhatsApp/Drive par bhej dein (ye debug-unsigned nahi, normal signed APK hai)."
fi
if [ ! -f "$REPO/keystore.properties" ]; then
  echo
  warn "Reminder: abhi debug key se sign hua hai. Doston ke liye ek hi key se build karke 'update in place' chahate ho to scripts/make-keystore.sh zaroori hai."
fi
