using System.Windows.Threading;
using Windows.Media;
using Windows.Storage.Streams;

namespace Powerstone.Windows.Services;

/// <summary>
/// Hooks the app into Windows' system media controls: keyboard media keys, the
/// volume flyout / lock screen card, and Bluetooth headset buttons.
/// </summary>
public sealed class MediaControls
{
    private readonly SystemMediaTransportControls? _smtc;

    public event Action? PlayPressed, PausePressed, NextPressed, PreviousPressed;

    public MediaControls(IntPtr hwnd, Dispatcher ui)
    {
        try
        {
            _smtc = SystemMediaTransportControlsInterop.GetForWindow(hwnd);
            _smtc.IsEnabled = true;
            _smtc.IsPlayEnabled = _smtc.IsPauseEnabled = _smtc.IsNextEnabled = _smtc.IsPreviousEnabled = true;
            _smtc.ButtonPressed += (_, e) => ui.BeginInvoke(() =>
            {
                switch (e.Button)
                {
                    case SystemMediaTransportControlsButton.Play: PlayPressed?.Invoke(); break;
                    case SystemMediaTransportControlsButton.Pause: PausePressed?.Invoke(); break;
                    case SystemMediaTransportControlsButton.Next: NextPressed?.Invoke(); break;
                    case SystemMediaTransportControlsButton.Previous: PreviousPressed?.Invoke(); break;
                }
            });
        }
        catch
        {
            _smtc = null; // older Windows: the app still works, just without media keys
        }
    }

    public void Update(string title, string artist, string album, string? coverUrl, bool playing, bool hasTrack)
    {
        if (_smtc == null) return;
        try
        {
            _smtc.PlaybackStatus = !hasTrack ? MediaPlaybackStatus.Closed : playing ? MediaPlaybackStatus.Playing : MediaPlaybackStatus.Paused;
            var d = _smtc.DisplayUpdater;
            d.Type = MediaPlaybackType.Music;
            d.MusicProperties.Title = title;
            d.MusicProperties.Artist = artist;
            d.MusicProperties.AlbumTitle = album;
            d.Thumbnail = coverUrl != null ? RandomAccessStreamReference.CreateFromUri(new Uri(coverUrl)) : null;
            d.Update();
        }
        catch { }
    }

    public void SetPlaying(bool playing, bool hasTrack)
    {
        if (_smtc == null) return;
        try { _smtc.PlaybackStatus = !hasTrack ? MediaPlaybackStatus.Closed : playing ? MediaPlaybackStatus.Playing : MediaPlaybackStatus.Paused; }
        catch { }
    }
}
