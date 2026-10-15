using System.Text.Json;
using System.Text.Json.Serialization;

namespace Powerstone.Core;

public sealed class Catalog
{
    [JsonPropertyName("releases")] public List<Release> Releases { get; set; } = new();
}

public sealed class Release
{
    public static readonly string[] SeriesNames = { "WT", "HX", "VGM", "ガムビー", "家" };

    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("title")] public string Title { get; set; } = "";
    [JsonPropertyName("artist")] public string Artist { get; set; } = "";
    [JsonPropertyName("year")] public string Year { get; set; } = "";
    [JsonPropertyName("date")] public string Date { get; set; } = "";
    [JsonPropertyName("sort")] public long Sort { get; set; }
    [JsonPropertyName("kind")] public string Kind { get; set; } = "released";
    [JsonPropertyName("preview_seconds")] public int PreviewSeconds { get; set; } = 28;
    [JsonPropertyName("cover")] public string? Cover { get; set; }
    [JsonPropertyName("thumb")] public string? Thumb { get; set; }
    [JsonPropertyName("genres")] public List<string> Genres { get; set; } = new();
    [JsonPropertyName("tags")] public List<string> Tags { get; set; } = new();
    [JsonPropertyName("bandcamp")] public string? Bandcamp { get; set; }
    [JsonPropertyName("tracks")] public List<Track> Tracks { get; set; } = new();

    /// <summary>Release series, the same grouping as the site's 表示 view.</summary>
    [JsonIgnore] public string Series => SeriesNames.FirstOrDefault(s => Title.StartsWith(s, StringComparison.Ordinal)) ?? "OTHER";
    [JsonIgnore] public double TotalSeconds => Tracks.Sum(t => t.Duration);
    [JsonIgnore] public bool HasVideo => Tracks.Any(t => t.Video != null);
    [JsonIgnore] public (int Lo, int Hi)? BpmRange
    {
        get
        {
            var b = Tracks.Where(t => t.Bpm != null).Select(t => t.Bpm!.Value).ToList();
            return b.Count == 0 ? null : (b.Min(), b.Max());
        }
    }
    [JsonIgnore] public string KindLabel => Kind switch { "unreleased" => "UNRELEASED", "loose" => "RARE TRAX", _ => "RELEASED · REMASTERED" };
    [JsonIgnore] public string DisplayDate => string.IsNullOrEmpty(Date) ? Year : Date.Replace('-', '.');
    [JsonIgnore] public bool IsAlbum => Kind is "released" or "unreleased" or "loose";
    [JsonIgnore] public string Summary => $"{DisplayDate} · {Tracks.Count} TRK";
    [JsonIgnore] public string Details => string.Join(" · ", new[]
    {
        Series == "OTHER" ? null : Series, DisplayDate, $"{Tracks.Count} TRK", Format.Duration(TotalSeconds),
        BpmRange is { } b ? (b.Lo == b.Hi ? $"{b.Lo} BPM" : $"{b.Lo}–{b.Hi} BPM") : null,
        HasVideo ? "▣ VIDEO" : null,
    }.Where(x => x != null));
}

public sealed class Track
{
    [JsonPropertyName("n")] public int N { get; set; }
    [JsonPropertyName("title")] public string Title { get; set; } = "";
    [JsonPropertyName("duration")] public double Duration { get; set; }
    [JsonPropertyName("bpm")] public int? Bpm { get; set; }
    [JsonPropertyName("key")] public string? Key { get; set; }
    [JsonPropertyName("key_ja")] public string? KeyJa { get; set; }
    [JsonPropertyName("tags")] public List<string> Tags { get; set; } = new();
    [JsonPropertyName("video")] public string? Video { get; set; }
    [JsonPropertyName("preview")] public string? Preview { get; set; }
    [JsonPropertyName("lo")] public string? Lo { get; set; }
    [JsonPropertyName("std")] public string? Std { get; set; }
    [JsonPropertyName("full")] public string? Full { get; set; }

    [JsonIgnore] public bool IsMinor => Key?.EndsWith('m') == true;

    /// <summary>Same choice as the site: subscribers get the chosen quality, everyone else the preview.</summary>
    public string? StreamPath(bool subscriber, Quality quality) =>
        !subscriber ? Preview : (quality switch
        {
            Quality.Lo => Lo ?? Std ?? Full,
            Quality.Std => Std ?? Full,
            _ => Full,
        }) ?? Preview;
}

/// <summary>Stream quality, as on the site. Auto is resolved by the app before streaming.</summary>
public enum Quality { Auto, High, Std, Lo }

public sealed class Me
{
    [JsonPropertyName("tier")] public string Tier { get; set; } = "guest";
    [JsonPropertyName("signedIn")] public bool SignedIn { get; set; }
    [JsonPropertyName("email")] public string? Email { get; set; }
    [JsonPropertyName("owner")] public bool Owner { get; set; }
    [JsonPropertyName("status")] public string? Status { get; set; }
    [JsonPropertyName("plan")] public string? Plan { get; set; }
    [JsonPropertyName("currency")] public string Currency { get; set; } = "usd";

    [JsonIgnore] public bool IsSubscriber => Tier == "sub";
    [JsonIgnore] public (string Month, string Year) Prices => Currency == "jpy" ? ("¥888", "¥8,888") : ("$8.88", "$88.88");
}

public sealed record Fav([property: JsonPropertyName("id")] string Id, [property: JsonPropertyName("t")] long T = 0);
public sealed class FavsResponse { [JsonPropertyName("favs")] public List<Fav> Favs { get; set; } = new(); }

public sealed record PlayEvent(
    [property: JsonPropertyName("r")] string R,
    [property: JsonPropertyName("n")] int N,
    [property: JsonPropertyName("ts")] long Ts,
    [property: JsonPropertyName("ms")] long Ms);

public sealed record TrackRef([property: JsonPropertyName("r")] string R, [property: JsonPropertyName("n")] int N);

public sealed class Playlist
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("tracks")] public List<TrackRef> Tracks { get; set; } = new();
    [JsonPropertyName("updated")] public long Updated { get; set; }

    public Playlist Clone() => new() { Id = Id, Name = Name, Tracks = new(Tracks), Updated = Updated };
}

public sealed class PlaylistsBody
{
    [JsonPropertyName("playlists")] public List<Playlist> Playlists { get; set; } = new();
    [JsonPropertyName("deleted")] public List<string> Deleted { get; set; } = new();
}

public sealed class SharedPlaylist
{
    [JsonPropertyName("name")] public string Name { get; set; } = "Shared playlist";
    [JsonPropertyName("tracks")] public List<TrackRef> Tracks { get; set; } = new();
}

public sealed record ListeningSession(long Start, List<PlayEvent> Plays);

public sealed class StatTrack
{
    [JsonPropertyName("r")] public string R { get; set; } = "";
    [JsonPropertyName("n")] public int N { get; set; }
    [JsonPropertyName("plays")] public int Plays { get; set; }
    [JsonPropertyName("ms")] public long Ms { get; set; }
    [JsonPropertyName("title")] public string? Title { get; set; }
    [JsonPropertyName("release")] public string? Release { get; set; }
}

public sealed class StatRelease
{
    [JsonPropertyName("r")] public string R { get; set; } = "";
    [JsonPropertyName("title")] public string Title { get; set; } = "";
    [JsonPropertyName("plays")] public int Plays { get; set; }
}

public sealed class StatTag
{
    [JsonPropertyName("tag")] public string Tag { get; set; } = "";
    [JsonPropertyName("plays")] public int Plays { get; set; }
}

public sealed class Stats
{
    [JsonPropertyName("minutes")] public int Minutes { get; set; }
    [JsonPropertyName("plays")] public int Plays { get; set; }
    [JsonPropertyName("uniqueTracks")] public int UniqueTracks { get; set; }
    [JsonPropertyName("topTracks")] public List<StatTrack> TopTracks { get; set; } = new();
    [JsonPropertyName("topReleases")] public List<StatRelease> TopReleases { get; set; } = new();
    [JsonPropertyName("topTags")] public List<StatTag> TopTags { get; set; } = new();
    /// <summary>Play count per "release|n" in the period.</summary>
    [JsonPropertyName("counts")] public Dictionary<string, int> Counts { get; set; } = new();
}

public static class Json
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNameCaseInsensitive = true,
        NumberHandling = JsonNumberHandling.AllowReadingFromString,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
    };

    public static T Parse<T>(string s) => JsonSerializer.Deserialize<T>(s, Options) ?? throw new JsonException("empty JSON");
    public static string Write<T>(T v) => JsonSerializer.Serialize(v, Options);
}

public static class Format
{
    public static string Duration(double seconds)
    {
        var s = (int)seconds;
        return s >= 3600 ? $"{s / 3600}:{s / 60 % 60:00}:{s % 60:00}" : $"{s / 60}:{s % 60:00}";
    }

    /// <summary>Fav / play ids on the site are "&lt;releaseId&gt;/&lt;trackNumber&gt;".</summary>
    public static string TrackId(Release r, Track t) => $"{r.Id}/{t.N}";
}
