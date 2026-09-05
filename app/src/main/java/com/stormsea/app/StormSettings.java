package com.stormsea.app;

import android.os.Bundle;

/** Render settings. Volatile fields make UI changes visible to the render thread. */
public final class StormSettings {
    public volatile float storm = .8f;
    public volatile float rain = .65f;
    public volatile boolean lightning = true;
    public volatile boolean paused = false;
    /** 0: low, 1: balanced, 2: high. */
    public volatile int quality = 1;
    public volatile boolean drift = true;

    public void save(Bundle out) {
        out.putFloat("scene.storm", storm);
        out.putFloat("scene.rain", rain);
        out.putBoolean("scene.lightning", lightning);
        out.putBoolean("scene.paused", paused);
        out.putInt("scene.quality", quality);
        out.putBoolean("scene.drift", drift);
    }

    public void restore(Bundle in) {
        if (in == null) return;
        storm = unit(in.getFloat("scene.storm", .8f), .8f);
        rain = unit(in.getFloat("scene.rain", .65f), .65f);
        lightning = in.getBoolean("scene.lightning", true);
        paused = in.getBoolean("scene.paused", false);
        quality = Math.max(0, Math.min(2, in.getInt("scene.quality", 1)));
        drift = in.getBoolean("scene.drift", true);
    }

    private static float unit(float value, float fallback) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : fallback;
    }
}
