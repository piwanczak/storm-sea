# Verification

Version 1.1 checked on 5 September 2026.

- The Android debug APK and separate test APK build successfully.
- Android Lint reports 0 errors and 6 advisory warnings. These concern SDK versions, landscape orientation, backup policy, layout editor constructors, and translation support.
- 28 device checks passed on Android 16 / API 36 at 1280 × 720, with an AMD hardware GPU through the Android emulator.
- The vertex shader and fragment shader, including the wave and rain modules, compiled and linked on the Android graphics driver.
- Image checks confirmed that the scene changes while running and stays fixed while paused.
- Touch checks covered drag, pinch, travel, adding/removing second and third pointers, and camera limits.
- Controls, camera save/restore, and GL pause/resume checks passed.
- The final test reported 59.6 FPS. This is an emulator result, not a phone performance measurement.
- The APK has not been tested on a physical phone. It requires Android 8.0 or later and OpenGL ES 3.0.

See `device-tests.txt` for the native test results. See `device/sea.png` for the scene captured from the Android emulator. The Android full-screen notice was dismissed during the screenshot test.

Fixed views compare rain on and off with the same camera, time, storm strength, and render detail. The wide views use the maximum 28 metre height and 92 degree field of view, at two camera headings. Visual inspection confirms irregular wave groups and clearly visible rain. Captures are in `revision/`, with the fixed-view test log in `fixed-view-tests-v2.txt`.

The revised wave field stays below 3.15 metres. The camera stays at least 3.6 metres above sea level. Numerical checks covered 4,096 surface slopes and 1,822 rays, including displaced cameras. The selected rendering steps had no missed first surfaces or unresolved roots in these samples. See `wave-checks-v2.md`, its JSON results, and the executable Node.js check for the method and limits.
