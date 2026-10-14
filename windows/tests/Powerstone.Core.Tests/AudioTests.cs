using NAudio.Wave;
using Powerstone.Core;
using Powerstone.Core.Audio;

namespace Powerstone.Core.Tests;

/// <summary>Stereo sine source with a known number of frames.</summary>
internal sealed class Sine(double freq, int frames, int rate = 44100, float amp = 0.5f) : ISampleProvider
{
    private int _i;
    public WaveFormat WaveFormat { get; } = WaveFormat.CreateIeeeFloatWaveFormat(rate, 2);
    public int Consumed => _i;
    public int Read(float[] buffer, int offset, int count)
    {
        var n = 0;
        while (n + 2 <= count && _i < frames)
        {
            var v = amp * (float)Math.Sin(2 * Math.PI * freq * _i / WaveFormat.SampleRate);
            buffer[offset + n] = v; buffer[offset + n + 1] = v;
            n += 2; _i++;
        }
        return n;
    }
}

public class AudioTests
{
    private static float[] ReadAll(ISampleProvider p, int frames)
    {
        var all = new float[frames * 2];
        var done = 0;
        while (done < all.Length)
        {
            var n = p.Read(all, done, Math.Min(4096, all.Length - done));
            if (n == 0) break;
            done += n;
        }
        return all[..done];
    }

    private static double Rms(float[] x, int from) => Math.Sqrt(x.Skip(from).Where((_, i) => i % 2 == 0).Average(v => (double)v * v));

    private static double EqGain(double freq, EqState eq)
    {
        var g = new EqGains(); g.Apply(eq);
        var outp = ReadAll(new DjEqSampleProvider(new Sine(freq, 44100), g), 44100);
        var inp = ReadAll(new Sine(freq, 44100), 44100);
        var tail = outp.Length * 3 / 4;
        return Rms(outp, tail) / Rms(inp, tail);
    }

    [Theory]
    [InlineData(60)] [InlineData(1000)] [InlineData(8000)]
    public void FlatEqIsUnity(double f) => Assert.InRange(EqGain(f, new EqState()), 0.85, 1.15);

    [Fact]
    public void KillMidRemovesMidrangeOnly()
    {
        Assert.True(EqGain(1000, new EqState(Kill: new[] { "mid" })) < 0.1);
        Assert.True(EqGain(60, new EqState(Kill: new[] { "mid" })) > 0.85);
    }

    [Fact]
    public void IsoLoKeepsOnlyBass()
    {
        Assert.True(EqGain(60, new EqState(Solo: "lo")) > 0.85);
        Assert.True(EqGain(8000, new EqState(Solo: "lo")) < 0.05);
    }

    [Fact]
    public void EqGainRulesMatchTheSite()
    {
        var eq = new EqState(Lo: 1.2f, Mid: 0.5f, Kill: new[] { "hi" });
        Assert.Equal(1.2f, eq.Gain("lo"));
        Assert.Equal(0f, eq.Gain("hi"));
        var iso = eq.ToggleSolo("mid");
        Assert.Equal(0.6f, iso.Gain("mid"));
        Assert.Equal(0f, iso.Gain("lo"));
        Assert.False(eq.ToggleKill("hi").IsKilled("hi"));
    }

    [Fact]
    public void TurntablePassesThroughUntouched()
    {
        var a = ReadAll(new TurntableSampleProvider(new Sine(440, 10000)), 10000);
        var b = ReadAll(new Sine(440, 10000), 10000);
        Assert.Equal(b, a);
    }

    [Fact]
    public void BrakeSlowsDownThenGoesSilentAndSignals()
    {
        var src = new Sine(440, 44100 * 10);
        var tt = new TurntableSampleProvider(src);
        var stopped = 0;
        tt.Stopped += () => stopped++;
        ReadAll(tt, 4410); // play 0.1 s normally
        tt.Brake(0.9);
        var before = src.Consumed;
        var ramp = ReadAll(tt, 44100); // 1 s of output covers the 0.9 s ramp
        Assert.Equal(1, stopped);
        Assert.True(tt.IsStopped);
        // Slowing down consumes far less source than real time (the record is winding down).
        var used = src.Consumed - before;
        Assert.InRange(used, 0.2 * 0.9 * 44100, 0.75 * 0.9 * 44100);
        // Last stretch of output is silence.
        Assert.All(ramp[^2000..], v => Assert.Equal(0f, v));
    }

    [Fact]
    public void SpinUpReturnsToNormalSpeed()
    {
        var src = new Sine(440, 44100 * 10);
        var tt = new TurntableSampleProvider(src);
        tt.Brake(0.45);
        ReadAll(tt, 44100);
        tt.SpinUp();
        ReadAll(tt, 44100); // ramp 0.22 s then normal
        var before = src.Consumed;
        ReadAll(tt, 44100);
        // Back at 1×: one second of output consumes one second of source.
        Assert.InRange(src.Consumed - before, 44100 - 1100, 44100 + 1100);
        Assert.False(tt.IsStopped);
    }

    [Fact]
    public void SequencerContinuesGaplesslyIntoTheNextTrack()
    {
        var seq = new TrackSequencer(TrackSequencer.StandardFormat);
        var second = new Sine(440, 1000);
        seq.SetTrack(new Sine(440, 1000));
        var handedOut = 0;
        seq.NextReady = () => handedOut++ == 0 ? second : null;
        var ended = new List<bool>();
        seq.TrackEnded += ended.Add;
        var buf = new float[3000 * 2];
        Assert.Equal(buf.Length, seq.Read(buf, 0, buf.Length));
        Assert.Equal(new[] { true, false }, ended); // switched once, then ran out
        Assert.Equal(1000, second.Consumed);
        Assert.All(buf[(2000 * 2)..], v => Assert.Equal(0f, v)); // silence after both
    }

    [Fact]
    public void SpectrumSeesATone()
    {
        var sp = new SpectrumSampleProvider(new Sine(1000, 44100, amp: 0.8f));
        ReadAll(sp, 44100);
        var peak = Array.IndexOf(sp.Bands, sp.Bands.Max());
        var f = 30.0 * Math.Pow(16000.0 / 30.0, (peak + 0.5) / SpectrumSampleProvider.BandCount);
        Assert.InRange(f, 700, 1400);
    }
}

public class SequencerEdgeTests
{
    [Fact]
    public void AnEmptyNextTrackDoesNotHang()
    {
        var seq = new TrackSequencer(TrackSequencer.StandardFormat);
        seq.SetTrack(new Sine(440, 10));
        seq.NextReady = () => new Sine(440, 0); // always hands back an already-finished track
        var buf = new float[1000];
        Assert.Equal(buf.Length, seq.Read(buf, 0, buf.Length));
        Assert.Equal(buf.Length, seq.Read(buf, 0, buf.Length)); // and stays silent afterwards
    }
}
