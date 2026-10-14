using System.Windows;
using System.Windows.Controls;
using Powerstone.Windows.ViewModels;

namespace Powerstone.Windows.Views;

/// <summary>Page templates; the few bits XAML can't bind (password boxes, the ⋯ menu button).</summary>
public partial class Pages : ResourceDictionary
{
    public Pages() => InitializeComponent();

    private void More_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement b && b.Tag is FrameworkElement owner && owner.ContextMenu is { } menu)
        {
            menu.PlacementTarget = b;
            menu.DataContext = owner.DataContext;
            menu.IsOpen = true;
        }
    }

    private async void PasswordSignIn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: PasswordBox pw, DataContext: AccountViewModel vm })
        {
            await vm.PasswordLogin(pw.Password);
            if (vm.SignedIn) pw.Clear();
        }
    }

    private async void SetPassword_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: PasswordBox pw, DataContext: AccountViewModel vm })
        {
            await vm.SetPassword(pw.Password);
            if (!vm.ChangingPassword) pw.Clear();
        }
    }
}
