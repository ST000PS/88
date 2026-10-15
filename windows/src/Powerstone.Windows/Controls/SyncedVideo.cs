using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;

namespace Powerstone.Windows.Controls;

/// <summary>
/// A music video, muted and locked to the audio clock: it follows <see cref="Playing"/> and is
/// nudged back to <see cref="Position"/> whenever it drifts more than a third of a second — the
/// same approach the site uses. Hidden until the file has loaded, so the artwork shows meanwhile.
/// </summary>
public sealed class SyncedVideo : ContentControl
{
    public static readonly DependencyProperty SourceProperty = DependencyProperty.Register(
        nameof(Source), typeof(string), typeof(SyncedVideo), new PropertyMetadata(null, (d, _) => ((SyncedVideo)d).Load()));
    public static readonly DependencyProperty PlayingProperty = DependencyProperty.Register(nameof(Playing), typeof(bool), typeof(SyncedVideo));
    public static readonly DependencyProperty PositionProperty = DependencyProperty.Register(nameof(Position), typeof(double), typeof(SyncedVideo));

    public string? Source { get => (string?)GetValue(SourceProperty); set => SetValue(SourceProperty, value); }
    public bool Playing { get => (bool)GetValue(PlayingProperty); set => SetValue(PlayingProperty, value); }
    public double Position { get => (double)GetValue(PositionProperty); set => SetValue(PositionProperty, value); }

    private readonly MediaElement _video = new()
    {
        LoadedBehavior = MediaState.Manual, UnloadedBehavior = MediaState.Manual,
        IsMuted = true, Volume = 0, Stretch = Stretch.Uniform, ScrubbingEnabled = true,
    };
    private readonly DispatcherTimer _sync = new() { Interval = TimeSpan.FromMilliseconds(400) };
    private bool _ready, _running;

    public SyncedVideo()
    {
        Content = new Border { Background = Brushes.Black, Child = _video };
        Visibility = Visibility.Collapsed;
        _video.MediaOpened += (_, _) => { _ready = true; Visibility = Visibility.Visible; Sync(force: true); };
        _video.MediaFailed += (_, _) => { _ready = false; Visibility = Visibility.Collapsed; };
        _video.MediaEnded += (_, _) => _video.Pause();
        _sync.Tick += (_, _) => Sync(force: false);
        Loaded += (_, _) => { _sync.Start(); Load(); };
        Unloaded += (_, _) => { _sync.Stop(); _video.Stop(); _video.Close(); _ready = false; };
    }

    private void Load()
    {
        _ready = false; _running = false;
        Visibility = Visibility.Collapsed;
        _video.Stop();
        if (string.IsNullOrEmpty(Source) || !IsLoaded) { _video.Source = null; return; }
        _video.Source = new Uri(Source);
        _video.Play(); // opens the file; Sync() pauses it again if the audio is paused
    }

    private void Sync(bool force)
    {
        if (!_ready) return;
        var target = TimeSpan.FromSeconds(Math.Max(0, Position));
        if (force || Math.Abs((_video.Position - target).TotalSeconds) > 0.33) _video.Position = target;
        if (Playing && !_running) { _video.Play(); _running = true; }
        else if (!Playing && (_running || force)) { _video.Pause(); _running = false; }
    }
}
