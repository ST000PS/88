namespace Powerstone.Core;

public sealed record QueueItem(Release Release, Track Track)
{
    public string Id => Format.TrackId(Release, Track);
}

/// <summary>What a queue was started from; decides what happens when it runs out.</summary>
public enum QueueKind { Album, Shuffle, List }

/// <summary>
/// The play queue with the site's rules: REPEAT 1 holds on auto-advance only, REPEAT TAPE
/// loops the queue, an album rolls into the next album, and shuffle never runs out.
/// </summary>
public sealed class PlayQueue
{
    private readonly Func<IReadOnlyList<Release>> _catalog;
    private readonly Func<int, ISet<string>, List<QueueItem>> _moreShuffle;

    public List<QueueItem> Items { get; } = new();
    public int Index { get; private set; } = -1;
    public QueueKind Kind { get; private set; } = QueueKind.List;
    public RepeatMode Repeat { get; set; } = RepeatMode.Off;

    public QueueItem? Current => Index >= 0 && Index < Items.Count ? Items[Index] : null;

    public PlayQueue(Func<IReadOnlyList<Release>> catalog, Func<int, ISet<string>, List<QueueItem>> moreShuffle)
    {
        _catalog = catalog;
        _moreShuffle = moreShuffle;
    }

    public void Set(IEnumerable<QueueItem> items, int start, QueueKind kind)
    {
        Items.Clear();
        Items.AddRange(items);
        Kind = kind;
        Index = Items.Count == 0 ? -1 : Math.Clamp(start, 0, Items.Count - 1);
    }

    public void JumpTo(int index) { if (index >= 0 && index < Items.Count) Index = index; }

    /// <summary>The item that would play next, without moving. <paramref name="auto"/> = end of track.</summary>
    public QueueItem? PeekNext(bool auto)
    {
        if (Current == null) return null;
        if (auto && Repeat == RepeatMode.One) return Current;
        Extend();
        if (Index + 1 < Items.Count) return Items[Index + 1];
        if (Repeat == RepeatMode.All && Kind != QueueKind.Shuffle && Items.Count > 0) return Items[0];
        return null;
    }

    public QueueItem? Next(bool auto)
    {
        if (Current == null) return null;
        if (auto && Repeat == RepeatMode.One) return Current;
        Extend();
        if (Index + 1 < Items.Count) { Index++; return Current; }
        if (Repeat == RepeatMode.All && Kind != QueueKind.Shuffle && Items.Count > 0) { Index = 0; return Current; }
        return null;
    }

    public QueueItem? Previous()
    {
        if (Index > 0) { Index--; return Current; }
        if (Index == 0 && Repeat == RepeatMode.All && Items.Count > 1) { Index = Items.Count - 1; return Current; }
        return null;
    }

    /// <summary>Tops the queue up when it is about to run out (album → next album, shuffle → more).</summary>
    private void Extend()
    {
        if (Repeat != RepeatMode.Off || Current == null) return;
        var remaining = Items.Count - 1 - Index;
        if (Kind == QueueKind.Shuffle && remaining < 3)
        {
            // Avoid only what's recently queued: avoiding the whole queue would eventually exclude everything.
            Items.AddRange(_moreShuffle(50, Items.Skip(Math.Max(0, Items.Count - 50)).Select(i => i.Id).ToHashSet()));
        }
        else if (Kind == QueueKind.Album && remaining == 0)
        {
            var next = NextAlbum(_catalog(), Current.Release.Id);
            if (next != null) Items.AddRange(next.Tracks.Select(t => new QueueItem(next, t)));
        }
    }

    /// <summary>Release order for "keep playing the next album": released first, then unreleased / rare.</summary>
    public static List<Release> Sequence(IReadOnlyList<Release> releases) =>
        releases.Where(r => r.Kind == "released" && r.Tracks.Count > 0)
            .Concat(releases.Where(r => r.Kind is "unreleased" or "loose" && r.Tracks.Count > 0)).ToList();

    public static Release? NextAlbum(IReadOnlyList<Release> releases, string currentId)
    {
        var seq = Sequence(releases);
        var i = seq.FindIndex(r => r.Id == currentId);
        return i < 0 || seq.Count < 2 ? null : seq[(i + 1) % seq.Count];
    }

    /// <summary>
    /// Smart shuffle: a random release, then a random track in it, steered to songs not
    /// heard yet; once everything has been heard it starts over (clearing <paramref name="heard"/>).
    /// </summary>
    public static List<QueueItem> SmartShuffle(IReadOnlyList<Release> releases, ISet<string> heard, int count, ISet<string>? avoid = null, Random? rng = null)
    {
        rng ??= Random.Shared;
        var rels = releases.Where(r => r.Tracks.Count > 0).ToList();
        var picked = new HashSet<string>();
        var result = new List<QueueItem>();
        for (var i = 0; i < count; i++)
        {
            List<QueueItem> Pool() => rels.SelectMany(r => r.Tracks.Select(t => new QueueItem(r, t)))
                .Where(q => !heard.Contains(q.Id) && !picked.Contains(q.Id) && avoid?.Contains(q.Id) != true).ToList();
            var pool = Pool();
            if (pool.Count == 0) { heard.Clear(); pool = Pool(); }
            if (pool.Count == 0 && avoid is { Count: > 0 }) { avoid = null; pool = Pool(); }
            if (pool.Count == 0) break;
            var byRel = pool.GroupBy(q => q.Release.Id).ToList();
            var group = byRel[rng.Next(byRel.Count)].ToList();
            var pick = group[rng.Next(group.Count)];
            picked.Add(pick.Id);
            result.Add(pick);
        }
        return result;
    }
}
