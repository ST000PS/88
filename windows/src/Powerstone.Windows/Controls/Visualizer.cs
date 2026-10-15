using System.Windows;
using System.Windows.Media;
using Powerstone.Core.Audio;

namespace Powerstone.Windows.Controls;

/// <summary>
/// Live spectrum of what's playing, drawn every frame from <see cref="SpectrumSampleProvider.Bands"/>.
/// Click to cycle styles: bars, blocks, mirror, line.
/// </summary>
public sealed class Visualizer : FrameworkElement
{
    public static readonly string[] Modes = { "BARS", "BLOCKS", "MIRROR", "LINE" };

    public static readonly DependencyProperty SourceProperty = DependencyProperty.Register(
        nameof(Source), typeof(SpectrumSampleProvider), typeof(Visualizer));

    public static readonly DependencyProperty IsActiveProperty = DependencyProperty.Register(
        nameof(IsActive), typeof(bool), typeof(Visualizer), new PropertyMetadata(true));

    public SpectrumSampleProvider? Source { get => (SpectrumSampleProvider?)GetValue(SourceProperty); set => SetValue(SourceProperty, value); }
    public bool IsActive { get => (bool)GetValue(IsActiveProperty); set => SetValue(IsActiveProperty, value); }

    private readonly float[] _shown = new float[SpectrumSampleProvider.BandCount];
    private int _mode;
    private static readonly Brush Rose = new SolidColorBrush(Color.FromRgb(0xC0, 0x4A, 0x5E));
    private static readonly Pen RosePen = new(Rose, 2);

    static Visualizer()
    {
        Rose.Freeze();
        RosePen.Freeze();
    }

    public Visualizer()
    {
        Loaded += (_, _) => CompositionTarget.Rendering += OnFrame;
        Unloaded += (_, _) => CompositionTarget.Rendering -= OnFrame;
        MouseLeftButtonUp += (_, _) => { _mode = (_mode + 1) % Modes.Length; ToolTip = Modes[_mode]; };
        ToolTip = Modes[0];
        Cursor = System.Windows.Input.Cursors.Hand;
    }

    private void OnFrame(object? sender, EventArgs e)
    {
        var src = Source;
        var fresh = IsActive && src != null && Environment.TickCount64 - src.UpdatedTicks < 200;
        for (var i = 0; i < _shown.Length; i++) _shown[i] = fresh ? src!.Bands[i] : _shown[i] * 0.9f;
        InvalidateVisual();
    }

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight;
        dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, w, h)); // hit-testable
        var n = _shown.Length;
        var bw = w / n;
        switch (Modes[_mode])
        {
            case "BARS":
                for (var i = 0; i < n; i++) dc.DrawRectangle(Rose, null, new Rect(i * bw + 1, h * (1 - _shown[i]), Math.Max(1, bw - 2), h * _shown[i]));
                break;
            case "BLOCKS":
                var rows = Math.Max(1, (int)(h / bw));
                for (var i = 0; i < n; i++)
                {
                    var lit = (int)(_shown[i] * rows);
                    for (var r = 0; r < lit; r++)
                        dc.DrawRectangle(r == lit - 1 ? Brushes.White : Rose, null, new Rect(i * bw + 1, h - (r + 1) * bw + 1, Math.Max(1, bw - 2), Math.Max(1, bw - 2)));
                }
                break;
            case "MIRROR":
                for (var i = 0; i < n; i++) { var bh = h * _shown[i] / 2; dc.DrawRectangle(Rose, null, new Rect(i * bw + 1, h / 2 - bh, Math.Max(1, bw - 2), bh * 2)); }
                break;
            default:
                var g = new StreamGeometry();
                using (var ctx = g.Open())
                {
                    for (var i = 0; i < n; i++)
                    {
                        var p = new Point(i * bw + bw / 2, h * (1 - _shown[i]));
                        if (i == 0) ctx.BeginFigure(p, false, false); else ctx.LineTo(p, true, false);
                    }
                }
                g.Freeze();
                dc.DrawGeometry(null, RosePen, g);
                break;
        }
    }
}
