#version 300 es
precision highp float;
precision highp int;

uniform vec2 uResolution;
uniform float uTime;
uniform vec3 uCamera;
uniform vec3 uForward;
uniform vec3 uRight;
uniform vec3 uUp;
uniform float uFov;
uniform float uStorm;
uniform float uRain;
uniform float uLightning;
uniform int uQuality;

out vec4 fragColor;

const vec3 LIGHT_DIR = vec3(-0.3906, 0.3400, -0.8555);
const float FAR_DISTANCE = 1250.0;

// All geometry stays below 3.2 m. Keep the camera above 3.6 m.
// The output includes tone mapping and gamma conversion.

float hash12(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

float valueNoise(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    vec2 s = f * f * (3.0 - 2.0 * f);
    float a = hash12(cell);
    float b = hash12(cell + vec2(1.0, 0.0));
    float c = hash12(cell + vec2(0.0, 1.0));
    float d = hash12(cell + vec2(1.0, 1.0));
    return mix(mix(a, b, s.x), mix(c, d, s.x), s.y);
}

float cloudNoise(vec2 p) {
    mat2 turn = mat2(0.80, -0.60, 0.60, 0.80);
    float value = 0.52 * valueNoise(p);
    p = turn * p * 2.06 + 7.3;
    value += 0.27 * valueNoise(p);
    p = turn * p * 2.03 + 5.7;
    value += 0.14 * valueNoise(p);
    if (uQuality > 0) {
        p = turn * p * 2.01 + 9.1;
        value += 0.07 * valueNoise(p);
    }
    return value;
}

#include "waves.glsl"

float lightningBolt(vec3 direction) {
    if (uLightning < 0.003 || direction.y < 0.015 || direction.z > -0.15) {
        return 0.0;
    }
    vec2 p = direction.xy / max(-direction.z, 0.15);
    float path = -0.59 + 0.025 * sin(p.y * 87.0) + 0.013 * sin(p.y * 213.0);
    path += 0.030 * sin(p.y * 29.0);
    float distanceToBolt = abs(p.x - path);
    float endMask = smoothstep(0.025, 0.045, p.y)
                  * (1.0 - smoothstep(0.44, 0.48, p.y));
    float bolt = (exp(-distanceToBolt * 1500.0) * 4.0
                + exp(-distanceToBolt * 110.0) * 0.22) * endMask;
    float branchPath = -0.56 + (p.y - 0.26) * 0.84
                     + 0.012 * sin(p.y * 170.0);
    float branchMask = smoothstep(0.15, 0.17, p.y)
                     * (1.0 - smoothstep(0.25, 0.27, p.y));
    bolt += exp(-abs(p.x - branchPath) * 1300.0) * 1.7 * branchMask;
    return bolt * uLightning;
}

vec3 skyColor(vec3 direction, bool reflection) {
    float elevation = max(direction.y, 0.0);
    float horizon = pow(1.0 - elevation, 5.0);
    vec3 top = mix(vec3(0.100, 0.153, 0.192), vec3(0.048, 0.071, 0.090), uStorm);
    vec3 edge = mix(vec3(0.40, 0.49, 0.54), vec3(0.29, 0.36, 0.39), uStorm);
    vec3 color = mix(top, edge, horizon);

    vec2 plane = direction.xz / (elevation + 0.26);
    plane = plane * 2.15 + vec2(uTime * 0.021, -uTime * 0.010);
    float largeCloud = cloudNoise(plane * 0.73 + vec2(2.0, 7.0));
    float smallCloud = valueNoise(plane * 3.4 + largeCloud * 1.8);
    float density = smoothstep(0.24, 0.73, largeCloud * 0.84 + smallCloud * 0.16);
    float cover = smoothstep(0.15, 0.65, elevation) * 0.48 + 0.52;
    vec3 cloudDark = vec3(0.025, 0.039, 0.050);
    vec3 cloudLight = vec3(0.25, 0.30, 0.32);
    float silver = pow(max(dot(direction, LIGHT_DIR), 0.0), 9.0);
    vec3 cloudColor = mix(cloudLight, cloudDark, density);
    cloudColor += silver * (1.0 - density) * vec3(0.24, 0.25, 0.23);
    color = mix(color, cloudColor, cover * (0.64 + uStorm * 0.29));

    // The horizon has a thin band of sea mist.
    float mist = exp(-elevation * 21.0);
    color = mix(color, vec3(0.30, 0.37, 0.39), mist * 0.57);
    color += vec3(0.56, 0.66, 0.79) * uLightning
           * (0.20 + 0.40 * pow(max(dot(direction, normalize(vec3(-0.5, 0.3, -1.0))), 0.0), 6.0));
    color += vec3(0.66, 0.79, 1.0) * lightningBolt(direction);
    if (reflection) {
        color = mix(color, vec3(0.24, 0.30, 0.32), 0.07);
    }
    return color;
}

// Search from the camera so that a near crest hides the waves behind it.
float intersectSea(vec3 origin, vec3 direction) {
    if (direction.y >= -0.0015) {
        return -1.0;
    }
    float nearT = max(0.0, (origin.y - 3.2) / -direction.y);
    float farT = min(FAR_DISTANCE, (origin.y + 3.2) / -direction.y);
    if (nearT >= farT) {
        return -1.0;
    }
    vec3 farPoint = origin + direction * farT;
    float farHeight = farPoint.y - seaHeight(farPoint.xz);
    if (farHeight > 0.0) {
        return -1.0;
    }
    vec3 nearPoint = origin + direction * nearT;
    float nearHeight = nearPoint.y - seaHeight(nearPoint.xz);
    float resultT = nearT;
    float marchT = nearT;
    float marchHeight = nearHeight;
    // Small steps prevent a steep nearby crest from being skipped.
    for (int i = 0; i < 160; i++) {
        float stepSize = clamp(marchHeight / (-direction.y + 0.85), 0.005, 0.90);
        float nextT = min(farT, marchT + stepSize);
        vec3 nextPoint = origin + direction * nextT;
        float nextHeight = nextPoint.y - seaHeight(nextPoint.xz);
        if (abs(nextHeight) < 0.002 + nextT * 0.000025) {
            return nextT;
        }
        if (nextHeight < 0.0) {
            nearT = marchT;
            nearHeight = marchHeight;
            farT = nextT;
            farHeight = nextHeight;
            break;
        }
        marchT = nextT;
        marchHeight = nextHeight;
        nearT = marchT;
        nearHeight = marchHeight;
    }

    // Refine the crossing. The distant fallback is hidden by sea mist.
    for (int i = 0; i < 8; i++) {
        float ratio = clamp(nearHeight / (nearHeight - farHeight), 0.05, 0.95);
        resultT = mix(nearT, farT, ratio);
        vec3 point = origin + direction * resultT;
        float height = point.y - seaHeight(point.xz);
        if (abs(height) < 0.002 + resultT * 0.000025) {
            break;
        }
        if (height > 0.0) {
            nearT = resultT;
            nearHeight = height;
        } else {
            farT = resultT;
            farHeight = height;
        }
    }
    return resultT;
}

vec3 waterColor(vec3 origin, vec3 direction, float distanceToWater) {
    vec3 point = origin + direction * distanceToWater;
    vec4 field = seaField(point.xz);
    vec2 slope = field.yz + smallRipples(point.xz, distanceToWater);
    vec3 normal = normalize(vec3(-slope.x, 1.0, -slope.y));
    vec3 view = -direction;
    float facing = clamp(dot(normal, view), 0.0, 1.0);
    vec3 reflected = reflect(direction, normal);
    reflected.y = max(reflected.y, 0.008);
    reflected = normalize(reflected);

    float fresnel = 0.025 + 0.975 * pow(1.0 - facing, 5.0);
    vec3 reflectedSky = skyColor(reflected, true);
    vec3 deepWater = vec3(0.003, 0.028, 0.036);
    float waveLight = smoothstep(-1.2, 1.9, field.x);
    float throughCrest = pow(max(dot(view, -LIGHT_DIR), 0.0), 2.0);
    vec3 scatter = vec3(0.009, 0.100, 0.103) * waveLight
                 * (0.35 + throughCrest * 0.70) * (0.35 + facing * 0.65);
    vec3 water = deepWater + scatter;
    vec3 color = mix(water, reflectedSky * vec3(0.87, 0.96, 0.98), fresnel);
    color += reflectedSky * 0.055;

    vec3 halfVector = normalize(LIGHT_DIR + view);
    float specular = pow(max(dot(normal, halfVector), 0.0), mix(125.0, 76.0, uStorm));
    color += vec3(0.45, 0.51, 0.51) * specular * (0.23 + 0.45 * fresnel);
    color += vec3(0.10, 0.15, 0.19) * uLightning * (0.3 + fresnel);

    // Crest foam breaks into small moving patches.
    float crest = smoothstep(0.48, 0.88, field.w);
    if (crest > 0.005 && distanceToWater < 500.0) {
        vec2 foamPosition = point.xz * 1.7 + vec2(-uTime * 0.22, uTime * 0.13);
        float foamNoise = valueNoise(foamPosition) * 0.62
                        + valueNoise(foamPosition * 3.8 + 2.7) * 0.38;
        float lace = smoothstep(0.33, 0.67, foamNoise);
        float foam = crest * lace * (0.30 + 0.70 * uStorm);
        foam *= 1.0 - smoothstep(220.0, 500.0, distanceToWater);

        // Small gaps and thin foam lines keep a crest from looking flat.
        // Fade each scale before it becomes smaller than a screen pixel.
        float detailFilter = exp(-distanceToWater / uResolution.y * 15.0);
        float foamDetail = valueNoise(foamPosition * 14.0 + foamNoise * 3.0);
        float foamGrain = valueNoise(foamPosition * 35.0 + vec2(3.1, 8.7));
        foamGrain = mix(0.5, foamGrain, detailFilter * detailFilter);
        float smallGaps = smoothstep(0.24, 0.69, foamDetail * 0.75 + foamGrain * 0.25);
        float fineLines = 1.0 - smoothstep(0.035, 0.135, abs(foamDetail - 0.52));
        float foamTexture = clamp(smallGaps * 0.72 + fineLines * 0.50, 0.0, 1.0);
        foam *= mix(0.60, foamTexture, detailFilter);
        vec3 foamColor = vec3(0.40, 0.49, 0.50) * (0.75 + normal.y * 0.25);
        foamColor *= mix(0.92, 0.70 + fineLines * 0.48 + foamGrain * 0.20, detailFilter);
        foamColor += vec3(0.22, 0.27, 0.33) * uLightning;
        color = mix(color, foamColor, foam * 0.92);
    }

    float fog = 1.0 - exp(-distanceToWater * (0.0020 + uStorm * 0.0020));
    vec3 fogColor = vec3(0.27, 0.34, 0.37) + vec3(0.15, 0.19, 0.24) * uLightning;
    color = mix(color, fogColor, fog);
    return color;
}

#include "rain.glsl"

vec3 toneMap(vec3 color) {
    color = max(color, vec3(0.0));
    color = (color * (2.51 * color + 0.03))
          / (color * (2.43 * color + 0.59) + 0.14);
    return pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2));
}

void main() {
    vec2 uv = (gl_FragCoord.xy * 2.0 - uResolution.xy) / uResolution.y;
    float lensScale = tan(uFov * 0.5);
    vec3 direction = normalize(uForward + (uv.x * uRight + uv.y * uUp) * lensScale);
    float distanceToWater = intersectSea(uCamera, direction);
    vec3 color;
    if (distanceToWater < 0.0) {
        color = skyColor(direction, false);
    } else {
        color = waterColor(uCamera, direction, distanceToWater);
    }

    color = applyRain(color, uv, direction, distanceToWater);

    // A weak vignette holds detail at the center of the view.
    vec2 screen = gl_FragCoord.xy / uResolution;
    float vignette = 1.0 - 0.14 * dot(screen - 0.5, screen - 0.5);
    color = toneMap(color * vignette * 1.05);
    float dither = hash12(gl_FragCoord.xy + fract(uTime) * 117.0) - 0.5;
    color += dither / 255.0;
    fragColor = vec4(color, 1.0);
}
