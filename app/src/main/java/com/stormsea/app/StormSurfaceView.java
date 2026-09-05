package com.stormsea.app;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

/** Native GL surface with one-finger look and two-finger move and zoom. */
public final class StormSurfaceView extends GLSurfaceView {
    private final CameraState camera;
    private final OceanRenderer oceanRenderer;
    private final float density;
    private int firstId = -1;
    private int secondId = -1;
    private float lastX, lastY, lastSpan;
    private boolean moved;

    public StormSurfaceView(Context context, CameraState camera, StormSettings settings) {
        super(context);
        this.camera = camera;
        density = getResources().getDisplayMetrics().density;
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 0, 16, 0);
        setPreserveEGLContextOnPause(true);
        oceanRenderer = new OceanRenderer(context, camera, settings);
        setRenderer(oceanRenderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setFocusable(true);
        setContentDescription("Storm sea. Drag to look. Use two fingers to move. Pinch to zoom.");
    }

    public OceanRenderer getOceanRenderer() {
        return oceanRenderer;
    }

    @Override public void onResume() {
        super.onResume();
        queueEvent(oceanRenderer::resetFrameClock);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                moved = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                anchorPointers(event, -1);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                moved = true;
                anchorPointers(event, -1);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                anchorPointers(event, event.getActionIndex());
                return true;
            case MotionEvent.ACTION_MOVE:
                movePointers(event);
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved) performClick();
                clearPointers();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                clearPointers();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }

    /** Reset the anchors whenever a pointer enters or leaves. */
    private void anchorPointers(MotionEvent event, int excludedIndex) {
        firstId = -1;
        secondId = -1;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == excludedIndex) continue;
            if (firstId == -1) firstId = event.getPointerId(i);
            else {
                secondId = event.getPointerId(i);
                break;
            }
        }
        int first = event.findPointerIndex(firstId);
        if (first < 0) return;
        lastX = event.getX(first);
        lastY = event.getY(first);
        lastSpan = 0f;
        int second = event.findPointerIndex(secondId);
        if (second >= 0) {
            lastSpan = span(event, first, second);
            lastX = (lastX + event.getX(second)) * .5f;
            lastY = (lastY + event.getY(second)) * .5f;
        }
    }

    private void movePointers(MotionEvent event) {
        int first = event.findPointerIndex(firstId);
        int second = event.findPointerIndex(secondId);
        if (first < 0 || (secondId != -1 && second < 0)) {
            anchorPointers(event, -1);
            return;
        }
        float x = event.getX(first);
        float y = event.getY(first);
        if (second >= 0) {
            x = (x + event.getX(second)) * .5f;
            y = (y + event.getY(second)) * .5f;
            float currentSpan = span(event, first, second);
            if (currentSpan > density * 12f && lastSpan > density * 12f) {
                camera.scaleFov(currentSpan / lastSpan);
            }
            float metresPerPixel = .018f * camera.snapshot().height / 4.2f / density;
            camera.pan(-(x - lastX) * metresPerPixel, (y - lastY) * metresPerPixel);
            lastSpan = currentSpan;
        } else {
            camera.rotate((x - lastX) * .18f / density, -(y - lastY) * .15f / density);
        }
        if (Math.abs(x - lastX) + Math.abs(y - lastY) > density) moved = true;
        lastX = x;
        lastY = y;
    }

    private static float span(MotionEvent event, int first, int second) {
        return (float) Math.hypot(event.getX(first) - event.getX(second),
                event.getY(first) - event.getY(second));
    }

    private void clearPointers() {
        firstId = -1;
        secondId = -1;
        lastSpan = 0f;
    }

    @Override public void onPause() {
        clearPointers();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        super.onPause();
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
