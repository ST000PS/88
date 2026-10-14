using System.Windows;
using Powerstone.Windows.ViewModels;

namespace Powerstone.Windows;

public partial class App : Application
{
    public static Shell Shell { get; private set; } = null!;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        var dataDir = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "88.8FM");
        Shell = new Shell(dataDir);
        var window = new MainWindow { DataContext = Shell };
        MainWindow = window;
        window.Show();
        _ = Shell.StartAsync(e.Args.FirstOrDefault());
    }

    protected override void OnExit(ExitEventArgs e)
    {
        Shell?.Shutdown();
        base.OnExit(e);
    }
}
