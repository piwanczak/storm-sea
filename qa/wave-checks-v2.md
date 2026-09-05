# Wave field V2 checks

The new field uses ten wave components and two moving noise fields. The noise changes wave direction, wave length, and local amplitude. Opposing amplitude weights move energy between crossing wave groups. The shader does not wrap world positions or use a repeated texture.

`seaHeight` and `seaField` use the same geometry. The normal includes the derivatives of the noise warp, local amplitude, crest shape, and final height limit. Foam uses the combined wave height and slope.

The height limit is continuous and stays below 3.15 m. The camera can retain its 3.6 m minimum height.

Run `node qa/wave-checks-v2.cjs` from the project directory. The script reads the wave tables from `waves.glsl` and writes `wave-checks-v2.json`, with the shader SHA-256 hash.

Results:

- 4,096 derivative samples at different positions, times, storm levels, and quality settings passed.
- The maximum derivative error was 0.000000064.
- Sampled heights were -2.674 m to +2.631 m. The maximum sampled slope was 2.721.
- 1,822 camera rays included the 4.2 m / 64 degree and 28 m / 92 degree views, plus 1,024 independent positions and times.
- The reference surface used 0.08 m steps and fourteen bisection steps at each first crossing.
- The recommended intersection selected no surface more than 1 m behind the first surface. No root was unresolved. No ray used the distant fallback. The maximum positive distance error was 0.045 m.
- Average cost was 20.62 height queries per ray, excluding the two initial bracket queries.

Use `height / (-direction.y + 0.85)` with a step range of 0.005 m to 0.90 m and a limit of 160 steps. Keep the existing eight refinement steps and `0.002 + distance * 0.000025` height tolerance. The old intersection selected a later wave on 85 of the same 1,822 rays.

These are double precision CPU checks. They do not prove that every possible camera ray is correct or replace native Android visual and performance checks. The wide Android screenshot at `revision/v2-wide-dry.png` shows irregular crests without the straight rows in the previous field.
