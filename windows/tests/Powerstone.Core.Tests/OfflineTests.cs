using Powerstone.Core;

namespace Powerstone.Core.Tests;

public class OfflineTests
{
    private static Release Rel(string id, int n) => new() { Id = id, Title = id, Tracks = Enumerable.Range(1, n).Select(i => new Track { N = i, Full = $"/a/full/{id}/{i}.mp3" }).ToList() };
    private static readonly Me Sub = new() { Tier = "sub", SignedIn = true };

    private static (OfflineStore Store, string Dir, List<string> Fetched, Func<DateTimeOffset> Clock, Action<TimeSpan> Advance) Make()
    {
        var dir = Directory.CreateTempSubdirectory().FullName;
        var fetched = new List<string>();
        var now = DateTimeOffset.UtcNow;
        var store = new OfflineStore(dir, (url, _) => { fetched.Add(url); return Task.FromResult(new byte[] { 1, 2, 3 }); }, () => now);
        return (store, dir, fetched, () => now, d => now += d);
    }

    [Fact]
    public async Task NonSubscribersCannotDownload()
    {
        var (store, _, _, _, _) = Make();
        var r = Rel("a", 1);
        await Assert.ThrowsAsync<ApiException>(() => store.DownloadAsync(new[] { new QueueItem(r, r.Tracks[0]) }, i => i.Track.Full));
    }

    [Fact]
    public async Task DownloadsOnceAndPersists()
    {
        var (store, dir, fetched, clock, _) = Make();
        store.AccountChecked(Sub);
        var r = Rel("gt-3", 2);
        var items = r.Tracks.Select(t => new QueueItem(r, t)).ToList();
        Assert.Equal(2, await store.DownloadAsync(items, i => i.Track.Full));
        Assert.Equal(0, await store.DownloadAsync(items, i => i.Track.Full)); // already there
        Assert.Equal(2, fetched.Count);
        Assert.NotNull(store.PathFor("gt-3/1"));
        var reloaded = new OfflineStore(dir, (_, _) => throw new Exception("no network"), clock);
        Assert.Equal(2, reloaded.Count);
        Assert.Equal(6, reloaded.TotalBytes);
        Assert.True(File.Exists(reloaded.PathFor("gt-3/2")));
    }

    [Fact]
    public async Task LockedAfterTheGracePeriodAndWipedWhenNoLongerSubscribed()
    {
        var (store, _, _, _, advance) = Make();
        store.AccountChecked(Sub);
        var r = Rel("a", 1);
        await store.DownloadAsync(new[] { new QueueItem(r, r.Tracks[0]) }, i => i.Track.Full);
        advance(TimeSpan.FromDays(31));
        Assert.Null(store.PathFor("a/1"));       // locked: not confirmed for a month
        Assert.Equal(1, store.Count);            // …but not deleted (could just be offline)
        store.AccountChecked(Sub);
        Assert.NotNull(store.PathFor("a/1"));    // confirmed again: back
        store.AccountChecked(new Me { Tier = "guest", SignedIn = true });
        Assert.Equal(0, store.Count);            // site says not subscribed: gone
        Assert.False(store.Usable);
    }
}
