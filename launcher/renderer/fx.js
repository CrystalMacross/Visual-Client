/* Visual Launcher - effects for the new UI (particles, cursor light, glow borders, tilt,
 * ripples, magnetic play button, sliding navigation indicator). Purely visual: everything here can be
 * switched off in the settings ("Effekte") and pauses while Minecraft runs or the window is hidden. */
'use strict';

(() => {
  const body = document.body;
  const root = document.documentElement;
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  let enabled = !reduced && !body.classList.contains('no-fx');

  const active = () => enabled && !document.hidden && !body.classList.contains('game-running');

  // ---------------------------------------------------------------- particles
  const canvas = document.getElementById('fx-particles');
  const ctx = canvas.getContext('2d');
  let W = 0, H = 0, raf = 0, sprite = null, accent = '';
  const parts = [];
  const COUNT = 48;

  function readAccent() {
    const v = getComputedStyle(root).getPropertyValue('--accent-rgb').trim() || '139, 92, 246';
    if (v === accent && sprite) return;
    accent = v;
    const s = document.createElement('canvas');
    s.width = s.height = 64;
    const g = s.getContext('2d');
    const grd = g.createRadialGradient(32, 32, 0, 32, 32, 32);
    grd.addColorStop(0, 'rgba(255, 255, 255, 1)');
    grd.addColorStop(0.18, `rgba(${accent}, 0.95)`);
    grd.addColorStop(0.45, `rgba(${accent}, 0.28)`);
    grd.addColorStop(1, `rgba(${accent}, 0)`);
    g.fillStyle = grd;
    g.fillRect(0, 0, 64, 64);
    sprite = s;
  }

  function resize() {
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    W = window.innerWidth;
    H = window.innerHeight;
    canvas.width = Math.round(W * dpr);
    canvas.height = Math.round(H * dpr);
    canvas.style.width = W + 'px';
    canvas.style.height = H + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  function spawn(p, anywhere) {
    p.x = Math.random() * W;
    p.y = anywhere ? Math.random() * H : H + 20 + Math.random() * 60;
    p.r = 0.8 + Math.random() * 2.4;
    p.vy = 0.12 + Math.random() * 0.42;
    p.vx = (Math.random() - 0.5) * 0.12;
    p.ph = Math.random() * Math.PI * 2;
    p.a = 0.25 + Math.random() * 0.55;
    return p;
  }

  let last = 0;
  function frame(t) {
    raf = 0;
    if (!active()) { ctx.clearRect(0, 0, W, H); return; }
    const dt = last ? Math.min(3, (t - last) / 16.7) : 1;
    last = t;
    ctx.clearRect(0, 0, W, H);
    for (const p of parts) {
      p.y -= p.vy * dt;
      p.x += (p.vx + Math.sin(t / 2600 + p.ph) * 0.14) * dt;
      // particles drift away from the cursor a little
      const dx = p.x - mx, dy = p.y - my, d2 = dx * dx + dy * dy;
      if (d2 < 14400) { const f = (1 - d2 / 14400) * 0.9 * dt; p.x += dx / Math.sqrt(d2 + 1) * f; p.y += dy / Math.sqrt(d2 + 1) * f; }
      if (p.y < -30 || p.x < -40 || p.x > W + 40) spawn(p, false);
      const s = p.r * 8;
      ctx.globalAlpha = p.a * (0.55 + 0.45 * Math.sin(t / 900 + p.ph));
      ctx.drawImage(sprite, p.x - s / 2, p.y - s / 2, s, s);
    }
    ctx.globalAlpha = 1;
    raf = requestAnimationFrame(frame);
  }

  function run() {
    if (active()) { if (!raf) { last = 0; raf = requestAnimationFrame(frame); } } else ctx.clearRect(0, 0, W, H);
  }

  // ---------------------------------------------------------------- pointer: light, glow borders, tilt, magnet
  const light = document.getElementById('fx-cursor');
  const aurora = document.querySelector('.aurora');
  let mx = -9999, my = -9999, queued = false;
  let tiltEl = null;
  const play = document.getElementById('play');

  function onPointer() {
    queued = false;
    if (!active()) return;
    light.style.transform = `translate(${mx}px, ${my}px)`;
    if (aurora) aurora.style.translate = `${(mx / W - 0.5) * -26}px ${(my / H - 0.5) * -18}px`;
  }

  const GLOW = '.fx-card, .card, .result';
  const TILT = '.fx-tilt, .posters .card, .cards .card';

  document.addEventListener('pointermove', (e) => {
    mx = e.clientX;
    my = e.clientY;
    if (!queued) { queued = true; requestAnimationFrame(onPointer); }
    if (!enabled) return;
    const glow = e.target.closest && e.target.closest(GLOW);
    if (glow) {
      const r = glow.getBoundingClientRect();
      glow.style.setProperty('--mx', `${e.clientX - r.left}px`);
      glow.style.setProperty('--my', `${e.clientY - r.top}px`);
    }
    const t = e.target.closest && e.target.closest(TILT);
    if (t !== tiltEl) { resetTilt(); tiltEl = t; }
    if (t) {
      const r = t.getBoundingClientRect();
      const px = (e.clientX - r.left) / r.width - 0.5;
      const py = (e.clientY - r.top) / r.height - 0.5;
      const max = t.classList.contains('stage') ? 1.6 : 6;
      t.style.transform = `perspective(1000px) rotateX(${(-py * max).toFixed(2)}deg) rotateY(${(px * max).toFixed(2)}deg) translateY(-3px)`;
    }
    if (play && !play.disabled) {
      const r = play.getBoundingClientRect();
      const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      const dx = e.clientX - cx, dy = e.clientY - cy;
      const near = Math.abs(dx) < r.width / 2 + 60 && Math.abs(dy) < r.height / 2 + 50;
      play.style.setProperty('--px', near ? `${(dx * 0.07).toFixed(1)}px` : '0px');
      play.style.setProperty('--py', near ? `${(dy * 0.14).toFixed(1)}px` : '0px');
    }
  }, { passive: true });

  function resetTilt() {
    if (tiltEl) tiltEl.style.transform = '';
    tiltEl = null;
  }
  document.addEventListener('pointerleave', () => { resetTilt(); mx = my = -9999; light.style.transform = 'translate(-999px, -999px)'; });

  // ---------------------------------------------------------------- ripples
  document.addEventListener('pointerdown', (e) => {
    if (!enabled || e.button !== 0) return;
    let el = e.target.closest && e.target.closest('.btn, .play, .segmented button');
    if (!el || el.disabled) return;
    // the play button is a chamfered shape: the ripple goes into its (clipped) fill
    if (el.classList.contains('play') && el.querySelector('.play-fill')) el = el.querySelector('.play-fill');
    const r = el.getBoundingClientRect();
    const size = Math.max(r.width, r.height) * 2.2;
    const dot = document.createElement('span');
    dot.className = 'ripple';
    dot.style.width = dot.style.height = size + 'px';
    dot.style.left = (e.clientX - r.left - size / 2) + 'px';
    dot.style.top = (e.clientY - r.top - size / 2) + 'px';
    el.append(dot);
    setTimeout(() => dot.remove(), 700);
  });

  // ---------------------------------------------------------------- navigation indicator
  const dock = document.getElementById('dock');
  const indicator = document.getElementById('dock-indicator');
  function placeIndicator() {
    const act = dock.querySelector('.nav.active');
    // only touch attributes that really change: the observer below would otherwise fire forever
    if (!act) { if (dock.classList.contains('has-indicator')) dock.classList.remove('has-indicator'); return; }
    const vars = { '--ix': act.offsetLeft + 'px', '--iw': act.offsetWidth + 'px', '--iy': act.offsetTop + 'px', '--ih': act.offsetHeight + 'px' };
    for (const [k, v] of Object.entries(vars)) if (indicator.style.getPropertyValue(k) !== v) indicator.style.setProperty(k, v);
    if (!dock.classList.contains('has-indicator')) dock.classList.add('has-indicator');
  }
  // watch only the nav buttons (not the bar itself, whose class placeIndicator sets)
  const navObserver = new MutationObserver(placeIndicator);
  dock.querySelectorAll('.nav').forEach((n) => navObserver.observe(n, { attributes: true, attributeFilter: ['class'] }));

  // ---------------------------------------------------------------- state changes
  new MutationObserver(() => { run(); if (body.classList.contains('game-running')) resetTilt(); })
    .observe(body, { attributes: true, attributeFilter: ['class'] });
  new MutationObserver(readAccent).observe(root, { attributes: true, attributeFilter: ['style'] });
  document.addEventListener('visibilitychange', run);
  window.addEventListener('vl-fx', (e) => {
    enabled = !reduced && !!e.detail;
    if (!enabled) { resetTilt(); if (play) { play.style.setProperty('--px', '0px'); play.style.setProperty('--py', '0px'); } }
    run();
  });
  window.addEventListener('resize', () => { resize(); placeIndicator(); });
  // labels can hide/show with the window width and fonts load late: keep the indicator in place
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(placeIndicator);

  resize();
  readAccent();
  for (let i = 0; i < COUNT; i++) parts.push(spawn({}, true));
  placeIndicator();
  run();
})();
