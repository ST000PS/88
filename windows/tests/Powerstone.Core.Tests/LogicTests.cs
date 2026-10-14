using Powerstone.Core;

namespace Powerstone.Core.Tests;

public class LogicTests
{
    private static Release Rel(string id, string title, string kind = "released", int n = 2, params string[] tags) =>
        new() { Id = id, Title = title, Kind = kind, Tags = tags.ToList(), Tracks = Enumerable.Range(1, n).Select(i => new Track { N = i, Title = $"{title} {i}", Duration = 60 }).ToList() };

    private const string Sample = """
        {"releases": [{"id": "gt-3", "title": "GT.3", "artist": "Knx.", "year": "2026", "date": "2026-10-03",
          "sort": 20261003, "kind": "released", "preview_seconds": 28, "cover": "/a/cover/gt-3.jpg?v=1",
          "thumb": "/a/thumb/gt-3.jpg?v=1", "genres": [], "tags": [], "bandcamp": null,
          "tracks": [{"n": 1, "title": "WDWGW", "duration": 58.9, "tag": "", "tags": [], "video": null,
            "std": "/a/std/gt-3/01-WDWGW.mp3", "lo": "/a/lo/gt-3/01-WDWGW.mp3", "bpm": 62, "key": "F",
            "key_ja": "ヘ長調", "preview": "/a/preview/gt-3/01-WDWGW.mp3", "full": "/a/full/gt-3/01-WDWGW.mp3"},
            {"n": 2, "title": "DNTWNTU", "duration": 126, "preview": "/a/preview/gt-3/02-DNTWNTU.mp3", "full": "/a/full/gt-3/02-DNTWNTU.mp3"}]}]}
        """;

    [Fact]
    public void ParsesTheRealCatalogueShape()
    {
        var r = Json.Parse<Catalog>(Sample).Releases.Single();
        Assert.Equal("gt-3", r.Id);
        Assert.Equal(28, r.PreviewSeconds);
        Assert.Equal(126, r.Tracks[1].Duration);
        Assert.Equal("ヘ長調", r.Tracks[0].KeyJa);
        Assert.Equal("gt-3/1", Format.TrackId(r, r.Tracks[0]));
        Assert.Equal("OTHER", r.Series);
        Assert.Equal("WT", new Release { Title = "WT.4" }.Series);
    }

    [Fact]
    public void GuestsGetPreviewsSubscribersGetTheirQuality()
    {
        var t = Json.Parse<Catalog>(Sample).Releases[0].Tracks;
        Assert.Equal("/a/preview/gt-3/01-WDWGW.mp3", t[0].StreamPath(false, Quality.High));
        Assert.Equal("/a/full/gt-3/01-WDWGW.mp3", t[0].StreamPath(true, Quality.High));
        Assert.Equal("/a/lo/gt-3/01-WDWGW.mp3", t[0].StreamPath(true, Quality.Lo));
        Assert.Equal("/a/full/gt-3/02-DNTWNTU.mp3", t[1].StreamPath(true, Quality.Lo));
    }

    [Fact]
    public void FormatsDurations()
    {
        Assert.Equal("1:41", Format.Duration(101.7));
        Assert.Equal("1:01:01", Format.Duration(3661));
    }

    private static PlayQueue Queue(List<Release> all) =>
        new(() => all, (n, avoid) => PlayQueue.SmartShuffle(all, new HashSet<string>(), n, avoid));

    [Fact]
    public void RepeatOneHoldsOnlyOnAutoAdvance()
    {
        var a = Rel("a", "A");
        var q = Queue(new() { a });
        q.Set(a.Tracks.Select(t => new QueueItem(a, t)), 0, QueueKind.List);
        q.Repeat = RepeatMode.One;
        Assert.Equal(1, q.Next(auto: true)!.Track.N);
        Assert.Equal(2, q.Next(auto: false)!.Track.N);
    }

    [Fact]
    public void RepeatTapeLoopsTheQueue()
    {
        var a = Rel("a", "A");
        var q = Queue(new() { a });
        q.Set(a.Tracks.Select(t => new QueueItem(a, t)), 1, QueueKind.Album);
        q.Repeat = RepeatMode.All;
        Assert.Equal(1, q.Next(true)!.Track.N);
        Assert.Equal(2, q.Previous()!.Track.N);
    }

    [Fact]
    public void AlbumRollsIntoTheNextAlbumThenRareTrax()
    {
        var a = Rel("a", "A"); var rare = Rel("r", "Rare", "loose", 1); var b = Rel("b", "B", n: 1);
        var all = new List<Release> { a, rare, b };
        Assert.Equal(new[] { "a", "b", "r" }, PlayQueue.Sequence(all).Select(r => r.Id));
        Assert.Equal("a", PlayQueue.NextAlbum(all, "r")!.Id);
        var q = Queue(all);
        q.Set(a.Tracks.Select(t => new QueueItem(a, t)), 1, QueueKind.Album);
        Assert.Equal("b", q.PeekNext(true)!.Release.Id);
        Assert.Equal("b", q.Next(true)!.Release.Id);
        Assert.Equal("r", q.Next(true)!.Release.Id);
        // A plain list just ends.
        var list = Queue(all);
        list.Set(new[] { new QueueItem(a, a.Tracks[1]) }, 0, QueueKind.List);
        Assert.Null(list.Next(true));
    }

    [Fact]
    public void ShuffleNeverRunsOut()
    {
        var all = Enumerable.Range(0, 5).Select(i => Rel($"r{i}", $"R{i}", n: 3)).ToList();
        var q = Queue(all);
        q.Set(PlayQueue.SmartShuffle(all, new HashSet<string>(), 2), 0, QueueKind.Shuffle);
        for (var i = 0; i < 100; i++) Assert.NotNull(q.Next(true));
    }

    [Fact]
    public void SmartShufflePlaysEverythingBeforeRepeating()
    {
        var all = Enumerable.Range(0, 4).Select(i => Rel($"r{i}", $"R{i}", n: 3)).ToList();
        var heard = new HashSet<string>();
        var picks = PlayQueue.SmartShuffle(all, heard, 12, rng: new Random(1));
        Assert.Equal(12, picks.Select(p => p.Id).Distinct().Count());
        foreach (var p in picks) heard.Add(p.Id);
        var more = PlayQueue.SmartShuffle(all, heard, 3, rng: new Random(2));
        Assert.Equal(3, more.Count); // everything heard → starts over
        // Even when every track is "avoided", shuffle still delivers.
        Assert.Equal(2, PlayQueue.SmartShuffle(all, new HashSet<string>(), 2, picks.Select(p => p.Id).ToHashSet()).Count);
    }

    [Fact]
    public void SessionsSplitOnThirtyMinuteGaps()
    {
        const long min = 60_000;
        var s = StatsMath.Sessions(new[] { new PlayEvent("a", 1, 0, 1000), new PlayEvent("a", 2, 10 * min, 1000), new PlayEvent("b", 1, 50 * min, 1000) });
        Assert.Equal(2, s.Count);
        Assert.Equal(50 * min, s[0].Start);
        Assert.Equal(2, s[1].Plays.Count);
    }

    [Fact]
    public void StatsRankTracksReleasesAndTags()
    {
        const long min = 60_000;
        var a = Rel("a", "Album A", tags: "jazz");
        var b = Rel("b", "Album B", n: 1);
        b.Tracks[0].Tags.Add("vgm");
        var st = StatsMath.Compute(new[]
        {
            new PlayEvent("a", 1, 100, 2 * min), new PlayEvent("a", 1, 200, 2 * min),
            new PlayEvent("b", 1, 300, min), new PlayEvent("a", 2, 50, min),
        }, 100, long.MaxValue, new[] { a, b });
        Assert.Equal(3, st.Plays);
        Assert.Equal(5, st.Minutes);
        Assert.Equal(2, st.UniqueTracks);
        Assert.Equal("Album A 1", st.TopTracks[0].Title);
        Assert.Equal("a", st.TopReleases[0].R);
        Assert.Equal(new[] { "jazz", "vgm" }, st.TopTags.Select(t => t.Tag));
        Assert.Equal(2, st.Counts["a|1"]);
    }

    [Fact]
    public void ParsesServerHistorySessions()
    {
        var s = StatsMath.ParseServerSessions("""{"sessions":[{"s":1000,"e":2000,"items":[["gt-3",1,1000,12000],["gt-3","2",1500,9000.0]]}]}""");
        Assert.Single(s);
        Assert.Equal(new PlayEvent("gt-3", 2, 1500, 9000), s[0].Plays[1]);
    }

    [Fact]
    public void PlaylistMergeFollowsTheSiteRules()
    {
        var server = new PlaylistsBody
        {
            Playlists = new() { new Playlist { Id = "s1", Name = "Server" }, new Playlist { Id = "gone", Name = "Deleted elsewhere" } },
            Deleted = new() { "gone" },
        };
        var local = new List<Playlist> { new() { Id = "g1", Name = "Made as guest", Tracks = new() { new("a", 1), new("a", 1) } } };
        var merged = PlaylistStore.Merge(server, Array.Empty<string>(), local, wasGuest: true);
        Assert.Equal(new[] { "s1", "g1" }, merged.Select(p => p.Id));
        Assert.Single(merged[1].Tracks); // deduped
        Assert.Equal(new[] { "s1" }, PlaylistStore.Merge(server, new[] { "x" }, local, wasGuest: false).Select(p => p.Id));
    }

    [Fact]
    public void PlaylistStoreEditsAndPersists()
    {
        var dir = Directory.CreateTempSubdirectory().FullName;
        var settings = Settings.Load(dir);
        var store = new PlaylistStore(null, settings, dir);
        var pl = store.Create();
        Assert.True(store.Add(pl.Id, new("a", 1)));
        Assert.False(store.Add(pl.Id, new("a", 1)));
        store.Add(pl.Id, new("b", 2));
        store.Move(pl.Id, 1, 0);
        store.Rename(pl.Id, "  Night drive  ");
        Assert.Equal(new TrackRef("b", 2), store.Get(pl.Id)!.Tracks[0]);
        Assert.Equal("Night drive", new PlaylistStore(null, settings, dir).Get(pl.Id)!.Name);
        store.Delete(pl.Id);
        Assert.Null(store.Get(pl.Id));
        Assert.Contains(pl.Id, Settings.Load(dir).PlaylistsDeleted);
    }
}

public class SessionTests
{
    private static Release Rel(string id, int n) => new() { Id = id, Title = id, Tracks = Enumerable.Range(1, n).Select(i => new Track { N = i, Title = $"{id}{i}" }).ToList() };

    [Fact]
    public void SessionSurvivesASaveAndReload()
    {
        var a = Rel("a", 3); var b = Rel("b", 2);
        var q = new PlayQueue(() => new[] { a, b }, (_, _) => new());
        q.Set(new[] { new QueueItem(a, a.Tracks[0]), new QueueItem(b, b.Tracks[1]), new QueueItem(a, a.Tracks[2]) }, 1, QueueKind.Shuffle);
        var dir = Directory.CreateTempSubdirectory().FullName;
        var s = Settings.Load(dir);
        s.LastSession = SessionSnapshot.From(q, 42.5);
        s.Save();
        var loaded = Settings.Load(dir).LastSession!;
        Assert.Equal(QueueKind.Shuffle, loaded.Kind);
        Assert.Equal(42.5, loaded.Position);
        var (items, index) = loaded.Resolve(new[] { a, b })!.Value;
        Assert.Equal(3, items.Count);
        Assert.Equal("b/2", items[index].Id);
    }

    [Fact]
    public void TracksThatDisappearedAreSkipped()
    {
        var a = Rel("a", 1);
        var snap = new SessionSnapshot(new() { "gone/1", "a/1", "a/9" }, 1, QueueKind.List, 0);
        var (items, index) = snap.Resolve(new[] { a })!.Value;
        Assert.Single(items);
        Assert.Equal(0, index);
        Assert.Null(new SessionSnapshot(new() { "gone/1" }, 0, QueueKind.List, 0).Resolve(new[] { a }));
    }
}
