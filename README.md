# Chaman Browser

A small Android browser built on the system WebView (Chromium). Kotlin, no heavy dependencies.

## Design rule

- **Ad blocker: always on.** There is no switch for it.
- **Everything else is a toggle in Settings, and every toggle starts OFF.**

## Features

| Area | What it does |
|------|--------------|
| Browsing | Address bar with search, multiple tabs, back/forward/reload, progress bar, fullscreen video, file upload, downloads |
| Data | Bookmarks and history (JSON files), long-press on links and images, clear browsing data |
| Ad blocker | Domain block list + URL patterns checked on every sub-resource request, plus a small cosmetic stylesheet |
| Settings (all default off) | Private mode, block all cookies, block third-party cookies, Safe Browsing, HTTPS-only, block JavaScript, block images, allow pop-ups, dark mode, desktop site |
| Settings (values) | Search engine (default DuckDuckGo), home page (empty = Chaman start page) |

## How the control works

All control comes from hooks the WebView gives the app:

- `WebViewClient.shouldInterceptRequest` runs for every request before it leaves the browser. The ad blocker returns an empty response for blocked hosts (see `AdBlocker.kt`).
- `WebViewClient.shouldOverrideUrlLoading` decides link navigation: HTTPS upgrade, internal `chaman://` links, opening other apps only after a real tap.
- `WebSettings` and `CookieManager` carry the toggles (JavaScript, images, user agent, cookies, cache mode, Safe Browsing, pop-ups).
- `WebChromeClient` handles progress, titles, fullscreen video, file chooser and pop-up windows.
- Page-level scripts are injected with `evaluateJavascript` (cosmetic ad hiding).

## Build with GitHub Actions

1. Create a GitHub repo and push this folder to the `main` branch.
2. Open the **Actions** tab. The workflow `Build Chaman Browser APK` runs on every push.
3. When it finishes, download `Chaman-Browser` from the run's **Artifacts**.
4. For a proper release, push a tag: `git tag v0.4.0 && git push origin v0.4.0`. The APK is attached to a GitHub Release automatically.

During the build the workflow downloads Peter Lowe's public ad server list and appends it to `blocklist.txt`. If the download fails, the bundled list is used.

### Stable signing key (recommended)

Without a key, each build is signed with a new debug key, so a new APK cannot update an older install (uninstall first). To fix that, create a keystore once and add these repository secrets:

```
keytool -genkeypair -v -keystore chaman.jks -alias chaman -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 chaman.jks     # paste the output into KEYSTORE_BASE64
```

Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Never commit the `.jks` file.

## Build locally

Android Studio (Koala or newer) opens the folder directly. From a terminal with Gradle 8.9 and JDK 17: `gradle assembleRelease`.

## Known limits

- No Chrome extensions (WebView does not support them). GeckoView would be the path for that.
- Private mode is app-level: history and cache are skipped and data is wiped when the app closes normally.
- Camera, microphone and location requests from sites are denied for now.
- `blob:` and `data:` downloads are not supported yet.
- Ads served from the same domain as the page (for example inside video streams) cannot be blocked this way.
- The WebView itself updates through the Play Store, not through this app.

## Project layout

```
app/src/main/java/com/chaman/browser/
  MainActivity.kt      tabs, address bar, WebView setup, menu, dialogs
  AdBlocker.kt         block list loading and request matching
  Prefs.kt             typed settings, defaults all off
  SettingsActivity.kt  settings screen (res/xml/preferences.xml)
  Store.kt             bookmarks and history
  Tab.kt               per-tab state
app/src/main/assets/   blocklist.txt, home.html (start page)
.github/workflows/     build.yml
```
# Chaman-Browser
