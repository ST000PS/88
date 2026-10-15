using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using Powerstone.Core;
using Powerstone.Windows.ViewModels;

namespace Powerstone.Windows;

public partial class MainWindow : Window
{
    private MiniPlayerWindow? _mini;

    public MainWindow()
    {
        InitializeComponent();
        SourceInitialized += (_, _) =>
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            UseDarkTitleBar(hwnd);
            Shell.Player.AttachMediaControls(hwnd);
        };
        DataContextChanged += (_, _) =>
        {
            RestorePlacement();
            Shell.ToggleMiniRequested += ToggleMini;
        };
        Closing += OnClosing;
    }

    private Shell Shell => (Shell)DataContext;
    private PlayerViewModel Player => Shell.Player;

    // ---------- window placement ----------

    /// <summary>Reopen where it was, as long as that's still on a screen; never bigger than the screen.</summary>
    private void RestorePlacement()
    {
        var area = SystemParameters.WorkArea;
        if (Shell.Settings.Window is { } w && w.Width > 300 && w.Height > 200 &&
            w.Left + w.Width > SystemParameters.VirtualScreenLeft + 80 && w.Left < SystemParameters.VirtualScreenLeft + SystemParameters.VirtualScreenWidth - 80 &&
            w.Top >= SystemParameters.VirtualScreenTop - 10 && w.Top < SystemParameters.VirtualScreenTop + SystemParameters.VirtualScreenHeight - 80)
        {
            WindowStartupLocation = WindowStartupLocation.Manual;
            Left = w.Left; Top = w.Top; Width = w.Width; Height = w.Height;
            if (w.Maximized) WindowState = WindowState.Maximized;
        }
        else
        {
            Width = Math.Min(Width, area.Width - 40);
            Height = Math.Min(Height, area.Height - 40);
        }
    }

    private void OnClosing(object? sender, CancelEventArgs e)
    {
        var r = WindowState == WindowState.Normal ? new Rect(Left, Top, Width, Height) : RestoreBounds;
        if (!r.IsEmpty) Shell.Settings.Window = new WindowPlacement(r.Left, r.Top, r.Width, r.Height, WindowState == WindowState.Maximized);
        _mini?.Close();
    }

    // ---------- mini player ----------

    private void ToggleMini()
    {
        if (_mini == null)
        {
            _mini = new MiniPlayerWindow { DataContext = Shell };
            _mini.Expand += () => ToggleMini();
            _mini.Show();
            Hide();
        }
        else
        {
            var m = _mini;
            _mini = null;
            Show();
            if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
            Activate();
            m.CloseQuietly();
        }
    }

    // ---------- player bar ----------

    private void Sleep_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { ContextMenu: { } menu } b)
        {
            menu.PlacementTarget = b;
            menu.DataContext = b.DataContext;
            menu.IsOpen = true;
        }
    }

    // Seeking: pause position updates while dragging or clicking, then jump once on release.
    private void Seek_DragStarted(object sender, RoutedEventArgs e) => Player.BeginSeek();
    private void Seek_DragCompleted(object sender, RoutedEventArgs e) => Player.EndSeek(Seek.Value);
    private void Seek_PreviewMouseDown(object sender, MouseButtonEventArgs e) => Player.BeginSeek();
    private void Seek_PreviewMouseUp(object sender, MouseButtonEventArgs e) => Dispatcher.BeginInvoke(() => Player.EndSeek(Seek.Value));

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    /// <summary>Dark title bar to match the app (Windows 10 20H1+ / 11).</summary>
    internal static void UseDarkTitleBar(IntPtr hwnd)
    {
        var on = 1;
        try { DwmSetWindowAttribute(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */, ref on, sizeof(int)); } catch { }
    }
}
