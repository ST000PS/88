# 88.8FM for Windows

A native Windows desktop app (C# / .NET 8 / WPF) for [thepowerstone.com](https://thepowerstone.com),
the 88.8FM Knxwledge catalogue. It's the same personal client as the Android app: you sign in with
your own account and the server decides what you can hear (28-second previews for guests, full
tracks for subscribers).

## Features

- **Catalogue**: cover grid or detailed list, grouped into Released and Unreleased / Rare trax.
  Filter by series (WT, HX, VGM, ガムビー, 家), by music video, and sort by newest, oldest or A–Z.
  Search covers releases and songs. **Paste a thepowerstone.com link** into search to open it.
- **BPM browser**: every track by tempo, with range sliders and a major/minor filter.
- **Playback**:
  - Gapless: the next track is pre-loaded and starts in the same audio buffer.
  - Smart shuffle: unheard songs first, and it never ends.
  - Albums roll into the next album.
  - Repeat 1 and Repeat tape (subscribers only).
- **3-band DJ EQ**: LO / MID / HI at 0–150%, KILL and ISO per band. It uses the site's crossovers
  (4th-order Linkwitz–Riley at 250 Hz and 4 kHz).
- **Vinyl stop**: pausing really winds the record down (speed and pitch fall together), and
  playing spins it back up. This is done in the audio stream.
- **Visualizer**: a live spectrum of the audio after the EQ. Click it to change style.
- **Windows media controls**: keyboard media keys, the volume flyout and lock-screen card, and
  Bluetooth headset buttons.
- **Playlists**: create, rename, reorder (right-click a track) and delete. They sync with your
  account, the same way as on the site and the Android app.
- **Share links**: copy links to releases, tracks, tags and playlists. Shared playlists open with
  "Save to my playlists".
- **MY 88.8**:
  - Favourites.
  - History grouped into listening sessions.
  - Stats for this month and any year, with the yearly wrap-up.
  - Signed out, history and stats come from this PC.
- **Account**:
  - Sign in with an email code or a password.
  - Make or change a password.
  - Subscribe or manage your subscription (opens Stripe in your browser).
  - Audio quality: Auto (by connection), 320, 160 or 64 kbps.
- **Play history**: plays are sent to your account (after 10 seconds of listening, as on the
  site), so stats stay in sync across the web, Android and Windows.

Keyboard: **Ctrl+Space** play/pause, **Ctrl+←/→** previous/next, **Alt+←** back.

Not included:
- Music videos. They need a video player that can send the session cookie; that's left for later.
- Google, Apple, Discord and Twitch sign-in. The email code works for any account.
- The site owner's admin tools.

## Get it

Every push to `main` and every PR builds `88.8FM.exe` on GitHub Actions. Open a run on the
**Actions** tab and download the **88.8FM-windows-x64** artifact. It's a single self-contained
file with nothing to install; it needs Windows 10 (2004) or newer.

The exe isn't code-signed, so the first time you run it SmartScreen may say "Windows protected
your PC". Click **More info → Run anyway**.

Your sign-in, settings, playlists cache and local history live in `%LOCALAPPDATA%\88.8FM`.

## Build it yourself

You need the .NET 8 SDK.

```sh
cd windows
dotnet test tests/Powerstone.Core.Tests
dotnet publish src/Powerstone.Windows -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o out
```

Or open `windows/Powerstone.sln` in Visual Studio 2022 and run **Powerstone.Windows**.

## Layout

```
windows/
  src/Powerstone.Core/        platform-neutral, unit-tested on any OS
    Api.cs                    thepowerstone.com endpoints + saved session cookie
    PlaylistStore.cs          playlist sync (local file + /api/playlists)
    Listening.cs              on-device play log, sessions and stats
    PlayQueue.cs              queue rules: repeat, next album, smart shuffle
    Audio/DjEq.cs             3-band crossover EQ (NAudio ISampleProvider)
    Audio/Turntable.cs        vinyl stop / spin-up (varispeed resampling)
    Audio/Spectrum.cs         FFT for the visualizer
    Audio/TrackSequencer.cs   gapless track switching
  src/Powerstone.Windows/     WPF app: views, view models, NAudio output, media keys
  tests/Powerstone.Core.Tests/
```
