# Android Foodly (standalone, no SDK)

A real native Android (Kotlin) commerce demo — sign in, products, favourites, cart, checkout — mirroring
`examples/flutter-foodly`'s flow and sharing its backend contract. One `MainActivity` switches screens
with programmatic Views; networking is `HttpURLConnection`, favourites are `SharedPreferences`.

**It contains no Operator SDK.** It is the "customer app that never integrated" case: the watchdog build
adds the SDK automatically (on Android the SDK self-installs via a `ContentProvider`, so just the Gradle
dependency), and this clean app is what proves native auto-inject end to end.

## Run it

```bash
# 1. the shared backend (same contract as every Foodly demo), reachable from the emulator at 10.0.2.2
PORT=8787 node ../foodly-backend/server.mjs

# 2. build + install
ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p ai.huntingdogs.foodly -c android.intent.category.LAUNCHER 1
```

The app talks to `http://10.0.2.2:8787` by default (the emulator's route to the host); change
`FoodlyApi.base` to point elsewhere.

## The flow

Sign in (any email with `@` + a 4+ char password) → Home (search, Favourites kept **on the device** in
`SharedPreferences`, the menu with a heart + Add per product) → Cart → Checkout (name, phone, address,
promo `SAVE10`) → Order confirmed.
