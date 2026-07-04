# Blackjack Overlay

An Android app that floats a **draggable popup** over any other app (a blackjack game/table),
captures what's on screen **only when you tap "Calculate Now"**, sends that one frame to
Claude's vision model, and shows the mathematically optimal basic-strategy play.

The manual button is deliberate: no polling, no background frames, no per-second API calls.
**One tap = exactly one API request**, which keeps token spend (and cost) fully under your control.

## How it works

```
MainActivity ──grants──►  • "Display over other apps" (SYSTEM_ALERT_WINDOW)
                          • Screen capture token (MediaProjection)
                                  │
                                  ▼
OverlayService (foreground) ── draws the draggable popup
        │                         └─ "Calculate Now" button
        ├─ ScreenCaptureManager ── mirrors the screen into an ImageReader
        └─ on tap ─────────────► grab 1 frame → JPEG → base64
                                        │
                                        ▼
                          BlackjackAdvisor → Claude vision API → advice text
```

Recommended flow at the table: let the dealer finish dealing the initial cards, **then** tap
**Calculate Now**. The overlay hides itself for one frame before capturing, so it never appears
in the screenshot it analyses.

## The model

You asked for **Sonnet 3.5** to keep credit spend low. Heads up: `claude-3-5-sonnet` was
**retired on 2025-10-28** and now returns a 404, so it can't be used.

This app defaults to **`claude-haiku-4-5`** instead — the cheapest *current* Claude model with
vision (**$1 / $5** per 1M input/output tokens), and more than capable of reading a blackjack
table. With a ~70%-quality JPEG and a 400-token cap, each tap costs a fraction of a cent.

To change models, edit one line in
[`BlackjackAdvisor.kt`](app/src/main/java/com/vortextrade/blackjackoverlay/BlackjackAdvisor.kt):

```kotlin
const val MODEL = "claude-haiku-4-5"   // → "claude-sonnet-5" for stronger reasoning
```

## Install the prebuilt APK (no Android Studio needed)

Every push to the feature branch builds an installable **debug APK** via GitHub Actions
(`.github/workflows/build-apk.yml`) and publishes it to the **`debug-latest`** release.

1. Go to the repo's **Releases** page → **Debug APK (latest)** → download `app-debug.apk`.
   (Or: **Actions** tab → latest "Build APK" run → **Artifacts** → `blackjack-overlay-debug-apk`.)
2. Copy `app-debug.apk` to your Android phone and open it. Allow **"install from unknown
   sources"** when prompted.
3. Open the app, paste your **Anthropic API key**, tap **Save API Key**.
4. **Start Overlay** → grant "Display over other apps" → press Start again → approve the
   screen-capture prompt. The floating advisor appears.
5. Drag it anywhere. When cards are dealt, tap **Calculate Now**.

The API key is entered **in the app** and stored on-device, so the same prebuilt APK works
for anyone — nothing is hardcoded into the binary.

## Building it yourself

You don't need Android Studio — any machine with a JDK works:

```bash
# Requires the Android SDK; on CI the runner already has it.
gradle :app:assembleDebug          # output: app/build/outputs/apk/debug/app-debug.apk
```

If you'd rather bake the key in at build time instead of typing it in the app, put it in
`local.properties` (copy from `local.properties.example`) — it's git-ignored and injected into
`BuildConfig` at build time. The in-app key always takes precedence if both are set.

## Security & scope notes

- **API key on-device:** Embedding an API key in a mobile app is inherently exposable — anyone
  with the APK can extract it. For a personal tool this is acceptable; for anything shared,
  put the key behind a small backend proxy and have the app call that instead.
- **Screen capture stays local** except for the single frame you explicitly send to Anthropic
  on each tap.
- **Use responsibly.** Basic-strategy advice is educational and legal, but many casinos and
  online platforms prohibit real-time assistance devices at the table. Check the rules that
  apply to you before using it anywhere real money is involved.

## Project layout

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Collects overlay + screen-capture permissions, starts the service |
| `OverlayService.kt` | Foreground service: draggable window, capture pipeline, Calculate flow |
| `ScreenCaptureManager.kt` | MediaProjection → ImageReader → single-frame `Bitmap` |
| `BlackjackAdvisor.kt` | Builds the base64 vision request and calls the Claude API |
| `res/layout/overlay_view.xml` | The floating popup UI |
