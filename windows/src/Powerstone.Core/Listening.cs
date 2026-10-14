using System.Text.Json;

namespace Powerstone.Core;

/// <summary>On-device listening log (last 3000 plays), for history and stats when signed out.</summary>
public sealed class ListeningHistory(string dataDir)
{
    private readonly string _file = Path.Combine(dataDir, "history.json");
    private readonly object _lock = new();

    public List<PlayEvent> All()
    {
        lock (_lock)
        {
            try { return File.Exists(_file) ? Json.Parse<List<PlayEvent>>(File.ReadAllText(_file)) : new(); }
            catch { return new(); }
        }
    }

    public void Add(IEnumerable<PlayEvent> events)
    {
        lock (_lock)
        {
            var all = All().Concat(events).ToList();
            if (all.Count > 3000) all = all.Skip(all.Count - 3000).ToList();
            try { File.WriteAllText(_file, Json.Write(all)); } catch { }
        }
    }
}

public static class StatsMath
{
    private const long SessionGap = 30 * 60 * 1000L;

    /// <summary>Plays grouped into sessions split by 30-minute gaps, newest first.</summary>
    public static List<ListeningSession> Sessions(IEnumerable<PlayEvent> events)
    {
        var outList = new List<List<PlayEvent>>();
        long last = long.MinValue;
        foreach (var e in events.OrderBy(e => e.Ts))
        {
            if (outList.Count == 0 || e.Ts - last > SessionGap) outList.Add(new());
            outList[^1].Add(e);
            last = Math.Max(last, e.Ts);
        }
        return outList.Select(l => new ListeningSession(l[0].Ts, l)).Reverse().ToList();
    }

    public static List<ListeningSession> ParseServerSessions(string body)
    {
        using var doc = JsonDocument.Parse(body);
        var result = new List<ListeningSession>();
        if (!doc.RootElement.TryGetProperty("sessions", out var sessions)) return result;
        foreach (var s in sessions.EnumerateArray())
        {
            var start = s.TryGetProperty("s", out var sv) ? sv.GetInt64() : 0;
            var plays = new List<PlayEvent>();
            if (s.TryGetProperty("items", out var items))
                foreach (var a in items.EnumerateArray())
                {
                    try
                    {
                        var arr = a.EnumerateArray().ToList();
                        plays.Add(new PlayEvent(arr[0].GetString()!, (int)Num(arr[1]),
                            arr.Count > 2 ? (long)Num(arr[2]) : start, arr.Count > 3 ? (long)Num(arr[3]) : 0));
                    }
                    catch { }
                }
            result.Add(new ListeningSession(start, plays));
        }
        return result;
    }

    private static double Num(JsonElement e) => e.ValueKind == JsonValueKind.String ? double.Parse(e.GetString()!, System.Globalization.CultureInfo.InvariantCulture) : e.GetDouble();

    public static long StartOfYear(int year) => new DateTimeOffset(year, 1, 1, 0, 0, 0, DateTimeOffset.Now.Offset).ToUnixTimeMilliseconds();

    public static long StartOfMonth()
    {
        var n = DateTimeOffset.Now;
        return new DateTimeOffset(n.Year, n.Month, 1, 0, 0, 0, n.Offset).ToUnixTimeMilliseconds();
    }

    /// <summary>The numbers the site computes for signed-out listeners.</summary>
    public static Stats Compute(IEnumerable<PlayEvent> events, long from, long until, IReadOnlyList<Release> releases)
    {
        var byId = releases.ToDictionary(r => r.Id);
        var inRange = events.Where(e => e.Ts >= from && e.Ts < until).ToList();
        var perTrack = inRange.GroupBy(e => (e.R, e.N))
            .Select(g => new StatTrack { R = g.Key.R, N = g.Key.N, Plays = g.Count(), Ms = g.Sum(e => e.Ms) })
            .OrderByDescending(t => t.Plays).ThenByDescending(t => t.Ms).ToList();
        var relPlays = new Dictionary<string, int>();
        var tagPlays = new Dictionary<string, int>();
        foreach (var t in perTrack)
        {
            if (!byId.TryGetValue(t.R, out var rel)) continue;
            var track = rel.Tracks.FirstOrDefault(x => x.N == t.N);
            t.Title = track?.Title; t.Release = rel.Title;
            if (track == null) continue;
            relPlays[t.R] = relPlays.GetValueOrDefault(t.R) + t.Plays;
            foreach (var g in track.Tags.Concat(rel.Tags).Concat(rel.Genres)) tagPlays[g] = tagPlays.GetValueOrDefault(g) + t.Plays;
        }
        return new Stats
        {
            Minutes = (int)(inRange.Sum(e => e.Ms) / 60000),
            Plays = inRange.Count,
            UniqueTracks = perTrack.Count,
            TopTracks = perTrack.Take(10).ToList(),
            TopReleases = relPlays.OrderByDescending(kv => kv.Value).Take(5).Select(kv => new StatRelease { R = kv.Key, Title = byId.TryGetValue(kv.Key, out var r) ? r.Title : kv.Key, Plays = kv.Value }).ToList(),
            TopTags = tagPlays.OrderByDescending(kv => kv.Value).Take(8).Select(kv => new StatTag { Tag = kv.Key, Plays = kv.Value }).ToList(),
            Counts = inRange.GroupBy(e => $"{e.R}|{e.N}").ToDictionary(g => g.Key, g => g.Count()),
        };
    }
}
