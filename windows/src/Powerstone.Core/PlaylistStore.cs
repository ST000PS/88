namespace Powerstone.Core;

/// <summary>
/// Playlists, synced the way the site does it: kept on disk, and when signed in
/// mirrored to /api/playlists with a list of deleted ids so deletions stick across
/// devices. Playlists made while signed out are merged into the account on sign-in.
/// </summary>
public sealed class PlaylistStore
{
    private readonly Api? _api;
    private readonly Settings _settings;
    private readonly string _file;
    private bool _signedIn;
    private CancellationTokenSource? _pushCts;
    private Task _pushTask = Task.CompletedTask;

    public List<Playlist> List { get; private set; }
    public event Action? Changed;

    public PlaylistStore(Api? api, Settings settings, string dataDir)
    {
        _api = api;
        _settings = settings;
        _file = Path.Combine(dataDir, "playlists.json");
        List = ReadLocal();
    }

    private List<Playlist> ReadLocal()
    {
        try { return File.Exists(_file) ? Json.Parse<List<Playlist>>(File.ReadAllText(_file)) : new(); }
        catch { return new(); }
    }

    private void SaveLocal() { try { File.WriteAllText(_file, Json.Write(List)); } catch { } }

    private void Set(List<Playlist> list)
    {
        List = list;
        Changed?.Invoke();
        Push();
    }

    private void Push()
    {
        SaveLocal();
        if (!_signedIn || _api == null) return;
        _pushCts?.Cancel();
        var cts = _pushCts = new CancellationTokenSource();
        var body = new PlaylistsBody { Playlists = List.Select(p => p.Clone()).ToList(), Deleted = new(_settings.PlaylistsDeleted) };
        _pushTask = Task.Run(async () =>
        {
            await Task.Delay(400, cts.Token);
            await _api.PutPlaylistsAsync(body);
        }, cts.Token).ContinueWith(_ => { });
    }

    internal static List<Playlist> Dedupe(List<Playlist> list)
    {
        foreach (var pl in list)
        {
            var unique = pl.Tracks.Distinct().ToList();
            if (unique.Count != pl.Tracks.Count) { pl.Tracks = unique; pl.Updated = Now(); }
        }
        return list;
    }

    /// <summary>Merge rule from the site: server minus deletions, plus guest-made local playlists.</summary>
    internal static List<Playlist> Merge(PlaylistsBody remote, IEnumerable<string> localDeleted, List<Playlist> local, bool wasGuest)
    {
        var gone = remote.Deleted.Concat(localDeleted).ToHashSet();
        var server = remote.Playlists.Where(p => !gone.Contains(p.Id)).ToList();
        var ids = server.Select(p => p.Id).ToHashSet();
        return Dedupe(server.Concat(local.Where(p => wasGuest && !ids.Contains(p.Id) && !gone.Contains(p.Id))).ToList());
    }

    public async Task LoadAsync(bool signedIn)
    {
        await _pushTask; // don't let a stale read overwrite edits still being uploaded
        _signedIn = signedIn;
        var local = ReadLocal();
        if (!signedIn || _api == null)
        {
            _settings.PlaylistsGuest = true; _settings.Save();
            List = Dedupe(local); SaveLocal(); Changed?.Invoke();
            return;
        }
        try
        {
            var remote = await _api.PlaylistsAsync();
            var wasGuest = _settings.PlaylistsGuest;
            _settings.PlaylistsGuest = false; _settings.Save();
            List = Merge(remote, _settings.PlaylistsDeleted, local, wasGuest);
            Changed?.Invoke();
            if (List.Count != remote.Playlists.Count || wasGuest) Push(); else SaveLocal();
        }
        catch
        {
            List = Dedupe(local); Changed?.Invoke();
        }
    }

    public void SignedOut() => _signedIn = false;

    public Playlist? Get(string id) => List.FirstOrDefault(p => p.Id == id);

    private static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public Playlist Create(string? name = null, IEnumerable<TrackRef>? tracks = null)
    {
        var id = Convert.ToString(Now(), 16) + Random.Shared.Next(0x10000).ToString("x4");
        var pl = new Playlist { Id = id, Name = name ?? $"MY PLAYLIST {List.Count + 1}", Tracks = (tracks ?? Enumerable.Empty<TrackRef>()).Distinct().ToList(), Updated = Now() };
        Set(new List<Playlist> { pl }.Concat(List).ToList());
        return pl;
    }

    private void Update(string id, Action<Playlist> f)
    {
        var copy = List.Select(p => p.Clone()).ToList();
        var pl = copy.FirstOrDefault(p => p.Id == id);
        if (pl == null) return;
        f(pl);
        pl.Updated = Now();
        Set(copy);
    }

    /// <summary>False when the track is already in the playlist.</summary>
    public bool Add(string id, TrackRef r)
    {
        var pl = Get(id);
        if (pl == null || pl.Tracks.Contains(r)) return false;
        Update(id, p => p.Tracks.Add(r));
        return true;
    }

    public void RemoveAt(string id, int index) => Update(id, p => { if (index >= 0 && index < p.Tracks.Count) p.Tracks.RemoveAt(index); });

    public void Move(string id, int from, int to) => Update(id, p =>
    {
        if (from < 0 || from >= p.Tracks.Count || to < 0 || to >= p.Tracks.Count) return;
        var t = p.Tracks[from]; p.Tracks.RemoveAt(from); p.Tracks.Insert(to, t);
    });

    public void Rename(string id, string name)
    {
        var clean = name.Trim();
        if (clean.Length > 80) clean = clean[..80];
        if (clean.Length > 0) Update(id, p => p.Name = clean);
    }

    public void Delete(string id)
    {
        _settings.PlaylistsDeleted.Remove(id);
        _settings.PlaylistsDeleted.Insert(0, id);
        _settings.Save();
        Set(List.Where(p => p.Id != id).ToList());
    }
}
