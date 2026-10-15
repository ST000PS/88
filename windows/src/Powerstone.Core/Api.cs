using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Powerstone.Core;

public sealed class ApiException(string message) : Exception(message);

/// <summary>
/// Client for the thepowerstone.com JSON API. One cookie-backed session, saved to
/// disk so signing in once is enough; the server decides what this account can hear.
/// </summary>
public sealed class Api : IDisposable
{
    public const string Host = "thepowerstone.com";
    public const string Base = "https://" + Host;

    private readonly string _cookieFile;
    private readonly string _catalogFile;
    private readonly CookieContainer _cookies = new();
    public HttpClient Http { get; }

    public Api(string dataDir)
    {
        Directory.CreateDirectory(dataDir);
        _cookieFile = Path.Combine(dataDir, "cookies.json");
        _catalogFile = Path.Combine(dataDir, "catalog.json");
        LoadCookies();
        var handler = new HttpClientHandler { CookieContainer = _cookies, UseCookies = true, AutomaticDecompression = DecompressionMethods.All };
        Http = new HttpClient(handler) { BaseAddress = new Uri(Base), Timeout = TimeSpan.FromSeconds(60) };
        Http.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("Powerstone-Windows", "1.0"));
    }

    public static string? Absolute(string? path) =>
        string.IsNullOrEmpty(path) ? null : path.StartsWith("http", StringComparison.Ordinal) ? path : Base + path;

    // ---------- cookies ----------

    private sealed record StoredCookie(string Name, string Value, string Domain, string Path, DateTime? Expires, bool Secure, bool HttpOnly);

    private void LoadCookies()
    {
        try
        {
            if (!File.Exists(_cookieFile)) return;
            foreach (var c in Json.Parse<List<StoredCookie>>(File.ReadAllText(_cookieFile)))
            {
                if (c.Expires is { } e && e < DateTime.UtcNow) continue;
                _cookies.Add(new Cookie(c.Name, c.Value, c.Path, c.Domain) { Secure = c.Secure, HttpOnly = c.HttpOnly });
            }
        }
        catch { /* a bad cookie file just means signing in again */ }
    }

    /// <summary>Session cookies have no expiry; keep them anyway so sign-in survives restarts.</summary>
    private void SaveCookies()
    {
        try
        {
            var list = _cookies.GetAllCookies().Cast<Cookie>().Where(c => !c.Expired)
                .Select(c => new StoredCookie(c.Name, c.Value, c.Domain, c.Path, c.Expires == DateTime.MinValue ? null : c.Expires.ToUniversalTime(), c.Secure, c.HttpOnly))
                .ToList();
            File.WriteAllText(_cookieFile, Json.Write(list));
        }
        catch { }
    }

    public void ClearCookies()
    {
        foreach (Cookie c in _cookies.GetAllCookies()) c.Expired = true;
        try { File.Delete(_cookieFile); } catch { }
    }

    // ---------- plumbing ----------

    private async Task<(int Code, string Body)> SendAsync(HttpMethod method, string path, string? json = null, CancellationToken ct = default)
    {
        using var req = new HttpRequestMessage(method, path);
        req.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
        if (json != null) req.Content = new StringContent(json, Encoding.UTF8, "application/json");
        using var resp = await Http.SendAsync(req, ct).ConfigureAwait(false);
        var body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        if (resp.Headers.Contains("Set-Cookie")) SaveCookies();
        return ((int)resp.StatusCode, body);
    }

    private Task<(int, string)> Get(string path, CancellationToken ct = default) => SendAsync(HttpMethod.Get, path, null, ct);
    private Task<(int, string)> Post(string path, object body) => SendAsync(HttpMethod.Post, path, JsonSerializer.Serialize(body, Json.Options));

    private async Task<T> GetJson<T>(string path, string what)
    {
        var (code, body) = await Get(path);
        if (code == 401) throw new ApiException($"Sign in to see {what}.");
        if (code != 200) throw new ApiException($"Couldn't load {what} ({code}).");
        return Json.Parse<T>(body);
    }

    private static JsonObject? Obj(string body) { try { return JsonNode.Parse(body) as JsonObject; } catch { return null; } }

    // ---------- catalogue & account ----------

    /// <summary>Last catalogue saved to disk, for instant start-up and offline browsing.</summary>
    public Catalog? CachedCatalog()
    {
        try { return File.Exists(_catalogFile) ? Json.Parse<Catalog>(File.ReadAllText(_catalogFile)) : null; }
        catch { return null; }
    }

    public async Task<Catalog> CatalogAsync()
    {
        var (code, body) = await Get("/api/catalog");
        if (code != 200) throw new ApiException($"Catalogue request failed ({code})");
        var c = Json.Parse<Catalog>(body);
        try { await File.WriteAllTextAsync(_catalogFile, body); } catch { }
        return c;
    }

    /// <summary>/api/me, or null when the site couldn't be reached (so callers can tell "offline" from "signed out").</summary>
    public async Task<Me?> TryMeAsync()
    {
        try
        {
            var (code, body) = await Get("/api/me");
            return code == 200 ? Json.Parse<Me>(body) : code is 401 or 403 ? new Me() : null;
        }
        catch { return null; }
    }

    public async Task<Me> MeAsync()
    {
        try
        {
            var (code, body) = await Get("/api/me");
            return code == 200 ? Json.Parse<Me>(body) : new Me();
        }
        catch { return new Me(); }
    }

    /// <summary>Step 1 of email sign-in: the site emails a 6-digit code.</summary>
    public async Task RequestCodeAsync(string email)
    {
        var (code, _) = await Post("/api/login", new { email });
        if (code is < 200 or > 299) throw new ApiException($"Couldn't send a code ({code}).");
    }

    /// <summary>Step 2: on success the session cookie lands in the cookie jar.</summary>
    public async Task VerifyCodeAsync(string email, string sixDigits)
    {
        var (code, body) = await Post("/api/login/code", new { email, code = sixDigits });
        var o = Obj(body);
        if (code is < 200 or > 299 || o?["ok"]?.GetValue<bool>() != true)
            throw new ApiException(o?["error"]?.GetValue<string>() == "expired"
                ? "That code expired or was already used. Send a new one."
                : "That code didn't work.");
        SaveCookies();
    }

    public async Task PasswordLoginAsync(string email, string password)
    {
        var (code, body) = await Post("/api/login/password", new { email, password });
        var o = Obj(body);
        if (code == 404) throw new ApiException("Password sign-in isn't available. Use an email code.");
        if (code is < 200 or > 299 || (o?["ok"] is JsonValue v && v.TryGetValue<bool>(out var ok) && !ok))
            throw new ApiException(o?["message"]?.GetValue<string>() ?? "Email or password didn't match.");
        SaveCookies();
    }

    public async Task LogoutAsync()
    {
        try { await Get("/logout"); } catch { }
        ClearCookies();
    }

    public async Task<bool> HasPasswordAsync()
    {
        try { return Obj((await Get("/api/password")).Item2)?["hasPassword"]?.GetValue<bool>() == true; }
        catch { return false; }
    }

    public async Task SetPasswordAsync(string password)
    {
        var (code, body) = await Post("/api/password", new { password });
        if (code is < 200 or > 299) throw new ApiException(Obj(body)?["message"]?.GetValue<string>() ?? $"Couldn't save the password ({code}).");
    }

    /// <summary>Stripe checkout ("month" / "year") or the billing portal; opened in the browser.</summary>
    public async Task<string> CheckoutUrlAsync(string plan)
    {
        var (code, body) = await Post("/api/checkout", new { plan });
        return code is >= 200 and <= 299 && Obj(body)?["url"]?.GetValue<string>() is { } u ? u : throw new ApiException("Checkout didn't open. Try again in a moment.");
    }

    public async Task<string> PortalUrlAsync()
    {
        var (code, body) = await Post("/api/portal", new { });
        return code is >= 200 and <= 299 && Obj(body)?["url"]?.GetValue<string>() is { } u ? u : throw new ApiException("Your account page didn't open. Try again.");
    }

    // ---------- favourites ----------

    public async Task<List<Fav>> FavsAsync()
    {
        var (code, body) = await Get("/api/favs");
        if (code == 401) return new();
        if (code != 200) throw new ApiException($"Favourites request failed ({code})");
        return Json.Parse<FavsResponse>(body).Favs;
    }

    public async Task<List<Fav>?> SetFavAsync(string id, bool on)
    {
        var (code, body) = await Post("/api/favs", new { id, on });
        if (code is < 200 or > 299) throw new ApiException($"Couldn't update favourite ({code})");
        try { return Json.Parse<FavsResponse>(body).Favs; } catch { return null; }
    }

    // ---------- playlists & sharing ----------

    public Task<PlaylistsBody> PlaylistsAsync() => GetJson<PlaylistsBody>("/api/playlists", "playlists");

    public async Task<bool> PutPlaylistsAsync(PlaylistsBody body)
    {
        try { return (await SendAsync(HttpMethod.Put, "/api/playlists", Json.Write(body))).Code is >= 200 and <= 299; }
        catch { return false; }
    }

    /// <summary>Publishes a playlist snapshot; returns the id used in /?s=&lt;id&gt; links.</summary>
    public async Task<string> SharePlaylistAsync(string name, List<TrackRef> tracks)
    {
        var (code, body) = await Post("/api/share", new SharedPlaylist { Name = name, Tracks = tracks });
        return code is >= 200 and <= 299 && Obj(body)?["id"]?.GetValue<string>() is { } id ? id : throw new ApiException("Couldn't share right now.");
    }

    public Task<SharedPlaylist> SharedPlaylistAsync(string id) => GetJson<SharedPlaylist>("/api/share/" + Uri.EscapeDataString(id), "that shared playlist");

    // ---------- listening history ----------

    public async Task<bool> ReportPlaysAsync(IReadOnlyList<PlayEvent> events)
    {
        if (events.Count == 0) return true;
        try { return (await Post("/api/plays", new { events })).Item1 is >= 200 and <= 299; }
        catch { return false; }
    }

    /// <summary>Sessions from /api/history; each item is a [release, n, ts, ms] array.</summary>
    public async Task<List<ListeningSession>> HistoryAsync()
    {
        var (code, body) = await Get("/api/history");
        if (code == 401) throw new ApiException("Sign in to see your history.");
        if (code != 200) throw new ApiException($"Couldn't load your history ({code}).");
        return StatsMath.ParseServerSessions(body);
    }

    /// <summary><paramref name="period"/> is "month" or "year".</summary>
    public Task<Stats> StatsAsync(string period, int year) => GetJson<Stats>($"/api/stats?period={period}&year={year}", "your stats");

    // ---------- media ----------

    /// <summary>Downloads a track or image with the session cookie (full tracks need it).</summary>
    public async Task<byte[]> DownloadAsync(string path, IProgress<double>? progress = null, CancellationToken ct = default)
    {
        using var resp = await Http.GetAsync(Absolute(path), HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        if (resp.StatusCode == HttpStatusCode.Unauthorized) throw new ApiException("This track needs a subscription.");
        resp.EnsureSuccessStatusCode();
        var total = resp.Content.Headers.ContentLength ?? 0;
        await using var s = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var ms = total > 0 ? new MemoryStream((int)total) : new MemoryStream();
        var buf = new byte[81920];
        int n;
        while ((n = await s.ReadAsync(buf, ct).ConfigureAwait(false)) > 0)
        {
            ms.Write(buf, 0, n);
            if (total > 0) progress?.Report((double)ms.Length / total);
        }
        return ms.ToArray();
    }

    public void Dispose() => Http.Dispose();
}
