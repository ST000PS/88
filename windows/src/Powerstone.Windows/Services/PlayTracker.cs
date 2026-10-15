using System.Diagnostics;
using Powerstone.Core;

namespace Powerstone.Windows.Services;

/// <summary>
/// Mirrors the site's tracker: a play counts after 10 s of actual listening. Plays are
/// kept on this PC and, when signed in, sent to the account in batches of 5.
/// </summary>
public sealed class PlayTracker(Api api, ListeningHistory history, Func<bool> signedIn)
{
    private QueueItem? _item;
    private long _startedAt;
    private readonly Stopwatch _listened = new();
    private readonly List<PlayEvent> _buffer = new();

    public void Begin(QueueItem item, bool playing)
    {
        Finish(flush: false);
        _item = item;
        _startedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        _listened.Reset();
        if (playing) _listened.Start();
    }

    public void SetPlaying(bool playing)
    {
        if (playing) _listened.Start();
        else { _listened.Stop(); if (_buffer.Count >= 5) _ = SendAsync(); }
    }

    public void Finish(bool flush = true)
    {
        _listened.Stop();
        if (_item != null && _listened.ElapsedMilliseconds >= 10_000)
        {
            var e = new PlayEvent(_item.Release.Id, _item.Track.N, _startedAt, _listened.ElapsedMilliseconds);
            _buffer.Add(e);
            Task.Run(() => history.Add(new[] { e }));
        }
        _item = null;
        _listened.Reset();
        if (flush || _buffer.Count >= 5) _ = SendAsync();
    }

    /// <summary>At exit: send what's left without needing the UI thread (best effort, 3 s max).</summary>
    public void FlushBlocking()
    {
        if (_buffer.Count == 0 || !signedIn()) return;
        var batch = _buffer.ToList();
        _buffer.Clear();
        try { Task.Run(() => api.ReportPlaysAsync(batch)).Wait(TimeSpan.FromSeconds(3)); } catch { }
    }

    public async Task SendAsync()
    {
        if (_buffer.Count == 0 || !signedIn()) return;
        var batch = _buffer.ToList();
        _buffer.Clear();
        if (!await api.ReportPlaysAsync(batch)) _buffer.InsertRange(0, batch);
    }
}
