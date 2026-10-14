# 88.8FM for Android

> There's also a native **Windows** app in [`windows/`](windows/README.md).

A personal native Android client for [thepowerstone.com](https://thepowerstone.com), the 88.8FM
Knxwledge catalogue. It talks to the same JSON API the website uses. You sign in with your own
account, and the server decides what you can hear: guests get the 28-second previews, and
subscribers get full tracks.

## Features

These are ported from the website's own code.

**Catalogue**
- Cover grid or detailed list, grouped into Released and Unreleased / Rare trax.
- Filter by series (WT, HX, VGM, ガムビー, 家, other), by "has music video", and by genre. Sort by
  newest, oldest or A–Z.
- Search covers both releases and individual songs; tap a matching song to play it.
- **BPM browser** (the site's テンポ view): every track sorted by tempo, with a BPM range slider
  and a major/minor key filter. You can play the whole selection.
- Tags: tap a tag to get every track carrying it.

**Playback** (Media3 / ExoPlayer, runs in the background with lock-screen, notification and
headset controls)
- **Smart shuffle**: random songs from across the whole catalogue, picking ones you haven't heard
  yet until you've heard everything. It never runs out.
- When an album finishes, playback rolls straight into the next one, as on the site.
- **Repeat 1** and **Repeat tape** (loop the whole album or playlist), for subscribers.
- **3-band DJ EQ**: LO / MID / HI at 0–150%, with KILL and ISO (solo) per band. It uses the
  site's crossovers (4th-order Linkwitz–Riley at 250 Hz and 4 kHz), implemented as a custom
  ExoPlayer audio processor.
- **Vinyl stop**: pausing winds down like a turntable losing power, and playing spins back up.
  Off / short / medium / long. It works from the lock screen and headphones too.
- **Music videos** (subscribers): muted and kept in sync with the audio.
- **Visualizer**: a live spectrum of the audio after the EQ, with four styles (tap to switch).
- Audio quality: Auto (by connection), 320, 160 or 64 kbps.

**Home-screen widget, sleep timer, resume**
- **Widget** (styled after the site: monospace, 88.8 red, FM radio):
  - **Sizes:**
    - **4×1 row:** art, title, a level meter, previous/play/next and a progress hairline.
    - **Card:** adds an ON AIR lamp, a ticking timer with the sleep countdown, and an FM tuner strip.
    - **Big card:** the cover becomes the label of a spinning record, ringed by the track's
      progress, with "next ›", favourite and shuffle.
  - **Motion** (only while playing):
    - The tuner needle sweeps in and locks onto 88.8 on every new track, and whenever you come
      back to the home screen.
    - The record spins up like a turntable, then holds 33⅓ rpm.
    - The level meter bounces at about the track's BPM.
    - On Android 12+, play/pause morph and the heart pops the instant you tap them.
  - **Colour:** the card takes its colour from the cover.
  - **When the app is closed:** it shows the last track (dimmed) and today's listening, and the
    needle parks off-station.
  - **Per-widget settings:** long-press → settings to choose record or cover, motion on or still,
    and cover colour or classic black.
  - **Battery and data:** the animations are vectors the launcher runs. Covers reach the launcher
    as content:// links, so the once-a-second progress refresh (only with the screen on and music
    playing) sends a few hundred bytes.
  - `WidgetRenderTest` draws every state to `app/build/widget-shots/`, and CI uploads them as
    **widget-renders**.
- **Sleep timer** (moon button on the player): 15–90 minutes or the end of the current track. It
  pauses with the vinyl stop.
- **Up next**: the queue on the player screen. Tap a track to jump to it.
- **Resume where you left off**: the queue and position are saved and come back paused on the next
  launch. Bluetooth play buttons and Android's media resumption can also restart them.

**Offline, crossfade, lock screen**
- **Downloads** (subscribers): ↓ on a release, a playlist or favourites keeps the tracks on the
  phone, and they play from there instead of streaming.
  - Stored in the app's private storage, not as loose MP3s.
  - Playable offline for 30 days after the subscription was last confirmed.
  - Deleted on sign-out or when the site says the account isn't subscribed.
  - A failed network check never deletes anything.
  - Account → Downloads shows the space used and has *Remove all*.
- **Crossfade** (Sound: off / 2–8 s): fades the end of one track out and the next one in, in
  shuffles and playlists. Albums stay gapless. Android's player can't overlap two tracks, so this
  is a fade-out and fade-in rather than a true overlap (the Windows app does a true overlap).
- **Lock screen**: the media controls (lock screen, notification, quick settings) get ♥ favourite
  and shuffle-everything buttons. Tapping the notification on the lock screen opens a full
  now-playing screen over it, without unlocking.

**Playlists & sharing**
- Create, rename, reorder, remove tracks and delete playlists. They sync with your account (the
  site's `/api/playlists`, including deletions). Playlists made while signed out are merged in
  when you sign in.
- Share releases, tracks, tags and playlists as thepowerstone.com links. A shared playlist is
  published through `/api/share`.
- **Links open in the app**: `?r=` (release), `&t=` (track), `?s=` (shared playlist, with
  "Save to my playlists"), `?tag=`, `?me` and `?wrap=<year>`.

**MY 88.8**
- Favourites, synced with the hearts on the site.
- History, grouped into listening sessions, with how many times you've played each track this
  year.
- Stats for this month and for any year (the yearly wrap-up): minutes, plays, top tracks, top
  releases and top tags.
- Signed out, history and stats come from what you've played on this device.

**Account**
- Sign in with an emailed 6-digit code or a password; you stay signed in. You can make or change
  a password.
- Subscribe monthly or yearly, or manage your subscription. These open Stripe in your browser,
  and the app picks up the change when you come back.

Plays count after 10 seconds of listening and are sent to your account, so the site's stats
include what you play here.

Not ported: Google/Apple/Discord/Twitch sign-in (the email code covers any account), and the
owner-only tools (catalogue editor, friends & family list, subscriber admin).

### Opening links in the app

The site doesn't publish a verified app-link file, so Android won't send its links here
automatically. To turn this on: **Settings → Apps → 88.8FM → Open by default → Add link**, then
tick `thepowerstone.com`.

## Build & install

You need JDK 17 or newer and the Android SDK (Android Studio installs both).

```sh
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app needs Android 8.0 (API 26) or newer.

## Layout

```
app/src/main/java/com/st000ps/powerstone/
  PowerstoneApp.kt            app singleton: API, settings, playlists, catalogue, account
  data/Api.kt                 thepowerstone.com endpoints
  data/PlaylistStore.kt       playlist sync (local file + /api/playlists)
  data/ListeningHistory.kt    on-device play log + session/stats math
  data/Settings.kt            EQ, vinyl stop, quality, smart-shuffle memory
  audio/DjEqProcessor.kt      3-band crossover EQ + vinyl envelope (ExoPlayer AudioProcessor)
  audio/Spectrum.kt           FFT for the visualizer
  audio/VinylPlayer.kt        turntable stop / spin-up on every pause/play
  playback/PlaybackService.kt MediaSessionService: streaming, play tracking, queue auto-extend
  playback/Queue.kt           queue building, next-album order, smart shuffle
  ui/                         Jetpack Compose screens
```
