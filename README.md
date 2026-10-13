# 88.8FM for Android

A personal native Android client for [thepowerstone.com](https://thepowerstone.com), the 88.8FM
Knxwledge catalogue. It talks to the same JSON API the website uses. You sign in with your own
account, and the server decides what you can hear: guests get the 28-second previews, and
subscribers get full tracks.

## Features

- **Catalogue**: every release in a cover grid, with search across release and track titles.
- **Release pages**: track list with BPM and key, Play and Shuffle buttons, and a Bandcamp link.
- **Background playback** (Media3 / ExoPlayer): notification, lock-screen, headset and Bluetooth
  controls. Playback pauses when headphones are unplugged.
- **Now playing**: seek bar, previous/next, shuffle, and repeat (all / one).
- **Sign in** with a 6-digit email code (the same flow as the site) or a password. The session is
  remembered between launches.
- **Favourites**: synced with your favourites on the site.
- **Stream quality** for subscribers: High (320), Standard (160) or Data saver (64).
- **Listening history**: a play counts after 10 seconds, the same rule the site uses, and is sent
  to the site so your stats there stay accurate.
- The catalogue is cached on the device, so the app opens instantly and you can browse it offline.

Google/Apple/Discord/Twitch sign-in isn't supported. Use the email code instead; it works for any
account with an email address.

## Build & install

You need JDK 17 or newer and the Android SDK (Android Studio installs both).

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You can also open the folder in Android Studio and press Run. The app needs Android 8.0 (API 26)
or newer.

## Layout

```
app/src/main/java/com/st000ps/powerstone/
  PowerstoneApp.kt          app singleton: API client + settings
  data/Api.kt               thepowerstone.com endpoints (catalog, me, login, favs, plays)
  data/PersistentCookieJar  keeps the session cookie across restarts
  data/Models.kt            JSON models + preview/full stream selection
  playback/PlaybackService  MediaSessionService: ExoPlayer streaming with the session cookie
  ui/                       Jetpack Compose screens, theme, view model
```
