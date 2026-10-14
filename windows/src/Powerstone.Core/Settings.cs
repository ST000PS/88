namespace Powerstone.Core;

/// <summary>The site's 3-band DJ EQ: each band 0–1.5×, KILL per band, one ISO (solo) band.</summary>
public sealed record EqState(float Lo = 1f, float Mid = 1f, float Hi = 1f, string[]? Kill = null, string? Solo = null)
{
    public static readonly string[] Bands = { "lo", "mid", "hi" };

    public float Level(string band) => band switch { "lo" => Lo, "mid" => Mid, _ => Hi };

    public bool IsKilled(string band) => Kill?.Contains(band) == true;

    /// <summary>Effective linear gain, same rules as the site.</summary>
    public float Gain(string band) =>
        Solo != null ? (band == Solo ? Math.Max(Level(band), 0.6f) : 0f) : IsKilled(band) ? 0f : Level(band);

    public EqState With(string band, float v) => band switch { "lo" => this with { Lo = v }, "mid" => this with { Mid = v }, _ => this with { Hi = v } };

    public EqState ToggleKill(string band) =>
        this with { Kill = IsKilled(band) ? Kill!.Where(b => b != band).ToArray() : (Kill ?? Array.Empty<string>()).Append(band).ToArray() };

    public EqState ToggleSolo(string band) => this with { Solo = Solo == band ? null : band };

    public bool IsFlat => Lo == 1f && Mid == 1f && Hi == 1f && (Kill == null || Kill.Length == 0) && Solo == null;
}

/// <summary>Pause like a turntable losing power. Lengths in seconds, as on the site.</summary>
public enum VinylStop { Off, Short, Medium, Long }

public enum RepeatMode { Off, One, All }

public static class VinylStopExt
{
    public static double Seconds(this VinylStop v) => v switch { VinylStop.Short => 0.45, VinylStop.Medium => 0.9, VinylStop.Long => 1.6, _ => 0 };
}

/// <summary>Everything the app remembers between runs, saved as JSON in the data folder.</summary>
public sealed class Settings
{
    public Quality Quality { get; set; } = Quality.Auto;
    public EqState Eq { get; set; } = new();
    public VinylStop Vinyl { get; set; } = VinylStop.Medium;
    public float Volume { get; set; } = 0.9f;
    public bool VisualizerOn { get; set; } = true;
    public RepeatMode Repeat { get; set; } = RepeatMode.Off;
    /// <summary>Tracks smart shuffle has played, so it works through everything before repeating.</summary>
    public HashSet<string> ShuffleHeard { get; set; } = new();
    public List<string> PlaylistsDeleted { get; set; } = new();
    /// <summary>True while playlists were made signed-out and still need merging into the account.</summary>
    public bool PlaylistsGuest { get; set; }

    private string? _file;

    public static Settings Load(string dataDir)
    {
        var file = Path.Combine(dataDir, "settings.json");
        Settings s;
        try { s = File.Exists(file) ? Json.Parse<Settings>(File.ReadAllText(file)) : new Settings(); }
        catch { s = new Settings(); }
        s._file = file;
        return s;
    }

    public void Save()
    {
        if (_file == null) return;
        try
        {
            if (PlaylistsDeleted.Count > 200) PlaylistsDeleted = PlaylistsDeleted.Take(200).ToList();
            File.WriteAllText(_file, Json.Write(this));
        }
        catch { }
    }
}
