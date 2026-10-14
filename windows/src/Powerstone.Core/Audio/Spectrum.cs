using NAudio.Wave;

namespace Powerstone.Core.Audio;

/// <summary>
/// Pass-through tap that publishes a log-spaced spectrum of what's playing (after
/// the EQ) for the visualizer: <see cref="Bands"/>, 0..1 from low to high.
/// </summary>
public sealed class SpectrumSampleProvider : ISampleProvider
{
    public const int BandCount = 48;
    private const int N = 2048;
    private const float MinDb = -92f, MaxDb = -18f;

    private readonly ISampleProvider _source;
    private readonly int _channels;
    private readonly float[] _ring = new float[N];
    private int _fill;
    private readonly float[] _re = new float[N], _im = new float[N];
    private readonly float[] _window = new float[N], _cos = new float[N / 2], _sin = new float[N / 2];
    private readonly int[] _edges = new int[BandCount + 1];

    public float[] Bands { get; } = new float[BandCount];
    public long UpdatedTicks { get; private set; }
    public volatile bool Enabled = true;

    public SpectrumSampleProvider(ISampleProvider source)
    {
        _source = source;
        _channels = source.WaveFormat.Channels;
        var rate = source.WaveFormat.SampleRate;
        for (var i = 0; i < N; i++) _window[i] = (float)(0.5 - 0.5 * Math.Cos(2 * Math.PI * i / (N - 1)));
        for (var i = 0; i < N / 2; i++) { _cos[i] = (float)Math.Cos(2 * Math.PI * i / N); _sin[i] = (float)Math.Sin(2 * Math.PI * i / N); }
        for (var i = 0; i <= BandCount; i++)
        {
            var f = 30.0 * Math.Pow(16000.0 / 30.0, (double)i / BandCount);
            _edges[i] = Math.Clamp((int)(f * N / rate), 1, N / 2 - 1);
        }
    }

    public WaveFormat WaveFormat => _source.WaveFormat;

    public int Read(float[] buffer, int offset, int count)
    {
        var n = _source.Read(buffer, offset, count);
        if (!Enabled) return n;
        for (var i = 0; i + _channels <= n; i += _channels)
        {
            var m = 0f;
            for (var c = 0; c < _channels; c++) m += buffer[offset + i + c];
            _ring[_fill++] = m / _channels;
            if (_fill == N)
            {
                Analyse();
                Array.Copy(_ring, N / 2, _ring, 0, N / 2); // 50% overlap
                _fill = N / 2;
            }
        }
        return n;
    }

    private void Analyse()
    {
        for (var i = 0; i < N; i++) { _re[i] = _ring[i] * _window[i]; _im[i] = 0; }
        Fft();
        for (var b = 0; b < BandCount; b++)
        {
            int lo = _edges[b], hi = Math.Max(_edges[b + 1], lo + 1);
            var peak = 0f;
            for (var i = lo; i < hi; i++) peak = Math.Max(peak, _re[i] * _re[i] + _im[i] * _im[i]);
            var db = 10f * MathF.Log10(peak / (N * N / 16f) + 1e-12f);
            var v = Math.Clamp((db - MinDb) / (MaxDb - MinDb), 0f, 1f);
            // fast attack, slower fall, like the site's analyser bars
            Bands[b] = v > Bands[b] ? v : Bands[b] * 0.82f + v * 0.18f;
        }
        UpdatedTicks = Environment.TickCount64;
    }

    private void Fft()
    {
        for (int i = 1, j = 0; i < N; i++)
        {
            var bit = N >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { (_re[i], _re[j]) = (_re[j], _re[i]); (_im[i], _im[j]) = (_im[j], _im[i]); }
        }
        for (var len = 2; len <= N; len <<= 1)
        {
            int step = N / len, half = len / 2;
            for (var i = 0; i < N; i += len)
                for (var k = 0; k < half; k++)
                {
                    float wr = _cos[k * step], wi = -_sin[k * step];
                    int a = i + k, b = a + half;
                    var xr = _re[b] * wr - _im[b] * wi;
                    var xi = _re[b] * wi + _im[b] * wr;
                    _re[b] = _re[a] - xr; _im[b] = _im[a] - xi;
                    _re[a] += xr; _im[a] += xi;
                }
        }
    }
}
