using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Powerstone.Core;

namespace Powerstone.Windows.ViewModels;

// ---------------------------------------------------------------- MY 88.8

public sealed record Kpi(string Value, string Label);
public sealed record RankItem(string Rank, string Title, string Sub, string Count, Action Open)
{
    public RelayCommand OpenCommand => new(Open);
}
public sealed record SessionItem(string Header, string Summary, List<SessionTrack> Tracks);
public sealed record SessionTrack(string Title, string Release, string Times, string Year, Action Play)
{
    public RelayCommand PlayCommand => new(Play);
}

/// <summary>MY 88.8 — favourites, listening history, and monthly / yearly stats (the yearly wrap-up).</summary>
public sealed partial class MeViewModel : PageBase
{
    [ObservableProperty] private string _tab;
    [ObservableProperty] private int _year;
    [ObservableProperty] private bool _busy;
    [ObservableProperty] private string? _message;
    [ObservableProperty] private List<TrackRowViewModel> _favRows = new();
    [ObservableProperty] private List<SessionItem> _sessions = new();
    [ObservableProperty] private List<Kpi> _kpis = new();
    [ObservableProperty] private List<RankItem> _topTracks = new();
    [ObservableProperty] private List<RankItem> _topReleases = new();
    [ObservableProperty] private List<RankItem> _topTags = new();
    private int _load;

    public int CurrentYear => DateTime.Now.Year;
    public string Heading => Tab == "year" && Year != CurrentYear ? $"YOUR {Year} ON 88.8FM" : "MY 88.8";
    public string Note => Shell.Me.SignedIn ? "" : "Showing listening from this PC. Sign in to keep your history and stats across devices and get your yearly wrap-up.";
    public bool IsFavs => Tab == "favs";
    public bool IsHistory => Tab == "history";
    public bool IsStats => Tab is "month" or "year";
    public bool IsYear => Tab == "year";
    public string StatsLabel => Tab == "month" ? "THIS MONTH" : Year.ToString();

    public MeViewModel(Shell shell, int? wrapYear, string? tab = null) : base(shell)
    {
        _year = wrapYear ?? DateTime.Now.Year;
        _tab = tab ?? (wrapYear != null ? "year" : shell.Me.SignedIn ? "favs" : "history");
        _ = LoadAsync();
    }

    partial void OnTabChanged(string value) { Notify(); _ = LoadAsync(); }
    partial void OnYearChanged(int value) { Notify(); _ = LoadAsync(); }

    private void Notify()
    {
        foreach (var p in new[] { nameof(Heading), nameof(IsFavs), nameof(IsHistory), nameof(IsStats), nameof(IsYear), nameof(StatsLabel), nameof(Note) }) OnPropertyChanged(p);
    }

    public override void Refresh()
    {
        if (IsFavs) BuildFavs(); else RefreshRows(FavRows);
    }

    [RelayCommand] private void SetTab(string t) => Tab = t;
    [RelayCommand] private void PrevYear() => Year--;
    [RelayCommand] private void NextYear() { if (Year < CurrentYear) Year++; }

    private void BuildFavs()
    {
        var items = Shell.Favs.Select(id => Shell.Lookup(id)).OfType<QueueItem>().ToList();
        FavRows = items.Select((it, i) => Row(it, i + 1, true, () => Player.Play(items, i))).ToList();
        Message = !Shell.Me.SignedIn ? "Sign in to see your favourites." : items.Count == 0 ? "Tap the heart on any track to save it here." : null;
    }

    public bool CanDownload => Shell.Me.IsSubscriber && Shell.Favs.Count > 0;
    [RelayCommand] private Task DownloadFavs() => Shell.DownloadAsync(Shell.Favs.Select(id => Shell.Lookup(id)).OfType<QueueItem>().ToList(), "favourites");

    [RelayCommand] private void PlayFavs() { var items = Shell.Favs.Select(id => Shell.Lookup(id)).OfType<QueueItem>().ToList(); Player.Play(items, 0); }

    private async Task LoadAsync()
    {
        var my = ++_load;
        Message = null;
        if (IsFavs) { BuildFavs(); return; }
        Busy = true;
        try
        {
            if (IsHistory) await LoadHistory(my); else await LoadStats(my);
        }
        catch (Exception e) { if (my == _load) Message = e.Message; }
        finally { if (my == _load) Busy = false; }
    }

    private async Task LoadHistory(int my)
    {
        List<ListeningSession> sessions;
        Dictionary<string, int> yearCounts;
        if (Shell.Me.SignedIn)
        {
            sessions = await Shell.Api.HistoryAsync();
            try { yearCounts = (await Shell.Api.StatsAsync("year", CurrentYear)).Counts; } catch { yearCounts = new(); }
        }
        else
        {
            var local = await Task.Run(Shell.History.All);
            sessions = StatsMath.Sessions(local);
            var from = StatsMath.StartOfYear(CurrentYear);
            yearCounts = local.Where(e => e.Ts >= from).GroupBy(e => $"{e.R}|{e.N}").ToDictionary(g => g.Key, g => g.Count());
        }
        if (my != _load) return;
        if (sessions.Count == 0) { Message = "Nothing yet. Play something and it shows up here, grouped by listening session."; Sessions = new(); return; }
        Sessions = sessions.Take(30).Select(s =>
        {
            var groups = s.Plays.GroupBy(p => (p.R, p.N)).ToList();
            var items = groups.Select(g => Shell.Lookup(g.Key.R, g.Key.N)).OfType<QueueItem>().ToList();
            var tracks = groups.Select(g =>
            {
                var it = Shell.Lookup(g.Key.R, g.Key.N);
                if (it == null) return null;
                var idx = items.FindIndex(x => x.Id == it.Id);
                return new SessionTrack(it.Track.Title, it.Release.Title, $"×{g.Count()}",
                    $"THIS YEAR {yearCounts.GetValueOrDefault($"{g.Key.R}|{g.Key.N}", g.Count())} · {DateTimeOffset.FromUnixTimeMilliseconds(g.First().Ts).ToLocalTime():HH:mm}",
                    () => Player.Play(items, idx));
            }).OfType<SessionTrack>().ToList();
            var mins = s.Plays.Sum(p => p.Ms) / 60000;
            return new SessionItem(DateTimeOffset.FromUnixTimeMilliseconds(s.Start).ToLocalTime().ToString("yyyy.MM.dd HH:mm"),
                $"{mins} MIN · {s.Plays.Count} PLAYS · {groups.Count} TRK", tracks);
        }).ToList();
    }

    private async Task LoadStats(int my)
    {
        var period = Tab == "month" ? "month" : "year";
        var year = period == "month" ? CurrentYear : Year;
        Stats st;
        if (Shell.Me.SignedIn) st = await Shell.Api.StatsAsync(period, year);
        else
        {
            var local = await Task.Run(Shell.History.All);
            var from = period == "month" ? StatsMath.StartOfMonth() : StatsMath.StartOfYear(year);
            var until = period == "month" ? long.MaxValue : StatsMath.StartOfYear(year + 1);
            st = StatsMath.Compute(local, from, until, Shell.Releases);
        }
        if (my != _load) return;
        if (st.Plays == 0) { Message = "No listening in this period yet."; Kpis = new(); TopTracks = TopReleases = TopTags = new(); return; }
        Kpis = new() { new($"{st.Minutes}", "MINUTES"), new($"{st.Plays}", "PLAYS"), new($"{st.UniqueTracks}", "TRACKS") };
        var top = st.TopTracks.Select(t => Shell.Lookup(t.R, t.N)).OfType<QueueItem>().ToList();
        TopTracks = st.TopTracks.Select((t, i) => new RankItem($"{i + 1:00}", t.Title ?? Shell.Lookup(t.R, t.N)?.Track.Title ?? "?",
            t.Release ?? Shell.Release(t.R)?.Title ?? "", $"{t.Plays}×", () =>
            {
                var idx = top.FindIndex(q => q.Release.Id == t.R && q.Track.N == t.N);
                if (idx >= 0) Player.Play(top, idx);
            })).ToList();
        TopReleases = st.TopReleases.Select((r, i) => new RankItem($"{i + 1:00}", r.Title, "", $"{r.Plays}×", () => { if (Shell.Release(r.R) is { } rel) Shell.OpenRelease(rel); })).ToList();
        TopTags = st.TopTags.Select((t, i) => new RankItem($"{i + 1:00}", "#" + t.Tag, "", $"{t.Plays}", () => Shell.OpenTag(t.Tag))).ToList();
    }
}

// ---------------------------------------------------------------- account

public sealed partial class AccountViewModel : PageBase
{
    [ObservableProperty] private string _email = "";
    [ObservableProperty] private string _code = "";
    [ObservableProperty] private bool _codeSent;
    [ObservableProperty] private bool _usePassword;
    [ObservableProperty] private bool _busy;
    [ObservableProperty] private string? _message;
    [ObservableProperty] private bool _changingPassword;

    public bool ShowPassword => UsePassword;
    public bool ShowSend => !UsePassword && !CodeSent;
    public bool ShowCode => !UsePassword && CodeSent;
    public string MethodText => UsePassword ? "Use an email code instead" : "Use a password instead";
    partial void OnUsePasswordChanged(bool value) { OnPropertyChanged(nameof(ShowPassword)); OnPropertyChanged(nameof(ShowSend)); OnPropertyChanged(nameof(ShowCode)); OnPropertyChanged(nameof(MethodText)); }
    partial void OnCodeSentChanged(bool value) { OnPropertyChanged(nameof(ShowSend)); OnPropertyChanged(nameof(ShowCode)); }
    [RelayCommand] private void SetQuality(string q) => Quality = Enum.Parse<Quality>(q);

    public Me Me => Shell.Me;
    public bool SignedIn => Me.SignedIn;
    public string Plan => Me.IsSubscriber ? "SUBSCRIBER" + (Me.Plan != null ? " · " + Me.Plan.ToUpperInvariant() : "") : "NO ACTIVE PLAN · previews only";
    public string MonthPrice => $"{Me.Prices.Month} / MONTH";
    public string YearPrice => $"{Me.Prices.Year} / YEAR";
    public bool CanManage => Me.Status != null && !Me.Owner;
    public string PasswordNote => Shell.HasPassword ? "You can sign in with your email and password." : "Make a password to sign in without waiting for an email code.";
    public string PasswordAction => Shell.HasPassword ? "CHANGE PASSWORD" : "MAKE A PASSWORD";

    public Quality Quality
    {
        get => Shell.Settings.Quality;
        set { Shell.Settings.Quality = value; Shell.Settings.Save(); OnPropertyChanged(); OnPropertyChanged(nameof(QualityNote)); }
    }

    public string QualityNote
    {
        get
        {
            var r = Player.ResolvedQuality();
            var (name, mb) = r switch { Quality.Lo => ("DATA SAVER (64 kbps)", 0.5), Quality.Std => ("STANDARD (160 kbps)", 1.2), _ => ("HIGH (320 kbps)", 2.4) };
            return $"Now streaming {name} · about {mb} MB per minute." + (Me.IsSubscriber ? "" : " Previews always play at standard quality.") + " Applies to the next track you start.";
        }
    }

    public AccountViewModel(Shell shell) : base(shell) { }

    public override void Refresh()
    {
        foreach (var p in new[] { nameof(OfflineSummary), nameof(Me), nameof(SignedIn), nameof(Plan), nameof(MonthPrice), nameof(YearPrice), nameof(CanManage), nameof(PasswordNote), nameof(PasswordAction), nameof(QualityNote) }) OnPropertyChanged(p);
    }

    private async Task Run(Func<Task> f)
    {
        Busy = true; Message = null;
        try { await f(); }
        catch (Exception e) { Message = e.Message; }
        finally { Busy = false; Refresh(); }
    }

    [RelayCommand] private Task SendCode() => Run(async () => { await Shell.Api.RequestCodeAsync(Email.Trim()); CodeSent = true; });
    [RelayCommand] private Task VerifyCode() => Run(async () => { await Shell.Api.VerifyCodeAsync(Email.Trim(), new string(Code.Where(char.IsDigit).ToArray())); await Shell.RefreshAccountAsync(); });
    [RelayCommand] private void ChangeEmail() { CodeSent = false; Code = ""; }
    [RelayCommand] private void ToggleMethod() { UsePassword = !UsePassword; Message = null; }

    public Task PasswordLogin(string password) => Run(async () => { await Shell.Api.PasswordLoginAsync(Email.Trim(), password); await Shell.RefreshAccountAsync(); });

    public Task SetPassword(string password) => Run(async () =>
    {
        if (password.Length < 8) throw new ApiException("Use at least 8 characters.");
        await Shell.Api.SetPasswordAsync(password);
        Shell.HasPassword = true;
        ChangingPassword = false;
        Shell.ShowToast("Password saved");
    });

    [RelayCommand] private void StartPassword() => ChangingPassword = !ChangingPassword;

    public string OfflineSummary => Shell.OfflineSummary;
    [RelayCommand]
    private void RemoveAllDownloads()
    {
        if (System.Windows.MessageBox.Show("Remove all downloaded tracks from this PC?", "88.8FM", System.Windows.MessageBoxButton.OKCancel) != System.Windows.MessageBoxResult.OK) return;
        Shell.Offline.RemoveAll();
        Shell.Videos.Clear();
        Refresh();
    }
    [RelayCommand] private Task SubscribeMonth() => Run(async () => Shell.OpenUrl(await Shell.Api.CheckoutUrlAsync("month")));
    [RelayCommand] private Task SubscribeYear() => Run(async () => Shell.OpenUrl(await Shell.Api.CheckoutUrlAsync("year")));
    [RelayCommand] private Task Manage() => Run(async () => Shell.OpenUrl(await Shell.Api.PortalUrlAsync()));
    /// <summary>After paying in the browser: pick up the new plan.</summary>
    [RelayCommand] private Task Recheck() => Run(Shell.RefreshAccountAsync);

    [RelayCommand]
    private Task SignOut() => Run(async () =>
    {
        await Shell.Api.LogoutAsync();
        Player.Engine.Stop();
        Shell.Offline.RemoveAll();   // downloads belong to the subscription
        Shell.Videos.Clear();
        Shell.Settings.LastMe = null;
        Shell.Playlists.SignedOut();
        Shell.Me = new Me();
        Shell.HasPassword = false;
    });
}

// ---------------------------------------------------------------- sound

public sealed partial class EqBand : ObservableObject
{
    private readonly SoundViewModel _owner;
    public string Band { get; }
    public string Label { get; }

    public EqBand(SoundViewModel owner, string band, string label) { _owner = owner; Band = band; Label = label; }

    public double Level
    {
        get => _owner.Eq.Level(Band);
        set => _owner.SetEq(_owner.Eq.With(Band, (float)Math.Round(value, 2)));
    }

    public string Percent => $"{(int)Math.Round(Level * 100)}%";
    public bool Kill { get => _owner.Eq.IsKilled(Band); set => _owner.SetEq(_owner.Eq.ToggleKill(Band)); }
    public bool Iso { get => _owner.Eq.Solo == Band; set => _owner.SetEq(_owner.Eq.ToggleSolo(Band)); }

    public void Changed() { OnPropertyChanged(nameof(Level)); OnPropertyChanged(nameof(Percent)); OnPropertyChanged(nameof(Kill)); OnPropertyChanged(nameof(Iso)); }
}

/// <summary>SOUND: the 3-band DJ EQ with KILL / ISO, vinyl stop, visualizer, volume.</summary>
public sealed partial class SoundViewModel : PageBase
{
    public List<EqBand> Bands { get; }
    public EqState Eq => Shell.Settings.Eq;

    public SoundViewModel(Shell shell) : base(shell)
    {
        Bands = new() { new(this, "lo", "LO · 低音"), new(this, "mid", "MID · 中音"), new(this, "hi", "HI · 高音") };
    }

    public void SetEq(EqState eq)
    {
        Shell.Settings.Eq = eq;
        Shell.Settings.Save();
        Player.Engine.Gains.Apply(eq);
        foreach (var b in Bands) b.Changed();
    }

    [RelayCommand] private void Flat() => SetEq(new EqState());
    [RelayCommand] private void SetVinyl(string v) => Vinyl = Enum.Parse<VinylStop>(v);

    public VinylStop Vinyl
    {
        get => Shell.Settings.Vinyl;
        set { Shell.Settings.Vinyl = value; Shell.Settings.Save(); OnPropertyChanged(); }
    }

    public int Crossfade
    {
        get => Shell.Settings.CrossfadeSeconds;
        set { Shell.Settings.CrossfadeSeconds = value; Shell.Settings.Save(); OnPropertyChanged(); Player.Replan(); }
    }
    [RelayCommand] private void SetCrossfade(string s) => Crossfade = int.Parse(s);

    public bool VideoOn
    {
        get => Shell.Settings.VideoOn;
        set { Shell.Settings.VideoOn = value; Shell.Settings.Save(); OnPropertyChanged(); Player.LoadVideo(); }
    }

    public bool VisualizerOn
    {
        get => Shell.Settings.VisualizerOn;
        set { Shell.Settings.VisualizerOn = value; Player.Engine.Spectrum.Enabled = value; Shell.Settings.Save(); OnPropertyChanged(); }
    }
}

// ---------------------------------------------------------------- now playing

public sealed record UpNext(int Index, string Title, string Sub, bool IsCurrent, Action Play)
{
    public RelayCommand PlayCommand => new(Play);
}

public sealed partial class NowPlayingViewModel : PageBase
{
    [ObservableProperty] private List<UpNext> _queue = new();

    public NowPlayingViewModel(Shell shell) : base(shell) { Refresh(); }

    public override void Refresh()
    {
        var q = Player.Queue;
        var from = Math.Max(0, q.Index - 2);
        Queue = q.Items.Skip(from).Take(60).Select((it, i) =>
        {
            var idx = from + i;
            return new UpNext(idx, it.Track.Title, it.Release.Title, idx == q.Index, () => Player.JumpTo(idx));
        }).ToList();
    }
}
