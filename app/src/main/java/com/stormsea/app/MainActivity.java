package com.stormsea.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/** A small native control layer above the sea renderer. */
public final class MainActivity extends Activity {
    private static final int INK = 0xFFF2F0E8;
    private static final int MUTED = 0xFFB6C5C7;
    private static final int ACCENT = 0xFF9CDAD5;
    private static final int PANEL = 0xDD0D1A21;
    private static final String[] QUALITY_LABELS = {"Low", "Balanced", "High"};

    private final CameraState camera = new CameraState();
    private final StormSettings settings = new StormSettings();
    private StormSurfaceView sea;
    private FrameLayout root;
    private FrameLayout safeUi;
    private LinearLayout header;
    private LinearLayout controls;
    private TextView controlsButton;
    private TextView status;
    private TextView stormValue;
    private TextView heightValue;
    private SeekBar heightSlider;
    private TextView pauseButton, driftButton, rainButton, lightningButton, qualityButton;
    private boolean controlsVisible = true;
    private boolean errorVisible;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        camera.restore(savedInstanceState);
        settings.restore(savedInstanceState);
        if (savedInstanceState != null) controlsVisible = savedInstanceState.getBoolean("ui.controls", true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF071218);
        setContentView(root);

        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        boolean supportsGl = manager != null && manager.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30000;
        if (supportsGl) {
            sea = new StormSurfaceView(this, camera, settings);
            root.addView(sea, new FrameLayout.LayoutParams(-1, -1));
            sea.getOceanRenderer().setListener(new OceanRenderer.Listener() {
                @Override public void onFrameStats(float fps, int width, int height) {
                    // Keep the view clear. Frame data remains available through the renderer.
                }

                @Override public void onError(String message) {
                    runOnUiThread(() -> showRenderError(message));
                }
            });
        }
        buildUi();
        if (!supportsGl) showRenderError("This device needs OpenGL ES 3.0 to show the sea.");
        hideSystemBars();
    }

    private void buildUi() {
        safeUi = new FrameLayout(this);
        safeUi.setPadding(dp(20), dp(17), dp(20), dp(13));
        root.addView(safeUi, new FrameLayout.LayoutParams(-1, -1));
        safeUi.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                    left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
                    top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                    right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                    bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
                }
            }
            view.setPadding(left + dp(20), top + dp(13), right + dp(20), bottom + dp(10));
            return insets;
        });

        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("STORM SEA", 19f, INK);
        title.setSingleLine(true);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(.23f);
        title.setShadowLayer(dp(8), 0, dp(1), 0xCC001016);
        header.addView(title, new LinearLayout.LayoutParams(-2, -2));
        status = text("", 9.5f, MUTED);
        status.setLetterSpacing(.15f);
        status.setShadowLayer(dp(5), 0, dp(1), Color.BLACK);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2);
        statusParams.topMargin = dp(5);
        header.addView(status, statusParams);
        safeUi.addView(header, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        controlsButton = button("Hide controls", false);
        controlsButton.setPadding(dp(15), 0, dp(15), 0);
        controlsButton.setOnClickListener(view -> setControlsVisible(!controlsVisible));
        safeUi.addView(controlsButton, new FrameLayout.LayoutParams(-2, dp(44), Gravity.TOP | Gravity.END));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(14), dp(10), dp(14), dp(9));
        controls.setBackground(panel(PANEL, 18f, 0x42637B83));
        controls.setClickable(true);
        int screenDp = getResources().getConfiguration().screenWidthDp;
        FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(screenDp > 748 ? dp(704) : -1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        safeUi.addView(controls, dockParams);
        safeUi.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int availableWidth = right - left - view.getPaddingLeft() - view.getPaddingRight();
            if (availableWidth <= 0) return;
            int width = Math.min(dp(704), availableWidth);
            FrameLayout.LayoutParams parameters = (FrameLayout.LayoutParams) controls.getLayoutParams();
            if (parameters.width != width) {
                parameters.width = width;
                controls.setLayoutParams(parameters);
            }
        });

        LinearLayout sliders = new LinearLayout(this);
        sliders.setOrientation(LinearLayout.HORIZONTAL);
        controls.addView(sliders, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout stormGroup = sliderGroup("STORM", sliders, true);
        stormValue = (TextView) stormGroup.getTag();
        SeekBar stormSlider = slider("Storm strength", 100, Math.round(settings.storm * 100f));
        stormGroup.addView(stormSlider, new LinearLayout.LayoutParams(-1, dp(30)));
        stormSlider.setOnSeekBarChangeListener(new SliderListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) settings.storm = progress / 100f;
                updateValues();
            }
        });

        LinearLayout heightGroup = sliderGroup("VIEW HEIGHT", sliders, false);
        heightValue = (TextView) heightGroup.getTag();
        heightSlider = slider("Camera height", 1000, heightToProgress(camera.snapshot().height));
        heightGroup.addView(heightSlider, new LinearLayout.LayoutParams(-1, dp(30)));
        heightSlider.setOnSeekBarChangeListener(new SliderListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    float position = progress / 1000f;
                    camera.setHeight(CameraState.MIN_HEIGHT + position * position * (CameraState.MAX_HEIGHT - CameraState.MIN_HEIGHT));
                }
                updateValues();
            }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, dp(44));
        actionParams.topMargin = dp(4);
        controls.addView(actions, actionParams);
        pauseButton = action(actions, "Pause", () -> { settings.paused = !settings.paused; updateValues(); });
        driftButton = action(actions, "Drift", () -> { settings.drift = !settings.drift; updateValues(); });
        rainButton = action(actions, "Rain", () -> { settings.rain = settings.rain > 0f ? 0f : .65f; updateValues(); });
        lightningButton = action(actions, "Lightning", () -> { settings.lightning = !settings.lightning; updateValues(); });
        qualityButton = action(actions, "Balanced", () -> { settings.quality = (settings.quality + 1) % 3; updateValues(); });
        action(actions, "Reset view", () -> {
            camera.reset();
            heightSlider.setProgress(heightToProgress(camera.snapshot().height));
            updateValues();
        });

        TextView hint = text("Drag to look    ·    Two fingers to move    ·    Pinch to zoom", 10f, MUTED);
        hint.setGravity(Gravity.CENTER);
        hint.setLetterSpacing(.015f);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(8);
        controls.addView(hint, hintParams);
        updateValues();
        setControlsVisible(controlsVisible);
        safeUi.requestApplyInsets();
    }

    private LinearLayout sliderGroup(String label, LinearLayout parent, boolean first) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(0, -2, 1f);
        if (first) groupParams.rightMargin = dp(22);
        parent.addView(group, groupParams);
        LinearLayout labels = new LinearLayout(this);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 9.5f, MUTED);
        name.setLetterSpacing(.13f);
        labels.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView value = text("", 12f, INK);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labels.addView(value, new LinearLayout.LayoutParams(-2, -2));
        group.addView(labels, new LinearLayout.LayoutParams(-1, -2));
        group.setTag(value);
        return group;
    }

    private SeekBar slider(String description, int maximum, int progress) {
        SeekBar result = new SeekBar(this);
        result.setMax(maximum);
        result.setProgress(progress);
        result.setPadding(dp(7), 0, dp(7), 0);
        result.setProgressTintList(ColorStateList.valueOf(ACCENT));
        result.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF3E5158));
        result.setThumbTintList(ColorStateList.valueOf(INK));
        result.setSplitTrack(false);
        result.setContentDescription(description);
        return result;
    }

    private TextView action(LinearLayout parent, String label, Runnable click) {
        TextView result = button(label, false);
        result.setOnClickListener(view -> click.run());
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(0, -1, 1f);
        if (parent.getChildCount() > 0) parameters.leftMargin = dp(5);
        parent.addView(result, parameters);
        return result;
    }

    private TextView button(String label, boolean active) {
        TextView result = text(label, 11.5f, active ? ACCENT : INK);
        result.setGravity(Gravity.CENTER);
        result.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        result.setPadding(dp(4), 0, dp(4), 0);
        result.setSingleLine(true);
        result.setMinHeight(dp(44));
        result.setFocusable(true);
        result.setClickable(true);
        styleButton(result, active);
        return result;
    }

    private void styleButton(TextView view, boolean active) {
        if (view.getBackground() != null && view.isActivated() == active) return;
        view.setTextColor(active ? ACCENT : INK);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x4476B7B5),
                panel(active ? 0xC5223D44 : 0xB31A2A32, 9f, active ? 0x896FAEAD : 0x395E727B), null));
        view.setActivated(active);
    }

    private void updateValues() {
        if (stormValue == null || heightValue == null || pauseButton == null) return;
        stormValue.setText(Math.round(settings.storm * 100f) + "%");
        heightValue.setText(String.format(Locale.US, "%.1f m", camera.snapshot().height));
        status.setText(settings.paused ? "OPEN WATER  /  PAUSED" : "OPEN WATER  /  LIVE");
        pauseButton.setText(settings.paused ? "Play" : "Pause");
        pauseButton.setContentDescription(settings.paused ? "Play the storm" : "Pause the storm");
        styleButton(pauseButton, settings.paused);
        updateToggle(driftButton, "Drift", settings.drift);
        updateToggle(rainButton, "Rain", settings.rain > 0f);
        updateToggle(lightningButton, "Lightning", settings.lightning);
        qualityButton.setText(QUALITY_LABELS[settings.quality]);
        qualityButton.setContentDescription("Detail level: " + QUALITY_LABELS[settings.quality] + ". Tap to change.");
    }

    private void updateToggle(TextView view, String label, boolean on) {
        styleButton(view, on);
        view.setContentDescription(label + (on ? " on. Tap to turn off." : " off. Tap to turn on."));
        if (Build.VERSION.SDK_INT >= 30) view.setStateDescription(on ? "On" : "Off");
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        header.setVisibility(visible ? View.VISIBLE : View.GONE);
        controls.setVisibility(visible && !errorVisible ? View.VISIBLE : View.GONE);
        controlsButton.setText(visible ? "Hide controls" : "Controls");
        controlsButton.setContentDescription(visible ? "Hide controls for a clear view" : "Show sea controls");
    }

    private void showRenderError(String message) {
        if (errorVisible || isFinishing() || isDestroyed()) return;
        errorVisible = true;
        settings.paused = true;
        updateValues();
        if (controls != null) controls.setVisibility(View.GONE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(20), dp(24), dp(20));
        card.setBackground(panel(0xF00D1A21, 18f, 0xFF49636C));
        card.addView(text("The sea could not start", 20f, INK));
        TextView detail = text(message == null ? "Restart the app to try again." : message, 13f, MUTED);
        detail.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(12);
        card.addView(detail, detailParams);
        TextView close = button("Close", false);
        close.setOnClickListener(view -> finish());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(100), dp(44));
        closeParams.topMargin = dp(18);
        card.addView(close, closeParams);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(dp(Math.min(460, getResources().getConfiguration().screenWidthDp - 48)), -2, Gravity.CENTER);
        root.addView(card, cardParams);
    }

    private TextView text(String value, float size, int color) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setFontFeatureSettings("kern");
        result.setIncludeFontPadding(false);
        return result;
    }

    private GradientDrawable panel(int color, float radius, int stroke) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(radius));
        result.setStroke(dp(1), stroke);
        return result;
    }

    private int heightToProgress(float height) {
        return Math.round((float) Math.sqrt((height - CameraState.MIN_HEIGHT) / (CameraState.MAX_HEIGHT - CameraState.MIN_HEIGHT)) * 1000f);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override protected void onResume() {
        super.onResume();
        if (sea != null) sea.onResume();
        hideSystemBars();
    }

    @Override protected void onPause() {
        if (sea != null) sea.onPause();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        camera.save(outState);
        settings.save(outState);
        outState.putBoolean("ui.controls", controlsVisible);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        if (sea != null) sea.getOceanRenderer().setListener(null);
        super.onDestroy();
    }

    CameraState getCameraState() { return camera; }
    StormSettings getStormSettings() { return settings; }
    StormSurfaceView getStormSurfaceView() { return sea; }

    private abstract static class SliderListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar bar) { }
        @Override public void onStopTrackingTouch(SeekBar bar) { }
    }
}
