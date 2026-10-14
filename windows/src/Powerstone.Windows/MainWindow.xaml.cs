using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using Powerstone.Windows.ViewModels;

namespace Powerstone.Windows;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        SourceInitialized += (_, _) =>
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            UseDarkTitleBar(hwnd);
            ((Shell)DataContext).Player.AttachMediaControls(hwnd);
        };
    }

    private PlayerViewModel Player => ((Shell)DataContext).Player;

    // Seeking: pause position updates while dragging or clicking, then jump once on release.
    private void Seek_DragStarted(object sender, RoutedEventArgs e) => Player.BeginSeek();
    private void Seek_DragCompleted(object sender, RoutedEventArgs e) => Player.EndSeek(Seek.Value);
    private void Seek_PreviewMouseDown(object sender, MouseButtonEventArgs e) => Player.BeginSeek();
    private void Seek_PreviewMouseUp(object sender, MouseButtonEventArgs e) => Dispatcher.BeginInvoke(() => Player.EndSeek(Seek.Value));

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    /// <summary>Dark title bar to match the app (Windows 10 20H1+ / 11).</summary>
    private static void UseDarkTitleBar(IntPtr hwnd)
    {
        var on = 1;
        try { DwmSetWindowAttribute(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */, ref on, sizeof(int)); } catch { }
    }
}
