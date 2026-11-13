package com.limelight.yugi;

/** Pure input math for the on-screen pad (unit tested, no Android dependencies). */
public final class PadMath {
    private PadMath() {}

    /**
     * Stick offset (px) to axes in [-1, 1] with a radial deadzone, rescaled so the output is linear and
     * continuous from the deadzone edge (0) to the rim (1). Beyond the rim it saturates (no jumps).
     */
    public static float[] stick(float dx, float dy, float radius, float deadzone) {
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (radius <= 0 || len <= 0) return new float[]{0, 0};
        float m = Math.min(1f, len / radius);
        if (m <= deadzone) return new float[]{0, 0};
        float scaled = (m - deadzone) / (1f - deadzone);
        return new float[]{dx / len * scaled, dy / len * scaled};
    }

    /**
     * Look-zone drag to right-stick deflection for one axis.
     * speed = |delta| / dt (dp per ms); out = sign * (antiDeadzone + (1 - antiDeadzone) * min(1, sens * speed^curve)).
     * antiDeadzone skips the game's own inner deadzone so tiny drags still move the camera.
     */
    public static float look(float deltaDp, float dtMs, float sensitivity, float curve, float antiDeadzone) {
        if (deltaDp == 0) return 0f;
        float speed = Math.abs(deltaDp) / Math.max(1f, dtMs);
        float mag = Math.min(1f, sensitivity * (float) Math.pow(speed, curve));
        float out = antiDeadzone + (1f - antiDeadzone) * mag;
        return Math.signum(deltaDp) * Math.min(1f, out);
    }

    /** Exponential decay toward zero when the finger stops; returns 0 below 0.02. */
    public static float decay(float value, float factor) {
        float v = value * factor;
        return Math.abs(v) < 0.02f ? 0f : v;
    }

    public static float clamp1(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    /**
     * Gyroscope (rad/s, device axes) to right-stick deflection, for the current screen rotation
     * (0..3 = Surface.ROTATION_0..270). Returns {x, y}; y positive = look down (gamepad convention).
     */
    public static float[] gyroToLook(float gx, float gy, float gz, int rotation, float sensitivity, boolean invertY) {
        // Axis of the screen's vertical (yaw) and horizontal (pitch) directions, in device coordinates
        float yaw, pitch;
        switch (rotation & 3) {
            case 1: yaw = gx; pitch = -gy; break;   // landscape, top to the left
            case 2: yaw = -gy; pitch = -gx; break;  // upside down
            case 3: yaw = -gx; pitch = gy; break;   // landscape, top to the right
            default: yaw = gy; pitch = gx; break;   // natural portrait
        }
        float x = clamp1(-yaw * sensitivity);
        float y = clamp1(-pitch * sensitivity);
        if (invertY) y = -y;
        return new float[]{x, y};
    }
}
