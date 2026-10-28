# ROXXY AI

Native Android AI companion and OS controller - Hinglish, voice-first, with real
on-device screen control. Built from the "ROXXY AI master blueprint".

**Package:** `com.rout.roxxy` | **Min Android:** 8.0 (API 26) | **Stack:** Kotlin + Jetpack Compose, AccessibilityService, NotificationListenerService, Foreground overlay, Gemini API.

## Get started

1. Install the APK from the Releases page.
2. Open ROXXY, go to **Setup** and grant what you want to use (Accessibility is the big one - it gives ROXXY eyes and hands on your screen).
3. In **Settings**, paste your own free Gemini API key from https://aistudio.google.com/apikey - it is stored encrypted on your phone and never leaves it except to call Google.
4. Chat, tap the mic, or turn on the floating orb.

## What genuinely works

- **Hinglish AI chat + voice** - Gemini 2.5 Flash (configurable) with the ROXXY "Boss" persona; text and tap-to-talk voice in, natural TTS voice out.
- **Keyless local commands** (no API key needed): time, date, battery, torch on/off, volume, open apps by name.
- **Floating orb (JARVIS HUD)** over other apps: tap to talk, long-press to scan your current screen with vision.
- **Real screen control** via Accessibility: click by text, long-press, tap coordinates, drag, pinch, scroll, type into fields, wait-for-element with auto-scroll retry, global actions (home/back/recents/notifications/quick settings/lock/split-screen/screenshot), live screenshot vision (Android 11+).
- **Notification reader + reply**: sees recent notifications; can reply to the latest one that has a reply action; optional **Ghost auto-reply** for WhatsApp (OFF by default, 60s per-sender rate limit, your own message text).
- **Siri/Bixby actions**: alarms, timers, direct calls (or dialer), WhatsApp message send with auto-tap on Send, media play/pause/next, YouTube/Spotify search-play, UPI apps (GPay/PhonePe/Paytm) launch + scanner tap, flashlight, volume, brightness, DND, wifi/bluetooth/hotspot panels, clipboard copy.
- **Routines/macros** - the brain chains 3-5 actions for things like "good night mode".
- **Camera vision** - snap a photo in the chat tab and ROXXY describes it ("dekho mere samne kya hai").
- **Screen summarizer** - long-press the orb or ask "ye screen summarize karo".

## Honest limits (not fake, read this)

- **No hosted Node.js backend.** The blueprint's Express server is replaced by direct
  on-device Gemini calls, because your key then stays on your phone and there is no
  server to run or pay for. Every blueprint brain feature works on-device.
  `server/` contains the original server as an optional reference if you ever want to self-host.
- **"Hey Roxxy" wake word is experimental.** It works while the orb service is running,
  using repeated short listening windows - it costs battery, shows the mic indicator,
  and aggressive OEM battery managers (Vivo/Oppo/Xiaomi) may kill it. There is no true
  always-on low-power DSP wake word in a sideloaded app; that needs OS-level support.
- **No continuous live camera tracking.** Camera vision is on-demand (you ask, it looks).
- **Deep in-app automation (tap inside WhatsApp/Instagram) is best-effort.** It depends on
  each app's current UI and Accessibility being ON. Apps change layouts; chains can miss.
- **Proactive monitoring** is limited to live telemetry (battery, foreground app,
  notifications) being included in every brain request. ROXXY does not yet interrupt you
  on her own.
- Untested on real hardware in CI - verified by build, lint, unit tests, and APK
  signature/manifest checks. Report issues and they will be fixed.

## Permissions used

INTERNET, RECORD_AUDIO, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE(+MICROPHONE),
POST_NOTIFICATIONS, CALL_PHONE, READ_CONTACTS, CAMERA, MODIFY_AUDIO_SETTINGS,
ACCESS_NOTIFICATION_POLICY, WRITE_SETTINGS, SET_ALARM, QUERY_ALL_PACKAGES,
AccessibilityService, NotificationListenerService.
All are requested at runtime / through Android settings screens; nothing runs until you grant it.

## Build

```
./gradlew assembleDebug    # debug APK
./gradlew testDebugUnitTest
```

## Privacy

Your Gemini key lives only in encrypted on-device storage. Screen contents, clipboard
snippets and notification text are sent to Google's Gemini API only when you actively
ask ROXXY something (that is how the AI works), and nowhere else. Ghost replies are
sent only to the chat that messaged you, only when you turned Ghost mode on.
