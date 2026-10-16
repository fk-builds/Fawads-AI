# Play Store par daalna (agar wohi chahiye)

Aap ne kaha "jesy playstore pr hota hy" — do cheezein alag hain:

1. **Play Store jaisa behave kare** (auto-update, har phone par install, signed) →
   ye repo ab **in-app updater** se de rahi hai: `docs/AUTO-UPDATES.md`. Free, foran, bina review.
2. **Asli Google Play Store** par publish karna → neeche.

## Asli Play Store ke liye checklist

| Step | Kya karna hai |
|---|---|
| 1 | https://play.google.com/console → developer account → **one-time $25** (credit card). Phone ke browser se bhi ho jata hai (Desktop mode on karein). |
| 2 | Ek **public HTTPS hosting** ya app ke andar hi **Play App Signing** use karein (recommended: Google key manage karta hai, aap ki upload key kho bhi jaye to bach jate hain) |
| 3 | `bash scripts/phone-build.sh --play` → `FawadsAI-v<ver>.aab` banti hai (`.apk` nahi — Play AAB maangta hai) |
| 4 | Console → *Create app* → store listing (icon, 2 phone screenshots, full description, privacy policy URL) |
| 5 | **App content** form: App access, Ads=No, **Data safety** (neeche dekh lein), Target audience |
| 6 | **Testing → Internal testing** track → AAB upload (Play Console browser me "Manage released app" → *Create new release*) → testers ka email list → link share |
| 7 | 12 testers, 14 din internal testing ke baad production (closed → open → production) |

## Is app ke liye Google ke saath masla ho sakta hai — pehle se jaan lein

Google ki policy review in 3 cheezon par sakht hai:

- **Accessibility Service** (`AccessibilityHelperService`) — Google "accessibility = sirf
  accessibility ke liye" policy lagata hai. App-control (apps open/close karna) ke liye use hone
  par **rejection common** hai. Agar reject kare to `ACCESSIBILITY` feature ko hatakar
  "device admin"-free version bhejna parta hai, ya sirf sideload hi rehna parta hai.
- **Call log / ANSWER_PHONE_CALLS / incoming-call handling** — sensitive permission; declaration
  form bharna parta hai ("app ki core functionality ke liye zaroori").
- **`QUERY_ALL_PACKAGES`-jaisa behaviour** (apps list kholna) — yahan package names hardcoded
  hain, is liye aam tor par theek hai; phir bhi "declared permissions" form me wajah likhni paregi.

Sath hi:

- **Privacy policy URL** lazmi hai (koi bhi static page chalega — GitHub Pages/Netlify par ek
  paragraph bhi kaafi hai) jis me likha ho ke Gemini API key device par rehti hai aur audio
  Google ke paas jata hai.
- **Data safety form**: audio + API key = "data collected" declare karna parega. Yahan key
  EncryptedSharedPreferences me hai aur backup se **exclude** ki ja chuki hai
  (`res/xml/data_extraction_rules.xml`) — form me wahi likhein.
- `targetSdk` har saal barhana parta hai (abhi 34 = theek hai; 2026 ke rules ke tehat 35 chahiye
  ho to `compileSdk`/`targetSdk` barha kar `buildToolsVersion '34.0.4'` line bhi match karein).

## Play App Signing ke sath apni key ka role

- `scripts/make-keystore.sh` se bani key = **upload key**. Play Console me "Upload key certificate"
  ke tor par use hogi.
- Play ki **app signing key** Google ke paas rehti hai — is liye update hamesha same key se sign
  hone ka masla nahi hota.
- Agar aap Play ke bagair (sirf sideload) distribution karte hain to **apni key hi sab kuch hai**:
  usi se sign kiye APKs hi update kar sakte hain. Is liye backup lena lazmi hai.

## Play ke bagair "Play jaisa" rakhne ka summary

- Signed universal release APK/AAB ✅ (`phone-build.sh`)
- Auto-update, install progress, "skip this version" ✅ (`UpdateManager`)
- Har device par install, koi lock nahi ✅
- New phone par settings restore (Google One backup) ✅ (`backup_rules.xml`)
- $0, koi review, koi wait ❌→✅ (ye Play ka masla hi hai)
