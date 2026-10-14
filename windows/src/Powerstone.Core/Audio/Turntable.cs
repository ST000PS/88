using NAudio.Wave;

namespace Powerstone.Core.Audio;

/// <summary>
/// Vinyl stop / spin-up, done properly in the audio stream: the record really slows
/// down (speed and pitch fall together, by resampling), the tail fades, and it outputs
/// silence once stopped. Same curves as the site's VINYL STOP.
/// </summary>
public sealed class TurntableSampleProvider : ISampleProvider
{
    private enum Mode { Normal, Braking, SpinUp, Stopped }

    private readonly ISampleProvider _source;
    private readonly int _channels;
    private readonly object _lock = new();
    private Mode _mode = Mode.Normal;
    private long _elapsed, _total; // frames into the current ramp
    private bool _brakeDoneRaised;

    // Resampling buffer (interleaved source frames) and fractional read position in frames.
    private float[] _buf = new float[4096];
    private int _bufFrames;
    private double _pos;
    private bool _sourceEnded;

    public TurntableSampleProvider(ISampleProvider source)
    {
        _source = source;
        _channels = source.WaveFormat.Channels;
    }

    public WaveFormat WaveFormat => _source.WaveFormat;

    /// <summary>Raised (on the audio thread) when a brake has wound all the way down.</summary>
    public event Action? Stopped;

    public bool IsStopped { get { lock (_lock) return _mode == Mode.Stopped; } }
    public bool IsBraking { get { lock (_lock) return _mode == Mode.Braking; } }

    public void Brake(double seconds)
    {
        lock (_lock)
        {
            _mode = Mode.Braking; _elapsed = 0;
            _total = Math.Max(1, (long)(seconds * WaveFormat.SampleRate));
            _brakeDoneRaised = false;
        }
    }

    public void SpinUp(double seconds = 0.22)
    {
        lock (_lock) { _mode = Mode.SpinUp; _elapsed = 0; _total = Math.Max(1, (long)(seconds * WaveFormat.SampleRate)); }
    }

    /// <summary>Back to normal speed immediately (seek, new track, stop).</summary>
    public void Reset()
    {
        lock (_lock) { _mode = Mode.Normal; _bufFrames = 0; _pos = 0; _sourceEnded = false; }
    }

    /// <summary>Speed (and pitch) ratio and gain for the current ramp position.</summary>
    internal static (double Rate, float Gain) Envelope(bool braking, double p)
    {
        if (braking)
        {
            var r = Math.Max(0.07, Math.Pow(1 - p, 1.15));
            var g = Math.Pow(r, 1.2) * (p > 0.7 ? Math.Max(0, (1 - p) / 0.3) : 1);
            return (r, (float)g);
        }
        return (0.35 + 0.65 * Math.Pow(p, 0.8), (float)(0.5 + 0.5 * p));
    }

    public int Read(float[] buffer, int offset, int count)
    {
        var raise = false;
        int written;
        lock (_lock)
        {
            if (_mode == Mode.Stopped)
            {
                OutputBuffer.Clear(buffer, offset, count);
                return count;
            }
            var frames = count / _channels;
            var o = 0;
            while (o < frames)
            {
                if (_mode == Mode.Normal)
                {
                    // Normal speed: first play out whatever a ramp left buffered, then read straight through.
                    var left = _bufFrames - (int)_pos;
                    if (left > 0)
                    {
                        var n = Math.Min(left, frames - o);
                        OutputBuffer.Copy(_buf, (int)_pos * _channels, buffer, offset + o * _channels, n * _channels);
                        _pos += n; o += n;
                        if ((int)_pos >= _bufFrames) { _bufFrames = 0; _pos = 0; }
                        continue;
                    }
                    _bufFrames = 0; _pos = 0;
                    o += _source.Read(buffer, offset + o * _channels, (frames - o) * _channels) / _channels;
                    break;
                }

                var p = Math.Min(1.0, (double)_elapsed / _total);
                var (rate, gain) = Envelope(_mode == Mode.Braking, p);
                _elapsed++;
                if (!EnsureFrames()) break; // source ran out mid-ramp
                var i0 = (int)_pos;
                var frac = (float)(_pos - i0);
                for (var c = 0; c < _channels; c++)
                {
                    var a = _buf[i0 * _channels + c];
                    var b = _buf[(i0 + 1) * _channels + c];
                    buffer[offset + o * _channels + c] = (a + (b - a) * frac) * gain;
                }
                o++;
                _pos += rate;
                if (p >= 1)
                {
                    if (_mode == Mode.Braking)
                    {
                        _mode = Mode.Stopped;
                        raise = !_brakeDoneRaised; _brakeDoneRaised = true;
                        OutputBuffer.Clear(buffer, offset + o * _channels, (frames - o) * _channels);
                        o = frames;
                    }
                    else
                    {
                        // Spun up: snap to a whole frame (under half a sample, inaudible) and go back to normal.
                        _mode = Mode.Normal;
                        _pos = Math.Min(Math.Round(_pos), _bufFrames);
                    }
                }
            }
            written = o * _channels;
        }
        if (raise) Stopped?.Invoke();
        return written;
    }

    /// <summary>Makes sure the two frames around the read position are buffered; false if the source ran out.</summary>
    private bool EnsureFrames()
    {
        if ((int)_pos + 2 <= _bufFrames) return true;
        Compact();
        while (_bufFrames < (int)_pos + 2)
        {
            if (_sourceEnded) return false;
            var want = 1024 * _channels;
            if (_buf.Length < _bufFrames * _channels + want) Array.Resize(ref _buf, _bufFrames * _channels + want);
            var got = _source.Read(_buf, _bufFrames * _channels, want);
            if (got == 0) { _sourceEnded = true; return false; }
            _bufFrames += got / _channels;
        }
        return true;
    }

    /// <summary>Drops fully-consumed frames from the front of the buffer.</summary>
    private void Compact()
    {
        var drop = (int)_pos;
        if (drop <= 0) return;
        Array.Copy(_buf, drop * _channels, _buf, 0, (_bufFrames - drop) * _channels);
        _bufFrames -= drop;
        _pos -= drop;
    }
}
