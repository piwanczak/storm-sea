package com.stormsea.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Device checks. This class is installed in a separate test APK. */
public final class StormInstrumentation extends Instrumentation {
    private final StringBuilder results = new StringBuilder();
    private MainActivity activity;
    private String capturePrefix;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) capturePrefix = arguments.getString("capturePrefix");
        if (capturePrefix != null && !capturePrefix.matches("[a-z0-9-]+")) capturePrefix = null;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            cameraChecks();
            Intent intent = new Intent(getTargetContext(), MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = (MainActivity) startActivitySync(intent);
            waitForIdleSync();
            check(activity.getStormSurfaceView() != null, "Native OpenGL surface starts");
            long deadline = SystemClock.uptimeMillis() + 30000;
            while (activity.getStormSurfaceView().getOceanRenderer().getRenderedFrames() < 3
                    && activity.getStormSurfaceView().getOceanRenderer().getFailure() == null
                    && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(200);
            check(activity.getStormSurfaceView().getOceanRenderer().getFailure() == null,
                    "GPU compiles and links both shaders");
            check(activity.getStormSurfaceView().getOceanRenderer().getRenderedFrames() >= 3,
                    "GPU draws native ocean frames");
            SystemClock.sleep(600);
            if (capturePrefix != null) {
                captureFixedViews();
                result.putString("stream", results.toString());
                finish(Activity.RESULT_OK, result);
                return;
            }
            runOnMainSync(() -> activity.getStormSettings().drift = false);
            gestureChecks();
            runOnMainSync(() -> activity.getCameraState().reset());
            SystemClock.sleep(500);
            Bitmap first = screenshot("controls.png");
            SystemClock.sleep(1300);
            Bitmap second = screenshot("animated.png");
            check(pixelDifference(first, second) > .5, "Sea pixels change while animation runs");
            check(imageRange(second) > 35, "Scene has visible light and dark detail");

            click("Pause");
            check(activity.getStormSettings().paused, "Pause button stops scene time");
            SystemClock.sleep(500);
            Bitmap pausedA = screenshot("paused.png");
            SystemClock.sleep(800);
            Bitmap pausedB = screenshot("paused-check.png");
            check(pixelDifference(pausedA, pausedB) < .3, "Paused scene pixels remain fixed");
            click("Hide controls");
            SystemClock.sleep(300);
            screenshot("sea.png");
            click("Controls");
            check(findText(activity.getWindow().getDecorView(), "Reset view") != null,
                    "Controls can be hidden and restored");
            click("Play");
            click("Rain");
            check(activity.getStormSettings().rain == 0f, "Rain toggle works");
            click("Rain");
            click("Lightning");
            check(!activity.getStormSettings().lightning, "Lightning toggle works");
            click("Lightning");
            click("Balanced");
            check(activity.getStormSettings().quality == 2, "High detail can be selected");
            SystemClock.sleep(500);
            click("High");
            check(activity.getStormSettings().quality == 0, "Low detail can be selected");
            SystemClock.sleep(500);
            click("Low");

            runOnMainSync(() -> {
                activity.getCameraState().rotate(35f, -20f);
                activity.getCameraState().setHeight(16f);
            });
            SystemClock.sleep(400);
            screenshot("high-view.png");
            click("Reset view");
            check(Math.abs(activity.getCameraState().snapshot().height - 4.2f) < .001,
                    "Reset view restores camera defaults");

            Bundle saved = new Bundle();
            runOnMainSync(() -> {
                activity.getCameraState().rotate(20f, 3f);
                callActivityOnSaveInstanceState(activity, saved);
                callActivityOnPause(activity);
                callActivityOnResume(activity);
            });
            SystemClock.sleep(400);
            CameraState restored = new CameraState();
            restored.restore(saved);
            check(Math.abs(restored.snapshot().yaw - 20f) < .001,
                    "Activity saves the camera and resumes the GL surface");
            check(activity.getStormSurfaceView().getOceanRenderer().getFailure() == null,
                    "Renderer has no error after lifecycle and detail changes");
            runOnMainSync(() -> { activity.getCameraState().reset(); activity.getStormSettings().drift = true; });
            results.append("Renderer FPS at test end: ")
                    .append(activity.getStormSurfaceView().getOceanRenderer().getFramesPerSecond()).append('\n');
            results.append("PASS: all native checks\n");
            result.putString("stream", results.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            results.append("FAIL: ").append(error).append('\n');
            result.putString("stream", results.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void cameraChecks() {
        CameraState camera = new CameraState();
        camera.setHeight(-100f);
        check(camera.snapshot().height >= 3.6f, "Camera stays above wave crests");
        camera.setHeight(10000f);
        check(camera.snapshot().height == 28f, "Camera height has an upper limit");
        camera.rotate(450f, 200f);
        check(camera.snapshot().yaw == 90f && camera.snapshot().pitch <= 72f,
                "Camera angles stay in range");
        camera.reset();
        camera.rotate(90f, 0f);
        camera.pan(0f, 4f);
        check(Math.abs(camera.snapshot().x - 4f) < .001, "Travel follows the camera heading");
        camera.scaleFov(100f);
        check(camera.snapshot().fov == 36f, "Zoom has a safe lower limit");
        camera.scaleFov(.001f);
        check(camera.snapshot().fov == 92f, "Zoom has a safe upper limit");
        Bundle state = new Bundle();
        camera.save(state);
        CameraState other = new CameraState();
        other.restore(state);
        check(other.snapshot().x == camera.snapshot().x && other.snapshot().fov == 92f,
                "Camera state survives save and restore");
    }

    /** Compare APK versions at the same time, camera pose, and render size. */
    private void captureFixedViews() throws Exception {
        click("Hide controls");
        fixedView(4.2f, 0f, -8f, 64f, 12f, 0f);
        Bitmap dry = screenshot(capturePrefix + "-low-dry.png");
        fixedView(4.2f, 0f, -8f, 64f, 12f, .65f);
        Bitmap wet = screenshot(capturePrefix + "-low-rain.png");
        results.append("Rain image difference (0..255): ").append(pixelDifference(dry, wet)).append('\n');
        fixedView(28f, 0f, -42f, 92f, 12f, 0f);
        screenshot(capturePrefix + "-wide-dry.png");
        fixedView(28f, 0f, -42f, 92f, 12f, .65f);
        screenshot(capturePrefix + "-wide-rain.png");
        fixedView(28f, 83f, -42f, 92f, 19f, 0f);
        screenshot(capturePrefix + "-wide-cross.png");
        fixedView(4.2f, 0f, -8f, 64f, 12.17f, .65f);
        screenshot(capturePrefix + "-rain-motion.png");
        results.append("PASS: fixed camera captures\n");
    }

    private void fixedView(float height, float yaw, float pitch, float fov, float time, float rain) throws Exception {
        runOnMainSync(() -> {
            StormSettings settings = activity.getStormSettings();
            settings.paused = true;
            settings.drift = false;
            settings.lightning = false;
            settings.storm = 1f;
            settings.quality = 1;
            settings.rain = rain;
            CameraState camera = activity.getCameraState();
            camera.reset();
            camera.setHeight(height);
            camera.rotate(yaw, pitch + 8f);
            camera.scaleFov(64f / fov);
        });
        OceanRenderer renderer = activity.getStormSurfaceView().getOceanRenderer();
        Field clock = OceanRenderer.class.getDeclaredField("time");
        clock.setAccessible(true);
        CountDownLatch applied = new CountDownLatch(1);
        activity.getStormSurfaceView().queueEvent(() -> {
            try { clock.setFloat(renderer, time); }
            catch (IllegalAccessException error) { throw new IllegalStateException(error); }
            finally { applied.countDown(); }
        });
        check(applied.await(10, TimeUnit.SECONDS), "Fixed scene time reached the GL thread");
        long frame = renderer.getRenderedFrames();
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (renderer.getRenderedFrames() < frame + 3 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        check(renderer.getRenderedFrames() >= frame + 3, "Fixed view reached the screen");
        SystemClock.sleep(150);
    }

    private void gestureChecks() {
        runOnMainSync(() -> {
            CameraState camera = activity.getCameraState();
            StormSurfaceView view = activity.getStormSurfaceView();
            camera.reset();
            event(view, MotionEvent.ACTION_DOWN, new float[]{400}, new float[]{350});
            event(view, MotionEvent.ACTION_MOVE, new float[]{600}, new float[]{300});
            event(view, MotionEvent.ACTION_UP, new float[]{600}, new float[]{300});
            check(camera.snapshot().yaw > 0f && camera.snapshot().pitch > -8f,
                    "One-finger drag turns and tilts the camera");
            camera.reset();
            event(view, MotionEvent.ACTION_DOWN, new float[]{400}, new float[]{350});
            event(view, MotionEvent.ACTION_POINTER_DOWN | (1 << 8), new float[]{400,600}, new float[]{350,350});
            event(view, MotionEvent.ACTION_MOVE, new float[]{400,600}, new float[]{350,350});
            check(camera.snapshot().yaw == 0f && camera.snapshot().x == 0f && camera.snapshot().fov == 64f,
                    "Second pointer does not cause a camera jump");
            event(view, MotionEvent.ACTION_POINTER_DOWN | (2 << 8), new float[]{400,600,800}, new float[]{350,350,350});
            event(view, MotionEvent.ACTION_POINTER_UP | (2 << 8), new float[]{400,600,800}, new float[]{350,350,350});
            event(view, MotionEvent.ACTION_MOVE, new float[]{400,600}, new float[]{350,350});
            check(camera.snapshot().x == 0f && camera.snapshot().fov == 64f,
                    "Third pointer does not cause a camera jump");
            event(view, MotionEvent.ACTION_MOVE, new float[]{350,650}, new float[]{350,350});
            check(camera.snapshot().fov < 64f, "Pinch changes perspective");
            event(view, MotionEvent.ACTION_MOVE, new float[]{400,700}, new float[]{410,410});
            check(Math.abs(camera.snapshot().x) > .1f && Math.abs(camera.snapshot().z) > .1f,
                    "Two-finger drag moves the camera across the sea");
            event(view, MotionEvent.ACTION_POINTER_UP | (1 << 8), new float[]{400,700}, new float[]{410,410});
            float yaw = camera.snapshot().yaw;
            event(view, MotionEvent.ACTION_MOVE, new float[]{400}, new float[]{410});
            check(camera.snapshot().yaw == yaw, "Pointer removal does not cause a camera jump");
            event(view, MotionEvent.ACTION_UP, new float[]{400}, new float[]{410});
        });
    }

    private void event(View view, int action, float[] xs, float[] ys) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[xs.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[xs.length];
        for (int i = 0; i < xs.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = xs[i]; coords[i].y = ys[i]; coords[i].pressure = 1; coords[i].size = 1;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent motion = MotionEvent.obtain(now, now, action, xs.length, properties, coords,
                0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        view.dispatchTouchEvent(motion);
        motion.recycle();
    }

    private void click(String text) {
        runOnMainSync(() -> {
            View view = findText(activity.getWindow().getDecorView(), text);
            if (view == null) throw new AssertionError("Missing control: " + text);
            view.performClick();
        });
        waitForIdleSync();
    }

    private View findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView)view).getText()) && view.isShown()) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Bitmap screenshot(String name) throws Exception {
        // The first-use full-screen notice can arrive after the first GL frame.
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        if (root != null) {
            List<AccessibilityNodeInfo> notices = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/ok");
            for (AccessibilityNodeInfo notice : notices) {
                if ("Got it".contentEquals(notice.getText())) {
                    if (!notice.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        throw new AssertionError("Cannot dismiss the Android full-screen notice");
                    }
                    SystemClock.sleep(450);
                }
            }
        }
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("No screenshot from Android");
        File directory = new File(getTargetContext().getExternalFilesDir(null), "qa");
        directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        return bitmap;
    }

    private double pixelDifference(Bitmap a, Bitmap b) {
        double sum = 0; int count = 0;
        for (int y = a.getHeight() / 3; y < a.getHeight() * 2 / 3; y += 7) {
            for (int x = a.getWidth() / 5; x < a.getWidth() * 4 / 5; x += 7) {
                int ca = a.getPixel(x, y), cb = b.getPixel(x, y);
                sum += Math.abs(Color.red(ca)-Color.red(cb)) + Math.abs(Color.green(ca)-Color.green(cb))
                        + Math.abs(Color.blue(ca)-Color.blue(cb));
                count += 3;
            }
        }
        return sum / count;
    }

    private int imageRange(Bitmap a) {
        int minimum = 255, maximum = 0;
        for (int y = a.getHeight()/3; y < a.getHeight()*2/3; y += 13) {
            for (int x = a.getWidth()/5; x < a.getWidth()*4/5; x += 13) {
                int value = Color.green(a.getPixel(x,y));
                minimum = Math.min(minimum, value); maximum = Math.max(maximum, value);
            }
        }
        return maximum - minimum;
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        results.append("PASS: ").append(message).append('\n');
    }
}
