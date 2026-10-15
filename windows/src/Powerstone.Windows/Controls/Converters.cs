using System.Collections.Concurrent;
using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Powerstone.Core;

namespace Powerstone.Windows.Controls;

/// <summary>Site-relative image path → decoded, cached bitmap (downscaled for lists).</summary>
public sealed class UrlToImageConverter : IValueConverter
{
    private static readonly ConcurrentDictionary<string, BitmapImage> Cache = new();
    public int DecodeWidth { get; set; } = 240;

    public object? Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var url = Api.Absolute(value as string);
        if (url == null) return null;
        return Cache.GetOrAdd($"{DecodeWidth}|{url}", _ =>
        {
            var b = new BitmapImage();
            b.BeginInit();
            b.UriSource = new Uri(url);
            b.DecodePixelWidth = DecodeWidth;
            b.CacheOption = BitmapCacheOption.OnLoad;
            b.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
            b.EndInit();
            return b;
        });
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => throw new NotSupportedException();
}

public sealed class InverseBoolToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? p, CultureInfo c) => value is true ? Visibility.Collapsed : Visibility.Visible;
    public object ConvertBack(object? value, Type t, object? p, CultureInfo c) => throw new NotSupportedException();
}

/// <summary>Hidden when null, empty text or zero; with any parameter ("inverse") it's the other way round.</summary>
public sealed class NullToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? p, CultureInfo c)
    {
        var empty = value == null || value is string { Length: 0 } || value is int and 0;
        if (p != null) empty = !empty;
        return empty ? Visibility.Collapsed : Visibility.Visible;
    }
    public object ConvertBack(object? value, Type t, object? p, CultureInfo c) => throw new NotSupportedException();
}

public sealed class BoolToBrushConverter : IValueConverter
{
    public Brush? True { get; set; }
    public Brush? False { get; set; }
    public object? Convert(object? value, Type t, object? p, CultureInfo c) => value is true ? True : False;
    public object ConvertBack(object? value, Type t, object? p, CultureInfo c) => throw new NotSupportedException();
}

/// <summary>True when both bound values have the same text (selected chip / tab).</summary>
public sealed class EqualsConverter : IMultiValueConverter
{
    public object Convert(object?[] values, Type t, object? p, CultureInfo c) =>
        values.Length == 2 && string.Equals(values[0]?.ToString(), values[1]?.ToString(), StringComparison.OrdinalIgnoreCase);
    public object[] ConvertBack(object? value, Type[] t, object? p, CultureInfo c) => throw new NotSupportedException();
}

public sealed class Not : IValueConverter
{
    public static readonly Not Instance = new();
    public object Convert(object? value, Type t, object? p, CultureInfo c) => value is not true;
    public object ConvertBack(object? value, Type t, object? p, CultureInfo c) => value is not true;
}

public sealed class BoolToObjectConverter : IValueConverter
{
    public object? True { get; set; }
    public object? False { get; set; }
    public object? Convert(object? value, Type t, object? p, CultureInfo c) => value is true ? True : False;
    public object ConvertBack(object? value, Type t, object? p, CultureInfo c) => throw new NotSupportedException();
}
