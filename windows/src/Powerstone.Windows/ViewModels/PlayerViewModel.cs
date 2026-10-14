using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Powerstone.Core;
using Powerstone.Windows.Services;
using System.Windows.Shell;
using Windows.Networking.Connectivity;

namespace Powerstone.Windows.ViewModels;

/// <summary>Now playing + transport: the queue, the audio engine, media keys and play tracking.</summary>
public sealed partial class PlayerViewModel : ObservableObject
{
    private readonly Shell _shell;
    private readonly PlayQueue _queue;
    private readonly PlayTracker _tracker;
    private readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromMilliseconds(250) };
    private MediaControls? _media;
    private bool _seeking;
    private DateTime? _sleepAt;          // sleep timer: pause at this time…
    private bool _sleepAfterTrack;       // …or at the end of the current track
    private DateTime _lastSave;

    public AudioEngine Engine { get; }
    public PlayQueue Queue => _queue;

    [ObservableProperty] private QueueItem? _current;
    [ObservableProperty] private bool _isPlaying;
    [ObservableProperty] private bool _isLoading;
    [ObservableProperty] private double _position;   // seconds
    [ObservableProperty] private double _duration;   // seconds
    [ObservableProperty] private bool _isFav;
    [ObservableProperty] private float _volume;

    public bool HasTrack => Current != null;
    public string Title => Current?.Track.Title ?? "";
    public string Subtitle => Current == null ? "" : $"{Current.Release.Artist} · {Current.Release.Title}";
    public string? Cover => Current?.Release.Cover ?? Current?.Release.Thumb;
    public string PositionText => Format.Duration(Position);
    /// <summary>0..1, for the taskbar progress bar.</summary>
    public double Progress => Duration > 0 ? Math.Clamp(Position / Duration, 0, 1) : 0;
    public TaskbarItemProgressState TaskbarState => Current == null ? TaskbarItemProgressState.None : IsPlaying ? TaskbarItemProgressState.Normal : TaskbarItemProgressState.Paused;

    /// <summary>"SLEEP 23:10" / "SLEEP · END OF TRACK", empty when the timer is off.</summary>
    public string SleepLabel => _sleepAfterTrack ? "SLEEP · END OF TRACK"
        : _sleepAt is { } t ? $"SLEEP {Format.Duration(Math.Max(0, (t - DateTime.Now).TotalSeconds))}" : "";
    public bool SleepOn => _sleepAfterTrack || _sleepAt != null;
    /// <summary>Never 0, so an empty player shows an empty seek bar rather than a full one.</summary>
    public double SeekMax => Math.Max(Duration, 1);
    public string DurationText => Format.Duration(Duration);
    public bool IsShuffle => _queue.Kind == QueueKind.Shuffle && Current != null;
    public bool RepeatOne => _queue.Repeat == RepeatMode.One;
    public bool RepeatTape => _queue.Repeat == RepeatMode.All;
    public string PlayGlyph => IsPlaying ? "" : ""; // Segoe MDL2 Pause / Play
    public bool IsPreview => !_shell.Me.IsSubscriber;
    public string Meta => Current?.Track is { } t
        ? string.Join(" · ", new[] { t.Bpm is { } b ? $"{b} BPM" : null, t.Key is { Length: > 0 } k ? $"KEY {k}" + (t.KeyJa != null ? $" · {t.KeyJa}" : "") : null }.Where(x => x != null))
        : "";

    public PlayerViewModel(Shell shell)
    {
        _shell = shell;
        Engine = new AudioEngine(shell.Api, shell.Ui)
        {
            UrlFor = i => i.Track.StreamPath(_shell.Me.IsSubscriber, ResolvedQuality()),
            VinylSeconds = () => _shell.Settings.Vinyl.Seconds(),
        };
        Engine.Gains.Apply(shell.Settings.Eq);
        Engine.Spectrum.Enabled = shell.Settings.VisualizerOn;
        Engine.Volume = _volume = shell.Settings.Volume;
        _queue = new PlayQueue(() => _shell.Releases, (n, avoid) => PlayQueue.SmartShuffle(_shell.Releases, _shell.Settings.ShuffleHeard, n, avoid));
        _queue.Repeat = shell.Settings.Repeat;
        _tracker = new PlayTracker(shell.Api, shell.History, () => _shell.Me.SignedIn);

        Engine.Started += OnStarted;
        Engine.Finished += OnFinished;
        Engine.Error += m => _shell.ShowToast(m);
        Engine.StateChanged += SyncState;
        _tick.Tick += (_, _) => OnTick();
        _tick.Start();
    }

    /// <summary>Auto picks like the site: metered connection → 160k, otherwise 320k.</summary>
    public Quality ResolvedQuality()
    {
        var q = _shell.Settings.Quality;
        if (q != Quality.Auto) return q;
        try
        {
            var cost = NetworkInformation.GetInternetConnectionProfile()?.GetConnectionCost();
            if (cost != null && (cost.Roaming || cost.OverDataLimit)) return Quality.Lo;
            if (cost != null && cost.NetworkCostType != NetworkCostType.Unrestricted) return Quality.Std;
        }
        catch { }
        return Quality.High;
    }

    public void AttachMediaControls(IntPtr hwnd)
    {
        _media = new MediaControls(hwnd, _shell.Ui);
        _media.PlayPressed += () => Resume();
        _media.PausePressed += () => Engine.Pause();
        _media.NextPressed += () => Next();
        _media.PreviousPressed += () => Previous();
    }

    partial void OnPositionChanged(double value) { OnPropertyChanged(nameof(PositionText)); OnPropertyChanged(nameof(Progress)); }
    partial void OnDurationChanged(double value) { OnPropertyChanged(nameof(DurationText)); OnPropertyChanged(nameof(SeekMax)); OnPropertyChanged(nameof(Progress)); }
    partial void OnIsPlayingChanged(bool value) { OnPropertyChanged(nameof(PlayGlyph)); OnPropertyChanged(nameof(TaskbarState)); }

    private void OnTick()
    {
        if (!_seeking && Engine.Current != null) { Position = Engine.Position.TotalSeconds; Duration = Engine.Duration.TotalSeconds; }
        if (_sleepAt is { } at)
        {
            if (DateTime.Now >= at) { ClearSleep(); Engine.Pause(); _shell.ShowToast("Sleep timer — paused"); }
            OnPropertyChanged(nameof(SleepLabel));
        }
        if (IsPlaying && DateTime.Now - _lastSave > TimeSpan.FromSeconds(10)) SaveSession();
    }

    // ---------- sleep timer ----------

    /// <summary>"off", "end" (end of this track) or a number of minutes.</summary>
    [RelayCommand]
    public void SetSleep(string what)
    {
        _sleepAt = null; _sleepAfterTrack = false;
        if (what == "end") _sleepAfterTrack = true;
        else if (int.TryParse(what, out var min) && min > 0) _sleepAt = DateTime.Now.AddMinutes(min);
        OnPropertyChanged(nameof(SleepLabel)); OnPropertyChanged(nameof(SleepOn));
        _shell.ShowToast(_sleepAfterTrack ? "Sleep timer — stopping after this track" : _sleepAt != null ? $"Sleep timer — {what} min" : "Sleep timer off");
        // Stopping at the end of the track means no gapless hand-off into the next one.
        if (Current != null) Engine.PrepareNext(_sleepAfterTrack ? null : _queue.PeekNext(auto: true));
    }

    private void ClearSleep()
    {
        _sleepAt = null; _sleepAfterTrack = false;
        OnPropertyChanged(nameof(SleepLabel)); OnPropertyChanged(nameof(SleepOn));
    }

    // ---------- resume where you left off ----------

    public void SaveSession()
    {
        _lastSave = DateTime.Now;
        _shell.Settings.LastSession = SessionSnapshot.From(_queue, Engine.Position.TotalSeconds) ?? _shell.Settings.LastSession;
        _shell.Settings.Save();
    }

    /// <summary>Cues the last session (paused) once the catalogue is loaded.</summary>
    public void RestoreSession()
    {
        if (Current != null || _shell.Settings.LastSession?.Resolve(_shell.Releases) is not { } s) return;
        _queue.Set(s.Items, s.Index, _shell.Settings.LastSession.Kind);
        _ = Engine.PlayAsync(_queue.Current!, autoplay: false, at: TimeSpan.FromSeconds(_shell.Settings.LastSession.Position));
    }
    partial void OnVolumeChanged(float value) { Engine.Volume = value; _shell.Settings.Volume = value; }

    partial void OnCurrentChanged(QueueItem? value)
    {
        foreach (var p in new[] { nameof(HasTrack), nameof(Title), nameof(Subtitle), nameof(Cover), nameof(IsShuffle), nameof(Meta), nameof(IsPreview) }) OnPropertyChanged(p);
        RefreshFav();
        _shell.Page?.Refresh();
    }

    public void RefreshFav() => IsFav = Current != null && _shell.IsFav(Current);

    private void SyncState()
    {
        IsPlaying = Engine.IsPlaying;
        OnPropertyChanged(nameof(TaskbarState));
        IsLoading = Engine.IsLoading;
        _tracker.SetPlaying(IsPlaying);
        _media?.SetPlaying(IsPlaying, Current != null);
    }

    private void OnStarted(QueueItem item, bool gapless)
    {
        if (gapless) _queue.Next(auto: true);
        _tracker.Begin(item, playing: Engine.IsPlaying);
        _shell.Settings.ShuffleHeard.Add(item.Id);
        Current = item;
        Position = 0;
        Duration = Engine.Duration.TotalSeconds;
        SyncState();
        _media?.Update(item.Track.Title, item.Release.Artist, item.Release.Title, Api.Absolute(item.Release.Cover), Engine.IsPlaying, true);
        Engine.PrepareNext(_sleepAfterTrack ? null : _queue.PeekNext(auto: true));
        SaveSession();
    }

    private void OnFinished()
    {
        if (_sleepAfterTrack)
        {
            // Sleep timer "end of track": cue the next one up, paused.
            ClearSleep();
            _tracker.Finish();
            if (_queue.Next(auto: true) is { } cue) _ = Engine.PlayAsync(cue, autoplay: false);
            else Engine.EndOfQueue();
            _shell.ShowToast("Sleep timer — stopped at the end of the track");
            return;
        }
        var next = _queue.Next(auto: true);
        if (next != null) _ = Engine.PlayAsync(next);
        else { _tracker.Finish(); Engine.EndOfQueue(); Position = 0; }
    }

    // ---------- starting playback ----------

    public void Play(IReadOnlyList<QueueItem> items, int start, QueueKind kind = QueueKind.List)
    {
        if (items.Count == 0) { _shell.ShowToast("Nothing to play"); return; }
        _queue.Set(items, start, kind);
        OnPropertyChanged(nameof(IsShuffle));
        _ = Engine.PlayAsync(_queue.Current!);
    }

    public void PlayRelease(Release r, int start = 0) => Play(r.Tracks.Select(t => new QueueItem(r, t)).ToList(), start, QueueKind.Album);

    public void Shuffled(IEnumerable<QueueItem> items) => Play(items.OrderBy(_ => Random.Shared.Next()).ToList(), 0);

    /// <summary>The site's SHUFFLE: unheard songs from across the whole catalogue, forever.</summary>
    [RelayCommand]
    public void SmartShuffle()
    {
        var picks = PlayQueue.SmartShuffle(_shell.Releases, _shell.Settings.ShuffleHeard, 60);
        if (picks.Count == 0) return;
        Play(picks, 0, QueueKind.Shuffle);
        _shell.ShowToast($"Shuffle — {picks[0].Track.Title} / {picks[0].Release.Title}");
    }

    public void JumpTo(int index)
    {
        _queue.JumpTo(index);
        if (_queue.Current is { } c) _ = Engine.PlayAsync(c);
    }

    // ---------- transport ----------

    [RelayCommand]
    public void PlayPause()
    {
        if (Engine.IsPlaying) Engine.Pause();
        else Resume();
    }

    private void Resume()
    {
        if (Engine.Current != null) Engine.Resume();
        else if (_queue.Current is { } c) _ = Engine.PlayAsync(c);
    }

    [RelayCommand]
    public void Next()
    {
        if (_queue.Next(auto: false) is { } n) _ = Engine.PlayAsync(n);
    }

    /// <summary>Back to the start of the track, or the previous track if near the start.</summary>
    [RelayCommand]
    public void Previous()
    {
        if (Engine.Position.TotalSeconds > 3 || _queue.Index <= 0 && _queue.Repeat != RepeatMode.All) { Engine.Seek(TimeSpan.Zero); return; }
        if (_queue.Previous() is { } p) _ = Engine.PlayAsync(p);
    }

    public void BeginSeek() => _seeking = true;

    public void EndSeek(double seconds)
    {
        Engine.Seek(TimeSpan.FromSeconds(seconds));
        Position = seconds;
        _seeking = false;
    }

    private void SetRepeat(RepeatMode mode)
    {
        if (!_shell.Me.IsSubscriber) { _shell.ShowToast("Repeat is for subscribers"); return; }
        _queue.Repeat = _queue.Repeat == mode ? RepeatMode.Off : mode;
        _shell.Settings.Repeat = _queue.Repeat;
        OnPropertyChanged(nameof(RepeatOne)); OnPropertyChanged(nameof(RepeatTape));
        _shell.ShowToast(_queue.Repeat switch { RepeatMode.One => "Repeat 1 — looping this track", RepeatMode.All => "Repeat tape — looping the whole queue", _ => "Repeat off" });
        if (Current != null) Engine.PrepareNext(_queue.PeekNext(auto: true));
    }

    [RelayCommand] private void ToggleRepeatOne() => SetRepeat(RepeatMode.One);
    [RelayCommand] private void ToggleRepeatTape() => SetRepeat(RepeatMode.All);
    [RelayCommand] private async Task ToggleFav() { if (Current != null) await _shell.ToggleFavAsync(Current); }
    [RelayCommand] private void OpenCurrentRelease() { if (Current != null) _shell.OpenRelease(Current.Release, Current.Track.N); }

    public void Shutdown()
    {
        SaveSession();
        _tracker.Finish(flush: false);
        _tracker.FlushBlocking();
        Engine.Dispose();
    }
}
