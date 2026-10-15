namespace Powerstone.Core.Audio;

/// <summary>
/// Safe writes into the buffer a sample provider is asked to fill. NAudio's output hands
/// providers a byte[] reinterpreted as float[] (WaveBuffer), so Array.Copy / Array.Clear /
/// Span on it throw ArrayTypeMismatchException or cover the wrong range. Plain loops are fine.
/// </summary>
internal static class OutputBuffer
{
    public static void Clear(float[] buffer, int offset, int count)
    {
        for (int i = offset, end = offset + count; i < end; i++) buffer[i] = 0f;
    }

    public static void Copy(float[] source, int sourceOffset, float[] buffer, int offset, int count)
    {
        for (var i = 0; i < count; i++) buffer[offset + i] = source[sourceOffset + i];
    }
}
