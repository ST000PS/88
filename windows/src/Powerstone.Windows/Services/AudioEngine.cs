using System.IO;
using System.Windows.Threading;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;
using Powerstone.Core;
using Powerstone.Core.Audio;

namespace Powerstone.Windows.Services;

/// <summary>
/// Playback: downloads each track with the session cookie, decodes it, and plays it through
/// gapless switching → vinyl turntable → DJ EQ → spectrum tap → volume → Windows audio.
/// The next track is pre-loaded so it starts in the same audio buffer as the last one ends.
/// All public members are for the UI thread; events are raised on the UI thread.
/// </summary>
public sealed class AudioEngine : IDisposable
{
    private sealed record Loaded(QueueItem Item, WaveStream Reader, ISampleProvider Provider);

    private readonly Api _api;
    private readonly Dispatcher _ui;
    private readonly TrackSequencer _sequencer = new(TrackSequencer.StandardFormat);
    private readonly TurntableSampleProvider _turntable;
    private readonly VolumeSampleProvider _volume;
    private readonly LinkedList<(string Url, byte[] Bytes)> _cache = new();
    private WaveOutEvent? _out;
    private Loaded? _current;
    private Loaded? _prepared;   // next track, decoded and ready for a gapless switch
    private Loaded? _switchedTo; // set on the audio thread when it switched to _prepared
    private int _generation;
    private bool _braking;

    public EqGains Gains { get; } = new();
    public SpectrumSampleProvider Spectrum { get; }

    /// <summary>A track started playing; <c>gapless</c> = continued straight from the previous one.</summary>
    public event Action<QueueItem, bool>? Started;
    /// <summary>The track ended and nothing was ready to follow; the caller decides what's next.</summary>
    public event Action? Finished;
    public event Action<string>? Error;
    public event Action? StateChanged;

    /// <summary>Resolves the stream URL for an item (quality / preview), set by the player.</summary>
    public Func<QueueItem, string?> UrlFor { get; set; } = _ => null;
    public Func<double> VinylSeconds { get; set; } = () => 0;

    public QueueItem? Current => _current?.Item;
    public bool IsLoading { get; private set; }
    public bool IsPlaying => _out?.PlaybackState == PlaybackState.Playing && !_braking && _current != null;
    public TimeSpan Position { get { try { return _current?.Reader.CurrentTime ?? TimeSpan.Zero; } catch { return TimeSpan.Zero; } } }
    public TimeSpan Duration => _current?.Reader.TotalTime ?? TimeSpan.Zero;

    public float Volume { get => _volume.Volume; set => _volume.Volume = Math.Clamp(value, 0f, 1f); }

    public AudioEngine(Api api, Dispatcher ui)
    {
        _api = api;
        _ui = ui;
        _turntable = new TurntableSampleProvider(_sequencer);
        var eq = new DjEqSampleProvider(_turntable, Gains);
        Spectrum = new SpectrumSampleProvider(eq);
        _volume = new VolumeSampleProvider(Spectrum);

        _sequencer.NextReady = () =>
        {
            var p = Interlocked.Exchange(ref _prepared, null);
            if (p != null) Volatile.Write(ref _switchedTo, p);
            return p?.Provider;
        };
        _sequencer.TrackEnded += continued => _ui.BeginInvoke(() => OnTrackEnded(continued));
        _turntable.Stopped += () => _ui.BeginInvoke(OnBrakeDone);
    }

    private WaveOutEvent Output()
    {
        if (_out != null) return _out;
        _out = new WaveOutEvent { DesiredLatency = 200, NumberOfBuffers = 3 };
        _out.Init(_volume);
        _out.PlaybackStopped += (_, e) =>
        {
            if (e.Exception == null) return;
            _ui.BeginInvoke(() =>
            {
                // Device unplugged / changed: rebuild the output on the next play.
                _out?.Dispose(); _out = null;
                Error?.Invoke("Audio device error: " + e.Exception.Message);
                StateChanged?.Invoke();
            });
        };
        return _out;
    }

    // ---------- loading ----------

    private async Task<byte[]> BytesFor(string url, CancellationToken ct = default)
    {
        foreach (var (u, b) in _cache) if (u == url) return b;
        var bytes = await _api.DownloadAsync(url, null, ct);
        _cache.AddFirst((url, bytes));
        while (_cache.Count > 4) _cache.RemoveLast();
        return bytes;
    }

    private static Loaded Open(QueueItem item, byte[] bytes)
    {
        var reader = new Mp3FileReader(new MemoryStream(bytes, writable: false));
        ISampleProvider sp = reader.ToSampleProvider();
        if (sp.WaveFormat.Channels == 1) sp = new MonoToStereoSampleProvider(sp);
        if (sp.WaveFormat.SampleRate != TrackSequencer.StandardFormat.SampleRate)
            sp = new WdlResamplingSampleProvider(sp, TrackSequencer.StandardFormat.SampleRate);
        return new Loaded(item, reader, sp);
    }

    private async Task<Loaded?> LoadAsync(QueueItem item)
    {
        var url = UrlFor(item) ?? throw new ApiException("This track has no audio.");
        var bytes = await BytesFor(url);
        return await Task.Run(() => Open(item, bytes));
    }

    /// <summary>Starts <paramref name="item"/> from the beginning (downloads it first if needed).</summary>
    public async Task PlayAsync(QueueItem item)
    {
        var gen = ++_generation;
        Interlocked.Exchange(ref _prepared, null);
        IsLoading = true; StateChanged?.Invoke();
        try
        {
            var loaded = await LoadAsync(item);
            if (gen != _generation || loaded == null) return;
            var old = _current;
            _turntable.Reset();
            _braking = false;
            _sequencer.SetTrack(loaded.Provider);
            _current = loaded;
            old?.Reader.Dispose();
            var o = Output();
            if (o.PlaybackState != PlaybackState.Playing) o.Play();
            Started?.Invoke(item, false);
        }
        catch (Exception e)
        {
            if (gen == _generation) Error?.Invoke(e is ApiException ? e.Message : "Couldn't play this track: " + e.Message);
        }
        finally
        {
            if (gen == _generation) { IsLoading = false; StateChanged?.Invoke(); }
        }
    }

    /// <summary>Pre-loads what plays after the current track, for a gapless switch.</summary>
    public async void PrepareNext(QueueItem? next)
    {
        Interlocked.Exchange(ref _prepared, null);
        if (next == null) return;
        var gen = _generation;
        try
        {
            var loaded = await LoadAsync(next);
            if (gen == _generation && loaded != null && _current != null) Interlocked.Exchange(ref _prepared, loaded);
        }
        catch { /* it will simply load when it's due */ }
    }

    private void OnTrackEnded(bool continued)
    {
        var next = Interlocked.Exchange(ref _switchedTo, null);
        if (continued && next != null)
        {
            var old = _current;
            _current = next;
            old?.Reader.Dispose();
            Started?.Invoke(next.Item, true);
        }
        else
        {
            Finished?.Invoke();
        }
        StateChanged?.Invoke();
    }

    // ---------- transport ----------

    /// <summary>Pause; with vinyl stop on, the record winds down first.</summary>
    public void Pause()
    {
        if (_out == null || _out.PlaybackState != PlaybackState.Playing) return;
        var seconds = VinylSeconds();
        if (seconds <= 0 || _braking) { _braking = false; _out.Pause(); }
        else { _braking = true; _turntable.Brake(seconds); }
        StateChanged?.Invoke();
    }

    private async void OnBrakeDone()
    {
        if (!_braking) return;
        await Task.Delay(_out?.DesiredLatency ?? 200); // let the wound-down tail reach the speakers
        if (!_braking) return;
        _braking = false;
        _out?.Pause();
        StateChanged?.Invoke();
    }

    public void Resume()
    {
        if (_current == null) return;
        var spin = VinylSeconds() > 0 && (_braking || _turntable.IsStopped || _out?.PlaybackState == PlaybackState.Paused);
        _braking = false;
        if (spin) _turntable.SpinUp(); else _turntable.Reset();
        var o = Output();
        if (o.PlaybackState != PlaybackState.Playing) o.Play();
        StateChanged?.Invoke();
    }

    public void Seek(TimeSpan t)
    {
        if (_current == null) return;
        try { _current.Reader.CurrentTime = t < TimeSpan.Zero ? TimeSpan.Zero : t > Duration ? Duration : t; } catch { }
        if (!_braking) _turntable.Reset();
        StateChanged?.Invoke();
    }

    public void Stop()
    {
        _generation++;
        Interlocked.Exchange(ref _prepared, null);
        _sequencer.SetTrack(null);
        _out?.Stop();
        _current?.Reader.Dispose();
        _current = null;
        _braking = false;
        StateChanged?.Invoke();
    }

    public void Dispose()
    {
        _out?.Dispose();
        _current?.Reader.Dispose();
    }
}
