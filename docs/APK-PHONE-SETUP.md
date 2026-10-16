# APK banana aur install karna — SIRF AIK MOBILE SE

> PC nahi chahiye. Android Studio nahi chahiye. GitHub Actions / CI se build **nahi** ho rahi.
> Sab kuch aap ke phone par hota hai. APK **har device** par chalti hai (arm64 + x86_64 phones,
> Android 8.0+), koi device-lock nahi — jo chahe install karke use kar sakta hai.

---

## 0. Zaroori cheezein

| Chiz | Detail |
|---|---|
| Phone | arm64/aarch64 Android phone (aaj kal sab hi hain), **4GB+ RAM** behtar, **~4GB khali jagah** |
| Internet | Pehli build me ~700MB download hota hai (Gradle + libraries) |
| Termux | **F-Droid** ya GitHub se — Play Store wala Termux purana/blocked hai |
| Battery | Phone charger par rakhein, screen lock off kar dein |

Check karne ke liye (Termux me): `uname -m` → `aarch64` hona chahiye.

---

## 1. Termux install karein

1. Browser me kholein: **https://f-droid.org/en/packages/com.termux/** → *Download APK* → install
   (F-Droid app install kar lein to updates bhi milte rahenge).
2. Termux kholein. Pehli baar storage permission:
   ```bash
   termux-setup-storage
   ```
   (phone par ek popup aayega → **Allow**). Iske baad `~/storage/shared` se aap ka
   `Download` folder accessible hota hai — APK wahin niklegi.

> Termux ko background me marne se bachane ke liye: Termux ki notification par
> **"Acquire wakelock"** dabayein rahein.

---

## 2. Project phone par laayein

```bash
git clone https://github.com/fk-builds/Fawads-AI.git
cd Fawads-AI
```

(Repo **public** hai, is liye login ki zaroorat nahi. Agar kabhi private kar dein to:
`git clone https://github.com/<user>/<repo>.git` par username + **personal access token**
maanga jayega — Settings → Developer settings → Tokens → `repo` read permission.)

Agar pehle se clone hai to update karein:

```bash
cd ~/Fawads-AI && git pull
```

---

## 3. Build environment setup (ek hi baar)

```bash
bash scripts/phone-setup.sh
```

Ye script kya karti hai:

- Termux packages install: `openjdk-17` (ya 21), `git`, `unzip`, `xz-utils`, `apksigner`
- **arm64 ke liye patched Android build-tools** install karti hai
  (AndroidIDE `androidide-tools` se: `aapt2`, `zipalign`, `d8`, `aidl`) — kyunki Google ka
  official SDK **x86_64** binaries deta hai jo phone ke CPU par chalti hi nahi.
  Yehi woh cheez hai jo "Termux par Android build nahi hota" ki mashhoor baat ko todti hai.
- `platforms;android-34` Google se (wo arch-independent hai)
- `~/.gradle/gradle.properties` me likhti hai:
  `android.aapt2FromMavenOverride=…` (taake Gradle x86 wala aapt2 download karke execute karne ki koshish na kare),
  `org.gradle.daemon=false`, `org.gradle.vfs.watch=false`
- `local.properties` me `sdk.dir` set karti hai (ye file commit nahi hoti)
- Sab verify karke `✓/✗` report deti hai

~180MB download. Run ke baad **naya Termux session** kholein (ya `source ~/.bashrc`).

> Kuch bhi shak ho to: `bash scripts/phone-setup.sh --check`

---

## 4. Apni signing key (ek hi baar, 1 minute)

```bash
bash scripts/make-keystore.sh
```

- `fawads-release.keystore` + `keystore.properties` banegi (dono `.gitignore` me hain — **commit na karein**)
- **Backup lein** (Drive/email par copy). Key kho gayi = purane installs par update install nahi hoga,
  logon ko uninstall + dobara install karna parega.
- Ye key *security* ke liye nahi hai, *pehchaan* ke liye hai: jise same key se sign kiya gaya,
  wahi "update" ban sakta hai — bilkul Play Store ki tarah.

Skip bhi kar sakte hain: phir release build **debug key** se sign hogi (install sab par hota hai,
bas Play Store upload aur "same app ka update" nahi chalega).

---

## 5. APK build karein

```bash
bash scripts/phone-build.sh            # signed, universal, minified release APK
```

Pehli baar: **15–40 minute** (Gradle + dependencies download). Uske baad 3–8 minute.

Output:

```
/sdcard/Download/FawadsAI-latest.apk          ← hamesha latest
/sdcard/Download/FawadsAI-v1.1.0-1.apk        ← version ke naam se
Fawads-AI/dist/release.json                    ← auto-update feed file
```

Doosre options:

| Command | Kya |
|---|---|
| `bash scripts/phone-build.sh --debug` | Fast debug APK (minify off, `.debug` package name → purane ke sath coexist karta hai) |
| `bash scripts/phone-build.sh --both` | Debug + release dono |
| `bash scripts/phone-build.sh --bump` | `VERSION_CODE` +1 karke build (update bhejne se pehle ye karein) |
| `bash scripts/phone-build.sh --play` | Release APK + `.aab` (Play Console ke liye) |
| `bash scripts/phone-build.sh --low-memory` | 4GB-RAM phones ke liye chhota heap |
| `bash scripts/phone-build.sh doctor` | Environment check, build nahi |

---

## 6. Install (aap ke phone par)

1. **Files** app → `Download` → `FawadsAI-latest.apk` tap karein
2. "Install unknown apps" → **Fawad's AI / Files app ke liye Allow** → wapas aakar install
3. Open → permissions (Mic, Notifications, Phone, Contacts, Overlay, Accessibility) → **Allow all**
4. Settings → **Gemini API Key** paste karein (https://aistudio.google.com/apikey) → app restart

> Kisi app ko "unknown sources" se install karne ki permission sirf **pehli baar** milti hai;
> ye normal hai, phone kharab nahi hai.

## 7. Doston ke phones par

APK file bhej dein — WhatsApp / Telegram / Google Drive / ShareIt, jo ho:

- `FawadsAI-latest.apk` bhejein (Play Store ki tarah link nahi, **file** bhejna hai —
  WhatsApp me "Document" option se bhejein, "Gallery" se nahi)
- Samne wala phone bhi **Android 8+ (arm64)** ho to chal jayegi. Purane 32-bit phones
  (Android 5/6) par nahi chalegi — unke liye `minSdk` aur compile path alag chahiye.
- Aap ki APK **har device par install ho sakti hai**: koi device-ID lock, koi license check nahi.

---

## 8. Aage updates kaise jayenge (Play Store jaisa)

Do raste:

**A) In-app auto-update (recommended, free)** — app khud check karti hai, download karti hai,
installer khol deti hai. Setup: `docs/AUTO-UPDATES.md`

**B) Real Play Store** — `bash scripts/phone-build.sh --play` se `.aab` banayein, Play Console
(browser me: https://play.google.com/console) se upload. Ek baar ka **$25**. Detail: `docs/PLAYSTORE.md`

Naya version bhejne ka routine:

```bash
cd ~/Fawads-AI
git pull                     # nayi changes
bash scripts/phone-build.sh --bump   # versionCode +1, build, dist/ + Download/ update
# ab APK + dist/release.json upload karein (docs/AUTO-UPDATES.md)
```

`versionCode` barhana **zaroori** hai — warna Android "pehle se install hai" karke update
reply kar dega.

---

## 9. Agar build atke / fail ho

| Error | Fix |
|---|---|
| `cannot execute binary file: Exec format error` | arm64 build-tools nahi lage → `bash scripts/phone-setup.sh` dobara |
| `AAPT2 ... daemon startup failed` | `~/.gradle/gradle.properties` me `android.aapt2FromMavenOverride` line check karein (phone-setup likh deta hai) |
| Gradle `Killed` / `OutOfMemory` | `--low-memory`, aur background apps band; charger par rakhein |
| `SDK location not found` | `local.properties` me `sdk.dir=/data/data/com.termux/files/home/android-sdk` |
| `Failed to install the following Android SDK packages` (licenses) | `yes \| sdkmanager --licenses` |
| Download bahut slow | Termux me `termux-change-repo` → speedtest se best mirror chunein |
| Kya chal raha hai dekhna hai | `tail -f dist/build.log` |
| Sab theek hai ya nahi | `bash scripts/phone-build.sh doctor` |

Full log hamesha: `Fawads-AI/dist/build.log`.
