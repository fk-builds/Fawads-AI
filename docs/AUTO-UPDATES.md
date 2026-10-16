# In-app auto-update (Play Store jaisa, bina Play Store)

App khud check karti hai ke naya version hai ya nahi → user tap karta hai → APK download →
system installer khulta hai. Koi PC nahi, koi Play account nahi, **$0**.

## Kya chahiye

Sirf ek **static HTTPS hosting** jahan do files pari rahein:

```
https://<aap-ka-host>/fawads/release.json          ← app isi ko padhti hai
https://<aap-ka-host>/fawads/FawadsAI-latest.apk   ← actual APK
```

Host kuch bhi ho sakta hai — GitHub **Actions ke bagair** (sirf file storage), ya:

| Host | Kaise | Note |
|---|---|---|
| GitHub Release (manual upload) | Browser → repo → Releases → *Draft a new release* → APK + `release.json` **drag & drop** → publish. Asset ka direct link `https://github.com/<user>/<repo>/releases/download/v1.1.0/FawadsAI-latest.apk` | mobile browser se bhi ho jata hai; **build GitHub par nahi ho raha**, bas file pari hai |
| Cloudflare R2 / Pages | file upload | free tier kaafi, fast, direct link |
| Netlify Drop | app.netlify.com/drop → folder drag | https link foran mil jata hai |
| Aap ka VPS / shared hosting | `scp`/cPanel file manager | sabse simple |
| Dropbox | share link me `www` → `dl` ya `?dl=1` | `release.json` ke liye thora santra: content-type; phir bhi chal jata hai |
| Google Drive | ❌ | direct download link nahi deta, updater fail hoga |

## `release.json` format

Ye file `scripts/phone-build.sh` apne aap bana deti hai (`dist/release.json`). Haath se bhi likh sakte hain:

```json
{
  "versionCode": 4,
  "versionName": "1.1.3",
  "apkUrl": "https://files.example.com/fawads/FawadsAI-latest.apk",
  "fileName": "FawadsAI-latest.apk",
  "sizeBytes": 14233120,
  "sha256": "…64 hex chars…",
  "minSdkVersion": 26,
  "forceUpdate": false,
  "notes": "Alarm bug fix, naye voices, faster reconnect."
}
```

| Field | Kaam |
|---|---|
| `versionCode` | **Number**. Isko app ke apne versionCode se barhna chahiye warna "latest ho" ka message |
| `versionName` | User ko dikhne wala label |
| `apkUrl` | Direct download link (redirect chalega, HTML page nahi) |
| `sizeBytes` | Progress bar + corruption check |
| `sha256` | Download ki ghair-tamimi/total pakad — khaali chhorne se skip ho jata hai |
| `forceUpdate` | `true` = dialog dismiss nahi hoga (app kharab hone par) |
| `notes` | "What's new" line |

Na banaein to: `APK_BASE_URL=https://files.example.com/fawads bash scripts/make-update-feed.sh dist/FawadsAI-latest.apk dist/release.json`

## App me enable karna (har phone par ek baar)

**Settings → Updates → Update feed URL** me `release.json` ka poora URL daalein →
**CHECK FOR UPDATES** → *Saved* me SAVE.

- URL khaali = updater **oor** (default). App kahin bhi phone nahi karti — privacy ke liye yunhi chhoda hai.
- **Auto-check (12 hr)** switch: app khule waqt chupke se check karti hai (throttle ke sath).
- **Skip** dabane se wohi versionCode ignore hota hai; nayi release aane par phir poochhega.
- Pehli baar install ke liye phone **"Install unknown apps"** permission maangega → Allow.

## Test kaise karein

```bash
# 1) ek chhota local server (phone par Termux me bhi chalega)
cd ~/Fawads-AI/dist
python3 -m http.server 8000      # Termux me: pkg install python

# 2) release.json ka versionCode, installed app se zyada rakhein
# 3) phone ke browser me: http://<PC-ka-IP>:8000/release.json  ← 200 aana chahiye
```
Debug build cleartext `http://` chala sakta hai (network security config me debug-overrides hain);
**release build sirf https://** leega, is liye production host par https zaroori hai.

## Release ka poora routine (phone par)

```bash
cd ~/Fawads-AI
git pull
bash scripts/phone-build.sh --bump
# ab dist/FawadsAI-latest.apk (ya /sdcard/Download/FawadsAI-latest.apk)
# + dist/release.json  host par upload karein  — bas ho gaya
```

## Security notes (aap ke users ke liye)

- `sha256` **hamesha** bhar dein: attacker APK file badal de to hash match nahi karegi aur install ruk jayega.
- HTTPS hi use karein; `apkUrl` par MITM bacha hua khatra hai warna.
- Signing key (`fawads-release.keystore`) kabhi host/cloud par publicly na daalein —
  jis ke paas key hai woh aap ke users ko "update" bhej sakta hai.
- Ye tarah **sideload** hai: phone "unknown sources" warning dikhayega. Ye theek hai,
  lekin users ko pehle se bata dein.
