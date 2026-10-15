using System.IO;
using System.Windows;
using System.Windows.Threading;
using Powerstone.Windows.ViewModels;

namespace Powerstone.Windows;

public partial class App : Application
{
    public static Shell Shell { get; private set; } = null!;
    public static string DataDir { get; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "88.8FM");

    protected override void OnStartup(StartupEventArgs e)
    {
        // Never die silently: log anything unhandled and say so on screen.
        DispatcherUnhandledException += (_, ex) => { Report(ex.Exception, fatal: false); ex.Handled = true; };
        AppDomain.CurrentDomain.UnhandledException += (_, ex) => Report(ex.ExceptionObject as Exception, fatal: true);
        TaskScheduler.UnobservedTaskException += (_, ex) => { Log(ex.Exception); ex.SetObserved(); };

        base.OnStartup(e);
        try
        {
            Shell = new Shell(DataDir);
            var window = new MainWindow { DataContext = Shell };
            MainWindow = window;
            window.Show();
            // Arguments: a thepowerstone.com link to open, and/or --mini to start in the mini player.
            _ = Shell.StartAsync(e.Args.FirstOrDefault(a => a.StartsWith("http", StringComparison.OrdinalIgnoreCase)));
            if (e.Args.Contains("--mini")) window.Dispatcher.BeginInvoke(() => Shell.ToggleMiniCommand.Execute(null));
        }
        catch (Exception ex)
        {
            Report(ex, fatal: true);
            Shutdown(1);
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try { Shell?.Shutdown(); } catch (Exception ex) { Log(ex); }
        base.OnExit(e);
    }

    private static string Log(Exception? ex)
    {
        var file = Path.Combine(DataDir, "crash.log");
        try
        {
            Directory.CreateDirectory(DataDir);
            File.AppendAllText(file, $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {ex}\r\n\r\n");
        }
        catch { }
        return file;
    }

    private static void Report(Exception? ex, bool fatal)
    {
        var file = Log(ex);
        var inner = ex;
        while (inner?.InnerException != null) inner = inner.InnerException;
        try
        {
            MessageBox.Show(
                (fatal ? "88.8FM couldn't start.\n\n" : "Something went wrong (the app will keep running).\n\n") +
                $"{inner?.GetType().Name}: {inner?.Message}\n\nDetails were saved to:\n{file}",
                "88.8FM", MessageBoxButton.OK, fatal ? MessageBoxImage.Error : MessageBoxImage.Warning);
        }
        catch { }
    }
}
