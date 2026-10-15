using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Windows;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Powerstone.Core;

namespace Powerstone.Windows.ViewModels;

/// <summary>The app: account, catalogue, favourites, playlists, navigation and the player.</summary>
public sealed partial class Shell : ObservableObject
{
    public Api Api { get; }
    public Settings Settings { get; }
    public PlaylistStore Playlists { get; }
    public ListeningHistory History { get; }
    public PlayerViewModel Player { get; }
    public OfflineStore Offline { get; }
    public Services.VideoCache Videos { get; }
    public Dispatcher Ui { get; } = Application.Current.Dispatcher;

    [ObservableProperty] private List<Release> _releases = new();
    [ObservableProperty] private Me _me = new();
    [ObservableProperty] private string? _toast;
    [ObservableProperty] private PageBase? _page;
    [ObservableProperty] private bool _loading = true;
    [ObservableProperty] private string? _error;
    [ObservableProperty] private bool _hasPassword;
    [ObservableProperty] private string? _downloadStatus;
    [ObservableProperty] private bool _isOffline;

    public ObservableCollection<Playlist> PlaylistList { get; } = new();
    public List<string> Favs { get; private set; } = new();
    private readonly Stack<PageBase> _back = new();
    private readonly DispatcherTimer _toastTimer = new() { Interval = TimeSpan.FromSeconds(2.6) };

    public bool CanGoBack => _back.Count > 0;
    public string TierText => Me.IsSubscriber ? "SUBSCRIBER · full tracks" : Me.SignedIn ? "NO ACTIVE PLAN · previews" : "GUEST · 28s previews";

    public Shell(string dataDir)
    {
        Api = new Api(dataDir);
        Settings = Settings.Load(dataDir);
        Playlists = new PlaylistStore(Api, Settings, dataDir);
        History = new ListeningHistory(dataDir);
        Offline = new OfflineStore(dataDir, (url, ct) => Api.DownloadAsync(url, null, ct));
        Videos = new Services.VideoCache(Api, dataDir);
        if (Settings.LastMe != null) Me = Settings.LastMe; // known account even when starting offline
        Player = new PlayerViewModel(this);
        Offline.Changed += () => Ui.BeginInvoke(() => { Page?.Refresh(); OnPropertyChanged(nameof(OfflineSummary)); });
        Playlists.Changed += () => Ui.BeginInvoke(SyncPlaylists);
        SyncPlaylists();
        _toastTimer.Tick += (_, _) => { Toast = null; _toastTimer.Stop(); };
        var cached = Api.CachedCatalog();
        if (cached != null) { Releases = cached.Releases; Loading = false; }
        Page = new CatalogueViewModel(this);
    }

    partial void OnMeChanged(Me value) => OnPropertyChanged(nameof(TierText));

    public async Task StartAsync(string? link)
    {
        await RefreshAsync();
        Player.RestoreSession();
        if (link != null) OpenLink(link);
    }

    [RelayCommand]
    public async Task RefreshAsync()
    {
        Error = null;
        try { Releases = (await Api.CatalogAsync()).Releases; }
        catch (Exception e) { if (Releases.Count == 0) Error = "Couldn't load the catalogue. " + e.Message; }
        Loading = false;
        await RefreshAccountAsync();
        Page?.Refresh();
    }

    public async Task RefreshAccountAsync()
    {
        var me = await Api.TryMeAsync();
        IsOffline = me == null;
        if (me != null)
        {
            Me = me;
            Settings.LastMe = me.SignedIn ? me : null;
            Settings.Save();
            // Only a real answer from the site may lock or delete downloads — never a network failure.
            Offline.AccountChecked(me);
            if (!me.IsSubscriber) Videos.Clear();
        }
        try { Favs = Me.SignedIn ? (await Api.FavsAsync()).Select(f => f.Id).ToList() : new(); } catch { }
        HasPassword = Me.SignedIn && await Api.HasPasswordAsync();
        await Playlists.LoadAsync(Me.SignedIn);
        Page?.Refresh();
    }

    // ---------- downloads ----------

    private CancellationTokenSource? _downloadCts;

    public string OfflineSummary => Offline.Count == 0 ? "No downloads yet."
        : $"{Offline.Count} tracks · {Offline.TotalBytes / 1_048_576.0:0} MB" + (Offline.Usable ? "" : " · locked until your subscription is confirmed online");

    /// <summary>Downloads tracks for offline listening (subscribers), one at a time, with status in the rail.</summary>
    public async Task DownloadAsync(IReadOnlyList<QueueItem> items, string what)
    {
        if (!Me.IsSubscriber) { ShowToast("Downloads are for subscribers"); return; }
        if (_downloadCts != null) { ShowToast("A download is already running"); return; }
        var cts = _downloadCts = new CancellationTokenSource();
        try
        {
            var q = Player.DownloadQuality();
            var progress = new Progress<(int Done, int Total)>(p => DownloadStatus = p.Total == 0 ? null : $"↓ {what} · {p.Done}/{p.Total}");
            var n = await Offline.DownloadAsync(items, i => i.Track.StreamPath(true, q), progress, cts.Token);
            ShowToast(n == 0 ? $"{what} is already downloaded" : $"Downloaded {what} ({n} tracks)");
        }
        catch (OperationCanceledException) { ShowToast("Download stopped"); }
        catch (Exception e) { ShowToast("Download failed: " + e.Message); }
        finally { _downloadCts = null; DownloadStatus = null; OnPropertyChanged(nameof(OfflineSummary)); Page?.Refresh(); }
    }

    [RelayCommand] private void CancelDownload() => _downloadCts?.Cancel();

    public void RemoveDownloads(IEnumerable<QueueItem> items) { Offline.Remove(items.Select(i => i.Id)); ShowToast("Download removed"); }

    public void Shutdown()
    {
        Player.Shutdown();
        Settings.Save();
    }

    public void ShowToast(string message)
    {
        Toast = message;
        _toastTimer.Stop();
        _toastTimer.Start();
    }

    private void SyncPlaylists()
    {
        PlaylistList.Clear();
        foreach (var p in Playlists.List) PlaylistList.Add(p);
        Page?.Refresh();
    }

    // ---------- lookups ----------

    public Release? Release(string id) => Releases.FirstOrDefault(r => r.Id == id);

    public QueueItem? Lookup(string r, int n)
    {
        var rel = Release(r);
        var t = rel?.Tracks.FirstOrDefault(x => x.N == n);
        return rel != null && t != null ? new QueueItem(rel, t) : null;
    }

    public QueueItem? Lookup(string id)
    {
        var i = id.LastIndexOf('/');
        return i > 0 && int.TryParse(id[(i + 1)..], out var n) ? Lookup(id[..i], n) : null;
    }

    public List<QueueItem> Resolve(IEnumerable<TrackRef> refs) => refs.Select(r => Lookup(r.R, r.N)).OfType<QueueItem>().ToList();

    public List<QueueItem> Tagged(string tag) => Releases.SelectMany(r =>
    {
        var relHas = r.Tags.Contains(tag) || r.Genres.Contains(tag);
        return r.Tracks.Where(t => relHas || t.Tags.Contains(tag)).Select(t => new QueueItem(r, t));
    }).ToList();

    // ---------- navigation ----------

    public void Navigate(PageBase page)
    {
        if (Page != null) _back.Push(Page);
        Page = page;
        OnPropertyChanged(nameof(CanGoBack));
    }

    /// <summary>Top-level destinations from the rail clear the back stack.</summary>
    public void NavigateRoot(PageBase page)
    {
        _back.Clear();
        Page = page;
        OnPropertyChanged(nameof(CanGoBack));
    }

    [RelayCommand]
    public void Back()
    {
        if (_back.Count == 0) return;
        Page = _back.Pop();
        Page.Refresh();
        OnPropertyChanged(nameof(CanGoBack));
    }

    [RelayCommand] private void GoCatalogue() => NavigateRoot(new CatalogueViewModel(this));
    [RelayCommand] private void GoBpm() => NavigateRoot(new BpmViewModel(this));
    [RelayCommand] private void GoPlaylists() => NavigateRoot(new PlaylistsViewModel(this));
    [RelayCommand] private void GoMe() => NavigateRoot(new MeViewModel(this, null));
    [RelayCommand] private void GoAccount() => NavigateRoot(new AccountViewModel(this));
    [RelayCommand] private void GoSound() => NavigateRoot(new SoundViewModel(this));
    [RelayCommand] private void GoNowPlaying() => Navigate(new NowPlayingViewModel(this));

    /// <summary>Switch between the full window and the always-on-top mini player.</summary>
    public event Action? ToggleMiniRequested;
    [RelayCommand] private void ToggleMini() => ToggleMiniRequested?.Invoke();

    public void OpenRelease(Release r, int? focus = null) => Navigate(new ReleaseViewModel(this, r, focus));
    public void OpenTag(string tag) => Navigate(new TagViewModel(this, tag));
    public void OpenPlaylist(string id) => Navigate(new PlaylistViewModel(this, id));
    [RelayCommand] private void OpenPlaylistItem(Playlist p) => OpenPlaylist(p.Id);

    /// <summary>thepowerstone.com links: ?r= &amp;t= ?s= ?tag= ?wrap= ?me (paste one into search, or pass it on the command line).</summary>
    public bool OpenLink(string text)
    {
        if (!Uri.TryCreate(text.Trim(), UriKind.Absolute, out var uri) || !uri.Host.EndsWith(Api.Host, StringComparison.OrdinalIgnoreCase)) return false;
        var q = System.Web.HttpUtility.ParseQueryString(uri.Query);
        if (q["r"] is { } r)
        {
            var rel = Release(r);
            if (rel != null) OpenRelease(rel, int.TryParse(q["t"], out var t) ? t : null);
            else ShowToast("That release isn't in the catalogue.");
        }
        else if (q["tag"] is { } tag) OpenTag(tag);
        else if (q["s"] is { } s) Navigate(new SharedPlaylistViewModel(this, s));
        if (int.TryParse(q["wrap"], out var wrap)) Navigate(new MeViewModel(this, wrap));
        else if (q.AllKeys.Contains("me") || uri.Query.Contains("?me")) Navigate(new MeViewModel(this, null));
        return true;
    }

    // ---------- favourites ----------

    public bool IsFav(QueueItem item) => Favs.Contains(item.Id);

    public async Task ToggleFavAsync(QueueItem item)
    {
        if (!Me.SignedIn) { ShowToast("Sign in to save favourites"); return; }
        var on = !Favs.Contains(item.Id);
        var before = Favs.ToList();
        Favs = on ? new[] { item.Id }.Concat(Favs).ToList() : Favs.Where(f => f != item.Id).ToList();
        Page?.Refresh(); Player.RefreshFav();
        ShowToast(on ? $"{item.Track.Title} — added to favourites" : $"{item.Track.Title} — removed");
        try
        {
            if (await Api.SetFavAsync(item.Id, on) is { } server) Favs = server.Select(f => f.Id).ToList();
        }
        catch { Favs = before; ShowToast("Couldn't update favourites"); }
        Page?.Refresh(); Player.RefreshFav();
    }

    // ---------- playlists & sharing ----------

    public Playlist NewPlaylist(QueueItem? with = null)
    {
        var pl = Playlists.Create(tracks: with != null ? new[] { new TrackRef(with.Release.Id, with.Track.N) } : null);
        if (with != null) ShowToast($"Added to {pl.Name}");
        return pl;
    }

    public void AddToPlaylist(Playlist pl, QueueItem item) =>
        ShowToast(Playlists.Add(pl.Id, new TrackRef(item.Release.Id, item.Track.N)) ? $"Added to {pl.Name}" : $"Already in {pl.Name}");

    /// <summary>Copies a thepowerstone.com link (it opens in this app or on the site).</summary>
    public void Share(string path, string what)
    {
        try { Clipboard.SetText(Api.Base + path); ShowToast($"Link to {what} copied"); }
        catch { ShowToast(Api.Base + path); }
    }

    public void ShareTrack(QueueItem i) => Share($"/?r={Uri.EscapeDataString(i.Release.Id)}&t={i.Track.N}", i.Track.Title);

    public static void OpenUrl(string url)
    {
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); } catch { }
    }
}
