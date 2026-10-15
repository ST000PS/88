using NAudio.Wave;

namespace Powerstone.Core.Audio;

/// <summary>Live EQ gains shared between the UI and the audio thread.</summary>
public sealed class EqGains
{
    public volatile float Lo = 1f, Mid = 1f, Hi = 1f;
    public void Apply(EqState eq) { Lo = eq.Gain("lo"); Mid = eq.Gain("mid"); Hi = eq.Gain("hi"); }
}

/// <summary>RBJ biquad, transposed direct form II.</summary>
internal sealed class Biquad
{
    private readonly float _b0, _b1, _b2, _a1, _a2;
    private float _z1, _z2;

    public Biquad(bool lowpass, double freq, int rate)
    {
        var w = 2 * Math.PI * freq / rate;
        var alpha = Math.Sin(w) / (2 * Math.Sqrt(0.5));
        var c = Math.Cos(w);
        var a0 = 1 + alpha;
        if (lowpass) { _b0 = (float)((1 - c) / 2 / a0); _b1 = (float)((1 - c) / a0); }
        else { _b0 = (float)((1 + c) / 2 / a0); _b1 = (float)(-(1 + c) / a0); }
        _b2 = _b0;
        _a1 = (float)(-2 * c / a0); _a2 = (float)((1 - alpha) / a0);
    }

    public float Run(float x)
    {
        var y = _b0 * x + _z1;
        _z1 = _b1 * x - _a1 * y + _z2;
        _z2 = _b2 * x - _a2 * y;
        return y;
    }
}

/// <summary>4th-order Linkwitz–Riley section = two Butterworth biquads in series (the site's lr4()).</summary>
internal sealed class Lr4(bool lowpass, double freq, int rate)
{
    private readonly Biquad _a = new(lowpass, freq, rate), _b = new(lowpass, freq, rate);
    public float Run(float x) => _b.Run(_a.Run(x));
}

/// <summary>
/// The site's DJ EQ: split at 250 Hz and 4 kHz with Linkwitz–Riley crossovers into
/// LO / MID / HI, each with its own gain (0–1.5×, KILL, ISO), then summed.
/// </summary>
public sealed class DjEqSampleProvider : ISampleProvider
{
    private readonly ISampleProvider _source;
    private readonly EqGains _gains;
    private readonly (Lr4 lo, Lr4 hpA, Lr4 mid, Lr4 hi)[] _ch;
    private float _gLo = 1, _gMid = 1, _gHi = 1;

    public DjEqSampleProvider(ISampleProvider source, EqGains gains)
    {
        _source = source;
        _gains = gains;
        var rate = source.WaveFormat.SampleRate;
        _ch = Enumerable.Range(0, source.WaveFormat.Channels)
            .Select(_ => (new Lr4(true, 250, rate), new Lr4(false, 250, rate), new Lr4(true, 4000, rate), new Lr4(false, 4000, rate)))
            .ToArray();
    }

    public WaveFormat WaveFormat => _source.WaveFormat;

    public int Read(float[] buffer, int offset, int count)
    {
        var n = _source.Read(buffer, offset, count);
        var chs = _ch.Length;
        float tLo = _gains.Lo, tMid = _gains.Mid, tHi = _gains.Hi;
        const float k = 0.003f; // per-frame smoothing (~7 ms): no zipper noise on slider moves
        for (var i = 0; i + chs <= n; i += chs)
        {
            _gLo += (tLo - _gLo) * k; _gMid += (tMid - _gMid) * k; _gHi += (tHi - _gHi) * k;
            for (var c = 0; c < chs; c++)
            {
                var x = buffer[offset + i + c];
                var f = _ch[c];
                var high = f.hpA.Run(x);
                var y = _gLo * f.lo.Run(x) + _gMid * f.mid.Run(high) + _gHi * f.hi.Run(high);
                buffer[offset + i + c] = Math.Clamp(y, -1f, 1f);
            }
        }
        return n;
    }
}
