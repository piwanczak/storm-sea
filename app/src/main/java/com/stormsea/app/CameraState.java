package com.stormsea.app;

import android.os.Bundle;

/** Camera values shared by the UI thread and the render thread. */
public final class CameraState {
    public static final float MIN_HEIGHT = 3.6f;
    public static final float MAX_HEIGHT = 28f;
    private float x = 0f;
    private float z = 0f;
    private float height = 4.2f;
    private float yaw = 0f;
    private float pitch = -8f;
    private float fov = 64f;

    /** Angles are in degrees. Zero yaw faces -Z. Positive pitch looks up. */
    public static final class Snapshot {
        public final float x, z, height, yaw, pitch, fov;

        private Snapshot(float x, float z, float height, float yaw, float pitch, float fov) {
            this.x = x;
            this.z = z;
            this.height = height;
            this.yaw = yaw;
            this.pitch = pitch;
            this.fov = fov;
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(x, z, height, yaw, pitch, fov);
    }

    public synchronized void rotate(float deltaYaw, float deltaPitch) {
        if (!Float.isFinite(deltaYaw) || !Float.isFinite(deltaPitch)) return;
        yaw = wrap(yaw + deltaYaw);
        pitch = clamp(pitch + deltaPitch, -80f, 72f);
    }

    /** Move in metres, along the horizontal camera axes. */
    public synchronized void pan(float right, float forward) {
        if (!Float.isFinite(right) || !Float.isFinite(forward)) return;
        float angle = (float) Math.toRadians(yaw);
        float sin = (float) Math.sin(angle);
        float cos = (float) Math.cos(angle);
        x += cos * right + sin * forward;
        z += sin * right - cos * forward;
    }

    public synchronized void setHeight(float value) {
        if (Float.isFinite(value)) height = clamp(value, MIN_HEIGHT, MAX_HEIGHT);
    }

    /** A factor above one narrows the field of view. */
    public synchronized void scaleFov(float factor) {
        if (Float.isFinite(factor) && factor > 0f) fov = clamp(fov / factor, 36f, 92f);
    }

    /** Call once per render frame. The time limit prevents a jump after resume. */
    public synchronized void advance(float seconds, boolean drift) {
        if (drift && Float.isFinite(seconds)) pan(0f, clamp(seconds, 0f, .1f) * .8f);
    }

    public synchronized void reset() {
        x = 0f;
        z = 0f;
        height = 4.2f;
        yaw = 0f;
        pitch = -8f;
        fov = 64f;
    }

    public synchronized void save(Bundle out) {
        out.putFloat("camera.x", x);
        out.putFloat("camera.z", z);
        out.putFloat("camera.height", height);
        out.putFloat("camera.yaw", yaw);
        out.putFloat("camera.pitch", pitch);
        out.putFloat("camera.fov", fov);
    }

    public synchronized void restore(Bundle in) {
        if (in == null) return;
        x = finiteOr(in.getFloat("camera.x", 0f), 0f);
        z = finiteOr(in.getFloat("camera.z", 0f), 0f);
        height = clamp(finiteOr(in.getFloat("camera.height", 4.2f), 4.2f), MIN_HEIGHT, MAX_HEIGHT);
        yaw = wrap(finiteOr(in.getFloat("camera.yaw", 0f), 0f));
        pitch = clamp(finiteOr(in.getFloat("camera.pitch", -8f), -8f), -80f, 72f);
        fov = clamp(finiteOr(in.getFloat("camera.fov", 64f), 64f), 36f, 92f);
    }

    private static float wrap(float angle) {
        return ((angle + 180f) % 360f + 360f) % 360f - 180f;
    }

    private static float finiteOr(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
}
