# Touch Grass 🦗🌱
### An Android app that forcibly kicks you out of Instagram after 30 minutes, because you clearly can't be trusted.

---

## What it does

Lockout runs as a background service and monitors which app you're using. Once you've hit your daily time limit for a tracked app, it throws a wall in your face and traps the back button. The only way out is the home button. Come back tomorrow.

It resets at midnight. No exceptions. No "just five more minutes."

---

## Tracked apps & limits

Out of the box, Touch Grass watches these apps:

| App | Daily Limit |
|-----|-------------|
| WhatsApp | 200 minutes |
| Instagram | 30 minutes |
| Webtoon | 25 minutes |
| YouTube | 15 minutes |

---

## The data is hardcoded. On purpose.

Yes, the app limits and package names are hardcoded in the source. You'll need to edit them yourself before building:

- **`BlockerService.kt`** — the `appLimits` map is what actually enforces the limits
- **`MainActivity.kt`** — has its own copy of `appLimits` used only for displaying limits in the UI

Just swap in your own package names and limits:

```kotlin
private val appLimits = mapOf(
    "com.whatsapp"          to 200L,  // change these
    "com.instagram.android" to 30L,
    "com.your.app.here"     to 20L    // add your own
)
```

To find an app's package name, look it up on the Play Store — it's in the URL: `https://play.google.com/store/apps/details?id=com.package.name.here`

---

## Why not just add a settings screen?

I thought about it. Then I remembered that if I could change the timer, I would change the timer. The whole point is that you *can't* easily undo it. Hardcoding is a feature, not a bug — it adds just enough friction to make cheating annoying.

If you want to change your limits, you have to pull up Android Studio, edit the code, and rebuild. By that point, the urge to doomscroll has probably passed.

---

## Permissions required

- **Usage Access** — to detect which app is in the foreground
- **Display over other apps** — to show the blocker screen on top
- **Notifications** — to keep the background service alive

---

## Building & running

1. Clone the repo
2. Open in Android Studio
3. Edit `appLimits` in both `BlockerService.kt` and `MainActivity.kt` to match your needs
4. Build & install on your device
5. Grant all three permissions when prompted
6. Try to open Instagram. See what happens.
