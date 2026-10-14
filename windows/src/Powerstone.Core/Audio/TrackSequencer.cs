using NAudio.Wave;

namespace Powerstone.Core.Audio;

/// <summary>
/// The output's single, never-ending source. It plays the current track and, the moment
/// it ends, switches to the next one inside the same buffer (gapless) if it is ready;
/// otherwise it plays silence until a track is set. Every track must already be in
/// <see cref="Format"/>.
/// </summary>
public sealed class TrackSequencer(WaveFormat format) : ISampleProvider
{
    private readonly object _lock = new();
    private ISampleProvider? _current;

    public WaveFormat WaveFormat { get; } = format;
    public static WaveFormat StandardFormat { get; } = WaveFormat.CreateIeeeFloatWaveFormat(44100, 2);

    /// <summary>Called on the audio thread when the current track ends; return the next track to continue gaplessly, or null.</summary>
    public Func<ISampleProvider?>? NextReady { get; set; }

    /// <summary>Raised on the audio thread after the current track ended; the argument says whether playback continued into a ready track.</summary>
    public event Action<bool>? TrackEnded;

    public void SetTrack(ISampleProvider? track) { lock (_lock) _current = track; }

    public int Read(float[] buffer, int offset, int count)
    {
        var done = 0;
        var events = new List<bool>(1);
        lock (_lock)
        {
            var fresh = false;
            while (done < count && _current != null)
            {
                var n = _current.Read(buffer, offset + done, count - done);
                done += n;
                if (n > 0) { fresh = false; continue; }
                // Ended. A track that gives nothing straight after being switched in is empty: stop there.
                _current = fresh ? null : NextReady?.Invoke();
                fresh = _current != null;
                events.Add(_current != null);
            }
        }
        if (done < count) OutputBuffer.Clear(buffer, offset + done, count - done);
        foreach (var e in events) TrackEnded?.Invoke(e);
        return count;
    }
}
