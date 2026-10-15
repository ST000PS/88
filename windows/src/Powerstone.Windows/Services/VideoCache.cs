using System.IO;
using Powerstone.Core;

namespace Powerstone.Windows.Services;

/// <summary>
/// Music videos for subscribers. Windows' built-in video player can't send the session cookie,
/// so each video is fetched with the app's own (signed-in) client into a small cache in the data
/// folder and played from there. Oldest videos are dropped past <see cref="MaxBytes"/>.
/// </summary>
public sealed class VideoCache(Api api, string dataDir)
{
    private const long MaxBytes = 700L * 1024 * 1024;
    private readonly string _dir = Path.Combine(dataDir, "video-cache");
    private readonly Dictionary<string, Task<string?>> _inFlight = new();

    private static string Name(string id) => string.Concat(id.Select(c => char.IsLetterOrDigit(c) || c is '-' or '_' ? c : '_')) + ".mp4";

    /// <summary>Local path of the video for <paramref name="item"/> (downloading it if needed), or null.</summary>
    public Task<string?> GetAsync(QueueItem item)
    {
        if (item.Track.Video == null) return Task.FromResult<string?>(null);
        var file = Path.Combine(_dir, Name(item.Id));
        if (File.Exists(file)) { try { File.SetLastAccessTimeUtc(file, DateTime.UtcNow); } catch { } return Task.FromResult<string?>(file); }
        if (_inFlight.TryGetValue(item.Id, out var t)) return t;
        return _inFlight[item.Id] = FetchAsync(item, file);
    }

    private async Task<string?> FetchAsync(QueueItem item, string file)
    {
        try
        {
            Directory.CreateDirectory(_dir);
            var bytes = await api.DownloadAsync(item.Track.Video!);
            var tmp = file + ".part";
            await File.WriteAllBytesAsync(tmp, bytes);
            File.Move(tmp, file, overwrite: true);
            Trim();
            return file;
        }
        catch { return null; }
        finally { _inFlight.Remove(item.Id); }
    }

    private void Trim()
    {
        var files = new DirectoryInfo(_dir).GetFiles("*.mp4").OrderBy(f => f.LastAccessTimeUtc).ToList();
        var total = files.Sum(f => f.Length);
        foreach (var f in files)
        {
            if (total <= MaxBytes) break;
            total -= f.Length;
            try { f.Delete(); } catch { }
        }
    }

    public void Clear() { try { if (Directory.Exists(_dir)) Directory.Delete(_dir, recursive: true); } catch { } }
}
