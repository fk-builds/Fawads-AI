# Fawad's AI — Android AI Voice Assistant

[![Android CI](https://github.com/fk-builds/Fawads-AI/actions/workflows/android-ci.yml/badge.svg)](https://github.com/fk-builds/Fawads-AI/actions/workflows/android-ci.yml)

A production-ready **Android AI Voice Assistant** built in **Kotlin + MVVM**, powered by the **Google Gemini Live API over WebSocket** with **native PCM audio**. Renamed from MYRA → **Fawad's AI** across the whole app.

> ✅ **Every push to this repo is built automatically by GitHub Actions.**
> After each run you can download ready-to-install **debug & release APKs** from
> **Repository → Actions tab → (latest run) → Artifacts**.
> You can also build locally in *Android Studio* (see below).

---

## ✨ What it does

- **Natural human-like voice chat** with Gemini Live (native audio, no text-to-speech).
- **3 AI models** + **8 voices** + **3 personalities** (GF / Professional / Assistant) — selectable in Settings.
- **Speaks in Hinglish** by default, friendly and conversational.
- **Phone control by voice** (Hinglish + English):
  - Open / close apps (YouTube, WhatsApp, Instagram, Chrome, Maps, and more)
  - Call a contact, send SMS, open WhatsApp
  - Call/message **Prime Contacts** ("call my close friend")
  - Volume up/down, torch on/off, WiFi on/off, Bluetooth on/off
- **Incoming call announcement + voice decision** ("uthao" → accept, "reject" → reject).
- **Floating orb overlay** via **double-press of the power button**.
- **Live animated orb** (idle / listening / speaking / thinking / active) + **waveform visualizer**.
- **Auto-reconnect**, keep-alive & session renewal (9 min) for the WebSocket.
- MVVM architecture, ViewBinding, encrypted SharedPreferences, Material theming.
- **v1.1:** Screen Lock (PIN + Face), alarms/timers/reminders/notes, live weather/news/crypto, music/search, wake-word hands-free mode, luxury gold theme.
- **Four main pages:** Home, Tasks, Voice and Smart Chat, with Settings in the header menu.
- **Tasks:** persistent checklist with completion, deletion and optional date/time reminders; voice-set reminders and alarms appear here too.
- **Smart Chat:** typed Gemini conversation, locally saved history, clear-chat action, and routing for commands already supported by the assistant.
- **Design:** warm cream canvas, antique-gold actions, charcoal Voice screen, the FWD mark and Fawad's portrait.

---

## 📁 Project structure

```
FawadsAI/
├── settings.gradle / build.gradle / gradle.properties / gradlew(.bat) / gradle/wrapper/
└── app/
    ├── build.gradle            (AGP 8.2.2, Kotlin 1.9.22, targetSdk 34, minSdk 26)
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/fawads/ai/
        │   ├── ai/             GeminiLiveClient · AudioEngine · CommandParser
        │   ├── model/          AppCommand
        │   ├── service/         Accessibility · CallMonitor · FawadsOverlay · PowerButton · Boot
        │   ├── ui/main/         MainActivity · OrbAnimationView · UiComponents (Waveform + Chat)
        │   ├── ui/settings/    SettingsActivity
        │   ├── util/           Prefs · FawadsApp
        │   └── viewmodel/      MainViewModel
        └── res/                 layouts · drawables · values · xml configs · launcher icons
```

---

## 🧰 Requirements

- **Android Studio** (latest, e.g. Hedgehog / Iguana)
- **JDK 17** (bundled with recent Android Studio)
- **Android SDK 34** (install via SDK Manager)
- A **Google Gemini API key** (see below)

---

## 🔑 Get a Gemini API key (free)

1. Go to **https://aistudio.google.com/apikey**
2. Sign in with your Google account → **Create API key**
3. Copy the key. You'll paste it **inside the app**: Settings → **Gemini API Key**.

> The Key enters the app at runtime (stored encrypted via EncryptedSharedPreferences). We do **not** hardcode it in the source (that would expose it).

---

## 🛠️ Build the APK — **from your phone only** (no PC, no Android Studio, no CI)

The APK is built **on the Android device itself**, in Termux. One phone is enough.
Full guide: **[`docs/APK-PHONE-SETUP.md`](docs/APK-PHONE-SETUP.md)**

```bash
# in Termux (from F-Droid), after `termux-setup-storage`:
git clone https://github.com/fk-builds/Fawads-AI.git
cd Fawads-AI
bash scripts/phone-setup.sh        # once: JDK + arm64 Android build-tools + SDK 34
bash scripts/make-keystore.sh      # once: your release signing key
bash scripts/phone-build.sh        # → /sdcard/Download/FawadsAI-latest.apk
```

- The APK is **universal**: it installs and runs on every Android 8+ phone (arm64 / x86_64).
  No device binding, no license check — anyone you send the file to can install it.
- The layouts are size-token driven (`values/`, `values-h640dp`, `values-h900dp`, `values-sw600dp`),
  so small phones, tall phones, tablets and unfolded foldables all fit without clipping.
- Updates go out **Play-Store-style** without the Play Store: the app checks your `release.json`,
  downloads the new APK, verifies SHA-256 and opens the installer → `docs/AUTO-UPDATES.md`
- Want the real Play Store instead? `bash scripts/phone-build.sh --play` makes the `.aab`
  → `docs/PLAYSTORE.md`

> **Why `aapt2` needs a patch:** Google's official SDK ships x86_64 build-tools, which cannot
> execute on an arm64 phone. `scripts/phone-setup.sh` installs the aarch64-patched build-tools from
> [AndroidIDEOfficial/androidide-tools](https://github.com/AndroidIDEOfficial/androidide-tools)
> (verified against pinned SHA-256) and points Gradle at them via a **user-level**
> `~/.gradle/gradle.properties` override — never committed, so PC builds stay untouched.

<details>
<summary>Optional: build with Android Studio on a PC (same commands as the phone script)</summary>

```bash
./gradlew assembleRelease          # or assembleDebug, or --both style below
./gradlew assembleDebug assembleRelease
./gradlew bundleRelease            # .aab for Play Console
```

Output: `app/build/outputs/apk/release/app-release.apk`. `local.properties` must point at your SDK
(`sdk.dir=/path/to/Android/sdk`); the release build picks up `keystore.properties` when present and
falls back to the debug key (with a warning) when it is not.

</details>

<details>
<summary>Optional: GitHub Actions (manual only, off by default)</summary>

`.github/workflows/android-ci.yml` still exists but is now `workflow_dispatch`-only, so pushes no
longer build on GitHub. To use it: **Actions → Android CI → Run workflow → Artifacts**
(`FawadsAI-debug`, `FawadsAI-release`, kept 90 days).

</details>

---

## 🔑 Get a Gemini API key (free)

1. Go to **https://aistudio.google.com/apikey**
2. Sign in with your Google account → **Create API key**
3. Copy the key. You'll paste it **inside the app**: Settings → **Gemini API Key**.

> The key enters the app at runtime (stored in EncryptedSharedPreferences) and is never committed
> to the source — so the APK you share contains no secrets of yours.

---

## ⚙️ First-run setup on your phone

1. **Launch the app.** Home opens without starting microphone capture.
2. Open **Settings** from the header menu and save your Gemini API key.
3. Choose **Voice** when you want to speak; Android will request microphone access at that point.
4. Use **Chat** for typed questions and supported typed commands. Chat history is saved on this device and can be cleared from Chat.
5. To receive reminder notifications, allow notification access when you create a scheduled reminder.
6. **Enable Accessibility** only if you want app-control commands such as closing the current app:
   - Settings → *Accessibility* → **"Fawad's AI"** → enable.
   - Without it, app-control commands will warn you.
7. **Overlay permission** (`Display over other apps`) is optional and is only needed for the floating orb feature.

---

## 🗣️ Try these voice commands

| Say | It does |
|-----|---------|
| "YouTube kholo" / "open YouTube" | Opens YouTube |
| "WhatsApp band karo" | Closes WhatsApp |
| "Mom ko call karo" | Calls Mom (from contacts) |
| "call my close friend" | Calls your 1st Prime contact |
| "meri jaan ko message karo" | Opens WhatsApp for your love |
| "volume badhao / kam karo" | Volume up / down |
| "torch on / off" | Flashlight |
| "WiFi on / off", "Bluetooth on" | Toggles radios |
| Double-press POWER | Floating orb appears over any app |

---

## 🧩 Extended features (v1.1)

After the base assistant, the app now also does:

**🔒 Screen Lock (PIN + Face) — generic / portable**
- Whoever configures the lock owns it. The person who **sets the PIN** and **enrolls their face** is the only one who can unlock — it is *not* tied to the device owner.
- App launches → lock screen asks for the enrolled face or the PIN.
- Setup: **Settings → Security** → enable Screen Lock, Set/Change PIN, **Enroll Face** (live CameraX preview, ~8 frames averaged into a stored descriptor).
- Unlock matching uses ML Kit face landmarks + cosine similarity. A 4-digit PIN always works as a fallback.
- 🔒 *Note:* the face comparison is a lightweight geometric matcher. For bank-grade 1:1 verification you'd swap in a TFLite face-recognition model.

**⏰ Personal tasks (voice)**
- Set **alarms** · **timers** · **reminders** — they fire a notification and are **spoken aloud** (offline TTS).
  - "7:30 ka alarm lagao" · "5 minute ka timer" · "mujhe doctor yaad dilana"
- **Notes** — voice-save a note, then read it back:
  - "note likh lo milk lana hai" · "open notes"

**🌐 Live info (voice, keyless public APIs)**
- **Weather** — "mausam batao" / "weather in Lahore"
- **News** — "aaj ki khabar suna do"
- **Crypto prices** — "bitcoin kitna hai?"
- **Music / YouTube / web search** — "Arijit Singh gaana chalao", "youtube pe Python dikhao", "google karo weather"

**🎤 Hands-free (wake word)**
- Toggle in Settings. Say your **wake word (default "Fawad")** to talk without tapping the mic.
- Example: *"Fawad, YouTube kholo"* — the wake word is stripped and the rest runs as a command.

**🏅 Luxury gold theme**
- Recoloured to a refined **dark charcoal + champagne gold** palette (premium, not neon).

## 🎨 Theme

- Near-black background `#050505` with subtle red/purple glow.
- Accent **red `#FF1744`** / **purple `#D500F9`**.
- Monospace status bar (battery / RAM / time), animated orb + waveform.
- GF mode speaks warm Hinglish; Professional is formal; Assistant is balanced.

---

## ❓ Troubleshooting

- **"API key not set"** → open Settings, paste key, restart.
- **No voice / Gemini error** → check the key is valid & has billing/credits; try a different model.
- **Apps don't open** → enable Accessibility for Fawad's AI.
- **Incoming call not announced** → grant *Phone* + *Call Logs* permissions and ensure the Call monitor service is running (it starts on boot too).
- **Floating orb missing** → grant *Display over other apps* permission.

---

*Fawad's AI v1.0 — Android AI Voice Assistant — Gemin Live WebSocket + native PCM, MVVM.*
