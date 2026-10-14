using System.Windows;
using System.Windows.Input;
using Powerstone.Windows.ViewModels;

namespace Powerstone.Windows;

/// <summary>
/// Compact, draggable, always-on-top player. Remembers where it was left; the pin toggles
/// "keep on top". Closing it (×) quits the app; expanding returns to the full window.
/// </summary>
public partial class MiniPlayerWindow : Window
{
    private bool _quiet;

    public event Action? Expand;

    public MiniPlayerWindow()
    {
        InitializeComponent();
        DataContextChanged += (_, _) =>
        {
            var s = Shell.Settings;
            Topmost = s.MiniOnTop;
            Pin.IsChecked = s.MiniOnTop;
            var area = SystemParameters.WorkArea;
            if (s.MiniLeft is { } l && s.MiniTop is { } t &&
                l > SystemParameters.VirtualScreenLeft - 100 && l < SystemParameters.VirtualScreenLeft + SystemParameters.VirtualScreenWidth - 100 &&
                t > SystemParameters.VirtualScreenTop - 20 && t < SystemParameters.VirtualScreenTop + SystemParameters.VirtualScreenHeight - 60)
            {
                Left = l; Top = t;
            }
            else
            {
                // Default: bottom-right corner, above the taskbar.
                Left = area.Right - 440; Top = area.Bottom - 160;
            }
        };
        Closing += (_, _) =>
        {
            Shell.Settings.MiniLeft = Left;
            Shell.Settings.MiniTop = Top;
            Shell.Settings.Save();
            // × on the mini player quits the whole app (the main window is hidden behind it).
            if (!_quiet) Application.Current.MainWindow?.Close();
        };
    }

    private Shell Shell => (Shell)DataContext;
    private PlayerViewModel Player => Shell.Player;

    /// <summary>Close without quitting (switching back to the full window).</summary>
    public void CloseQuietly() { _quiet = true; Close(); }

    private void Drag(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is System.Windows.Controls.Image) { Expand?.Invoke(); return; } // click the cover → full player
        if (e.ButtonState == MouseButtonState.Pressed) { try { DragMove(); } catch { } }
    }

    private void Pin_Click(object sender, RoutedEventArgs e)
    {
        Topmost = Pin.IsChecked == true;
        Shell.Settings.MiniOnTop = Topmost;
        Shell.Settings.Save();
    }

    private void Expand_Click(object sender, RoutedEventArgs e) => Expand?.Invoke();
    private void Quit_Click(object sender, RoutedEventArgs e) => Close();

    private void Seek_DragStarted(object sender, RoutedEventArgs e) => Player.BeginSeek();
    private void Seek_DragCompleted(object sender, RoutedEventArgs e) => Player.EndSeek(Seek.Value);
    private void Seek_PreviewMouseDown(object sender, MouseButtonEventArgs e) { Player.BeginSeek(); e.Handled = false; }
    private void Seek_PreviewMouseUp(object sender, MouseButtonEventArgs e) => Dispatcher.BeginInvoke(() => Player.EndSeek(Seek.Value));
}
