using NAudio.Wave;

namespace Powerstone.Core.Audio;

/// <summary>A track handed to the <see cref="TrackSequencer"/>: its samples and length (frames, -1 if unknown).</summary>
public sealed record SequencerTrack(ISampleProvider Provider, long Frames = -1);

/// <summary>
/// The output's single, never-ending source. It plays the current track and moves to the next one
/// either gaplessly (the moment it ends, inside the same buffer) or with a crossfade: with
/// <see cref="CrossfadeFrames"/> set, the next track starts that many frames before the current one
/// ends and the two are mixed with equal-power curves. Between tracks it plays silence.
/// Every track must already be in <see cref="WaveFormat"/>.
/// </summary>
public sealed class TrackSequencer(WaveFormat format) : ISampleProvider
{
    private sealed class Slot(SequencerTrack t)
    {
        public readonly ISampleProvider Provider = t.Provider;
        public readonly long Frames = t.Frames;
        public long Played;
        public bool NextAsked;
        public long Remaining => Frames < 0 ? long.MaxValue : Math.Max(0, Frames - Played);
    }

    private readonly object _lock = new();
    private readonly int _ch = format.Channels;
    private Slot? _current;
    private Slot? _outgoing;           // fading out during a crossfade
    private long _fadeTotal, _fadePos;
    private float[] _mix = new float[8192];

    public WaveFormat WaveFormat { get; } = format;
    public static WaveFormat StandardFormat { get; } = WaveFormat.CreateIeeeFloatWaveFormat(44100, 2);

    /// <summary>Crossfade length in frames for the next transition; 0 = gapless.</summary>
    public int CrossfadeFrames { get; set; }

    /// <summary>Called on the audio thread when the next track is due; return it to continue, or null.</summary>
    public Func<SequencerTrack?>? NextReady { get; set; }

    /// <summary>Raised on the audio thread when playback moved on (true = into a ready track, false = ran out).</summary>
    public event Action<bool>? TrackEnded;

    /// <summary>Raised on the audio thread when a track will never be read again — safe to dispose it now.</summary>
    public event Action<ISampleProvider>? Released;

    public void SetTrack(ISampleProvider? track, long frames = -1)
    {
        var released = new List<ISampleProvider>(2);
        lock (_lock)
        {
            if (_current != null) released.Add(_current.Provider);
            if (_outgoing != null) released.Add(_outgoing.Provider);
            _outgoing = null;
            _current = track == null ? null : new Slot(new SequencerTrack(track, frames));
        }
        foreach (var r in released) if (!ReferenceEquals(r, track)) Released?.Invoke(r);
    }

    /// <summary>After seeking the current track, tell the sequencer where it now is (frames from the start).</summary>
    public void Seeked(long frame)
    {
        lock (_lock) { if (_current != null) { _current.Played = Math.Max(0, frame); _current.NextAsked = false; } }
    }

    public int Read(float[] buffer, int offset, int count)
    {
        var frames = count / _ch;
        var done = 0;
        var ended = new List<bool>(1);
        var released = new List<ISampleProvider>(1);
        lock (_lock)
        {
            while (done < frames && _current != null)
            {
                if (_outgoing != null) { done += MixFade(buffer, offset, done, frames, released); continue; }

                var cur = _current;
                // Start a crossfade exactly CrossfadeFrames before the end, if the next track is ready.
                var xf = CrossfadeFrames;
                if (xf > 0 && cur.Frames > 0 && !cur.NextAsked && cur.Remaining <= xf)
                {
                    cur.NextAsked = true;
                    if (NextReady?.Invoke() is { } next)
                    {
                        _outgoing = cur;
                        _current = new Slot(next);
                        _fadeTotal = Math.Max(1, cur.Remaining);
                        _fadePos = 0;
                        ended.Add(true);
                        continue;
                    }
                }
                var want = frames - done;
                if (xf > 0 && cur.Frames > 0 && !cur.NextAsked) want = (int)Math.Min(want, Math.Max(1, cur.Remaining - xf));
                var n = cur.Provider.Read(buffer, offset + done * _ch, want * _ch) / _ch;
                cur.Played += n;
                done += n;
                if (n > 0) continue;

                // Ended: carry straight on into the next track (gapless), or stop. A track that gives
                // nothing right after being switched in is empty, so stop there rather than spin.
                var wasFresh = cur.Played == 0;
                released.Add(cur.Provider);
                _current = null;
                if (!wasFresh && !cur.NextAsked && NextReady?.Invoke() is { } after) _current = new Slot(after);
                ended.Add(_current != null);
            }
        }
        if (done < frames) OutputBuffer.Clear(buffer, offset + done * _ch, (frames - done) * _ch);
        foreach (var r in released) Released?.Invoke(r);
        foreach (var e in ended) TrackEnded?.Invoke(e);
        return count;
    }

    /// <summary>Mixes outgoing and incoming tracks for one chunk of the crossfade. Returns frames written.</summary>
    private int MixFade(float[] buffer, int offset, int done, int frames, List<ISampleProvider> released)
    {
        var n = (int)Math.Min(frames - done, _fadeTotal - _fadePos);
        var samples = n * _ch;
        if (_mix.Length < samples) _mix = new float[samples];
        var inN = _current!.Provider.Read(_mix, 0, samples) / _ch;
        _current.Played += inN;
        for (var i = inN * _ch; i < samples; i++) _mix[i] = 0f; // incoming ended early (very short track)
        var outN = _outgoing!.Provider.Read(buffer, offset + done * _ch, samples) / _ch;
        for (var f = 0; f < n; f++)
        {
            var p = (_fadePos + f + 0.5) / _fadeTotal;
            var gIn = (float)Math.Sin(p * Math.PI / 2);
            var gOut = (float)Math.Cos(p * Math.PI / 2);
            for (var c = 0; c < _ch; c++)
            {
                var i = f * _ch + c;
                var o = f < outN ? buffer[offset + done * _ch + i] : 0f;
                buffer[offset + done * _ch + i] = Math.Clamp(o * gOut + _mix[i] * gIn, -1f, 1f);
            }
        }
        _fadePos += n;
        if (_fadePos >= _fadeTotal || outN == 0)
        {
            released.Add(_outgoing.Provider);
            _outgoing = null;
        }
        return Math.Max(n, 1);
    }
}
