// Irregular wave groups. This file is inserted after hash12 and valueNoise.
// There is no texture tile or repeated world region.

// Return noise in [-1, 1] and its two derivatives.
vec3 waveNoise(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    vec2 blend = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    vec2 derivative = 30.0 * f * f * (f - 1.0) * (f - 1.0);
    float a = hash12(cell);
    float b = hash12(cell + vec2(1.0, 0.0));
    float c = hash12(cell + vec2(0.0, 1.0));
    float d = hash12(cell + vec2(1.0, 1.0));
    float crossTerm = a - b - c + d;
    float value = a + (b - a) * blend.x + (c - a) * blend.y
                + crossTerm * blend.x * blend.y;
    vec2 gradient = derivative * vec2(b - a + crossTerm * blend.y,
                                      c - a + crossTerm * blend.x);
    return vec3(value * 2.0 - 1.0, gradient * 2.0);
}

struct WaveDomain {
    vec2 position;
    vec2 dx;
    vec2 dz;
    vec3 packetA;
    vec3 packetB;
};

WaveDomain waveDomain(vec2 p) {
    // Two rotated noise fields bend wave fronts and change their lengths.
    vec2 aPosition = vec2(dot(p, vec2(0.798, 0.602)),
                         dot(p, vec2(-0.602, 0.798))) * 0.037;
    vec2 bPosition = vec2(dot(p, vec2(0.362, -0.932)),
                         dot(p, vec2(0.932, 0.362))) * 0.061;
    vec3 a = waveNoise(aPosition + vec2(-uTime * 0.029, uTime * 0.012)
                      + vec2(17.31, -9.76));
    vec3 b = waveNoise(bPosition + vec2(uTime * 0.016, -uTime * 0.023)
                      + vec2(-31.77, 43.19));
    a.yz = vec2(dot(a.yz, vec2(0.798, -0.602)),
                dot(a.yz, vec2(0.602, 0.798))) * 0.037;
    b.yz = vec2(dot(b.yz, vec2(0.362, 0.932)),
                dot(b.yz, vec2(-0.932, 0.362))) * 0.061;

    const vec2 warpA = vec2(4.8, -3.2);
    const vec2 warpB = vec2(2.4, 3.9);
    WaveDomain domain;
    domain.position = p + warpA * a.x + warpB * b.x;
    domain.dx = vec2(1.0, 0.0) + warpA * a.y + warpB * b.y;
    domain.dz = vec2(0.0, 1.0) + warpA * a.z + warpB * b.z;
    domain.packetA = a;
    domain.packetB = b;
    return domain;
}

// Values are wave vector X, wave vector Z, speed, and phase.
// Energy is spread across several directions and uneven wave lengths.
const vec4 WAVE_PHASE[10] = vec4[10](
    vec4( 0.208, -0.134, 1.49, 0.72),
    vec4( 0.066, -0.329, 1.75, 4.31),
    vec4( 0.408, -0.207, 2.09, 2.19),
    vec4(-0.333, -0.514, 2.40, 5.47),
    vec4( 0.752,  0.121, 2.68, 3.02),
    vec4( 0.690, -0.812, 3.12, 0.11),
    vec4(-0.478, -1.389, 3.65, 4.83),
    vec4( 1.743, -0.604, 4.02, 1.56),
    vec4(-2.078,  0.910, 4.42, 5.93),
    vec4( 0.639, -3.010, 5.11, 2.72)
);

// Values are amplitude, base envelope, packet A weight, and packet B weight.
// Opposing weights move energy between crossing wave groups.
const vec4 WAVE_SIZE[10] = vec4[10](
    vec4(0.600, 0.78,  0.22,  0.00),
    vec4(0.520, 0.78, -0.18,  0.04),
    vec4(0.430, 0.76,  0.05,  0.19),
    vec4(0.360, 0.76,  0.11, -0.13),
    vec4(0.280, 0.82, -0.09, -0.09),
    vec4(0.230, 0.82,  0.05,  0.13),
    vec4(0.185, 0.86,  0.08, -0.06),
    vec4(0.140, 0.86, -0.06,  0.08),
    vec4(0.105, 0.90,  0.06,  0.04),
    vec4(0.075, 0.90, -0.04, -0.06)
);

// Preserve normal waves, then reduce only the largest combined peaks.
// The height and its derivative are continuous. Absolute height is <3.15 m.
vec2 boundWave(float rawHeight) {
    float extra = max(abs(rawHeight) - 1.8, 0.0);
    float divisor = 1.0 + extra / 1.35;
    float height = sign(rawHeight) * (min(abs(rawHeight), 1.8) + extra / divisor);
    return vec2(height, 1.0 / (divisor * divisor));
}

float seaHeight(vec2 p) {
    WaveDomain domain = waveDomain(p);
    float height = 0.0;
    for (int i = 0; i < 10; i++) {
        if (uQuality == 0 && i >= 8) {
            break;
        }
        vec4 phase = WAVE_PHASE[i];
        vec4 size = WAVE_SIZE[i];
        float envelope = size.y + size.z * domain.packetA.x + size.w * domain.packetB.x;
        float angle = dot(domain.position, phase.xy) - uTime * phase.z + phase.w;
        float s = sin(angle);
        float crest = s + 0.28 * (s * s - 0.5);
        height += size.x * envelope * crest;
    }
    return boundWave(height * (0.65 + 1.65 * uStorm)).x;
}

// Return the exact height, its world derivatives, and the foam strength.
vec4 seaField(vec2 p) {
    WaveDomain domain = waveDomain(p);
    vec3 field = vec3(0.0);
    for (int i = 0; i < 10; i++) {
        if (uQuality == 0 && i >= 8) {
            break;
        }
        vec4 phase = WAVE_PHASE[i];
        vec4 size = WAVE_SIZE[i];
        float envelope = size.y + size.z * domain.packetA.x + size.w * domain.packetB.x;
        vec2 envelopeGradient = size.z * domain.packetA.yz + size.w * domain.packetB.yz;
        float angle = dot(domain.position, phase.xy) - uTime * phase.z + phase.w;
        vec2 angleGradient = vec2(dot(domain.dx, phase.xy), dot(domain.dz, phase.xy));
        float s = sin(angle);
        float crest = s + 0.28 * (s * s - 0.5);
        float crestDerivative = cos(angle) * (1.0 + 0.56 * s);
        field.x += size.x * envelope * crest;
        field.yz += size.x * (envelopeGradient * crest
                    + envelope * crestDerivative * angleGradient);
    }
    field *= 0.65 + 1.65 * uStorm;
    vec2 bounded = boundWave(field.x);
    field.x = bounded.x;
    field.yz *= bounded.y;

    // White water forms on combined high peaks and steep shoulders.
    float foam = 0.86 * smoothstep(0.40, 2.20, field.x)
               + 0.18 * smoothstep(0.65, 1.60, length(field.yz));
    return vec4(field, foam);
}

vec2 smallRipples(vec2 p, float distanceToCamera) {
    float footprint = max(distanceToCamera / uResolution.y, 0.0001);
    float mediumFilter = exp(-footprint * 6.0);
    float fineFilter = exp(-footprint * 16.0);
    vec2 q = p + vec2(sin(dot(p, vec2(0.49, -0.31)) - uTime * 0.21),
                      sin(dot(p, vec2(-0.23, 0.63)) + uTime * 0.17)) * 0.32;
    vec3 medium = waveNoise(q * 1.35 + vec2(-uTime * 0.83, uTime * 0.22));
    vec2 slope = medium.yz * (0.090 * 1.35) * mediumFilter;
    float first = dot(q, vec2(4.7, -5.3)) - uTime * 3.4 + medium.x * 1.1;
    slope += vec2(0.064, -0.072) * cos(first) * mediumFilter;
    if (uQuality > 0) {
        vec2 turned = vec2(dot(q, vec2(0.600, -0.800)),
                           dot(q, vec2(0.800, 0.600)));
        vec3 fine = waveNoise(turned * 4.3 + vec2(uTime * 0.61, -uTime * 0.42));
        vec2 fineSlope = vec2(dot(fine.yz, vec2(0.600, 0.800)),
                              dot(fine.yz, vec2(-0.800, 0.600)));
        slope += fineSlope * (0.026 * 4.3) * fineFilter;
        float second = dot(q, vec2(11.3, 3.4)) - uTime * 5.1 + fine.x;
        slope += vec2(0.050, 0.015) * cos(second) * fineFilter;
    }
    return slope * (0.60 + 0.65 * uStorm);
}
