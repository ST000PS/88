using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Input;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Powerstone.Core;

namespace Powerstone.Windows.ViewModels;

public abstract partial class PageBase(Shell shell) : ObservableObject
{
    public Shell Shell { get; } = shell;
    public PlayerViewModel Player => Shell.Player;

    /// <summary>Re-reads shared state (favourites, now playing, playlists) into the page.</summary>
    public virtual void Refresh() { }

    protected TrackRowViewModel Row(QueueItem item, int number, bool showAlbum, Action play, IReadOnlyList<MenuEntry>? extra = null) =>
        new(Shell, item, number, showAlbum, play, extra);

    protected static void RefreshRows(IEnumerable<TrackRowViewModel> rows) { foreach (var r in rows) r.Refresh(); }
}

public sealed record MenuEntry(string Header, ICommand Command);

/// <summary>A track line: number or cover, title, BPM / key / video, duration, heart and a ⋯ menu.</summary>
public sealed partial class TrackRowViewModel : ObservableObject
{
    private readonly Shell _shell;
    private readonly Action _play;

    public QueueItem Item { get; }
    public int Number { get; }
    public bool ShowAlbum { get; }
    public IReadOnlyList<MenuEntry> ExtraMenu { get; }
    public bool HasExtraMenu => ExtraMenu.Count > 0;

    [ObservableProperty] private bool _isFav;
    [ObservableProperty] private bool _isCurrent;

    public string NumberText => Number.ToString("00");
    public string Title => Item.Track.Title;
    public string Duration => Format.Duration(Item.Track.Duration);
    public string? Thumb => Item.Release.Thumb ?? Item.Release.Cover;
    public bool HasVideo => Item.Track.Video != null;
    public bool IsOffline => _shell.Offline.Has(Item);
    public bool SignedIn => _shell.Me.SignedIn;
    public string Meta => string.Join(" · ", new[]
    {
        ShowAlbum ? Item.Release.Title : null,
        Item.Track.Bpm is { } b ? $"{b} BPM" : null,
        Item.Track.Key is { Length: > 0 } k ? $"KEY {k}" : null,
        HasVideo ? "▣ VIDEO" : null,
        IsOffline ? "↓ DOWNLOADED" : null,
    }.Where(x => x != null));

    public TrackRowViewModel(Shell shell, QueueItem item, int number, bool showAlbum, Action play, IReadOnlyList<MenuEntry>? extra)
    {
        _shell = shell; _play = play;
        Item = item; Number = number; ShowAlbum = showAlbum;
        ExtraMenu = extra ?? Array.Empty<MenuEntry>();
        Refresh();
    }

    public void Refresh()
    {
        IsFav = _shell.IsFav(Item);
        IsCurrent = _shell.Player.Current?.Id == Item.Id;
        OnPropertyChanged(nameof(SignedIn));
        OnPropertyChanged(nameof(PlaylistMenu));
        OnPropertyChanged(nameof(Meta));
    }

    /// <summary>"Add to playlist" entries, built when the menu opens so new playlists show up.</summary>
    public IEnumerable<MenuEntry> PlaylistMenu =>
        _shell.Playlists.List.Select(pl => new MenuEntry(
            (pl.Tracks.Contains(new TrackRef(Item.Release.Id, Item.Track.N)) ? "✓ " : "") + $"{pl.Name}  ({pl.Tracks.Count})",
            new RelayCommand(() => _shell.AddToPlaylist(pl, Item))))
        .Append(new MenuEntry("+ New playlist", new RelayCommand(() => _shell.NewPlaylist(Item))));

    [RelayCommand] private void Play() => _play();
    [RelayCommand] private Task Fav() => _shell.ToggleFavAsync(Item);
    [RelayCommand] private void Share() => _shell.ShareTrack(Item);
    [RelayCommand] private void OpenRelease() => _shell.OpenRelease(Item.Release, Item.Track.N);
}

// ---------------------------------------------------------------- catalogue

public sealed partial class CatalogueViewModel : PageBase
{
    public static readonly string[] SeriesChoices = new[] { "ALL" }.Concat(Release.SeriesNames).Append("OTHER").ToArray();

    [ObservableProperty] private string _query = "";
    [ObservableProperty] private string _series = "ALL";
    [ObservableProperty] private bool _videoOnly;
    [ObservableProperty] private string _sort = "new";
    [ObservableProperty] private bool _gridMode = true;
    [ObservableProperty] private List<Release> _released = new();
    [ObservableProperty] private List<Release> _unreleased = new();
    [ObservableProperty] private List<TrackRowViewModel> _songs = new();
    [ObservableProperty] private string _count = "";

    public CatalogueViewModel(Shell shell) : base(shell) => Apply();

    partial void OnQueryChanged(string value)
    {
        if (value.StartsWith("http", StringComparison.OrdinalIgnoreCase) && Shell.OpenLink(value)) { Query = ""; return; }
        Apply();
    }
    partial void OnSeriesChanged(string value) => Apply();
    partial void OnVideoOnlyChanged(bool value) => Apply();
    partial void OnSortChanged(string value) => Apply();

    public override void Refresh() { Apply(); }

    private void Apply()
    {
        var q = Query.Trim().ToLowerInvariant();
        IEnumerable<Release> list = Shell.Releases.Where(r =>
            (Series == "ALL" || r.Series == Series) && (!VideoOnly || r.HasVideo) &&
            (q.Length == 0 || (r.Title + " " + string.Join(" ", r.Tracks.Select(t => t.Title))).ToLowerInvariant().Contains(q)));
        list = Sort switch { "old" => list.Reverse(), "az" => list.OrderBy(r => r.Title, StringComparer.OrdinalIgnoreCase), _ => list };
        var all = list.ToList();
        Released = all.Where(r => r.Kind == "released").ToList();
        Unreleased = all.Where(r => r.Kind != "released").ToList();
        Count = $"{all.Count} / {Shell.Releases.Count}";
        var songs = q.Length < 2 ? new List<QueueItem>() : Shell.Releases.SelectMany(r => r.Tracks.Where(t => t.Title.ToLowerInvariant().Contains(q)).Select(t => new QueueItem(r, t))).ToList();
        Songs = songs.Take(40).Select((s, i) => Row(s, i + 1, true, () => Player.Play(songs, i))).ToList();
    }

    [RelayCommand] private void SetSeries(string s) => Series = s;
    [RelayCommand] private void SetSort(string s) => Sort = s;
    [RelayCommand] private void Open(Release r) => Shell.OpenRelease(r);
}

// ---------------------------------------------------------------- release / tag / playlists

public sealed partial class ReleaseViewModel : PageBase
{
    public Release Release { get; }
    public int? Focus { get; }
    public List<TrackRowViewModel> Rows { get; }
    public string Info => $"{Release.Artist} · {Release.DisplayDate} · {Release.Tracks.Count} trax · {Format.Duration(Release.TotalSeconds)}";
    public List<string> Tags => Release.Genres.Concat(Release.Tags).Distinct().ToList();
    public string PreviewNote => Shell.Me.IsSubscriber ? "" : $"Playing {Release.PreviewSeconds}-second previews. Subscribe for full tracks.";

    public ReleaseViewModel(Shell shell, Release r, int? focus) : base(shell)
    {
        Release = r; Focus = focus;
        Rows = r.Tracks.Select((t, i) => Row(new QueueItem(r, t), t.N, false, () => Player.PlayRelease(r, i))).ToList();
    }

    public override void Refresh() { RefreshRows(Rows); OnPropertyChanged(nameof(DownloadLabel)); OnPropertyChanged(nameof(CanDownload)); }

    private List<QueueItem> Items => Release.Tracks.Select(t => new QueueItem(Release, t)).ToList();
    public bool CanDownload => Shell.Me.IsSubscriber;
    public string DownloadLabel => Items.All(Shell.Offline.Has) ? "↓ DOWNLOADED · REMOVE" : "↓ DOWNLOAD";

    [RelayCommand]
    private async Task Download()
    {
        if (Items.All(Shell.Offline.Has)) Shell.RemoveDownloads(Items);
        else await Shell.DownloadAsync(Items, Release.Title);
    }

    [RelayCommand] private void Play() => Player.PlayRelease(Release);
    [RelayCommand] private void Shuffle() => Player.Shuffled(Release.Tracks.Select(t => new QueueItem(Release, t)));
    [RelayCommand] private void Share() => Shell.Share($"/?r={Uri.EscapeDataString(Release.Id)}", Release.Title);
    [RelayCommand] private void Bandcamp() { if (Release.Bandcamp != null) Shell.OpenUrl(Release.Bandcamp); }
    [RelayCommand] private void OpenTag(string tag) => Shell.OpenTag(tag);
}

public sealed partial class TagViewModel : PageBase
{
    public string Tag { get; }
    public List<TrackRowViewModel> Rows { get; }
    private readonly List<QueueItem> _items;
    public string Info => $"{_items.Count} trax · {Format.Duration(_items.Sum(i => i.Track.Duration))}";

    public TagViewModel(Shell shell, string tag) : base(shell)
    {
        Tag = tag;
        _items = shell.Tagged(tag);
        Rows = _items.Select((it, i) => Row(it, i + 1, true, () => Player.Play(_items, i))).ToList();
    }

    public override void Refresh() => RefreshRows(Rows);

    [RelayCommand] private void Play() => Player.Play(_items, 0);
    [RelayCommand] private void Shuffle() => Player.Shuffled(_items);
    [RelayCommand] private void Share() => Shell.Share($"/?tag={Uri.EscapeDataString(Tag)}", "#" + Tag);
}

public sealed partial class PlaylistsViewModel(Shell shell) : PageBase(shell)
{
    public string Note => Shell.Me.SignedIn ? "" : "Saved on this PC. Sign in to keep playlists on all your devices.";

    [RelayCommand] private void New() => Shell.OpenPlaylist(Shell.NewPlaylist().Id);
    [RelayCommand] private void Open(Playlist p) => Shell.OpenPlaylist(p.Id);
    [RelayCommand] private void OpenFavs() => Shell.Navigate(new MeViewModel(Shell, null, "favs"));
}

public sealed partial class PlaylistViewModel : PageBase
{
    public string Id { get; }
    [ObservableProperty] private string _name = "";
    [ObservableProperty] private List<TrackRowViewModel> _rows = new();
    [ObservableProperty] private string _info = "";
    [ObservableProperty] private bool _exists = true;
    private List<QueueItem> _items = new();

    public PlaylistViewModel(Shell shell, string id) : base(shell) { Id = id; Refresh(); }

    public override void Refresh()
    {
        var pl = Shell.Playlists.Get(Id);
        Exists = pl != null;
        if (pl == null) return;
        Name = pl.Name;
        _items = Shell.Resolve(pl.Tracks);
        OnPropertyChanged(nameof(CanDownload));
        Info = $"{_items.Count} trax · {Format.Duration(_items.Sum(i => i.Track.Duration))}";
        Rows = _items.Select((it, i) => Row(it, i + 1, true, () => Player.Play(_items, i), new[]
        {
            new MenuEntry("Move up", new RelayCommand(() => Shell.Playlists.Move(Id, i, i - 1), () => i > 0)),
            new MenuEntry("Move down", new RelayCommand(() => Shell.Playlists.Move(Id, i, i + 1), () => i < _items.Count - 1)),
            new MenuEntry("Remove from playlist", new RelayCommand(() => Shell.Playlists.RemoveAt(Id, i))),
        })).ToList();
    }

    [RelayCommand] private void Play() => Player.Play(_items, 0);
    [RelayCommand] private void Shuffle() => Player.Shuffled(_items);
    [RelayCommand] private void Rename() => Shell.Playlists.Rename(Id, Name);
    public bool CanDownload => Shell.Me.IsSubscriber && _items.Count > 0;
    [RelayCommand] private Task Download() => Shell.DownloadAsync(_items, Name);

    [RelayCommand]
    private void Delete()
    {
        if (MessageBox.Show($"Delete {Name}? This removes it from all your devices.", "88.8FM", MessageBoxButton.OKCancel, MessageBoxImage.Warning) != MessageBoxResult.OK) return;
        Shell.Playlists.Delete(Id);
        Shell.ShowToast("Playlist deleted");
        Shell.Back();
    }

    [RelayCommand]
    private async Task Share()
    {
        var pl = Shell.Playlists.Get(Id);
        if (pl == null || pl.Tracks.Count == 0) return;
        try { Shell.Share("/?s=" + Uri.EscapeDataString(await Shell.Api.SharePlaylistAsync(pl.Name, pl.Tracks)), pl.Name); }
        catch (Exception e) { Shell.ShowToast(e.Message); }
    }
}

public sealed partial class SharedPlaylistViewModel : PageBase
{
    public string Id { get; }
    [ObservableProperty] private SharedPlaylist? _shared;
    [ObservableProperty] private List<TrackRowViewModel> _rows = new();
    [ObservableProperty] private string? _error;
    private List<QueueItem> _items = new();

    public SharedPlaylistViewModel(Shell shell, string id) : base(shell) { Id = id; _ = LoadAsync(); }

    private async Task LoadAsync()
    {
        try
        {
            Shared = await Shell.Api.SharedPlaylistAsync(Id);
            _items = Shell.Resolve(Shared.Tracks);
            Rows = _items.Select((it, i) => Row(it, i + 1, true, () => Player.Play(_items, i))).ToList();
        }
        catch (Exception e) { Error = e.Message; }
    }

    public override void Refresh() => RefreshRows(Rows);

    [RelayCommand] private void Play() => Player.Play(_items, 0);
    [RelayCommand] private void Share() { if (Shared != null) Shell.Share("/?s=" + Uri.EscapeDataString(Id), Shared.Name); }

    [RelayCommand]
    private void Save()
    {
        if (Shared == null) return;
        var pl = Shell.Playlists.Create(Shared.Name, _items.Select(i => new TrackRef(i.Release.Id, i.Track.N)));
        Shell.ShowToast("Saved to your playlists");
        Shell.OpenPlaylist(pl.Id);
    }
}

// ---------------------------------------------------------------- BPM browser

public sealed record BpmHeader(string Text);

/// <summary>The site's テンポ view: tracks by tempo, with a BPM range and a major / minor filter.</summary>
public sealed partial class BpmViewModel : PageBase
{
    private readonly List<QueueItem> _all;
    public int Min { get; }
    public int Max { get; }
    [ObservableProperty] private double _lo;
    [ObservableProperty] private double _hi;
    [ObservableProperty] private string _key = "all";
    [ObservableProperty] private List<object> _items = new();
    [ObservableProperty] private string _count = "";
    private List<QueueItem> _shown = new();

    public string RangeText => $"{(int)Lo} – {(int)Hi} BPM";

    public BpmViewModel(Shell shell) : base(shell)
    {
        _all = shell.Releases.SelectMany(r => r.Tracks.Where(t => t.Bpm != null).Select(t => new QueueItem(r, t))).ToList();
        Min = _all.Count > 0 ? _all.Min(i => i.Track.Bpm!.Value) : 0;
        Max = _all.Count > 0 ? _all.Max(i => i.Track.Bpm!.Value) : 0;
        _lo = Min; _hi = Max;
        Apply();
    }

    partial void OnLoChanged(double value) { if (value > Hi) Hi = value; Apply(); }
    partial void OnHiChanged(double value) { if (value < Lo) Lo = value; Apply(); }
    partial void OnKeyChanged(string value) => Apply();

    public override void Refresh() { foreach (var r in Items.OfType<TrackRowViewModel>()) r.Refresh(); }

    private void Apply()
    {
        OnPropertyChanged(nameof(RangeText));
        _shown = _all.Where(i => i.Track.Bpm >= (int)Lo && i.Track.Bpm <= (int)Hi && (Key == "all" || (Key == "min") == i.Track.IsMinor))
            .OrderBy(i => i.Track.Bpm).ThenBy(i => i.Track.Title).ToList();
        Count = $"{_shown.Count} / {_all.Count} TRACKS";
        var list = new List<object>();
        var shown = _shown;
        foreach (var g in shown.GroupBy(i => i.Track.Bpm!.Value))
        {
            list.Add(new BpmHeader($"{g.Key} BPM · {g.Count()} TRK"));
            foreach (var it in g) { var idx = shown.IndexOf(it); list.Add(Row(it, idx + 1, true, () => Player.Play(shown, idx))); }
        }
        Items = list;
    }

    [RelayCommand] private void SetKey(string k) => Key = k;
    [RelayCommand] private void PlayAll() => Player.Play(_shown, 0);
}
