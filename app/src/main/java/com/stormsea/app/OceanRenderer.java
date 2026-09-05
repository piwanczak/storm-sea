package com.stormsea.app;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Draw the ocean on the GL thread. All UI callbacks run on the main thread. */
public final class OceanRenderer implements GLSurfaceView.Renderer {
    public interface Listener {
        void onFrameStats(float fps, int width, int height);
        void onError(String message);
    }

    private final Context context;
    private final CameraState camera;
    private final StormSettings settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    private volatile String failure;
    private volatile long renderedFrames;
    private volatile float framesPerSecond;
    private int program, vao, buffer, framebuffer, texture;
    private int surfaceWidth = 1, surfaceHeight = 1, renderWidth, renderHeight;
    private int resolutionLocation, timeLocation, cameraLocation;
    private int forwardLocation, rightLocation, upLocation, fovLocation;
    private int stormLocation, rainLocation, lightningLocation, qualityLocation;
    private int previousQuality = -1;
    private long previousNanos, statsNanos;
    private int statsFrames;
    private float time = 9f, adaptiveScale = 1f;
    private int slowWindows, fastWindows;
    private boolean ready;

    public OceanRenderer(Context context, CameraState camera, StormSettings settings) {
        this.context = context.getApplicationContext();
        this.camera = camera;
        this.settings = settings;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (failure != null && listener != null) main.post(() -> listener.onError(failure));
    }

    public String getFailure() { return failure; }
    public long getRenderedFrames() { return renderedFrames; }
    public float getFramesPerSecond() { return framesPerSecond; }

    /** Run on the GL thread when the surface resumes. */
    public void resetFrameClock() {
        previousNanos = statsNanos = 0;
        statsFrames = slowWindows = fastWindows = 0;
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // Context loss also destroys all GL names. Create each object again.
        program = vao = buffer = framebuffer = texture = 0;
        renderWidth = renderHeight = 0;
        previousNanos = statsNanos = 0;
        previousQuality = -1;
        ready = false;
        failure = null;
        try {
            int vertex = compile(GLES30.GL_VERTEX_SHADER, readAsset("shaders/ocean.vert"));
            String fragmentSource = readAsset("shaders/ocean.frag")
                    .replace("#include \"waves.glsl\"", readAsset("shaders/waves.glsl"))
                    .replace("#include \"rain.glsl\"", readAsset("shaders/rain.glsl"));
            int fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource);
            program = GLES30.glCreateProgram();
            GLES30.glAttachShader(program, vertex);
            GLES30.glAttachShader(program, fragment);
            GLES30.glLinkProgram(program);
            int[] status = new int[1];
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0);
            String linkLog = GLES30.glGetProgramInfoLog(program);
            GLES30.glDeleteShader(vertex);
            GLES30.glDeleteShader(fragment);
            if (status[0] == 0) throw new IllegalStateException("Shader link: " + linkLog);

            resolutionLocation = location("uResolution");
            timeLocation = location("uTime");
            cameraLocation = location("uCamera");
            forwardLocation = location("uForward");
            rightLocation = location("uRight");
            upLocation = location("uUp");
            fovLocation = location("uFov");
            stormLocation = location("uStorm");
            rainLocation = location("uRain");
            lightningLocation = location("uLightning");
            qualityLocation = location("uQuality");

            FloatBuffer points = ByteBuffer.allocateDirect(6 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            points.put(new float[] {-1, -1, 3, -1, -1, 3}).position(0);
            int[] names = new int[1];
            GLES30.glGenVertexArrays(1, names, 0); vao = names[0];
            GLES30.glBindVertexArray(vao);
            GLES30.glGenBuffers(1, names, 0); buffer = names[0];
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 24, points, GLES30.GL_STATIC_DRAW);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0);
            GLES30.glBindVertexArray(0);
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            GLES30.glDisable(GLES30.GL_BLEND);
            GLES30.glDisable(GLES30.GL_DITHER);
            ready = true;
            Log.i("StormSea", "OpenGL " + GLES30.glGetString(GLES30.GL_VERSION)
                    + "; GPU " + GLES30.glGetString(GLES30.GL_RENDERER));
        } catch (Exception error) {
            reportFailure(error);
        }
    }

    @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceWidth = Math.max(width, 1);
        surfaceHeight = Math.max(height, 1);
        if (ready) resizeTarget();
    }

    @Override public void onDrawFrame(GL10 gl) {
        if (!ready) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glClearColor(0.035f, 0.06f, 0.075f, 1f);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            return;
        }
        long now = System.nanoTime();
        float dt = previousNanos == 0 ? 0f : Math.min((now - previousNanos) / 1e9f, .06f);
        previousNanos = now;
        if (!settings.paused) time += dt;
        camera.advance(dt, settings.drift && !settings.paused);
        CameraState.Snapshot state = camera.snapshot();
        int quality = Math.max(0, Math.min(2, settings.quality));
        if (quality != previousQuality) {
            previousQuality = quality;
            adaptiveScale = 1f;
            slowWindows = fastWindows = 0;
            resizeTarget();
        }
        if (!ready) return;
        double yaw = Math.toRadians(state.yaw), pitch = Math.toRadians(state.pitch);
        float sy = (float)Math.sin(yaw), cy = (float)Math.cos(yaw);
        float sp = (float)Math.sin(pitch), cp = (float)Math.cos(pitch);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
        GLES30.glViewport(0, 0, renderWidth, renderHeight);
        GLES30.glUseProgram(program);
        GLES30.glUniform2f(resolutionLocation, renderWidth, renderHeight);
        GLES30.glUniform1f(timeLocation, time);
        GLES30.glUniform3f(cameraLocation, state.x, Math.max(3.6f, state.height), state.z);
        GLES30.glUniform3f(forwardLocation, sy * cp, sp, -cy * cp);
        GLES30.glUniform3f(rightLocation, cy, 0f, sy);
        GLES30.glUniform3f(upLocation, -sy * sp, cp, cy * sp);
        GLES30.glUniform1f(fovLocation, (float)Math.toRadians(state.fov));
        GLES30.glUniform1f(stormLocation, settings.storm);
        GLES30.glUniform1f(rainLocation, settings.rain);
        float phase = time % 13.7f;
        float flash = Math.min(1f, phase / .035f) * (float)Math.exp(-phase * 8f);
        GLES30.glUniform1f(lightningLocation, settings.lightning ? flash : 0f);
        GLES30.glUniform1i(qualityLocation, quality);
        GLES30.glBindVertexArray(vao);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        GLES30.glBindVertexArray(0);
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, framebuffer);
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
        GLES30.glBlitFramebuffer(0, 0, renderWidth, renderHeight,
                0, 0, surfaceWidth, surfaceHeight, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);

        renderedFrames++;
        statsFrames++;
        if (statsNanos == 0) statsNanos = now;
        float interval = (now - statsNanos) / 1e9f;
        if (interval >= 2f) {
            final float fps = statsFrames / interval;
            framesPerSecond = fps;
            final int w = renderWidth, h = renderHeight;
            Listener callback = listener;
            if (callback != null) main.post(() -> callback.onFrameStats(fps, w, h));
            statsFrames = 0;
            statsNanos = now;
            // Keep animation responsive when the GPU cannot sustain 30 frames/s.
            slowWindows = fps < 27f ? slowWindows + 1 : 0;
            fastWindows = fps > 52f ? fastWindows + 1 : 0;
            if (!settings.paused && slowWindows >= 2 && adaptiveScale > .5f) {
                adaptiveScale = Math.max(.5f, adaptiveScale * .85f);
                slowWindows = 0;
                resizeTarget();
            } else if (!settings.paused && fastWindows >= 5 && adaptiveScale < 1f) {
                adaptiveScale = Math.min(1f, adaptiveScale + .05f);
                fastWindows = 0;
                resizeTarget();
            }
        }
    }

    private void resizeTarget() {
        int quality = Math.max(0, Math.min(2, settings.quality));
        int maxEdge = new int[] {800, 1120, 1600}[quality];
        float scale = Math.min(1f, maxEdge * adaptiveScale / Math.max(surfaceWidth, surfaceHeight));
        int width = Math.max(2, Math.round(surfaceWidth * scale));
        int height = Math.max(2, Math.round(surfaceHeight * scale));
        if (width == renderWidth && height == renderHeight && framebuffer != 0) return;
        if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, new int[] {framebuffer}, 0);
        if (texture != 0) GLES30.glDeleteTextures(1, new int[] {texture}, 0);
        int[] names = new int[1];
        GLES30.glGenTextures(1, names, 0); texture = names[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height,
                0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glGenFramebuffers(1, names, 0); framebuffer = names[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0);
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            reportFailure(new IllegalStateException("The GPU cannot create the ocean render target."));
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        renderWidth = width;
        renderHeight = height;
    }

    private int location(String name) { return GLES30.glGetUniformLocation(program, name); }

    private int compile(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] status = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String info = GLES30.glGetShaderInfoLog(shader);
            GLES30.glDeleteShader(shader);
            throw new IllegalStateException("Shader compile: " + info);
        }
        return shader;
    }

    private String readAsset(String path) throws Exception {
        try (InputStream input = context.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096];
            int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            return output.toString("UTF-8");
        }
    }

    private void reportFailure(Exception error) {
        ready = false;
        failure = error.getMessage();
        Log.e("StormSea", "Ocean renderer stopped", error);
        Listener callback = listener;
        if (callback != null) main.post(() -> callback.onError(failure));
    }
}
