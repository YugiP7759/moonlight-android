package com.limelight.yugi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PadMathTest {
    private static final float E = 1e-4f;

    @Test public void stickInsideDeadzoneIsZero() {
        float[] a = PadMath.stick(5, 0, 100, 0.08f);
        assertEquals(0, a[0], E);
        assertEquals(0, a[1], E);
    }

    @Test public void stickIsLinearAndContinuousFromDeadzoneEdge() {
        assertEquals(0f, PadMath.stick(8, 0, 100, 0.08f)[0], E);
        assertEquals(0.5f, PadMath.stick(54, 0, 100, 0.08f)[0], E);
        assertEquals(1f, PadMath.stick(100, 0, 100, 0.08f)[0], E);
        // Saturates beyond the rim without jumps
        assertEquals(1f, PadMath.stick(250, 0, 100, 0.08f)[0], E);
        // Direction preserved, magnitude <= 1
        float[] d = PadMath.stick(-70.7f, 70.7f, 100, 0f);
        assertEquals(-0.707f, d[0], 1e-3f);
        assertEquals(0.707f, d[1], 1e-3f);
    }

    @Test public void stickMonotonic() {
        float prev = -1;
        for (int px = 0; px <= 120; px++) {
            float v = PadMath.stick(px, 0, 100, 0.1f)[0];
            assertTrue(v >= prev - E);
            prev = v;
        }
    }

    @Test public void lookZeroDeltaIsZero() {
        assertEquals(0f, PadMath.look(0, 8, 0.6f, 1f, 0.12f), E);
    }

    @Test public void lookSmallDragPassesGameDeadzone() {
        float v = PadMath.look(0.2f, 8, 0.6f, 1f, 0.12f);
        assertTrue(v >= 0.12f);
        assertTrue(v < 0.2f);
    }

    @Test public void lookSignAndSaturation() {
        assertTrue(PadMath.look(-3, 8, 0.6f, 1f, 0.12f) < 0);
        assertEquals(1f, PadMath.look(500, 8, 0.6f, 1f, 0.12f), E);
        assertEquals(-1f, PadMath.look(-500, 8, 0.6f, 1f, 0.12f), E);
    }

    @Test public void lookFasterDragMeansMoreDeflection() {
        float slow = PadMath.look(1, 16, 0.6f, 1f, 0.12f);
        float fast = PadMath.look(4, 16, 0.6f, 1f, 0.12f);
        assertTrue(fast > slow);
    }

    @Test public void lookCurveShapesSmallMovements() {
        // speed 0.5 dp/ms: curve 2 gives less than linear
        float lin = PadMath.look(4, 8, 0.6f, 1f, 0f);
        float curved = PadMath.look(4, 8, 0.6f, 2f, 0f);
        assertTrue(curved < lin);
    }

    @Test public void decayGoesToZeroQuickly() {
        float v = 1f;
        int ticks = 0;
        while (v != 0f && ticks < 100) { v = PadMath.decay(v, 0.45f); ticks++; }
        assertEquals(0f, v, 0f);
        assertTrue("ticks=" + ticks, ticks <= 6); // <= ~50 ms at 8 ms per tick
    }

    @Test public void gyroInvertY() {
        float[] a = PadMath.gyroToLook(0.5f, 0.5f, 0, 1, 0.5f, false);
        float[] b = PadMath.gyroToLook(0.5f, 0.5f, 0, 1, 0.5f, true);
        assertEquals(a[0], b[0], E);
        assertEquals(-a[1], b[1], E);
        assertTrue(Math.abs(a[0]) <= 1 && Math.abs(a[1]) <= 1);
    }
}
