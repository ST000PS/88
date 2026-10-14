namespace Powerstone.Core;

/// <summary>
/// Downloaded tracks for listening without a connection — subscribers only, the way
/// streaming services do offline mode:
/// <list type="bullet">
/// <item>files live in the app's own data folder, not as loose MP3s;</item>
/// <item>they play offline for <see cref="Grace"/> after the subscription was last confirmed;</item>
/// <item>if the site says the account is no longer subscribed (or you sign out), they're deleted.</item>
/// </list>
/// A failed network check never deletes anything.
/// </summary>
public sealed class OfflineStore
{
    public static readonly TimeSpan Grace = TimeSpan.FromDays(30);

    public sealed class Entry
    {
        public string File { get; set; } = "";
        public long Bytes { get; set; }
        public long Added { get; set; }
    }

    private sealed class Index
    {
        public Dictionary<string, Entry> Tracks { get; set; } = new();
        public long VerifiedAt { get; set; }
    }

    private readonly string _dir;
    private readonly string _indexFile;
    private readonly Func<string, CancellationToken, Task<byte[]>> _download;
    private readonly Func<DateTimeOffset> _now;
    private readonly object _lock = new();
    private Index _index;

    public event Action? Changed;

    public OfflineStore(string dataDir, Func<string, CancellationToken, Task<byte[]>> download, Func<DateTimeOffset>? now = null)
    {
        _dir = Path.Combine(dataDir, "offline");
        Directory.CreateDirectory(_dir);
        _indexFile = Path.Combine(_dir, "index.json");
        _download = download;
        _now = now ?? (() => DateTimeOffset.UtcNow);
        try { _index = File.Exists(_indexFile) ? Json.Parse<Index>(File.ReadAllText(_indexFile)) : new(); }
        catch { _index = new(); }
    }

    private void Save() { try { File.WriteAllText(_indexFile, Json.Write(_index)); } catch { } }

    public int Count { get { lock (_lock) return _index.Tracks.Count; } }
    public long TotalBytes { get { lock (_lock) return _index.Tracks.Values.Sum(e => e.Bytes); } }
    public bool Has(string id) { lock (_lock) return _index.Tracks.ContainsKey(id); }
    public bool Has(QueueItem i) => Has(i.Id);

    /// <summary>True while downloads may be played (subscription confirmed within the grace period).</summary>
    public bool Usable { get { lock (_lock) return _index.VerifiedAt > 0 && _now() - DateTimeOffset.FromUnixTimeMilliseconds(_index.VerifiedAt) < Grace; } }

    /// <summary>Local file for a downloaded track, or null if it isn't downloaded or downloads are locked.</summary>
    public string? PathFor(string id)
    {
        lock (_lock)
        {
            if (!_index.Tracks.TryGetValue(id, out var e) || !Usable) return null;
            var p = Path.Combine(_dir, e.File);
            return File.Exists(p) ? p : null;
        }
    }

    /// <summary>
    /// Call with a <b>successful</b> /api/me answer (never a failed one). Subscribers extend the grace
    /// period; anyone else loses their downloads.
    /// </summary>
    public void AccountChecked(Me me)
    {
        if (me.IsSubscriber)
        {
            lock (_lock) { _index.VerifiedAt = _now().ToUnixTimeMilliseconds(); Save(); }
        }
        else if (Count > 0) RemoveAll();
    }

    private static string FileName(string id) =>
        string.Concat(id.Select(c => char.IsLetterOrDigit(c) || c is '-' or '_' ? c : '_')) + ".mp3";

    /// <summary>
    /// Downloads the tracks that aren't already here, one at a time. <paramref name="urlFor"/> gives each
    /// track's full-quality path. Progress reports (done, total). Stops quietly when cancelled.
    /// </summary>
    public async Task<int> DownloadAsync(IReadOnlyList<QueueItem> items, Func<QueueItem, string?> urlFor, IProgress<(int Done, int Total)>? progress = null, CancellationToken ct = default)
    {
        if (!Usable) throw new ApiException("Downloads are for subscribers. Sign in with an active subscription first.");
        var todo = items.Where(i => !Has(i)).DistinctBy(i => i.Id).ToList();
        var done = 0;
        progress?.Report((0, todo.Count));
        foreach (var item in todo)
        {
            ct.ThrowIfCancellationRequested();
            var url = urlFor(item);
            if (url == null) continue;
            var bytes = await _download(url, ct).ConfigureAwait(false);
            var name = FileName(item.Id);
            var tmp = Path.Combine(_dir, name + ".part");
            await File.WriteAllBytesAsync(tmp, bytes, ct).ConfigureAwait(false);
            File.Move(tmp, Path.Combine(_dir, name), overwrite: true);
            lock (_lock)
            {
                _index.Tracks[item.Id] = new Entry { File = name, Bytes = bytes.Length, Added = _now().ToUnixTimeMilliseconds() };
                Save();
            }
            done++;
            progress?.Report((done, todo.Count));
            Changed?.Invoke();
        }
        return done;
    }

    public void Remove(IEnumerable<string> ids)
    {
        lock (_lock)
        {
            foreach (var id in ids)
            {
                if (!_index.Tracks.Remove(id, out var e)) continue;
                try { File.Delete(Path.Combine(_dir, e.File)); } catch { }
            }
            Save();
        }
        Changed?.Invoke();
    }

    public void RemoveAll()
    {
        lock (_lock)
        {
            foreach (var e in _index.Tracks.Values) { try { File.Delete(Path.Combine(_dir, e.File)); } catch { } }
            _index.Tracks.Clear();
            _index.VerifiedAt = 0;
            Save();
        }
        Changed?.Invoke();
    }
}
