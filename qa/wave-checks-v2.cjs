// Run with Node.js. This checks wave math, not GPU driver behavior.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const shaderPath = path.resolve(__dirname, '../app/src/main/assets/shaders/waves.glsl');
const source = fs.readFileSync(shaderPath, 'utf8');
function readTable(name) {
  const block = source.match(new RegExp('const vec4 ' + name + '\\[10\\][\\s\\S]*?\\);'))[0];
  return [...block.matchAll(/vec4\(([^)]+)\)/g)].map(m => m[1].split(',').map(Number));
}
const phases = readTable('WAVE_PHASE');
const sizes = readTable('WAVE_SIZE');
const fract = n => n - Math.floor(n);
const clamp = (n, a, b) => Math.max(a, Math.min(b, n));
function hash(x, y) {
  let a = fract(x * .1031), b = fract(y * .1030), c = fract(x * .0973);
  const d = a * (b + 33.33) + b * (c + 33.33) + c * (a + 33.33);
  a += d; b += d; c += d;
  return fract((a + b) * c);
}
function noise(x, y) {
  const cx = Math.floor(x), cy = Math.floor(y), fx = fract(x), fy = fract(y);
  const sx = fx ** 3 * (fx * (fx * 6 - 15) + 10);
  const sy = fy ** 3 * (fy * (fy * 6 - 15) + 10);
  const dx = 30 * fx * fx * (fx - 1) ** 2;
  const dy = 30 * fy * fy * (fy - 1) ** 2;
  const a = hash(cx, cy), b = hash(cx + 1, cy), c = hash(cx, cy + 1), d = hash(cx + 1, cy + 1);
  const cross = a - b - c + d;
  return [(a + (b-a)*sx + (c-a)*sy + cross*sx*sy)*2-1,
          dx * (b-a+cross*sy)*2, dy * (c-a+cross*sx)*2];
}
function sample(x, z, time, storm = 1, quality = 1, gradients = false) {
  const a = noise((.798*x+.602*z)*.037-time*.029+17.31,
                  (-.602*x+.798*z)*.037+time*.012-9.76);
  const b = noise((.362*x-.932*z)*.061+time*.016-31.77,
                  (.932*x+.362*z)*.061-time*.023+43.19);
  const ax = (.798*a[1]-.602*a[2])*.037, az = (.602*a[1]+.798*a[2])*.037;
  const bx = (.362*b[1]+.932*b[2])*.061, bz = (-.932*b[1]+.362*b[2])*.061;
  const qx = x+4.8*a[0]+2.4*b[0], qz = z-3.2*a[0]+3.9*b[0];
  let h=0, gx=0, gz=0;
  for (let i=0; i<(quality===0?8:10); i++) {
    const p=phases[i], size=sizes[i];
    const envelope=size[1]+size[2]*a[0]+size[3]*b[0];
    const angle=qx*p[0]+qz*p[1]-time*p[2]+p[3];
    const s=Math.sin(angle), crest=s+.28*(s*s-.5);
    h+=size[0]*envelope*crest;
    if (gradients) {
      const cd=Math.cos(angle)*(1+.56*s);
      const angleX=(1+4.8*ax+2.4*bx)*p[0]+(-3.2*ax+3.9*bx)*p[1];
      const angleZ=(4.8*az+2.4*bz)*p[0]+(1-3.2*az+3.9*bz)*p[1];
      gx+=size[0]*((size[2]*ax+size[3]*bx)*crest+envelope*cd*angleX);
      gz+=size[0]*((size[2]*az+size[3]*bz)*crest+envelope*cd*angleZ);
    }
  }
  const scale=.65+1.65*storm;
  h*=scale; gx*=scale; gz*=scale;
  const extra=Math.max(Math.abs(h)-1.8,0), divisor=1+extra/1.35;
  return [Math.sign(h)*(Math.min(Math.abs(h),1.8)+extra/divisor),gx/(divisor*divisor),gz/(divisor*divisor)];
}

let minHeight=Infinity,maxHeight=-Infinity,maxGradientError=0,maxSlope=0;
const points=4096, epsilon=.0001;
for(let i=0;i<points;i++) {
  const x=Math.sin(i*13.731)*700,z=Math.sin(i*11.399)*700,time=i*.017;
  const quality=i%3,storm=(i%5)/4,s=sample(x,z,time,storm,quality,true);
  const dx=(sample(x+epsilon,z,time,storm,quality)[0]-sample(x-epsilon,z,time,storm,quality)[0])/(2*epsilon);
  const dz=(sample(x,z+epsilon,time,storm,quality)[0]-sample(x,z-epsilon,time,storm,quality)[0])/(2*epsilon);
  minHeight=Math.min(minHeight,s[0]);maxHeight=Math.max(maxHeight,s[0]);
  maxSlope=Math.max(maxSlope,Math.hypot(s[1],s[2]));
  maxGradientError=Math.max(maxGradientError,Math.abs(dx-s[1]),Math.abs(dz-s[2]));
}

const rays=[];
for(const camera of [{height:4.2,fov:64},{height:28,fov:92}]) {
  for(const time of [0,4,13]) for(let col=0;col<19;col++) for(const uy of [-.8,-.6,-.4,-.25,-.15,-.05,.05]) {
    const ux=-1.55+col*3.1/18,pitch=-8*Math.PI/180,lens=Math.tan(camera.fov*Math.PI/360);
    const v=[ux*lens,Math.sin(pitch)+uy*lens*Math.cos(pitch),-Math.cos(pitch)+uy*lens*Math.sin(pitch)];
    const length=Math.hypot(...v),r=v.map(c=>c/length);
    if(r[1]>=-.0015)continue;
    const near=(camera.height-3.2)/-r[1],far=Math.min(1250,(camera.height+3.2)/-r[1]);
    if(near>=far)continue;
    const f=t=>camera.height+r[1]*t-sample(r[0]*t,r[2]*t,time)[0];
    let actual=-1,last=near;
    for(let d=near+.08;d<=far+.08;d+=.08) {
      const next=Math.min(d,far);
      if(f(next)<=0) {
        let a=last,b=next;
        for(let i=0;i<14;i++){const m=(a+b)/2;if(f(m)>0)a=m;else b=m;}
        actual=(a+b)/2;break;
      }
      last=next;
    }
    if(actual>=0)rays.push({camera:camera.height,time,r,near,far,f,actual});
  }
}
function testMarch(denominator,cap,budget,refine=8,minStep=.04,toleranceBase=.002,toleranceSlope=.000025) {
  let errors=0,nearErrors=0,wideErrors=0,unresolved=0,worst=0,samples=0,fallbacks=0;
  const examples=[];
  for(const ray of rays) {
    let a=ray.near,b=ray.far,ha=ray.f(a),hb=ray.f(b),t=a,th=ha,result=a,found=false,usedFallback=false;
    if(hb>0) {unresolved++;continue;}
    for(let i=0;i<budget;i++) {
      samples++;
      const next=Math.min(b,t+clamp(th/(-ray.r[1]+denominator),minStep,cap));
      const nh=ray.f(next);
      if(Math.abs(nh)<toleranceBase+next*toleranceSlope) {result=next;found=true;break;}
      if(nh<0){a=t;ha=th;b=next;hb=nh;break;}
      t=next;th=nh;a=t;ha=th;
      if(i===budget-1){fallbacks++;usedFallback=true;}
    }
    if(!found)for(let i=0;i<refine;i++) {
      samples++;
      result=a+(b-a)*clamp(ha/(ha-hb),.05,.95);
      const h=ray.f(result);
      if(Math.abs(h)<toleranceBase+result*toleranceSlope)break;
      if(h>0){a=result;ha=h;}else{b=result;hb=h;}
    }
    const error=result-ray.actual;
    if(error>1) {
      errors++;if(ray.camera<5)nearErrors++;else wideErrors++;
      if(examples.length<6)examples.push({camera:ray.camera,time:ray.time,firstSurface:ray.actual,selectedSurface:result,usedFallback});
    }
    if(Math.abs(ray.f(result))>.08)unresolved++;
    worst=Math.max(worst,error);
  }
  return {denominator,cap,budget,refine,minStep,toleranceBase,toleranceSlope,rays:rays.length,laterSurfaceErrorsOver1m:errors,nearCameraErrors:nearErrors,wideCameraErrors:wideErrors,
    unresolved,worstErrorMeters:worst,averageHeightQueries:samples/rays.length,fallbacks,examples};
}
// Check independent camera positions and times after the fixed view grid.
for(let i=0;i<1024;i++) {
  const camera=i%4===0?28:4.2,fov=camera>5?92:64,time=i*.163+19;
  const originX=Math.sin(i*5.73)*500,originZ=Math.cos(i*8.19)*500;
  const ux=Math.sin(i*4.79)*1.7,uy=-.40+Math.sin(i*7.33)*.44;
  const pitch=-8*Math.PI/180,lens=Math.tan(fov*Math.PI/360);
  const v=[ux*lens,Math.sin(pitch)+uy*lens*Math.cos(pitch),-Math.cos(pitch)+uy*lens*Math.sin(pitch)];
  const length=Math.hypot(...v),r=v.map(c=>c/length);
  if(r[1]>=-.0015)continue;
  const near=(camera-3.2)/-r[1],far=Math.min(1250,(camera+3.2)/-r[1]);
  if(near>=far)continue;
  const f=t=>camera+r[1]*t-sample(originX+r[0]*t,originZ+r[2]*t,time)[0];
  let actual=-1,last=near;
  for(let d=near+.08;d<=far+.08;d+=.08) {
    const next=Math.min(d,far);
    if(f(next)<=0) {
      let a=last,b=next;
      for(let j=0;j<14;j++){const m=(a+b)/2;if(f(m)>0)a=m;else b=m;}
      actual=(a+b)/2;break;
    }
    last=next;
  }
  if(actual>=0)rays.push({camera,time,r,near,far,f,actual});
}
const marches=[testMarch(.30,3.5,32),testMarch(.75,1.25,56),
  testMarch(.85,.9,160,8,.005),testMarch(1.0,.75,192,8,.005),testMarch(1.2,.75,224,8,.005),
  testMarch(1.2,1.25,256,8,.005)];
const shifts=[];
for(const shift of [[27,0],[0,27],[54,0],[0,54],[112,73],[400,400]]) {
  let delta=0,energy=0;
  for(let i=0;i<1024;i++) {
    const x=Math.sin(i*7.27)*250,z=Math.cos(i*3.14)*250;
    const a=sample(x,z,7)[0],b=sample(x+shift[0],z+shift[1],7)[0];
    delta+=(a-b)**2;energy+=a*a;
  }
  shifts.push({shiftMeters:shift,rmsDifference:Math.sqrt(delta/1024),rmsHeight:Math.sqrt(energy/1024)});
}
const result={shaderSha256:crypto.createHash('sha256').update(source).digest('hex'),method:'Double precision CPU model; tables read from shader. GLSL GPU output is checked separately.',
  heightBoundMeters:3.15,derivativeChecks:{points,epsilon,minHeight,maxHeight,maxGradientError,maxSlope},
  firstHitReferenceStepMeters:.08,rayMarchChecks:marches,recommendedMarcher:marches[2],spatialShiftChecks:shifts,
  conclusions:['The smooth limit guarantees absolute height below3.15 m for every finite input.',
    'Noise fields vary across world space. There is no tiled texture or world-coordinate wrapping.',
    'The sampled shift checks reject repeats at the listed distances. They are not a proof that a rendered pattern cannot look regular.',
    'The scalar CPU model checks height and its analytic derivatives. It does not replace Android screenshot and performance checks.']};
fs.writeFileSync(path.join(__dirname,'wave-checks-v2.json'),JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify({derivativeChecks:result.derivativeChecks,rayMarchChecks:marches},null,2));
if(process.argv.includes('--trace')) {
  const ray=rays.find(r=>Math.abs(r.actual-32.09266808166343)<.001);
  const track=[];let t=ray.near,h=ray.f(t);
  for(let i=0;i<128;i++){const step=clamp(h/(-ray.r[1]+1.2),.04,1.25);t+=step;h=ray.f(t);if(i%8===0||h<.01)track.push({i,t,h});if(h<0)break;}
  console.log(JSON.stringify({ray:{near:ray.near,far:ray.far,actual:ray.actual,direction:ray.r},track},null,2));
}
if(maxGradientError>2e-4 || maxHeight>=3.15 || minHeight<=-3.15
   || result.recommendedMarcher.laterSurfaceErrorsOver1m>0
   || result.recommendedMarcher.unresolved>0)process.exitCode=1;
