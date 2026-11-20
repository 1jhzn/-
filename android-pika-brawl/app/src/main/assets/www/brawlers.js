/* ============ 피카츄 배구 BRAWL — 브롤러 데이터 & 자체 캔버스 일러스트 ============ */
'use strict';
(function () {
  const OUTLINE = '#191228';

  const BRAWLERS = {
    pika: {
      key: 'pika', name: '전격 피카츄', short: '피카츄', emoji: '⚡',
      ult: '전격 스파이크', tag: '밸런스 · 번개 스파이크',
      body: '#ffd93b', body2: '#f7b500', accent: '#ff5a5a',
      speed: 1.00, power: 1.00, jump: 1.00, ultKind: 'thunder'
    },
    charmander: {
      key: 'charmander', name: '화염 파이리', short: '파이리', emoji: '🔥',
      ult: '화염 스매시', tag: '파워 · 강력 화염 스매시',
      body: '#ff9142', body2: '#ef6b1c', accent: '#ffe08a',
      speed: 0.93, power: 1.18, jump: 0.97, ultKind: 'fire'
    },
    squirtle: {
      key: 'squirtle', name: '물대포 꼬부기', short: '꼬부기', emoji: '💧',
      ult: '물대포', tag: '스피드 · 물대포 리시브',
      body: '#63c9ff', body2: '#2e9ad6', accent: '#ffe9b0',
      speed: 1.12, power: 0.93, jump: 1.08, ultKind: 'water'
    }
  };
  const BKEYS = ['pika', 'charmander', 'squirtle'];

  function ell(c, x, y, rx, ry, fill, lw) {
    c.beginPath();
    c.ellipse(x, y, Math.abs(rx), Math.abs(ry), 0, 0, Math.PI * 2);
    if (fill) { c.fillStyle = fill; c.fill(); }
    if (lw) { c.lineWidth = lw; c.strokeStyle = OUTLINE; c.stroke(); }
  }
  function poly(c, pts, fill, lw) {
    c.beginPath();
    c.moveTo(pts[0], pts[1]);
    for (let i = 2; i < pts.length; i += 2) c.lineTo(pts[i], pts[i + 1]);
    c.closePath();
    if (fill) { c.fillStyle = fill; c.fill(); }
    if (lw) { c.lineWidth = lw; c.strokeStyle = OUTLINE; c.stroke(); }
  }
  function eyes(c, r, lookX, lookY, big) {
    const ex = 0.30 * r, ey = -0.86 * r, er = (big ? 0.17 : 0.145) * r;
    ell(c, -ex, ey, er, er * 1.08, '#1b1430', 0);
    ell(c, ex, ey, er, er * 1.08, '#1b1430', 0);
    ell(c, -ex + 0.055 * r, ey - 0.05 * r, er * 0.42, er * 0.42, 'rgba(255,255,255,.92)', 0);
    ell(c, ex + 0.055 * r, ey - 0.05 * r, er * 0.42, er * 0.42, 'rgba(255,255,255,.92)', 0);
    void lookX; void lookY;
  }
  function legs(c, r, lw, pose, color, dark) {
    const tuck = pose === 'jump' ? -0.34 * r : 0;
    const spread = pose === 'atk' ? 0.1 * r : 0;
    ell(c, -0.36 * r, 0.86 * r + tuck, 0.3 * r, 0.22 * r, dark, lw);
    ell(c, 0.36 * r + spread, 0.86 * r + tuck, 0.3 * r, 0.22 * r, dark, lw);
    if (pose !== 'jump') {
      ell(c, -0.36 * r, 1.0 * r, 0.26 * r, 0.13 * r, color, lw);
      ell(c, 0.36 * r + spread, 1.0 * r, 0.26 * r, 0.13 * r, color, lw);
    }
  }

  /* ---------------- ⚡ 피카츄 ---------------- */
  function pika(c, r, o) {
    const pose = o.pose || 'idle';
    const t = o.t || 0;
    const lw = Math.max(1.6, r * 0.13);
    const B = BRAWLERS.pika;
    // 꼬리
    c.save();
    c.translate(-0.7 * r, 0.28 * r);
    c.rotate(Math.sin(t * 5) * 0.12 - 0.15);
    poly(c, [0, 0.05 * r, -0.55 * r, -0.35 * r, -0.2 * r, -0.5 * r, -0.85 * r, -1.0 * r,
      -0.45 * r, -0.95 * r, -0.9 * r, -1.55 * r, -0.3 * r, -1.15 * r, 0.1 * r, -0.35 * r],
      '#ffe066', lw * 0.75);
    c.restore();
    // 발
    legs(c, r, lw, pose, B.body, B.body2);
    // 몸통
    ell(c, 0, 0.22 * r, 0.8 * r, 0.74 * r, B.body, lw);
    ell(c, 0, 0.42 * r, 0.5 * r, 0.42 * r, 'rgba(255,255,255,.24)', 0);
    // 팔
    const armY = pose === 'atk' ? -0.1 * r : pose === 'jump' ? -0.55 * r : 0.1 * r;
    const armA = pose === 'atk' ? 1.0 : pose === 'jump' ? 0.6 : 0.35;
    c.save(); c.translate(-0.72 * r, armY); c.rotate(-armA);
    ell(c, 0, 0.2 * r, 0.2 * r, 0.34 * r, B.body, lw); c.restore();
    c.save(); c.translate(0.72 * r, armY); c.rotate(armA);
    ell(c, 0, 0.2 * r, 0.2 * r, 0.34 * r, B.body, lw); c.restore();
    // 귀
    const earTilt = pose === 'jump' ? 0.18 : 0.05;
    poly(c, [-0.55 * r, -1.35 * r, -0.95 * r, -2.35 * r, -0.12 * r, -2.15 * r], B.body, lw);
    poly(c, [-0.9 * r, -2.28 * r, -0.95 * r, -2.4 * r, -0.6 * r, -2.3 * r], '#2a2240', 0);
    poly(c, [0.55 * r, -1.35 * r, 0.95 * r + earTilt * r, -2.35 * r, 0.12 * r, -2.15 * r], B.body, lw);
    poly(c, [0.88 * r + earTilt * r, -2.28 * r, 0.95 * r + earTilt * r, -2.4 * r, 0.62 * r, -2.3 * r], '#2a2240', 0);
    // 머리
    ell(c, 0, -0.95 * r, 0.86 * r, 0.78 * r, B.body, lw);    // 볼
    ell(c, -0.58 * r, -0.72 * r, 0.2 * r, 0.18 * r, '#ff5a5a', 0);
    ell(c, 0.58 * r, -0.72 * r, 0.2 * r, 0.18 * r, '#ff5a5a', 0);
    eyes(c, r, 0, 0, false);
    // 입
    c.beginPath();
    c.moveTo(-0.16 * r, -0.66 * r);
    c.quadraticCurveTo(0, -0.5 * r, 0.16 * r, -0.66 * r);
    c.lineWidth = Math.max(1.2, r * 0.08); c.strokeStyle = OUTLINE; c.stroke();
  }

  /* ---------------- 🔥 파이리 ---------------- */
  function charmander(c, r, o) {
    const pose = o.pose || 'idle';
    const t = o.t || 0;
    const lw = Math.max(1.6, r * 0.13);
    const B = BRAWLERS.charmander;
    // 꼬리 불꽃
    c.save();
    c.translate(-0.8 * r, 0.5 * r);
    c.rotate(-0.5 + Math.sin(t * 7) * 0.12);
    poly(c, [0, 0, -0.3 * r, -0.7 * r, -0.05 * r, -0.75 * r, 0.05 * r, -0.1 * r], B.body2, lw);
    const fl = 1 + Math.sin(t * 11) * 0.13;
    ell(c, -0.25 * r, -1.0 * r * fl, 0.34 * r, 0.42 * r * fl, '#ff5a1f', 0);
    ell(c, -0.25 * r, -1.02 * r * fl, 0.22 * r, 0.3 * r * fl, '#ffab2e', 0);
    ell(c, -0.25 * r, -1.02 * r * fl, 0.1 * r, 0.16 * r * fl, '#fff4b8', 0);
    c.restore();
    legs(c, r, lw, pose, B.body, B.body2);
    ell(c, 0, 0.22 * r, 0.8 * r, 0.72 * r, B.body, lw);
    ell(c, 0, 0.36 * r, 0.5 * r, 0.46 * r, B.accent, 0);
    const armY = pose === 'atk' ? -0.12 * r : pose === 'jump' ? -0.5 * r : 0.08 * r;
    const armA = pose === 'atk' ? 1.1 : pose === 'jump' ? 0.7 : 0.4;
    c.save(); c.translate(-0.72 * r, armY); c.rotate(-armA);
    ell(c, 0, 0.2 * r, 0.19 * r, 0.33 * r, B.body, lw); c.restore();
    c.save(); c.translate(0.72 * r, armY); c.rotate(armA);
    ell(c, 0, 0.2 * r, 0.19 * r, 0.33 * r, B.body, lw);
    ell(c, 0.12 * r, 0.42 * r, 0.1 * r, 0.09 * r, '#fff', 0);
    c.restore();
    // 머리
    ell(c, 0, -0.95 * r, 0.9 * r, 0.8 * r, B.body, lw);
    poly(c, [0, -1.7 * r, -0.14 * r, -2.15 * r, 0.14 * r, -2.15 * r], B.body2, lw);
    ell(c, 0, -0.6 * r, 0.42 * r, 0.22 * r, B.accent, 0);
    eyes(c, r, 0, 0, true);
    c.beginPath();
    c.moveTo(-0.22 * r, -0.66 * r);
    c.quadraticCurveTo(0, -0.44 * r, 0.22 * r, -0.66 * r);
    c.lineWidth = Math.max(1.2, r * 0.08); c.strokeStyle = OUTLINE; c.stroke();
  }

  /* ---------------- 💧 꼬부기 ---------------- */
  function squirtle(c, r, o) {
    const pose = o.pose || 'idle';
    const t = o.t || 0;
    const lw = Math.max(1.6, r * 0.13);
    const B = BRAWLERS.squirtle;
    // 등껍질
    ell(c, 0, 0.24 * r, 0.72 * r, 0.66 * r, '#a9662f', lw);
    ell(c, 0, 0.24 * r, 0.56 * r, 0.5 * r, '#e0a860', lw * 0.7);
    // 꼬리
    c.save();
    c.translate(-0.7 * r, 0.55 * r);
    c.rotate(-0.3 + Math.sin(t * 5) * 0.15);
    poly(c, [0, 0, -0.3 * r, 0.14 * r, -0.26 * r, -0.2 * r, 0, -0.08 * r], B.body2, lw);
    c.restore();
    legs(c, r, lw, pose, B.body, B.body2);
    ell(c, 0, 0.32 * r, 0.62 * r, 0.56 * r, B.body, lw);
    ell(c, 0, 0.4 * r, 0.42 * r, 0.4 * r, B.accent, 0);
    const armY = pose === 'atk' ? -0.15 * r : pose === 'jump' ? -0.5 * r : 0.05 * r;
    const armA = pose === 'atk' ? 1.15 : pose === 'jump' ? 0.7 : 0.4;
    c.save(); c.translate(-0.68 * r, armY); c.rotate(-armA);
    ell(c, 0, 0.2 * r, 0.18 * r, 0.32 * r, B.body, lw); c.restore();
    c.save(); c.translate(0.68 * r, armY); c.rotate(armA);
    ell(c, 0, 0.2 * r, 0.18 * r, 0.32 * r, B.body, lw); c.restore();
    // 머리
    ell(c, 0, -0.95 * r, 0.88 * r, 0.8 * r, B.body, lw);
    ell(c, -0.3 * r, -0.62 * r, 0.3 * r, 0.2 * r, B.accent, 0);
    eyes(c, r, 0, 0, true);
    c.beginPath();
    c.moveTo(-0.2 * r, -0.68 * r);
    c.quadraticCurveTo(0, -0.5 * r, 0.2 * r, -0.68 * r);
    c.lineWidth = Math.max(1.2, r * 0.08); c.strokeStyle = OUTLINE; c.stroke();
  }

  const DRAW = { pika: pika, charmander: charmander, squirtle: squirtle };

  /**
   * 브롤러를 (x, y) 몸통 중앙 기준으로 그린다.
   * r: 몸 반지름(px), o: {facing, team, t, pose, glow, alpha}
   */
  function drawBrawler(c, kind, x, y, r, o) {
    o = o || {};
    const art = DRAW[kind] || pika;
    c.save();
    c.translate(x, y);
    if (o.alpha != null) c.globalAlpha = o.alpha;
    if (o.facing < 0) c.scale(-1, 1);
    if (o.pose === 'atk') c.rotate(0.06);
    if (o.glow) {
      c.save();
      const g = c.createRadialGradient(0, 0, r * 0.4, 0, 0, r * 2.3);
      g.addColorStop(0, o.glow + 'cc');
      g.addColorStop(1, o.glow + '00');
      c.fillStyle = g;
      c.beginPath(); c.arc(0, 0, r * 2.3, 0, Math.PI * 2); c.fill();
      c.restore();
    }
    art(c, r, o);
    // 팀 헤드밴드
    if (o.team) {
      c.beginPath();
      c.ellipse(0, -1.22 * r, 0.84 * r, 0.17 * r, 0, 0, Math.PI * 2);
      c.fillStyle = o.team; c.fill();
      c.lineWidth = Math.max(1.2, r * 0.09); c.strokeStyle = OUTLINE; c.stroke();
      c.beginPath();
      c.moveTo(-0.76 * r, -1.24 * r);
      c.lineTo(-1.3 * r, -0.92 * r);
      c.lineTo(-0.98 * r, -0.74 * r);
      c.lineTo(-0.7 * r, -1.12 * r);
      c.closePath();
      c.fillStyle = o.team; c.fill(); c.strokeStyle = OUTLINE; c.stroke();
    }
    c.restore();
  }

  /* 공 */
  function drawBall(c, x, y, r, t, hue) {
    c.save();
    c.translate(x, y);
    const g = c.createRadialGradient(-r * 0.3, -r * 0.35, r * 0.2, 0, 0, r);
    g.addColorStop(0, '#ffffff');
    g.addColorStop(0.55, hue || '#ffe14d');
    g.addColorStop(1, '#f0a400');
    c.beginPath(); c.arc(0, 0, r, 0, Math.PI * 2);
    c.fillStyle = g; c.fill();
    c.lineWidth = Math.max(1.4, r * 0.14); c.strokeStyle = '#3a2600'; c.stroke();
    // 번개 무늬
    c.save();
    c.rotate(t * 2.2);
    c.beginPath();
    c.moveTo(0.1 * r, -0.62 * r); c.lineTo(-0.28 * r, 0.02 * r); c.lineTo(0.02 * r, 0.02 * r);
    c.lineTo(-0.1 * r, 0.6 * r); c.lineTo(0.32 * r, -0.08 * r); c.lineTo(0.02 * r, -0.08 * r);
    c.closePath();
    c.fillStyle = 'rgba(255,120,0,.55)'; c.fill();
    c.restore();
    c.restore();
  }

  window.Brawl = { BRAWLERS, BKEYS, drawBrawler, drawBall, OUTLINE };
})();
