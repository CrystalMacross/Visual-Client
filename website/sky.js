/* VisualMod.com: the night sky behind every page.
   Aurora curtains, square stars, slowly tumbling voxel cubes and a blocky horizon with lanterns.
   At most 30 frames per second, nothing while the tab is hidden, one still frame with reduced
   motion. The aurora colours follow the open tab. */
(() => {
  'use strict';
  const canvas = document.getElementById('sky');
  const ctx = canvas && canvas.getContext ? canvas.getContext('2d', { alpha: false }) : null;
  if (!ctx) return;
  const still = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  const START = 15000;
  const TAU = Math.PI * 2;
  const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
  const wrap = (v, lo, span) => ((((v - lo) % span) + span) % span) + lo;
  const rnd = (a, b) => a + Math.random() * (b - a);
  const seeded = (s) => () => {
    s = (s + 0x6d2b79f5) | 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };

  // ---------------------------------------------------------------- colours per tab
  const TEAL = [45, 212, 191], SKY = [56, 189, 248], LIME = [163, 230, 53], MINT = [94, 234, 212],
    INDIGO = [129, 140, 248], VIOLET = [192, 132, 252], ROSE = [251, 113, 133], AMBER = [251, 191, 36];
  // three ribbons, each [curtain colour, lower edge colour], then the brightness
  const MOODS = {
    home: [[TEAL, LIME], [SKY, TEAL], [INDIGO, SKY], 1],
    mod: [[SKY, MINT], [TEAL, LIME], [INDIGO, SKY], 1],
    launcher: [[INDIGO, SKY], [SKY, TEAL], [VIOLET, INDIGO], 1],
    styles: [[ROSE, AMBER], [VIOLET, ROSE], [SKY, VIOLET], 1],
    info: [[TEAL, SKY], [SKY, INDIGO], [INDIGO, SKY], .8],
    legal: [[SKY, TEAL], [INDIGO, SKY], [TEAL, SKY], .5]
  };
  const moodOf = (hash) => {
    const v = String(hash || '').replace('#', '');
    if (MOODS[v] && v !== 'legal') return v;
    return /^(terms-of-service|terms-of-use|privacy)$/.test(v) ? 'legal' : 'home';
  };
  const RIB = [
    { y: .14, amp: .045, len: .30, k: 1, sp: 1, a: .62, ph: 0 },
    { y: .27, amp: .06, len: .20, k: 1.45, sp: -.8, a: .46, ph: 2.1 },
    { y: .06, amp: .035, len: .24, k: .7, sp: .5, a: .34, ph: 4.2 }
  ];
  const cur = new Float32Array(18), goal = new Float32Array(18);
  let bright = 1, goalBright = 1, dirty = true;
  function setMood(name, now) {
    const m = MOODS[name];
    for (let r = 0; r < 3; r++) for (let e = 0; e < 2; e++) for (let c = 0; c < 3; c++) goal[r * 6 + e * 3 + c] = m[r][e][c];
    goalBright = m[3];
    if (now) { cur.set(goal); bright = goalBright; dirty = true; }
  }

  // ---------------------------------------------------------------- canvases and sizes
  const aur = document.createElement('canvas'); // the aurora is drawn small and scaled up: soft for free
  const actx = aur.getContext('2d');
  const land = document.createElement('canvas');
  const lctx = land.getContext('2d');
  let w = 0, h = 0, dpr = 1, bg = null, glow = null, grads = [];
  let landH = 0, landW = 0, stars = [], cubes = [], scale = 1;
  let mx = 0, my = 0, tx = 0, ty = 0;
  let shoot = null, nextShoot = 0;

  const rgba = (i, a) => 'rgba(' + (cur[i] | 0) + ',' + (cur[i + 1] | 0) + ',' + (cur[i + 2] | 0) + ',' + a + ')';
  function buildGrads() {
    grads = RIB.map((r, i) => {
      const g = actx.createLinearGradient(0, 0, 0, 1);
      g.addColorStop(0, rgba(i * 6, 0));
      g.addColorStop(.62, rgba(i * 6, .3));
      g.addColorStop(.9, rgba(i * 6 + 3, .95));
      g.addColorStop(1, rgba(i * 6 + 3, 0));
      return g;
    });
    glow = ctx.createLinearGradient(0, h - landH * 1.3, 0, h);
    glow.addColorStop(0, rgba(3, 0));
    glow.addColorStop(1, rgba(3, (.16 * bright).toFixed(3)));
    dirty = false;
  }

  function resize() {
    dpr = Math.min(1.5, window.devicePixelRatio || 1);
    w = Math.max(1, window.innerWidth);
    h = Math.max(1, window.innerHeight);
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    canvas.style.width = w + 'px';
    canvas.style.height = h + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    bg = ctx.createLinearGradient(0, 0, 0, h);
    bg.addColorStop(0, '#081634');
    bg.addColorStop(.55, '#050b1d');
    bg.addColorStop(1, '#03060f');
    aur.width = Math.ceil(w / 4);
    aur.height = Math.ceil(h / 4);
    makeStars();
    scale = clamp(Math.min(w, h) / 850, .6, 1.2);
    const n = Math.round(clamp(w / 100, 6, 18));
    while (cubes.length < n) cubes.push(newCube());
    cubes.length = n;
    cubes.forEach((c) => { c.s = (14 + 46 * c.d) * scale; });
    cubes.sort((a, b) => a.d - b.d);
    buildLand();
    dirty = true;
  }

  // ---------------------------------------------------------------- stars
  function makeStars() {
    const r = seeded(3);
    const n = Math.round(clamp(w * h / 6500, 70, 280));
    stars = [];
    for (let i = 0; i < n; i++) stars.push({ u: r(), v: r(), s: r() < .9 ? 1 + r() : 2 + r(), a: .3 + .65 * r(), tw: .5 + 2 * r(), ph: r() * TAU });
  }
  function drawStars(t, sc) {
    const lim = h * .82;
    ctx.fillStyle = '#e0f2fe';
    for (const s of stars) {
      const x = wrap(s.u * w - mx * 3, 0, w);
      const y = wrap(s.v * lim - sc * .03, 0, lim);
      const fade = 1 - clamp((y - h * .4) / (h * .42), 0, 1);
      const a = s.a * fade * (still ? .8 : .55 + .45 * Math.sin(t * .001 * s.tw + s.ph));
      if (a < .03) continue;
      ctx.globalAlpha = a;
      ctx.fillRect(x | 0, y | 0, s.s, s.s); // square stars, like in the game
      if (s.s > 2) { ctx.globalAlpha = a * .22; ctx.fillRect((x | 0) - s.s, (y | 0) - s.s, s.s * 3, s.s * 3); }
    }
    ctx.globalAlpha = 1;
  }

  // ---------------------------------------------------------------- aurora
  function drawAurora(t) {
    const aw = aur.width, ah = aur.height, T = t * .001;
    actx.setTransform(1, 0, 0, 1, 0, 0);
    actx.globalAlpha = 1;
    actx.globalCompositeOperation = 'source-over';
    actx.clearRect(0, 0, aw, ah);
    actx.globalCompositeOperation = 'lighter';
    for (let ri = 0; ri < RIB.length; ri++) {
      const r = RIB[ri];
      actx.fillStyle = grads[ri];
      for (let x = 0; x < aw; x++) {
        const u = x / 360; // the same waves on every screen: a phone shows a part of them

        const top = ah * (r.y + r.amp * Math.sin(u * TAU * 1.1 * r.k + T * .21 * r.sp + r.ph) + r.amp * .45 * Math.sin(u * TAU * 2.9 * r.k - T * .33 + r.ph * 1.7));
        const len = ah * r.len * (.72 + .28 * Math.sin(u * 19 * r.k + T * .5 + r.ph));
        const rays = .55 + .25 * Math.sin(x * .31 * r.k + T * 1.3 * r.sp + r.ph) + .2 * Math.sin(x * .097 - T * .6 + r.ph * 2);
        const env = clamp(.3 + .7 * Math.sin(u * TAU * .55 + r.ph + T * .05), 0, 1);
        const a = r.a * rays * env;
        if (a < .01) continue;
        actx.globalAlpha = a;
        actx.setTransform(1, 0, 0, len, x, top);
        actx.fillRect(0, 0, 1.05, 1);
      }
    }
    actx.setTransform(1, 0, 0, 1, 0, 0);
    actx.globalAlpha = 1;
    ctx.globalCompositeOperation = 'lighter';
    ctx.globalAlpha = bright;
    ctx.drawImage(aur, 0, 0, aw, ah, -10 - mx * 6, -8 - my * 4, w + 20, h + 16);
    ctx.globalAlpha = 1;
    ctx.globalCompositeOperation = 'source-over';
  }

  // ---------------------------------------------------------------- shooting stars
  function drawShoot(t, dt) {
    if (still) return;
    if (!nextShoot) nextShoot = t + 2500;
    if (!shoot && t > nextShoot) {
      shoot = { x: rnd(w * .2, w), y: rnd(0, h * .3), vx: -rnd(520, 820), vy: rnd(160, 300), life: 0, max: rnd(.6, 1) };
      nextShoot = t + rnd(6000, 14000);
    }
    if (!shoot) return;
    shoot.life += dt;
    const k = shoot.life / shoot.max;
    if (k >= 1) { shoot = null; return; }
    const x = shoot.x + shoot.vx * shoot.life, y = shoot.y + shoot.vy * shoot.life;
    const x0 = x - shoot.vx * .12, y0 = y - shoot.vy * .12;
    const g = ctx.createLinearGradient(x0, y0, x, y);
    g.addColorStop(0, 'rgba(224,242,254,0)');
    g.addColorStop(1, 'rgba(224,242,254,' + ((1 - k) * .9).toFixed(3) + ')');
    ctx.strokeStyle = g;
    ctx.lineWidth = 1.6;
    ctx.beginPath(); ctx.moveTo(x0, y0); ctx.lineTo(x, y); ctx.stroke();
  }

  // ---------------------------------------------------------------- voxel cubes
  const FACES = [
    [0, 2, 6, 4, -1, 0, 0], [1, 5, 7, 3, 1, 0, 0],
    [0, 4, 5, 1, 0, -1, 0], [2, 3, 7, 6, 0, 1, 0],
    [0, 1, 3, 2, 0, 0, -1], [4, 6, 7, 5, 0, 0, 1]
  ];
  const LIGHT = (() => { const v = [-.45, -.8, -.4], n = Math.hypot(v[0], v[1], v[2]); return [v[0] / n, v[1] / n, v[2] / n]; })();
  const CUBE_COLORS = [TEAL, SKY, LIME, INDIGO, MINT, AMBER, SKY, TEAL];
  const PX = new Float32Array(8), PY = new Float32Array(8);
  function newCube() {
    const d = .2 + .8 * Math.pow(Math.random(), 1.5);
    return {
      d, x: rnd(-120, w + 120), y: rnd(-120, h + 120), ax: rnd(0, TAU), ay: rnd(0, TAU),
      vax: rnd(-.25, .25), vay: rnd(-.32, .32), vx: rnd(-6, 6), vy: -(4 + 15 * d),
      c: CUBE_COLORS[(Math.random() * CUBE_COLORS.length) | 0], s: 0
    };
  }
  function drawCube(c, cx, cy) {
    const s = c.s, ca = Math.cos(c.ax), sa = Math.sin(c.ax), cb = Math.cos(c.ay), sb = Math.sin(c.ay);
    for (let i = 0; i < 8; i++) {
      const x = i & 1 ? .5 : -.5, y = i & 2 ? .5 : -.5, z = i & 4 ? .5 : -.5;
      const x1 = x * cb + z * sb, z1 = z * cb - x * sb;
      PX[i] = cx + x1 * s;
      PY[i] = cy + (y * ca - z1 * sa) * s;
    }
    const alpha = .2 + .7 * c.d, col = c.c;
    if (c.d > .55) { // soft light around the near cubes
      const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, s * 1.5);
      g.addColorStop(0, 'rgba(' + col + ',' + (.16 * c.d).toFixed(3) + ')');
      g.addColorStop(1, 'rgba(' + col + ',0)');
      ctx.fillStyle = g;
      ctx.fillRect(cx - s * 1.5, cy - s * 1.5, s * 3, s * 3);
    }
    ctx.lineWidth = 1;
    for (let f = 0; f < 6; f++) {
      const F = FACES[f];
      const nx = F[4] * cb + F[6] * sb, nz1 = F[6] * cb - F[4] * sb;
      const ny = F[5] * ca - nz1 * sa, nz = F[5] * sa + nz1 * ca;
      if (nz > -.02) continue; // faces away from us
      const lit = clamp(nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2], 0, 1);
      const sh = .38 + .62 * lit;
      ctx.beginPath();
      ctx.moveTo(PX[F[0]], PY[F[0]]); ctx.lineTo(PX[F[1]], PY[F[1]]); ctx.lineTo(PX[F[2]], PY[F[2]]); ctx.lineTo(PX[F[3]], PY[F[3]]);
      ctx.closePath();
      ctx.fillStyle = 'rgba(' + ((col[0] * sh) | 0) + ',' + ((col[1] * sh) | 0) + ',' + ((col[2] * sh) | 0) + ',' + (alpha * (.2 + .45 * lit)).toFixed(3) + ')';
      ctx.fill();
      ctx.strokeStyle = 'rgba(' + col + ',' + (alpha * (.45 + .5 * lit)).toFixed(3) + ')';
      ctx.stroke();
      if (s > 34) { // 4 x 4 block pixels on the bigger cubes
        ctx.beginPath();
        for (let q = 1; q < 4; q++) {
          const u = q / 4;
          ctx.moveTo(PX[F[0]] + (PX[F[1]] - PX[F[0]]) * u, PY[F[0]] + (PY[F[1]] - PY[F[0]]) * u);
          ctx.lineTo(PX[F[3]] + (PX[F[2]] - PX[F[3]]) * u, PY[F[3]] + (PY[F[2]] - PY[F[3]]) * u);
          ctx.moveTo(PX[F[0]] + (PX[F[3]] - PX[F[0]]) * u, PY[F[0]] + (PY[F[3]] - PY[F[0]]) * u);
          ctx.lineTo(PX[F[1]] + (PX[F[2]] - PX[F[1]]) * u, PY[F[1]] + (PY[F[2]] - PY[F[1]]) * u);
        }
        ctx.strokeStyle = 'rgba(' + col + ',' + (alpha * .14).toFixed(3) + ')';
        ctx.stroke();
      }
    }
  }
  function drawCubes(sc, near) {
    for (const c of cubes) {
      if ((c.d >= .55) !== near) continue;
      const x = wrap(c.x + mx * 22 * c.d, -120, w + 240);
      const y = wrap(c.y - sc * .22 * c.d + my * 14 * c.d, -120, h + 240);
      drawCube(c, x, y);
    }
  }

  // ---------------------------------------------------------------- blocky horizon (drawn once per size)
  function buildLand() {
    const B = Math.round(clamp(w / 56, 12, 28));
    landH = B * 8;
    landW = w + 48;
    land.width = Math.ceil(landW * dpr);
    land.height = Math.ceil(landH * dpr);
    lctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    lctx.clearRect(0, 0, landW, landH);
    const r = seeded(7);
    hills(r, B * .6, 4.4, 2.6, 'rgb(13, 27, 58)', 'rgba(56, 189, 248, .28)', false);
    hills(r, B, 2.2, 1.3, '#040a17', 'rgba(94, 234, 212, .5)', true);
  }
  function hills(r, B, base, amp, fill, rim, deco) {
    const cols = Math.ceil(landW / B) + 1;
    const p0 = r() * TAU, p1 = r() * TAU, p2 = r() * TAU;
    const hs = [];
    for (let i = 0; i < cols; i++) hs.push(Math.max(1, Math.round(base + amp * (Math.sin(i * .19 + p0) + .55 * Math.sin(i * .47 + p1) + .3 * Math.sin(i * 1.21 + p2)))));
    lctx.fillStyle = fill;
    lctx.beginPath();
    lctx.moveTo(0, landH);
    for (let i = 0; i < cols; i++) { const y = landH - hs[i] * B; lctx.lineTo(i * B, y); lctx.lineTo((i + 1) * B, y); }
    lctx.lineTo(cols * B, landH);
    lctx.closePath();
    lctx.fill();
    const tops = [];
    for (let i = 0; i < cols; i++) tops.push([i * B, landH - hs[i] * B, B]);
    const lamps = [];
    if (deco) {
      for (let i = 2; i < cols - 2; i++) { // a few trees
        if (hs[i] > 3 || r() > .1) continue;
        const x = i * B, y = landH - hs[i] * B;
        lctx.fillRect(x + B * .36, y - B * 2.2, B * .28, B * 2.2);
        lctx.fillRect(x - B, y - B * 3.3, B * 3, B * 1.4);
        lctx.fillRect(x, y - B * 4, B, B * .8);
        tops.push([x - B, y - B * 3.3, B], [x + B, y - B * 3.3, B], [x, y - B * 4, B]);
        i += 3;
      }
      for (let i = 1; i < cols - 1; i++) if (r() < .07) lamps.push([i * B + B / 2, landH - hs[i] * B]);
    }
    lctx.strokeStyle = rim;
    lctx.lineWidth = 1.5;
    lctx.beginPath();
    for (const [x, y, len] of tops) { lctx.moveTo(x, y + .75); lctx.lineTo(x + len, y + .75); }
    lctx.stroke();
    for (const [x, y] of lamps) { // lanterns on posts
      const ly = y - B * .55;
      lctx.fillStyle = fill;
      lctx.fillRect(x - 1, ly, 2, B * .55);
      const g = lctx.createRadialGradient(x, ly, 0, x, ly, B * 1.3);
      g.addColorStop(0, 'rgba(251, 191, 36, .55)');
      g.addColorStop(.25, 'rgba(251, 146, 60, .18)');
      g.addColorStop(1, 'rgba(251, 146, 60, 0)');
      lctx.fillStyle = g;
      lctx.fillRect(x - B * 1.3, ly - B * 1.3, B * 2.6, B * 2.6);
      lctx.fillStyle = '#fde68a';
      lctx.fillRect(x - 2, ly - 3, 4, 4);
    }
  }

  // ---------------------------------------------------------------- frame
  function step(dt) {
    const k = Math.min(1, dt * 2.2);
    mx += (tx - mx) * Math.min(1, dt * 3);
    my += (ty - my) * Math.min(1, dt * 3);
    let moved = false;
    for (let i = 0; i < 18; i++) {
      const d = goal[i] - cur[i];
      if (d > .5 || d < -.5) { cur[i] += d * k; moved = true; } else cur[i] = goal[i];
    }
    if (Math.abs(goalBright - bright) > .005) { bright += (goalBright - bright) * k; moved = true; } else bright = goalBright;
    if (moved) dirty = true;
    for (const c of cubes) { c.x += c.vx * dt; c.y += c.vy * dt; c.ax += c.vax * dt; c.ay += c.vay * dt; }
  }
  function render(t, dt) {
    if (dirty) buildGrads();
    const sc = still ? 0 : (window.scrollY || 0);
    ctx.globalCompositeOperation = 'source-over';
    ctx.globalAlpha = 1;
    ctx.fillStyle = bg;
    ctx.fillRect(0, 0, w, h);
    drawStars(t, sc);
    drawAurora(t);
    drawShoot(t, dt || 0);
    drawCubes(sc, false);
    ctx.fillStyle = glow;
    ctx.fillRect(0, h - landH * 1.3, w, landH * 1.3);
    ctx.drawImage(land, 0, 0, land.width, land.height, -24 - mx * 8, h - landH, landW, landH);
    drawCubes(sc, true);
  }

  let raf = 0, last = 0;
  function frame(now) {
    raf = 0;
    if (document.hidden) return;
    raf = requestAnimationFrame(frame);
    const gap = now - last;
    if (gap < 31) return;
    last = now;
    const dt = Math.min(.1, gap / 1000);
    step(dt);
    render(now, dt);
  }
  const kick = () => {
    if (raf || still || document.hidden) return;
    last = performance.now() - 40;
    raf = requestAnimationFrame(frame);
  };

  setMood(moodOf(location.hash), true);
  resize();
  render(still ? START : performance.now(), 0);
  kick();

  let rz = 0;
  window.addEventListener('resize', () => {
    if (rz) return;
    rz = requestAnimationFrame(() => { rz = 0; resize(); render(still ? START : performance.now(), 0); });
  });
  window.addEventListener('hashchange', () => {
    setMood(moodOf(location.hash), still);
    if (still) render(START, 0);
  });
  window.addEventListener('pointermove', (e) => {
    if (e.pointerType !== 'mouse') return;
    tx = clamp(e.clientX / w * 2 - 1, -1, 1);
    ty = clamp(e.clientY / h * 2 - 1, -1, 1);
  }, { passive: true });
  document.addEventListener('visibilitychange', kick);
})();
