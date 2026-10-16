#!/usr/bin/env bash
#
# phone-setup.sh — Android build environment inside Termux, on ONE phone.
#
# No PC. No Android Studio. No GitHub Actions. Just the phone.
#
# Why this script exists: Google's official Android SDK build-tools (aapt2,
# zipalign, aidl…) are x86_64 binaries and will NOT execute on an arm64 Android
# phone. So instead of the official SDK we install the aarch64-patched,
# statically-linked build-tools published by AndroidIDEOfficial/androidide-tools,
# plus JDK 17 from Termux. That combination really does run
# `./gradlew assembleRelease` on a phone.
#
# Usage (inside Termux):
#   bash scripts/phone-setup.sh              # install everything
#   bash scripts/phone-setup.sh --check      # verify only, change nothing
#   bash scripts/phone-setup.sh --skip-update  # don't run `pkg update`
#
# Idempotent: run it as many times as you like. Re-runs skip finished steps.
#
set -uo pipefail

# ───────────────────────────── config ─────────────────────────────
SDK_DIR="${ANDROID_SDK_PHONE_DIR:-$HOME/android-sdk}"
BUILD_TOOLS_VERSION="34.0.4"     # aarch64-patched release from androidide-tools
PLATFORM_VERSION="34"            # must match compileSdk in app/build.gradle
BASE_URL="https://github.com/AndroidIDEOfficial/androidide-tools/releases/download"
CACHE_DIR="$HOME/.cache/fawads-phone-build"
USER_GRADLE_PROPS="$HOME/.gradle/gradle.properties"

# "file|url|sha256|marker-path-inside-sdk"
ARCHIVES=(
  "android-sdk.tar.xz|${BASE_URL}/sdk/android-sdk.tar.xz|253fab4ff1263ebf78bf89245acde98450ddae83d20ce7fa9d369b3f68f62d9e|platforms/android-33"
  "cmdline-tools.tar.xz|${BASE_URL}/sdk/cmdline-tools.tar.xz|aca602848ff3d6044ffa908efd134300d99623327fee06a7886b990b94049bfa|cmdline-tools/latest/bin/sdkmanager"
  "build-tools-${BUILD_TOOLS_VERSION}-aarch64.tar.xz|${BASE_URL}/v${BUILD_TOOLS_VERSION}/build-tools-${BUILD_TOOLS_VERSION}-aarch64.tar.xz|4bbbdeee608ff8d3a2c1534d0a5270305a02b1f200403a536c75ac84c41e52fa|build-tools/${BUILD_TOOLS_VERSION}"
  "platform-tools-${BUILD_TOOLS_VERSION}-aarch64.tar.xz|${BASE_URL}/v${BUILD_TOOLS_VERSION}/platform-tools-${BUILD_TOOLS_VERSION}-aarch64.tar.xz|08b5b5c9899201081586f8f8682882784458ff67e6421cef65b1d6a0da877236|platform-tools/adb"
)

C_G="\033[32m"; C_R="\033[31m"; C_Y="\033[33m"; C_D="\033[2m"; C_0="\033[0m"
info() { printf '%s==>%s %s\n' "$C_G" "$C_0" "$*"; }
warn() { printf '%swarn%s %s\n' "$C_Y" "$C_0" "$*"; }
die()  { printf '%sFAIL%s %s\n' "$C_R" "$C_0" "$*" >&2; exit 1; }

CHECK_ONLY=0
SKIP_UPDATE=0
for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    --skip-update) SKIP_UPDATE=1 ;;
    --sdk-dir) shift; SDK_DIR="${1:-$SDK_DIR}" ;;
    -h|--help)
      sed -n '1,25p' "$0"; exit 0 ;;
  esac
done

# ───────────────────────────── preflight ─────────────────────────────
[ -n "${PREFIX:-}" ] || die "Ye script Termux ke andar chalani hai (Play Store wala Termux nahi — F-Droid/GitHub wala)."
case "$PREFIX" in
  *com.termux*) : ;;
  *) die "Termux nahi mila (PREFIX=$PREFIX). F-Droid se Termux install karein." ;;
esac

ARCH="$(uname -m)"
if [ "$ARCH" != "aarch64" ] && [ "$ARCH" != "arm64" ]; then
  die "Is setup ka aarch64 build hai. Aap ka CPU: $ARCH — 32-bit phone par ye tarika kaam nahi karega (box64/proot chahiye hoga, jo fragile hai)."
fi

RAM_KB=$(grep MemTotal /proc/meminfo 2>/dev/null | awk '{print $2}')
if [ -n "${RAM_KB:-}" ] && [ "$RAM_KB" -lt 3000000 ]; then
  warn "RAM kam hai ($((RAM_KB/1024)) MB). Build ke waqt 'Logcat/Termux' ke alawa sab band karein; low-memory flag use karein."
fi

FREE_MB=$(df -Pm "$HOME" | awk 'NR==2{print $4}')
if [ -n "${FREE_MB:-}" ] && [ "$FREE_MB" -lt 3000 ]; then
  warn "Jagah kam hai: ${FREE_MB}MB free in \$HOME. Setup ke liye ~3GB chahiye (SDK + Gradle cache + build)."
fi

# ───────────────────────────── 1. termux packages ─────────────────────────────
install_packages() {
  local pm="pkg"
  command -v nala >/dev/null 2>&1 && pm="nala"
  if [ "$SKIP_UPDATE" -eq 0 ]; then
    info "pkg repo update…"
    $pm update -y || warn "update fail hua — continue kar rahe hain"
  fi
  info "Termux packages install ho rahe hain (JDK, unzip, xz, apksigner)…"
  # git/unzip/xz-utils are essential. openjdk-17 is what AGP 8.2 wants.
  $pm install -y git curl wget unzip tar xz-utils coreutils apksigner openjdk-17 \
    || $pm install -y git curl wget unzip tar xz-utils coreutils apksigner openjdk-21 \
    || die "Packages install nahi hue. Internet + 'termux-change-repo' check karein."
  # Optional extras — never fatal.
  $pm install -y aapt2 zipalign >/dev/null 2>&1 || true
}

java_home_of() {
  local javac; javac="$(command -v javac 2>/dev/null)"
  [ -n "$javac" ] || return 1
  local real; real="$(readlink -f "$javac")"
  dirname "$(dirname "$real")"
}

# ───────────────────────────── 2. download + verify ─────────────────────────────
fetch() { # fetch <name> <url> <sha256>
  local name="$1" url="$2" want="$3" out="$CACHE_DIR/$name"
  if [ -f "$out" ] && [ -n "$want" ]; then
    if [ "$(sha256sum "$out" | awk '{print $1}')" = "$want" ]; then
      printf '   %s[cached]%s %s\n' "$C_D" "$C_0" "$name"; return 0
    fi
    warn "Checksum mismatch — $name dobara download ho raha hai"
    rm -f "$out"
  fi
  [ -f "$out" ] && [ -z "$want" ] && { printf '   %s[cached]%s %s\n' "$C_D" "$C_0" "$name"; return 0; }
  info "download $name"
  curl -fL --retry 3 --continue-at - -o "$out" "$url" || die "Download fail: $url"
  if [ -n "$want" ]; then
    local got; got="$(sha256sum "$out" | awk '{print $1}')"
    [ "$got" = "$want" ] || die "SHA-256 mismatch for $name (got $got) — install ko roka gaya, safe rahein."
  fi
}

# ───────────────────────────── 3. extract ─────────────────────────────
extract_all() {
  mkdir -p "$SDK_DIR"
  local entry name url sha marker dest
  for entry in "${ARCHIVES[@]}"; do
    IFS='|' read -r name url sha marker <<<"$entry"
    dest="$SDK_DIR/$marker"
    if [ -e "$dest" ]; then
      printf '   %s[done]%s %s\n' "$C_D" "$C_0" "$name"; continue
    fi
    info "extract $name → $SDK_DIR"
    tar -C "$SDK_DIR" -xf "$CACHE_DIR/$name" || die "Extract fail: $name"
  done
}

# ───────────────────────────── 4. android platform ─────────────────────────────
install_platform() {
  export ANDROID_HOME="$SDK_DIR"
  export ANDROID_SDK_ROOT="$SDK_DIR"
  export PATH="$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION:$PATH"
  local javahome; javahome="$(java_home_of || true)"
  [ -n "$javahome" ] && export JAVA_HOME="$javahome"

  if [ -d "$SDK_DIR/platforms/android-$PLATFORM_VERSION" ]; then
    printf '   %s[done]%s platforms;android-%s\n' "$C_D" "$C_0" "$PLATFORM_VERSION"; return 0
  fi
  command -v sdkmanager >/dev/null 2>&1 || die "sdkmanager nahi mila — step 2/3 adhoora lagta hai."
  info "licenses accept + platforms;android-$PLATFORM_VERSION install (Google se, arch-independent)…"
  yes 2>/dev/null | sdkmanager --licenses >/dev/null 2>&1 || true
  yes 2>/dev/null | sdkmanager --sdk_root="$SDK_DIR" "platforms;android-$PLATFORM_VERSION" \
    || die "sdkmanager 'platforms;android-$PLATFORM_VERSION' fail — internet check karke dobara chalayein."
}

# ───────────────────────────── 5. gradle env ─────────────────────────────
write_gradle_props() {
  local aapt2="$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION/aapt2"
  [ -x "$aapt2" ] || aapt2="$(command -v aapt2 2>/dev/null || echo "$aapt2")"
  mkdir -p "$(dirname "$USER_GRADLE_PROPS")"
  touch "$USER_GRADLE_PROPS"
  # NOTE: these machine-specific overrides stay in ~/.gradle — never in the repo
  # (committing a Termux path breaks the build on every other machine).
  local changed=0
  set_prop() { # set_prop <key> <value> <file>
    local key="$1" val="$2" file="$3"
    if grep -q "^${key}=" "$file" 2>/dev/null; then
      sed -i.bak "s|^${key}=.*|${key}=${val}|" "$file" && rm -f "${file}.bak"
    else
      printf '%s=%s\n' "$key" "$val" >> "$file"; changed=1
    fi
  }
  info "user-level ~/.gradle/gradle.properties set (aapt2 arm64 + phone-safe flags)"
  set_prop "android.aapt2FromMavenOverride" "$aapt2" "$USER_GRADLE_PROPS"
  set_prop "org.gradle.vfs.watch" "false" "$USER_GRADLE_PROPS"
  set_prop "org.gradle.daemon" "false" "$USER_GRADLE_PROPS"
  set_prop "org.gradle.configureondemand" "false" "$USER_GRADLE_PROPS"
}

write_shell_env() {
  local bashrc="$HOME/.bashrc" block="# >>> fawads-phone-build >>>" endb="# <<< fawads-phone-build <<<"
  local javahome; javahome="$(java_home_of || true)"
  touch "$bashrc"
  if grep -qF "$block" "$bashrc"; then
    awk -v b="$block" -v e="$endb" '$0==b{inb=1;next} $0==e{inb=0;next} !inb{print}' "$bashrc" > "$bashrc.tmp" && mv "$bashrc.tmp" "$bashrc"
  fi
  {
    echo "$block"
    echo "export ANDROID_HOME=\"$SDK_DIR\""
    echo "export ANDROID_SDK_ROOT=\"\$ANDROID_HOME\""
    echo "export ANDROID_USER_HOME=\"\$HOME/.android\""
    [ -n "$javahome" ] && echo "export JAVA_HOME=\"$javahome\""
    echo "export PATH=\"\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION\""
    echo "$endb"
  } >> "$bashrc"
  info "shell env \$HOME/.bashrc me likh diya (naye terminal me auto-load)"
}

write_local_properties() {
  # local.properties = machine-specific, git-ignored. Points Gradle at our SDK.
  local repo; repo="$(cd "$(dirname "$0")/.." && pwd)"
  if [ -f "$repo/gradlew" ]; then
    printf 'sdk.dir=%s\n' "$SDK_DIR" > "$repo/local.properties"
    info "$repo/local.properties likha (sdk.dir=$SDK_DIR)"
  fi
}

# ───────────────────────────── 6. verify ─────────────────────────────
verify() {
  export ANDROID_HOME="$SDK_DIR"; export ANDROID_SDK_ROOT="$SDK_DIR"
  export PATH="$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION:$PATH"
  local fail=0
  check() { # check <label> <cmd...>
    local label="$1"; shift
    if "$@" >/dev/null 2>&1; then
      printf '  %s✓%s %-14s %s\n' "$C_G" "$C_0" "$label" "$($1 --version 2>/dev/null | head -1 || echo ok)"
    else
      printf '  %s✗%s %-14s (not runnable)\n' "$C_R" "$C_0" "$label"; fail=1
    fi
  }
  echo
  info "Verification:"
  for tool in aapt2 zipalign apksigner d8; do
    p="$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION/$tool"
    if [ -x "$p" ]; then printf '  %s✓%s %-14s %s\n' "$C_G" "$C_0" "$tool" "$(file "$p" 2>/dev/null | sed 's/.*: //;s/,.*//' || echo present)"
    else printf '  %s✗%s %-14s MISSING\n' "$C_R" "$C_0" "$tool"; fail=1; fi
  done
  if command -v java >/dev/null; then
    printf '  %s✓%s %-14s %s\n' "$C_G" "$C_0" "java" "$(java -version 2>&1 | head -1)"
  else printf '  %s✗%s java MISSING\n' "$C_R" "$C_0"; fail=1; fi
  if [ -d "$SDK_DIR/platforms/android-$PLATFORM_VERSION" ]; then
    printf '  %s✓%s %-14s android-%s\n' "$C_G" "$C_0" "platform" "$PLATFORM_VERSION"
  else printf '  %s✗%s platform MISSING (android-%s)\n' "$C_R" "$C_0" "$PLATFORM_VERSION"; fail=1; fi
  [ "$fail" -eq 0 ] || die "Kuch missing hai — dobara chalayein: bash scripts/phone-setup.sh"
  echo
  info "Ready. Ab:  bash scripts/phone-build.sh"
}

# ───────────────────────────── run ─────────────────────────────
if [ "$CHECK_ONLY" -eq 1 ]; then
  info "check-only mode"
  verify
  exit 0
fi

mkdir -p "$CACHE_DIR"
[ -d "$HOME/storage/shared" ] || warn "~/storage/shared nahi hai — 'termux-setup-storage' chalayein taake APK /sdcard/Download tak pohnche"

install_packages
extract_all
install_platform
write_gradle_props
write_shell_env
write_local_properties
verify

cat <<EOF

  $C_GSetup complete.$C_0

  Ab build ke liye:
      bash scripts/make-keystore.sh   # ek baar, apni signing key banane ke liye
      bash scripts/phone-build.sh     # har baar — APK /sdcard/Download me aayegi

  Tip: Termux ko background me zinda rakhne ke liye 'acquire wakelock'
  (notification se) on rakhein, aur phone charger par rakhein.
EOF
