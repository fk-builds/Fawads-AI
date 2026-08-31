# Fawad's AI — Android AI Voice Assistant

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

## 🛠️ Build the APK

### Option A — GitHub Actions (automatic, no setup)

1. Push any change to this repository (or open a PR).
2. Go to the **Actions** tab → **Android CI** workflow → wait for the ✅ check.
3. Download the artifacts:
   - **`FawadsAI-debug`** → `app-debug.apk` (fast, for testing)
   - **`FawadsAI-release`** → `app-release.apk` (minified; currently signed with the debug key)
4. (Optional) Build on demand: **Actions → Android CI → Run workflow**.

The CI job: JDK 17 (Temurin) → Gradle wrapper 8.5 → AGP 8.2.2 + Kotlin 1.9.22 → `assembleDebug assembleRelease` → uploads both APKs as artifacts (kept 90 days).

### Option B — Android Studio (local)

1. **Open Android Studio** → *File → Open* → choose the **FawadsAI** folder.
2. Let Gradle **sync** (it auto-downloads the bundled Gradle + dependencies — needs internet on first run).
3. (Optional) In *File → Project Structure → SDK Location*, make sure the SDK path is set.
4. **Build a debug APK:**
   - Menu → **Build → Build Bundle(s) / APK(s) → Build APK(s)**
   - The APK is at: `app/build/outputs/apk/debug/app-debug.apk`
5. **Or run from the terminal:**
   ```bash
   # macOS / Linux
   ./gradlew assembleDebug
   # Windows
   gradlew.bat assembleDebug
   ```
6. Install: copy `app-debug.apk` to your phone and enable **"Install unknown apps"**, or connect the phone and press ▶ Run.

> For a **release** APK you'd set up your own signing key. The provided `release` build type currently signs with the debug key so you can test quickly — don't publish that.

---

## ⚙️ First-run setup on your phone

1. **Launch the app** → it will ask for permissions. **Allow all.**

2. **Microphone** — required for voice.
3. **Notifications** — so the overlay / services can run.

4. **Paste your Gemini API Key** in **Settings**.

5. **Enable Accessibility** (for opening/closing apps):
   - Settings → *Accessibility* → **"Fawad's AI"** → enable.
   - Without it, app-control commands will warn you.

6. **Overlay permission** (`Display over other apps`) — enables the floating orb.

7. **Restart the app.** It connects to Gemini Live and greets you in a human voice. 🎙️

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
