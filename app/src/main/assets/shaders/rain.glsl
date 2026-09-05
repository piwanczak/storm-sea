// The rain field moves continuously. A drop keeps its position within its
// material cell. Each column has a different fall speed and each layer has a
// different cell size, so rows do not move together.
// Return the narrow bright core and the wider, weak absorption edge.
vec2 stormRainField(vec2 position, vec2 cellSize, float speed, float phase,
                    float density, float widthPixels, float lengthScale) {
    vec2 p = position / cellSize;
    p.x += phase;
    float column = floor(p.x);
    float columnSpeed = 0.74 + 0.52 * hash12(vec2(column, phase + 19.2));
    p.y -= uTime * speed * columnSpeed / cellSize.y;

    vec2 cell = floor(p);
    vec2 local = fract(p);
    float seed = hash12(cell + vec2(phase, 73.1));
    float dropMask = 1.0 - smoothstep(density - 0.06, density + 0.06, seed);

    float halfLength = lengthScale * mix(0.115, 0.245,
                                        hash12(cell + vec2(41.7, phase)));
    float centerY = mix(halfLength + 0.04, 0.96 - halfLength,
                       hash12(cell + vec2(17.3, 61.2)));
    float centerX = mix(0.15, 0.85, hash12(cell + vec2(91.4, 23.8)));
    vec2 offset = (local - vec2(centerX, centerY)) * cellSize;

    // Keep the line visible when the render scale falls. fwidth also covers
    // camera roll, pitch, and a wide field of view. Core width is 0.9 to 1.5
    // render pixels before the soft edge, depending on the layer.
    vec2 pixel = max(fwidth(position), vec2(0.00001));
    float width = pixel.x * widthPixels * 0.5;
    float edge = pixel.x * 0.55;
    float core = 1.0 - smoothstep(max(0.0, width - edge), width + edge,
                                 abs(offset.x));
    float absorption = 1.0 - smoothstep(width, width + edge * 2.8,
                                       abs(offset.x));
    float along = abs(offset.y) / max(halfLength * cellSize.y, 0.0001);
    float endEdge = max(0.12, pixel.y / (halfLength * cellSize.y));
    float ends = 1.0 - smoothstep(1.0 - endEdge, 1.0 + endEdge, along);
    // A soft tail and a brighter head read as moving water, rather than snow.
    float head = mix(0.48, 1.0, smoothstep(-halfLength * cellSize.y,
                                         halfLength * cellSize.y, offset.y));
    float brightness = mix(0.52, 1.0, hash12(cell + vec2(9.6, 47.5)));
    return vec2(core * head, absorption * 0.7) * ends * dropMask * brightness;
}

vec3 applyRain(vec3 color, vec2 uv, vec3 direction, float distanceToWater) {
    float amount = clamp(uRain, 0.0, 1.0);
    if (amount < 0.001) {
        return color;
    }

    // Project world gravity and wind into the camera. Rain changes direction
    // when the user turns the camera. Translation gives each layer parallax.
    vec3 worldVelocity = vec3(-5.4, -17.0, 3.8);
    vec2 projected = vec2(dot(worldVelocity, uRight), dot(worldVelocity, uUp));
    float projectedSpeed = length(projected);
    vec2 fall = projected / max(projectedSpeed, 0.001);
    // At the wind vanishing point use a small screen component to keep the
    // basis finite. The camera can look both up and down.
    fall = normalize(fall + vec2(0.025, -0.025));
    vec2 across = vec2(-fall.y, fall.x);
    mat2 rainBasis = mat2(across.x, fall.x, across.y, fall.y);
    float lens = tan(uFov * 0.5) / 0.625;
    vec2 cameraOffset = vec2(dot(uCamera, uRight), dot(uCamera, uUp));
    vec2 screenPosition = uv * lens;

    // Integrate the sideways gust as an offset. This bends the moving sheet
    // smoothly without changing the identity of a drop each frame.
    float gust = 0.075 * sin(uTime * 0.73) + 0.027 * sin(uTime * 1.63 + 2.1);
    float density = mix(0.12, 0.83, amount);
    vec2 nearPosition = rainBasis * (screenPosition + cameraOffset / 7.5);
    vec2 midPosition = rainBasis * (screenPosition + cameraOffset / 19.7);
    vec2 farPosition = rainBasis * (screenPosition + cameraOffset / 51.3);
    nearPosition.x -= gust;
    midPosition.x -= gust * 0.61;
    farPosition.x -= gust * 0.32;

    vec2 nearRain = stormRainField(nearPosition, vec2(0.111, 0.297),
                                  2.72, 5.13,
                                  density * 0.73, 1.50, 1.0);
    vec2 midRain = stormRainField(midPosition, vec2(0.063, 0.173),
                                 1.67, 37.91,
                                 density, 1.10, 1.0);
    vec2 farRain = stormRainField(farPosition, vec2(0.039, 0.107),
                                 0.91, 83.47,
                                 density * 0.86, 0.90, 0.92);

    if (distanceToWater > 0.0) {
        // A close water surface hides most of the distant rain volume.
        nearRain *= smoothstep(0.7, 8.0, distanceToWater);
        midRain *= smoothstep(2.0, 22.0, distanceToWater);
        farRain *= smoothstep(5.0, 58.0, distanceToWater);
    }

    vec2 rain = (nearRain * 0.86 + midRain * 0.56 + farRain * 0.30)
              * sqrt(amount);
    rain = min(rain, vec2(0.90));
    // The dark edge remains visible against bright foam and sky. The core
    // scatters sky light, so the same rain remains visible over dark water.
    color *= 1.0 - rain.y * 0.23;
    color += vec3(0.25, 0.32, 0.38) * rain.x * (0.90 + uLightning * 1.8);

    // Broad, moving rain curtains add depth near the horizon. Their opacity
    // stays low enough to keep the crests and cloud detail visible.
    vec2 curtainPosition = direction.xz / (0.30 + abs(direction.y));
    curtainPosition += uCamera.xz * 0.010;
    curtainPosition -= worldVelocity.xz * uTime * 0.008;
    float curtain = valueNoise(curtainPosition * vec2(2.1, 0.79) + 7.3);
    float horizon = exp(-abs(direction.y) * 5.0);
    float depth = distanceToWater > 0.0
                ? 1.0 - exp(-distanceToWater * 0.015) : 1.0;
    float veil = amount * depth * horizon
               * (0.025 + 0.10 * smoothstep(0.30, 0.78, curtain));
    vec3 mist = vec3(0.24, 0.30, 0.34) + vec3(0.15, 0.19, 0.24) * uLightning;
    return mix(color, mist, veil);
}
