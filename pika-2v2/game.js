'use strict';
/* =========================================================================
   ⚡ 피카츄 배구 2 vs 2 — 100% 자체 제작
   캐릭터 모양·3대 스킬(번개 강스매시 / 슬라이딩 다이빙 / 페인트 드롭샷)은
   지훈 GitHub의 omok-game/pika 사양을 그대로 이어받아 모바일 2대2 팀전으로 확장.
   ========================================================================= */

const $ = (id) => document.getElementById(id);
const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
const rnd = (a, b) => a + Math.random() * (b - a);
const TAU = Math.PI * 2;

/* ---------------- 사운드 ---------------- */
class SoundFX {
  constructor() { this.ctx = null; this.enabled = true; }
  init() {
    if (this.ctx) { if (this.ctx.state === 'suspended') this.ctx.resume().catch(() => {}); return; }
    try {
      const A = window.AudioContext || window.webkitAudioContext;
      if (A) this.ctx = new A();
    } catch (e) { this.ctx = null; }
  }
  beep(freq, dur, type = 'sine', vol = 0.14) {
    if (!this.enabled || !this.ctx) return;
    try {
      const o = this.ctx.createOscillator(), g = this.ctx.createGain();
      o.type = type;
      o.frequency.setValueAtTime(freq, this.ctx.currentTime);
      g.gain.setValueAtTime(vol, this.ctx.currentTime);
      g.gain.exponentialRampToValueAtTime(0.001, this.ctx.currentTime + dur);
      o.connect(g); g.connect(this.ctx.destination);
      o.start(); o.stop(this.ctx.currentTime + dur);
    } catch (e) {}
  }
  hit()   { this.beep(350, .08, 'triangle'); }
  smash() { this.beep(880, .18, 'sawtooth', .18); this.beep(420, .12, 'square', .08); }
  dive()  { this.beep(220, .12, 'triangle'); }
  feint() { this.beep(620, .06, 'sine', .1); }
  save()  { this.beep(980, .2, 'sine', .16); }
  score() { this.beep(560, .18, 'sine', .16); setTimeout(() => this.beep(760, .2, 'sine', .12), 110); }
  win()   { [523, 659, 784, 1046].forEach((f, i) => setTimeout(() => this.beep(f, .18, 'triangle', .16), i * 130)); }
}
const sfx = new SoundFX();

/* ---------------- 무대 기하(월드 좌표) ---------------- */
const geo = { W: 1000, H: 500, floor: 360, netTop: 200, netX: 500 };
const NETW = 12;
let cv, ctx, W = 0, H = 0, S = 1, OX = 0, OY = 0, portrait = false, DPR = 1;
let PS = 1, BS = 1;

const WIN_SCORE = 7;
const SKILL = { smash: 62, dive: 84, feint: 96 };

/* ---------------- 상태 ---------------- */
const G = {
  phase: 'demo',            // demo | play | over
  mode: 'ai',               // ai | online
  teamSize: 2,
  diff: 'normal',
  players: [],
  ball: { x: 500, y: 120, vx: 0, vy: 0, r: 18, rot: 0, fiery: false, nx: null, ny: null },
  score: { blue: 0, red: 0 },
  touch: { blue: 0, red: 0 },
  lastHit: null, lastSide: 'blue',
  freeze: 0, serveTeam: 'blue',
  winner: null,
  online: false, isHost: false,
  myId: 'p_' + Math.random().toString(36).slice(2, 9),
  myName: '지훈',
  effects: [], pops: [], shake: 0, time: 0,
  stats: { hits: 0, smashes: 0, saves: 0, feints: 0, points: 0, touches: 0 },
  pendingFx: [], demo: true,
};
const myPlayer = () => G.players.find((p) => p.id === G.myId && !p.isAI && (p.isLocal || p.isRemote)) ||
  G.players.find((p) => p.isLocal) || null;

/* ---------------- 레이아웃 ---------------- */
function layout() {
  const r = cv.getBoundingClientRect();
  W = Math.max(200, Math.round(r.width));
  H = Math.max(240, Math.round(r.height));
  DPR = Math.min(2, window.devicePixelRatio || 1);
  cv.width = Math.round(W * DPR);
  cv.height = Math.round(H * DPR);
  portrait = H > W * 1.05;
  document.body.classList.toggle('portrait', portrait);
  document.body.classList.toggle('landscape', !portrait);

  const baseW = portrait ? 480 : 900;      // 화면 폭 기준 세계 크기
  geo.floor = portrait ? 510 : 360;
  geo.netTop = geo.floor - (portrait ? 190 : 160);

  const visH = portrait ? H * 0.62 : H;
  S = Math.min(W / baseW, visH / 500);
  geo.W = W / S;                            // 무대가 화면 폭을 꽉 채운다 (여백·이음선 없음)
  geo.netX = geo.W / 2;
  OX = 0;
  OY = portrait ? Math.max(54, H * 0.09) : (H - 500 * S);
  geo.H = Math.max(portrait ? 720 : 500, (H - OY) / S);

  PS = Math.sqrt(geo.W / 1000);
  BS = geo.W / 1000;
  G.ball.r = 18;
  G.players.forEach((p) => { p.homeX = homeXFor(p); p.x = clamp(p.x, 40, geo.W - 40); });
}

/* ---------------- 플레이어 ---------------- */
function homeXFor(p) {
  const mates = G.players.filter((q) => q.team === p.team).sort((a, b) => a.slot - b.slot);
  const n = Math.max(1, mates.length);
  const i = Math.max(0, mates.indexOf(p));
  const fr = n === 1 ? [0.52] : n === 2 ? [0.30, 0.70] : [0.24, 0.48, 0.72];
  const d = fr[Math.min(i, fr.length - 1)];
  const half = geo.netX;
  return p.team === 'blue' ? geo.netX - half * d : geo.netX + half * d;
}
function boundsFor(team) {
  return team === 'blue'
    ? { min: 40, max: geo.netX - NETW / 2 - 30 }
    : { min: geo.netX + NETW / 2 + 30, max: geo.W - 40 };
}
function makePlayer(o) {
  const p = {
    id: o.id || 'p' + Math.random().toString(36).slice(2, 7),
    name: o.name || '플레이어',
    team: o.team === 'red' ? 'red' : 'blue',
    slot: o.slot || 0,
    isAI: !!o.isAI, isLocal: !!o.isLocal, isRemote: !!o.isRemote,
    x: 0, y: geo.floor, vx: 0, vy: 0, onGround: true,
    facing: (o.team === 'red' ? -1 : 1),
    diveT: 0, diveCd: 0, smashCd: 0, feintCd: 0, hitCd: 0,
    smashArm: 0, feintArm: 0, smashPose: 0, isDiving: false,
    animT: rnd(0, 6), runT: rnd(0, 6),
    homeX: 0, nx: null, ny: null, nf: 1, nflags: 0,
    input: { x: 0, jump: false, smash: false, dive: false, feint: false },
    ai: { think: 0, err: 0 },
  };
  p.homeX = homeXFor(p);
  p.x = p.homeX;
  return p;
}
function placeAll() {
  G.players.forEach((p) => {
    p.homeX = homeXFor(p);
    p.x = p.homeX;
    p.y = geo.floor; p.vx = 0; p.vy = 0; p.onGround = true;
    p.diveT = 0; p.isDiving = false; p.smashArm = 0; p.feintArm = 0; p.smashPose = 0;
    p.facing = p.team === 'red' ? -1 : 1;
    p.input.x = 0;
  });
}

/* ---------------- 데모(로비 배경) ---------------- */
const AI_NAMES = ['번개맨', '스파크', '찌릿이', '전기쥐'];
function setupDemo() {
  G.phase = 'demo'; G.demo = true; G.online = false;
  G.players = [
    makePlayer({ name: '지훈', team: 'blue', slot: 0, isAI: true }),
    makePlayer({ name: 'AI 번개', team: 'blue', slot: 1, isAI: true }),
    makePlayer({ name: 'AI 스파크', team: 'red', slot: 0, isAI: true }),
    makePlayer({ name: 'AI 찌릿이', team: 'red', slot: 1, isAI: true }),
  ];
  G.score = { blue: 0, red: 0 };
  placeAll();
  serveBall('blue');
  G.freeze = 40;
}

/* ---------------- 입력 ---------------- */
const ui = { stickX: 0, stickY: 0, jump: false, smash: false, dive: false, feint: false };
const keys = {};
function bindKeys() {
  const map = { ArrowLeft: 'l', KeyA: 'l', ArrowRight: 'r', KeyD: 'r' };
  window.addEventListener('keydown', (e) => {
    sfx.init();
    if (map[e.code]) { keys[map[e.code]] = true; e.preventDefault(); }
    if (e.code === 'ArrowUp' || e.code === 'KeyW' || e.code === 'Space') { ui.jump = true; e.preventDefault(); }
    if (e.code === 'KeyJ' || e.code === 'KeyZ' || e.code === 'Enter') { ui.smash = true; e.preventDefault(); }
    if (e.code === 'KeyK' || e.code === 'KeyC' || e.code === 'ShiftLeft') { ui.dive = true; e.preventDefault(); }
    if (e.code === 'KeyL' || e.code === 'KeyX') { ui.feint = true; e.preventDefault(); }
  });
  window.addEventListener('keyup', (e) => {
    if (map[e.code]) keys[map[e.code]] = false;
  });
}
function readKeys() {
  let x = 0;
  if (keys.l) x -= 1;
  if (keys.r) x += 1;
  if (x) ui.stickX = x;
  else if (!stick.active) ui.stickX = 0;
}

const stick = { active: false, id: null, cx: 0, cy: 0, r: 1, up: false };
function bindTouch() {
  const el = $('stick'), knob = $('knob');
  const start = (e) => {
    const r = el.getBoundingClientRect();
    stick.active = true; stick.id = e.pointerId;
    stick.cx = r.left + r.width / 2; stick.cy = r.top + r.height / 2; stick.r = r.width / 2;
    try { el.setPointerCapture(e.pointerId); } catch (err) {}
    el.classList.add('on');
    sfx.init();
    move(e);
  };
  const move = (e) => {
    if (!stick.active || e.pointerId !== stick.id) return;
    let dx = e.clientX - stick.cx, dy = e.clientY - stick.cy;
    const len = Math.hypot(dx, dy) || 1;
    const max = stick.r * 0.78;
    const k = len > max ? max / len : 1;
    dx *= k; dy *= k;
    knob.style.transform = `translate(calc(-50% + ${dx}px), calc(-50% + ${dy}px))`;
    ui.stickX = clamp(dx / max, -1, 1);
    ui.stickY = clamp(dy / max, -1, 1);
    const upNow = dy < -max * 0.62;
    if (upNow && !stick.up) ui.jump = true;
    stick.up = upNow;
  };
  const end = (e) => {
    if (e && e.pointerId != null && e.pointerId !== stick.id) return;
    stick.active = false; stick.id = null; stick.up = false;
    knob.style.transform = 'translate(-50%,-50%)';
    el.classList.remove('on');
    ui.stickX = 0; ui.stickY = 0;
  };
  el.addEventListener('pointerdown', (e) => { e.preventDefault(); start(e); });
  el.addEventListener('pointermove', (e) => { e.preventDefault(); move(e); });
  el.addEventListener('pointerup', end);
  el.addEventListener('pointercancel', end);
  window.addEventListener('pointerup', end);

  const btn = (id, key) => {
    const b = $(id);
    b.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      b.classList.add('press');
      ui[key] = true;
      sfx.init();
      if (navigator.vibrate) { try { navigator.vibrate(8); } catch (err) {} }
    });
    const up = () => b.classList.remove('press');
    b.addEventListener('pointerup', up);
    b.addEventListener('pointercancel', up);
    b.addEventListener('pointerleave', up);
  };
  btn('b-jump', 'jump');
  btn('b-smash', 'smash');
  btn('b-dive', 'dive');
  btn('b-feint', 'feint');
}

/* ---------------- 매치 진행 ---------------- */
function startAiMatch(size, diff) {
  G.mode = 'ai'; G.online = false; G.demo = false;
  G.teamSize = size; G.diff = diff;
  G.online = false; G.isHost = false;
  const names = AI_NAMES.slice();
  G.players = [];
  G.players.push(makePlayer({ id: G.myId, name: G.myName, team: 'blue', slot: 0, isLocal: true }));
  for (let i = 1; i < size; i++) {
    G.players.push(makePlayer({ id: 'bot_b' + i, name: 'AI ' + names[i % names.length], team: 'blue', slot: i, isAI: true }));
  }
  for (let i = 0; i < size; i++) {
    G.players.push(makePlayer({ id: 'bot_r' + i, name: 'AI ' + names[(i + 2) % names.length], team: 'red', slot: i, isAI: true }));
  }
  beginPlay();
}

function beginPlay() {
  G.phase = 'play'; G.demo = false;
  G.score = { blue: 0, red: 0 }; G.winner = null;
  G.effects = []; G.pops = []; G.shake = 0; G.freeze = 0;
  placeAll();
  serveBall('blue');
  G.freeze = 50;
  $('lobby').classList.add('hidden');
  $('room').classList.add('hidden');
  $('result').classList.add('hidden');
  $('hud').classList.remove('hidden');
  $('controls').classList.remove('hidden');
  refreshHud();
  showCenter('서브!');
  sfx.init();
}

function serveBall(team) {
  const b = G.ball;
  const mates = G.players.filter((p) => p.team === team);
  const server = mates.sort((a, c) => Math.abs(a.x - geo.netX) - Math.abs(c.x - geo.netX))[0];
  const sx = server ? server.x : (team === 'blue' ? geo.netX - geo.W * 0.18 : geo.netX + geo.W * 0.18);
  b.x = clamp(sx, 46, geo.W - 46);
  b.y = geo.floor - 138;
  b.vx = (team === 'blue' ? 1 : -1) * 1.2 * BS;
  b.vy = -3.4;
  b.rot = 0; b.fiery = false; b.nx = null; b.ny = null;
  G.touch.blue = 0; G.touch.red = 0; G.lastHit = null; G.lastSide = team;
  G.serveTeam = team;
  G.pops.length = 0;
}

function point(team) {
  G.score[team]++;
  if (G.online || !G.demo) G.stats.points++;
  refreshHud();
  sfx.score();
  pop(geo.netX, geo.floor - 130, team === 'blue' ? '🔵 파랑 득점!' : '🔴 빨강 득점!', team === 'blue' ? '#93c5fd' : '#fca5a5');
  spawnParticles(G.ball.x, geo.floor - 6, 14, 'dust');
  if (G.score[team] >= WIN_SCORE) { endMatch(team); return; }
  G.freeze = 55;
  placeAll();
  serveBall(team);
  showCenter('서브!');
  if (G.online && G.isHost) G.pendingFx.push({ k: 'serve' });
}

function endMatch(team) {
  G.phase = 'over'; G.winner = team;
  const me = myPlayer();
  const win = me ? team === me.team : team === 'blue';
  $('res-title').textContent = win ? '승리! 🎉' : '패배... 😭';
  $('res-score').textContent = `${G.score.blue} : ${G.score.red}`;
  $('res-desc').textContent = `${team === 'blue' ? '🔵 파랑팀' : '🔴 빨강팀'}이 ${WIN_SCORE}점을 먼저 달성!`;
  $('result').classList.remove('hidden');
  $('controls').classList.add('hidden');
  if (win) sfx.win();
  if (G.online && G.isHost) publish({ t: 'end', winner: team });
}

/* ---------------- 물리 ---------------- */
function stepPlayer(p) {
  const ip = p.input;
  p.diveCd = Math.max(0, p.diveCd - 1);
  p.smashCd = Math.max(0, p.smashCd - 1);
  p.feintCd = Math.max(0, p.feintCd - 1);
  p.hitCd = Math.max(0, p.hitCd - 1);
  p.smashArm = Math.max(0, p.smashArm - 1);
  p.feintArm = Math.max(0, p.feintArm - 1);
  p.smashPose = Math.max(0, p.smashPose - 1);
  const bnd = boundsFor(p.team);

  if (p.diveT > 0) {
    p.diveT--; p.isDiving = true;
    p.vx = p.facing * 12 * PS;
    if (p.diveT % 4 === 0) spawnParticles(p.x - p.facing * 20, geo.floor - 5, 1, 'dust');
  } else {
    p.isDiving = false;
    if (ip.dive && p.diveCd <= 0 && p.onGround) {
      p.diveT = 22; p.diveCd = SKILL.dive; p.vx = p.facing * 12 * PS;
      sfx.dive(); spawnParticles(p.x, geo.floor - 5, 7, 'dust');
      if (G.online && G.isHost) G.pendingFx.push({ k: 'dive', n: p.id });
    } else {
      p.vx = 0;
      const mx = clamp(ip.x, -1, 1);
      if (Math.abs(mx) > 0.12) { p.vx = mx * 6.5 * PS; p.facing = mx > 0 ? 1 : -1; p.runT += Math.abs(mx) * 0.32; }
      if (ip.jump && p.onGround) { p.vy = -13.8; p.onGround = false; sfx.hit(); }
    }
  }
  if (ip.smash && p.smashCd <= 0) {
    p.smashCd = SKILL.smash; p.smashArm = 30; p.smashPose = 30;
    if (p.onGround) { p.vy = -13.6; p.onGround = false; }
    sfx.dive();
  }
  if (ip.feint && p.feintCd <= 0) { p.feintCd = SKILL.feint; p.feintArm = 38; sfx.feint(); }

  ip.jump = false; ip.smash = false; ip.dive = false; ip.feint = false;

  p.x = clamp(p.x + p.vx, bnd.min, bnd.max);
  p.vy += 0.65;
  p.y += p.vy;
  if (p.y >= geo.floor) { p.y = geo.floor; p.vy = 0; p.onGround = true; } else p.onGround = false;
  p.animT += 0.12;
}

function predictLandingX() {
  const b = G.ball;
  let x = b.x, y = b.y, vx = b.vx, vy = b.vy, r = b.r;
  for (let i = 0; i < 220; i++) {
    vy += 0.38; x += vx; y += vy;
    if (x - r < 0) { x = r; vx = Math.abs(vx) * 0.85; }
    if (x + r > geo.W) { x = geo.W - r; vx = -Math.abs(vx) * 0.85; }
    if (y + r >= geo.floor) return x;
  }
  return x;
}

const DIFF = {
  easy:   { spd: 0.74, err: 105, smash: 0.34, dive: 0.5, think: 12 },
  normal: { spd: 0.92, err: 55,  smash: 0.6,  dive: 0.75, think: 7 },
  hard:   { spd: 1.04, err: 24,  smash: 0.85, dive: 0.95, think: 4 },
};

function chooseAttacker(p) {
  const mates = G.players.filter((q) => q.team === p.team);
  if (mates.length === 1) return p;
  const predX = predictLandingX();
  const sorted = mates.slice().sort((a, c) => Math.abs(a.x - predX) - Math.abs(c.x - predX));
  const human = mates.find((q) => (q.isLocal || q.isRemote) && !q.isAI);
  if (human) {
    const dh = Math.abs(human.x - predX), di = Math.abs(p.x - predX);
    if (dh < 105 && dh < di - 8) return human;   // 사람이 더 가까울 때만 양보
  }
  return sorted[0];
}

function aiUpdate(p) {
  const d = DIFF[G.diff] || DIFF.normal;
  const b = G.ball, bnd = boundsFor(p.team);
  const home = p.homeX;
  const isBlue = p.team === 'blue';
  const ballMine = isBlue ? b.x < geo.netX : b.x > geo.netX;
  const predX = predictLandingX();
  const predMine = isBlue ? predX < geo.netX : predX > geo.netX;

  p.ai.think--;
  if (p.ai.think <= 0) {
    p.ai.think = d.think + Math.round(rnd(0, 4));
    p.ai.err = rnd(-1, 1) * d.err * (G.demo ? 1.4 : 1);
  }

  const attacker = chooseAttacker(p);
  const dirOwn = isBlue ? 1 : -1;
  let targetX = home;
  if (predMine) {
    if (attacker === p) targetX = predX + p.ai.err;
    else targetX = home + (predX - home) * 0.3;
    if (attacker !== p && Math.abs(b.x - p.x) < 52) targetX = b.x;
  } else if (ballMine) {
    // 공이 우리 쪽에 있지만 상대 코트로 넘어갈 공 → 네트 앞에서 대기
    targetX = attacker === p ? geo.netX - dirOwn * 95 : home;
  } else {
    targetX = home + (predX - home) * 0.12;
  }
  targetX = clamp(targetX, bnd.min, bnd.max);
  const mate2 = G.players.find((q) => q.team === p.team && q !== p);
  if (mate2 && Math.abs(mate2.x - targetX) < 64) {
    const awayDir = (targetX < mate2.x || Math.abs(targetX - mate2.x) < 0.5)
      ? (p.slot <= mate2.slot ? -1 : 1) : 1;
    targetX = clamp(mate2.x + awayDir * 78, bnd.min, bnd.max);
  }
  const dx = targetX - p.x;
  p.input.x = Math.abs(dx) > 9 ? clamp((dx / 42) * d.spd, -1, 1) : 0;

  const headY = p.y - 40;
  const dist = Math.hypot(b.x - p.x, b.y - headY);

  if (ballMine && p.onGround && p.smashCd <= 0 &&
      Math.abs(b.x - p.x) < 100 && b.y < p.y - 52 && b.y > p.y - 330 &&
      attacker === p && Math.random() < d.smash * 0.55) {
    p.input.smash = true;
  }
  if (ballMine && p.onGround && p.diveCd <= 0 && b.y > geo.floor - 125 && b.vy > 1 &&
      Math.abs(b.x - p.x) > 46 && Math.abs(b.x - p.x) < 240 && predMine &&
      Math.random() < d.dive * 0.4) {
    p.facing = b.x > p.x ? 1 : -1;
    p.input.dive = true;
  }
  if (ballMine && p.feintCd <= 0 && attacker === p && dist < 88 && Math.random() < 0.05) {
    p.input.feint = true;
  }
}

function updateBallPhysics() {
  const b = G.ball;
  if (G.freeze > 0) { G.freeze--; return; }
  b.vy += 0.38;
  b.x += b.vx; b.y += b.vy; b.rot += b.vx * 0.05;

  if (b.x - b.r < 0) { b.x = b.r; b.vx = Math.abs(b.vx) * 0.85; sfx.hit(); }
  if (b.x + b.r > geo.W) { b.x = geo.W - b.r; b.vx = -Math.abs(b.vx) * 0.85; sfx.hit(); }
  if (b.y - b.r < 4) { b.y = b.r + 4; b.vy = Math.abs(b.vy) * 0.5; }

  if (b.x + b.r > geo.netX - NETW / 2 && b.x - b.r < geo.netX + NETW / 2 && b.y + b.r > geo.netTop) {
    if (b.y < geo.netTop + 12) { b.y = geo.netTop - b.r; b.vy = -Math.abs(b.vy) * 0.8; }
    else if (b.x < geo.netX) { b.x = geo.netX - NETW / 2 - b.r; b.vx = -Math.abs(b.vx) * 0.8; }
    else { b.x = geo.netX + NETW / 2 + b.r; b.vx = Math.abs(b.vx) * 0.8; }
    sfx.hit();
  }

  const side = b.x < geo.netX ? 'blue' : 'red';
  if (side !== G.lastSide) { G.lastSide = side; G.touch.blue = 0; G.touch.red = 0; G.lastHit = null; }

  for (const p of G.players) checkHit(p);

  if (b.y + b.r >= geo.floor) {
    b.y = geo.floor - b.r;
    if (G.demo) {
      G.freeze = 40; spawnParticles(b.x, geo.floor - 5, 8, 'dust');
      serveBall(Math.random() < 0.5 ? 'blue' : 'red');
    } else if (G.phase === 'play') {
      const winner = b.x < geo.netX ? 'red' : 'blue';
      spawnParticles(b.x, geo.floor - 5, 12, 'dust');
      point(winner);
    }
  }
}

/* 상대 코트의 목표 지점으로 공을 보내되 네트를 넘도록 궤적을 푼다 */
function aimShot(tx, ty, maxSpeed) {
  const b = G.ball;
  for (let T = 12; T <= 96; T += 2) {
    const vx = (tx - b.x) / T;
    if (Math.abs(vx) > maxSpeed) continue;
    const vy = (ty - b.y - 0.19 * T * T) / T;
    const tn = vx === 0 ? 999 : (geo.netX - b.x) / vx;
    if (tn > 0 && tn < T) {
      const yn = b.y + vy * tn + 0.19 * tn * tn;
      if (yn > geo.netTop - b.r - 8) continue;   // 네트에 걸림
    }
    return { vx, vy, T };
  }
  return { vx: clamp((tx - b.x) / 40, -maxSpeed, maxSpeed), vy: -11, T: 40 };
}

function checkHit(p) {
  if (p.hitCd > 0) return;
  const b = G.ball;
  const dir = p.team === 'blue' ? 1 : -1;

  if (p.isDiving) {
    const hx = p.x + p.facing * 18, hy = geo.floor - 18;
    if (Math.hypot(b.x - hx, b.y - hy) < b.r + 40 && b.y > geo.floor - 120) {
      if (!countTouch(p)) return;
      b.fiery = false;
      b.vy = -12.8; b.vx = dir * 3.2 * BS;
      b.y = hy - b.r - 4;
      sfx.save(); spawnParticles(b.x, b.y, 10, 'spark');
      if (p.id === G.myId) G.stats.saves++;
      pop(b.x, b.y - 24, '다이빙 세이브!', '#a7f3d0');
      p.hitCd = 12;
      if (G.online && G.isHost) G.pendingFx.push({ k: 'save', x: b.x / geo.W, y: b.y / geo.floor });
      return;
    }
  }

  const hx = p.x, hy = p.y - 40;
  const dist = Math.hypot(b.x - hx, b.y - hy);
  if (dist >= b.r + 34) return;
  if (!countTouch(p)) return;

  const ang = Math.atan2(b.y - hy, b.x - hx);

  if (p.smashArm > 0 && !p.onGround) {
    b.fiery = true;
    const sharp = (p.input && p.input.down) || (p.x - geo.netX) * dir > geo.netX * 0.3;
    const tx = geo.netX + dir * geo.netX * (sharp ? 0.26 : 0.58);
    const shot = aimShot(tx, geo.floor - b.r, sharp ? 17 : 23);
    b.vx = shot.vx; b.vy = Math.max(shot.vy, -6);
    sfx.smash(); spawnParticles(b.x, b.y, 18, 'spark');
    if (p.id === G.myId) { G.stats.smashes++; G.stats.hits++; }
    pop(b.x, b.y - 26, '⚡ 강스매시!', '#fde047');
    G.shake = Math.min(14, G.shake + 9);
    if (G.online && G.isHost) G.pendingFx.push({ k: 'smash', x: b.x / geo.W, y: b.y / geo.floor });
  } else if (p.feintArm > 0) {
    b.fiery = false;
    const fshot = aimShot(geo.netX + dir * (60 + Math.random() * 45), geo.floor - b.r, 9);
    b.vx = fshot.vx; b.vy = fshot.vy;
    sfx.feint(); spawnParticles(b.x, b.y, 6, 'dust');
    if (p.id === G.myId) { G.stats.feints++; G.stats.hits++; }
    pop(b.x, b.y - 24, '🎯 페인트!', '#f3e8ff');
  } else {
    // 기본 토스: 상대 코트로 넘기는 아치
    b.fiery = false;
    const nshot = aimShot(geo.netX + dir * geo.netX * (0.42 + Math.random() * 0.34), geo.floor - b.r, 10.5);
    b.vx = nshot.vx; b.vy = nshot.vy;
    sfx.hit(); spawnParticles(b.x, b.y, 5, 'dust');
    if (p.id === G.myId) G.stats.hits++;
  }
  b.x = hx + Math.cos(ang) * (b.r + 36);
  b.y = hy + Math.sin(ang) * (b.r + 36);
  p.hitCd = 12;
  if (b.y > geo.floor - b.r) b.y = geo.floor - b.r;
}

function countTouch(p) {
  if (p.team === 'blue' || p.team === 'red') {
    if (G.touch[p.team] >= 3) return false;
    if (G.lastHit === p) return false;
    G.touch[p.team]++;
    G.lastHit = p;
    G.stats.touches++;
  }
  return true;
}

/* ---------------- 시뮬레이션 스텝 ---------------- */
function stepHost() {
  if (G.phase === 'play') readLocalInput();
  const nowT = performance.now();
  for (const p of G.players) {
    if (p.isAI) aiUpdate(p);
    else if (p.id !== G.myId && (!p.lastI || nowT - p.lastI > 320)) p.input.x = 0;
    stepPlayer(p);
  }
  updateBallPhysics();
  updateEffects();
  if (G.phase === 'over' && G.demo) G.demo = false;
  if (G.online && G.isHost) {
    G.netTick = (G.netTick || 0) + 1;
    if (G.netTick % 3 === 0) sendState();
  }
}
function stepDemo() {
  for (const p of G.players) { aiUpdate(p); stepPlayer(p); }
  updateBallPhysics();
  updateEffects();
}
function stepGuest() {
  const me = myPlayer();
  if (me) {
    me.input.x = ui.stickX;
    me.input.jump = me.input.jump || ui.jump;
    me.input.smash = me.input.smash || ui.smash;
    me.input.dive = me.input.dive || ui.dive;
    me.input.feint = me.input.feint || ui.feint;
    if (ui.jump) net.pend.j = true;
    if (ui.smash) { net.pend.s = true; if (me.smashCd <= 0) me.smashCd = SKILL.smash; }
    if (ui.dive) { net.pend.d = true; if (me.diveCd <= 0 && me.onGround) me.diveCd = SKILL.dive; }
    if (ui.feint) { net.pend.f = true; if (me.feintCd <= 0) me.feintCd = SKILL.feint; }
    ui.jump = ui.smash = ui.dive = ui.feint = false;
  }
  for (const p of G.players) {
    p.animT += 0.12;
    p.smashPose = Math.max(0, p.smashPose - 1);
    const tx = p.nx != null ? p.nx * geo.W : p.x;
    const ty = p.ny != null ? p.ny * geo.floor : p.y;
    if (me && p.id === me.id) {
      const bnd = boundsFor(p.team);
      p.x = clamp(p.x + ui.stickX * 6.5 * PS, bnd.min, bnd.max);
      const dx = tx - p.x;
      if (Math.abs(dx) > 220) p.x = tx; else if (Math.abs(dx) > 52) p.x += dx * 0.22;
      p.y += (ty - p.y) * 0.35;
    } else {
      const dxp = tx - p.x;
      p.x += Math.abs(dxp) > 260 ? dxp : dxp * 0.4;
      p.y += (ty - p.y) * 0.4;
    }
    if (p.nflags != null) {
      p.onGround = !!(p.nflags & 1);
      p.isDiving = !!(p.nflags & 2);
      if (p.nflags & 4) p.smashPose = Math.max(p.smashPose, 6);
    }
    if (p.nf) p.facing = p.nf;
    p.vx = 0;
  }
  const b = G.ball;
  if (b.nx != null) {
    const dxb = b.nx * geo.W - b.x, dyb = b.ny * geo.floor - b.y;
    b.x += Math.abs(dxb) > 200 ? dxb : dxb * 0.5;
    b.y += Math.abs(dyb) > 200 ? dyb : dyb * 0.5;
    b.rot += 0.12;
  }
  if (G.freeze > 0) G.freeze--;
  if (me) {
    me.smashCd = Math.max(0, me.smashCd - 1);
    me.diveCd = Math.max(0, me.diveCd - 1);
    me.feintCd = Math.max(0, me.feintCd - 1);
  }
  updateEffects();
  G.netTick = (G.netTick || 0) + 1;
  if (G.netTick % 2 === 0) sendInput();
}

function readLocalInput() {
  const me = myPlayer();
  if (!me) return;
  me.input.x = ui.stickX;
  me.input.down = ui.stickY > 0.5;
  me.input.jump = me.input.jump || ui.jump;
  me.input.smash = me.input.smash || ui.smash;
  me.input.dive = me.input.dive || ui.dive;
  me.input.feint = me.input.feint || ui.feint;
  ui.jump = ui.smash = ui.dive = ui.feint = false;
}

/* ---------------- 이펙트 ---------------- */
function spawnParticles(x, y, count, type = 'spark') {
  for (let i = 0; i < count; i++) {
    const a = Math.random() * TAU, sp = Math.random() * 4.5 + 1.2;
    G.effects.push({
      x, y, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp,
      life: 1, decay: Math.random() * 0.04 + 0.03,
      size: Math.random() * 4 + 2, type,
    });
  }
  if (G.effects.length > 260) G.effects.splice(0, G.effects.length - 260);
}
function pop(x, y, text, color) {
  const last = G.pops[G.pops.length - 1];
  if (last && last.text === text && last.life > 0.6) return;   // 같은 문구 도배 방지
  G.pops.push({ x, y, text, color: color || '#fff', life: 1 });
  if (G.pops.length > 2) G.pops.shift();
}
function updateEffects() {
  for (let i = G.effects.length - 1; i >= 0; i--) {
    const p = G.effects[i];
    p.x += p.vx; p.y += p.vy; p.vx *= 0.985; p.vy *= 0.985;
    p.life -= p.decay;
    if (p.life <= 0) G.effects.splice(i, 1);
  }
  for (let i = G.pops.length - 1; i >= 0; i--) {
    const q = G.pops[i];
    q.y -= 0.6; q.life -= 0.021;
    if (q.life <= 0) G.pops.splice(i, 1);
  }
  if (G.shake > 0) G.shake *= 0.9;
}

/* ---------------- 렌더 ---------------- */
function render() {
  if (!ctx) return;
  ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
  ctx.clearRect(0, 0, W, H);
  const floorY = OY + geo.floor * S;
  const seaTopY = OY + (geo.floor - 104) * S;
  const seaBotY = OY + (geo.floor - 22) * S;

  // 배경 (화면 전체 — 무대 밖 여백까지 이어지게)
  const sky = ctx.createLinearGradient(0, 0, 0, Math.max(1, seaTopY));
  sky.addColorStop(0, '#38bdf8'); sky.addColorStop(1, '#bae6fd');
  ctx.fillStyle = sky; ctx.fillRect(0, 0, W, Math.max(1, seaTopY));
  const seaBg = ctx.createLinearGradient(0, seaTopY, 0, seaBotY);
  seaBg.addColorStop(0, '#7dd3fc'); seaBg.addColorStop(.45, '#0ea5e9'); seaBg.addColorStop(1, '#0369a1');
  ctx.fillStyle = seaBg; ctx.fillRect(0, seaTopY - 1, W, Math.max(1, seaBotY - seaTopY + 2));
  const sand = ctx.createLinearGradient(0, seaBotY, 0, H);
  sand.addColorStop(0, '#fde047'); sand.addColorStop(.22, '#eab308'); sand.addColorStop(1, '#b45309');
  ctx.fillStyle = sand; ctx.fillRect(0, seaBotY - 1, W, H - seaBotY + 1);

  ctx.save();
  const sh = G.shake;
  ctx.translate(OX + (sh ? rnd(-sh, sh) : 0), OY + (sh ? rnd(-sh, sh) : 0));
  ctx.scale(S, S);

  // 구름
  drawCloud(120, 60, 34); drawCloud(560, 82, 44); drawCloud(840, 46, 28); drawCloud(330, 40, 22);

  // 바다
  const seaTop = geo.floor - 104, seaBot = geo.floor - 22;
  const sea = ctx.createLinearGradient(0, seaTop, 0, seaBot);
  sea.addColorStop(0, '#7dd3fc'); sea.addColorStop(.45, '#0ea5e9'); sea.addColorStop(1, '#0369a1');
  ctx.fillStyle = sea; ctx.fillRect(0, seaTop, geo.W, seaBot - seaTop);
  ctx.strokeStyle = 'rgba(255,255,255,.55)'; ctx.lineWidth = 1.4;
  for (let i = -20; i < geo.W + 40; i += 58) {
    const off = Math.sin((G.time + i) * 0.02) * 2;
    ctx.beginPath(); ctx.arc(i + 26, seaTop + 16 + off, 11, Math.PI, 0); ctx.stroke();
  }
  // 파도 거품
  ctx.fillStyle = 'rgba(255,255,255,.75)';
  ctx.beginPath();
  ctx.moveTo(0, seaBot);
  for (let x = 0; x <= geo.W; x += 26) {
    const yy = seaBot - 3 + Math.sin((x + G.time * 1.6) * 0.05) * 2.4;
    ctx.lineTo(x, yy);
  }
  ctx.lineTo(geo.W, seaBot + 6); ctx.lineTo(0, seaBot + 6);
  ctx.closePath(); ctx.fill();

  // 모래사장
  const sg = ctx.createLinearGradient(0, seaBot, 0, geo.H);
  sg.addColorStop(0, '#fde047'); sg.addColorStop(.22, '#eab308'); sg.addColorStop(1, '#b45309');
  ctx.fillStyle = sg; ctx.fillRect(0, seaBot, geo.W, geo.H - seaBot);
  // 모래 결
  ctx.save();
  ctx.strokeStyle = 'rgba(120,53,15,.10)'; ctx.lineWidth = 2;
  for (let y = seaBot + 26; y < geo.H; y += 30) {
    ctx.beginPath();
    for (let x = 0; x <= geo.W; x += 40) {
      const yy = y + Math.sin((x + y * 3) * 0.012) * 3;
      if (x === 0) ctx.moveTo(x, yy); else ctx.lineTo(x, yy);
    }
    ctx.stroke();
  }
  ctx.restore();

  // 팀 진영 틴트
  ctx.fillStyle = 'rgba(37,99,235,.13)';
  ctx.fillRect(0, seaBot, geo.netX - NETW / 2, geo.floor - seaBot + 6);
  ctx.fillStyle = 'rgba(220,38,38,.13)';
  ctx.fillRect(geo.netX + NETW / 2, seaBot, geo.netX - NETW / 2, geo.floor - seaBot + 6);

  // 바닥 라인
  ctx.strokeStyle = 'rgba(161,98,7,.85)'; ctx.lineWidth = 2;
  ctx.beginPath(); ctx.moveTo(0, geo.floor); ctx.lineTo(geo.W, geo.floor); ctx.stroke();
  // 코트 경계선
  ctx.strokeStyle = 'rgba(255,255,255,.55)'; ctx.lineWidth = 3;
  ctx.beginPath();
  ctx.moveTo(geo.netX - geo.W * 0.46, geo.floor); ctx.lineTo(geo.netX - geo.W * 0.46, geo.floor + 26);
  ctx.lineTo(geo.netX + geo.W * 0.46, geo.floor + 26); ctx.lineTo(geo.netX + geo.W * 0.46, geo.floor);
  ctx.stroke();

  drawNet();
  drawBeachProps();

  // 그림자
  for (const p of G.players) {
    const air = clamp((geo.floor - p.y) / 240, 0, 1);
    ctx.save(); ctx.globalAlpha = 0.2 - air * 0.1; ctx.fillStyle = '#000';
    ctx.beginPath(); ctx.ellipse(p.x, geo.floor - 2, 24 * (1 - air * 0.35), 7 * (1 - air * 0.3), 0, 0, TAU);
    ctx.fill(); ctx.restore();
  }

  // 피카츄 (아래쪽 먼저)
  G.players.slice().sort((a, b) => a.y - b.y).forEach((p) => drawPikachu(p));
  drawBall();
  drawEffects();
  ctx.restore();

  // 이름표 (화면 좌표로 또렷하게)
  drawTags();
  drawPops();

  // HUD 쿨다운
  updateCdUI();
}

function drawCloud(x, y, r) {
  ctx.fillStyle = 'rgba(255,255,255,.82)';
  ctx.beginPath();
  ctx.arc(x, y, r, 0, TAU);
  ctx.arc(x + r * 0.72, y - r * 0.22, r * 0.8, 0, TAU);
  ctx.arc(x + r * 1.42, y, r * 0.62, 0, TAU);
  ctx.fill();
}

function drawNet() {
  const x = geo.netX, top = geo.netTop, bot = geo.floor;
  ctx.fillStyle = '#78350f';
  ctx.fillRect(x - 4, top - 12, 8, bot - top + 12);
  ctx.fillStyle = 'rgba(255,255,255,.88)';
  ctx.fillRect(x - NETW / 2, top, NETW, bot - top);
  ctx.strokeStyle = '#475569'; ctx.lineWidth = 1;
  ctx.strokeRect(x - NETW / 2, top, NETW, bot - top);
  for (let y = top; y <= bot; y += 13) {
    ctx.beginPath(); ctx.moveTo(x - NETW / 2, y); ctx.lineTo(x + NETW / 2, y); ctx.stroke();
  }
  ctx.fillStyle = '#f8fafc';
  ctx.fillRect(x - NETW / 2 - 3, top - 4, NETW + 6, 6);
}

function roundRect(x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.lineTo(x + w - r, y); ctx.quadraticCurveTo(x + w, y, x + w, y + r);
  ctx.lineTo(x + w, y + h - r); ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  ctx.lineTo(x + r, y + h); ctx.quadraticCurveTo(x, y + h, x, y + h - r);
  ctx.lineTo(x, y + r); ctx.quadraticCurveTo(x, y, x + r, y);
  ctx.closePath();
}

function drawPikachu(p) {
  const teamColor = p.team === 'blue' ? '#2563eb' : '#dc2626';
  const bodyColor = p.team === 'blue' ? '#facc15' : '#fbc531';
  const bob = p.onGround && Math.abs(p.vx) > 0.5 ? Math.sin(p.runT) * 1.8 : 0;

  ctx.save();
  ctx.translate(p.x, p.y + bob);
  if (p.facing === -1) ctx.scale(-1, 1);

  if (p.isDiving) { ctx.rotate(0.95); ctx.translate(14, 12); }

  // 꼬리
  ctx.strokeStyle = '#ca8a04'; ctx.lineWidth = 5; ctx.lineJoin = 'round';
  ctx.beginPath();
  ctx.moveTo(-16, -30); ctx.lineTo(-32, -45); ctx.lineTo(-24, -55); ctx.lineTo(-42, -76);
  ctx.stroke();

  // 몸통
  ctx.fillStyle = bodyColor;
  ctx.beginPath(); ctx.ellipse(0, -28, 22, 26, 0, 0, TAU); ctx.fill();
  ctx.strokeStyle = '#854d0e'; ctx.lineWidth = 1.5; ctx.stroke();

  // 팀 저지 밴드
  ctx.save();
  ctx.beginPath(); ctx.ellipse(0, -28, 22, 26, 0, 0, TAU); ctx.clip();
  ctx.fillStyle = teamColor;
  ctx.fillRect(-24, -34, 48, 9);
  ctx.restore();

  // 머리
  ctx.beginPath(); ctx.arc(0, -48, 18, 0, TAU); ctx.fill(); ctx.stroke();

  // 귀
  ctx.beginPath(); ctx.moveTo(-8, -62); ctx.lineTo(-21, -89); ctx.lineTo(-4, -64); ctx.fill(); ctx.stroke();
  ctx.fillStyle = '#000';
  ctx.beginPath(); ctx.moveTo(-14, -77); ctx.lineTo(-21, -89); ctx.lineTo(-10, -79); ctx.fill();
  ctx.fillStyle = bodyColor;
  ctx.beginPath(); ctx.moveTo(8, -62); ctx.lineTo(25, -86); ctx.lineTo(14, -62); ctx.fill(); ctx.stroke();
  ctx.fillStyle = '#000';
  ctx.beginPath(); ctx.moveTo(17, -75); ctx.lineTo(25, -86); ctx.lineTo(21, -73); ctx.fill();

  // 눈
  const blink = (Math.sin(p.animT * 0.6) > 0.985);
  ctx.fillStyle = '#000';
  if (blink) {
    ctx.fillRect(5, -50, 5, 1.6);
  } else {
    ctx.beginPath(); ctx.arc(7, -50, 3, 0, TAU); ctx.fill();
    ctx.fillStyle = '#fff'; ctx.beginPath(); ctx.arc(8, -51, 1.2, 0, TAU); ctx.fill();
  }
  // 볼
  ctx.fillStyle = '#ef4444';
  ctx.beginPath(); ctx.arc(9, -43, 5, 0, TAU); ctx.fill();
  // 코·입
  ctx.fillStyle = '#000';
  ctx.fillRect(13, -47, 2, 2);
  ctx.strokeStyle = '#000'; ctx.lineWidth = 1.6;
  ctx.beginPath(); ctx.arc(10, -42, 3, 0, Math.PI * 0.7); ctx.stroke();

  // 발
  ctx.fillStyle = bodyColor;
  ctx.beginPath(); ctx.ellipse(-10, -5, 8, 5, 0, 0, TAU); ctx.ellipse(10, -5, 8, 5, 0, 0, TAU);
  ctx.fill(); ctx.stroke();

  // 머리띠 (팀 색) — 2대2에서 팀 구분 핵심
  ctx.fillStyle = teamColor;
  ctx.beginPath();
  ctx.moveTo(-17, -60); ctx.lineTo(17, -60); ctx.lineTo(17, -53); ctx.lineTo(-17, -53);
  ctx.closePath(); ctx.fill();
  ctx.strokeStyle = 'rgba(255,255,255,.75)'; ctx.lineWidth = 1;
  ctx.beginPath(); ctx.moveTo(-16, -56.5); ctx.lineTo(16, -56.5); ctx.stroke();
  // 머리띠 끝자락
  ctx.fillStyle = teamColor;
  ctx.beginPath(); ctx.moveTo(-16, -58); ctx.lineTo(-25, -50); ctx.lineTo(-20, -46); ctx.lineTo(-13, -56);
  ctx.closePath(); ctx.fill();

  // 강스매시 오라
  if (p.smashPose > 0) {
    const a = p.smashPose / 30;
    ctx.save();
    ctx.globalAlpha = 0.35 + a * 0.55;
    ctx.strokeStyle = '#facc15'; ctx.lineWidth = 3;
    ctx.beginPath(); ctx.arc(0, -36, 40 + (1 - a) * 12, 0, TAU); ctx.stroke();
    ctx.globalAlpha = 0.9;
    ctx.strokeStyle = '#fef08a'; ctx.lineWidth = 3.4;
    ctx.beginPath();
    ctx.moveTo(-2, -86); ctx.lineTo(6, -74); ctx.lineTo(-2, -74); ctx.lineTo(6, -62);
    ctx.stroke();
    ctx.restore();
  }
  // 페인트 드롭샷 표시
  if (p.feintArm > 0 && p.feintArm % 10 < 5) {
    ctx.save(); ctx.globalAlpha = 0.75; ctx.fillStyle = '#c084fc';
    ctx.beginPath(); ctx.arc(0, -97, 3.4, 0, TAU); ctx.fill(); ctx.restore();
  }
  ctx.restore();
}


function drawBeachProps() {
  if (geo.H - geo.floor < 240) return;   // 세로 화면의 넓은 모래사장만
  const py = geo.floor + 148;
  ctx.save();
  // 파라솔
  const px = geo.W * 0.13;
  ctx.strokeStyle = '#7c2d12'; ctx.lineWidth = 7;
  ctx.beginPath(); ctx.moveTo(px, py); ctx.lineTo(px, py - 96); ctx.stroke();
  ctx.fillStyle = '#ef4444';
  ctx.beginPath(); ctx.moveTo(px - 68, py - 96); ctx.quadraticCurveTo(px, py - 160, px + 68, py - 96);
  ctx.closePath(); ctx.fill();
  ctx.fillStyle = '#fff';
  ctx.beginPath(); ctx.moveTo(px - 34, py - 96);
  ctx.quadraticCurveTo(px - 18, py - 136, px, py - 148);
  ctx.quadraticCurveTo(px + 18, py - 136, px + 34, py - 96);
  ctx.closePath(); ctx.fill();
  // 비치 매트 (비스듬히 눕힌 모양)
  const tx = geo.W * 0.56, ty = geo.floor + 34;
  ctx.save();
  ctx.beginPath();
  ctx.moveTo(tx, ty); ctx.lineTo(tx + 92, ty + 12);
  ctx.lineTo(tx + 74, ty + 68); ctx.lineTo(tx - 18, ty + 54);
  ctx.closePath();
  ctx.fillStyle = '#38bdf8'; ctx.fill();
  ctx.save(); ctx.clip();
  ctx.strokeStyle = 'rgba(255,255,255,.85)'; ctx.lineWidth = 7;
  for (let k = -1; k < 5; k++) {
    ctx.beginPath(); ctx.moveTo(tx + k * 24, ty - 10); ctx.lineTo(tx + k * 24 + 16, ty + 80); ctx.stroke();
  }
  ctx.restore(); ctx.restore();
  // 공
  ctx.fillStyle = '#fff'; ctx.strokeStyle = '#0f172a'; ctx.lineWidth = 2;
  ctx.beginPath(); ctx.arc(geo.W * 0.33, py + 8, 15, 0, TAU); ctx.fill(); ctx.stroke();
  ctx.fillStyle = '#ef4444';
  ctx.beginPath(); ctx.arc(geo.W * 0.33, py + 8, 15, -Math.PI * 0.9, -Math.PI * 0.1); ctx.fill();
  ctx.restore();
}

function drawTags() {
  const fs = portrait ? 10 : 10.5;
  ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  for (const p of G.players) {
    const sx = OX + p.x * S, sy = OY + (p.y - 104) * S;
    const label = (p.slot + 1) + '.' + (p.name.length > 6 ? p.name.slice(0, 6) : p.name);
    ctx.font = `800 ${fs}px -apple-system,"Noto Sans KR",sans-serif`;
    const w = ctx.measureText(label).width + 12;
    ctx.fillStyle = p.team === 'blue' ? 'rgba(29,78,216,.85)' : 'rgba(185,28,28,.85)';
    roundRect(sx - w / 2, sy - fs * 0.85, w, fs * 1.7, 5); ctx.fill();
    if (p.isLocal) { ctx.strokeStyle = 'rgba(253,224,71,.95)'; ctx.lineWidth = 1.4; ctx.stroke(); }
    ctx.fillStyle = '#fff';
    ctx.fillText(label, sx, sy + 0.5);
  }
}

function drawBall() {
  const b = G.ball;
  ctx.save();
  ctx.translate(b.x, b.y);

  if (b.fiery) {
    ctx.save();
    ctx.globalAlpha = 0.45;
    for (let i = 1; i <= 3; i++) {
      ctx.fillStyle = i % 2 ? '#fbbf24' : '#f97316';
      ctx.beginPath();
      ctx.arc(-b.vx * i * 1.4, -b.vy * i * 1.4, b.r * (1 - i * 0.18), 0, TAU);
      ctx.fill();
    }
    ctx.restore();
  }
  ctx.rotate(b.rot);
  ctx.shadowColor = b.fiery ? 'rgba(239,68,68,.9)' : 'rgba(0,0,0,.3)';
  ctx.shadowBlur = b.fiery ? 18 : 4;

  ctx.beginPath(); ctx.arc(0, 0, b.r, 0, TAU);
  ctx.fillStyle = '#fff'; ctx.fill();
  ctx.strokeStyle = '#000'; ctx.lineWidth = 2; ctx.stroke();

  ctx.beginPath(); ctx.arc(0, 0, b.r, Math.PI, 0);
  ctx.fillStyle = b.fiery ? '#f97316' : '#ef4444'; ctx.fill(); ctx.stroke();

  ctx.fillStyle = '#000'; ctx.fillRect(-b.r, -2, b.r * 2, 4);
  ctx.beginPath(); ctx.arc(0, 0, 5, 0, TAU); ctx.fillStyle = '#fff'; ctx.fill(); ctx.stroke();
  ctx.beginPath(); ctx.arc(0, 0, 2.5, 0, TAU); ctx.stroke();
  ctx.restore();
}

function drawEffects() {
  for (const p of G.effects) {
    ctx.save();
    ctx.globalAlpha = Math.max(0, p.life);
    if (p.type === 'spark') {
      ctx.fillStyle = '#fef08a'; ctx.shadowColor = '#facc15'; ctx.shadowBlur = 8;
      ctx.fillRect(p.x, p.y, p.size * 1.4, p.size * 1.4);
    } else {
      ctx.fillStyle = '#eab308';
      ctx.beginPath(); ctx.arc(p.x, p.y, p.size, 0, TAU); ctx.fill();
    }
    ctx.restore();
  }
}

function drawPops() {
  if (!G.pops.length) return;
  ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  G.pops.forEach((q, i) => {
    const sx = OX + q.x * S, sy = OY + q.y * S - 14;
    ctx.save();
    ctx.globalAlpha = clamp(q.life * 1.6, 0, 1);
    ctx.font = `900 ${portrait ? 17 : 18}px -apple-system,"Noto Sans KR",sans-serif`;
    ctx.lineWidth = 4; ctx.strokeStyle = 'rgba(8,15,30,.9)';
    ctx.strokeText(q.text, sx, sy - i * 2);
    ctx.fillStyle = q.color;
    ctx.fillText(q.text, sx, sy - i * 2);
    ctx.restore();
  });
}

/* ---------------- HUD ---------------- */
function refreshHud() {
  $('sb-blue').textContent = G.score.blue;
  $('sb-red').textContent = G.score.red;
  $('nb').textContent = G.teamSize > 1 ? '파랑팀' : '파랑';
  $('nr').textContent = G.teamSize > 1 ? '빨강팀' : '빨강';
  const mini = $('roster-mini');
  mini.innerHTML = '';
  G.players.forEach((p) => {
    const s = document.createElement('span');
    s.className = p.team + (p.isLocal ? ' me' : '');
    s.textContent = `${p.slot + 1} ${p.name}${p.isLocal ? ' (나)' : ''}`;
    mini.appendChild(s);
  });
}
let toastTimer = null;
function toast(t) {
  const el = $('toast');
  el.textContent = t; el.classList.add('on');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('on'), 1800);
}
let centerTimer = null;
function showCenter(t) {
  const el = $('center-msg');
  el.textContent = t; el.classList.add('on');
  clearTimeout(centerTimer);
  centerTimer = setTimeout(() => el.classList.remove('on'), 1100);
}
function updateCdUI() {
  const me = myPlayer();
  const set = (id, v, max) => {
    const el = document.querySelector('#' + id + ' .cd');
    const b = document.getElementById(id);
    if (!el || !b) return;
    const p = me ? clamp(v / max, 0, 1) : 0;
    el.style.height = (p * 100) + '%';
    b.classList.toggle('ready', p <= 0);
  };
  set('b-smash', me ? me.smashCd : 0, SKILL.smash);
  set('b-dive', me ? me.diveCd : 0, SKILL.dive);
  set('b-feint', me ? me.feintCd : 0, SKILL.feint);
}

/* ---------------- 루프 ---------------- */
let lastT = 0, acc = 0;
const STEP = 1000 / 60;
function loop(now) {
  if (!lastT) lastT = now;
  let dt = now - lastT; lastT = now;
  if (dt > 220) dt = 220;
  acc += dt;
  let guard = 0;
  while (acc >= STEP && guard++ < 4) {
    G.time++;
    readKeys();
    if (G.phase === 'demo') stepDemo();
    else if (G.online && !G.isHost) stepGuest();
    else if (G.phase === 'play' || G.phase === 'over') stepHost();
    acc -= STEP;
  }
  render();
  requestAnimationFrame(loop);
}

/* =========================================================================
   🌐 온라인 (MQTT · paho)
   ========================================================================= */
const BROKERS = [
  { tag: 'A', host: 'broker.emqx.io', port: 8084, path: '/mqtt' },
  { tag: 'B', host: 'broker.hivemq.com', port: 8884, path: '/mqtt' },
  { tag: 'C', host: 'test.mosquitto.org', port: 8081, path: '/' },
];
const TOPIC = (code) => 'pika2v2/v1/room/' + code;

const net = {
  client: null, ep: 0, code: null, isHost: false, connected: false,
  roster: [], hostId: null, started: false, size: 2,
  joinTimer: null, lobbyTimer: null, retry: null, fails: 0,
  pend: { x: 0, j: false, sm: false, d: false, f: false }, lastTx: 0,
  lastState: null,
};

function publish(obj) {
  if (!net.client || !net.connected || !net.code) return;
  obj.from = G.myId;
  try {
    const m = new Paho.MQTT.Message(JSON.stringify(obj));
    m.destinationName = TOPIC(net.code);
    m.qos = 0;
    net.client.send(m);
  } catch (e) {}
}

function netConnect() {
  netDisconnect(false);
  const ep = BROKERS[net.ep] || BROKERS[0];
  const cid = 'pk2_' + Math.random().toString(36).slice(2, 10);
  let cli;
  try {
    cli = ep.path ? new Paho.MQTT.Client(ep.host, ep.port, ep.path, cid)
                  : new Paho.MQTT.Client(ep.host, ep.port, cid);
  } catch (e) { toast('온라인 연결을 시작할 수 없어요'); return; }
  net.client = cli;
  cli.onConnectionLost = () => {
    net.connected = false;
    if (net.code) { toast('연결 끊김 · 다시 연결 중'); scheduleReconnect(); }
  };
  cli.onMessageArrived = (m) => {
    let d = null;
    try { d = JSON.parse(m.payloadString); } catch (e) { return; }
    if (!d || d.from === G.myId) return;
    handleNet(d);
  };
  cli.connect({
    useSSL: true, timeout: 8, keepAliveInterval: 30, cleanSession: true,
    onSuccess: () => {
      net.connected = true; net.fails = 0;
      cli.subscribe(TOPIC(net.code), { qos: 0 });
      $('net-hint').textContent = '';
      afterConnect();
    },
    onFailure: (err) => {
      net.connected = false; net.fails++;
      if (net.fails >= 2 && net.isHost && net.ep < BROKERS.length - 1) {
        net.ep++; net.fails = 0;
        const digits = net.code.slice(1);
        net.code = BROKERS[net.ep].tag + digits;
        $('room-code').textContent = net.code;
        toast('서버 변경 · 새 코드 ' + net.code);
      } else if (net.fails >= 3) {
        toast('온라인 서버 연결 실패');
        $('net-hint').textContent = '서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요. (AI 대전은 바로 됩니다)';
        return;
      }
      scheduleReconnect();
    },
  });
}
function scheduleReconnect() {
  clearTimeout(net.retry);
  net.retry = setTimeout(() => { if (net.code) netConnect(); }, 2000);
}
function netDisconnect(hard) {
  clearInterval(net.joinTimer); clearInterval(net.lobbyTimer);
  net.joinTimer = net.lobbyTimer = null;
  clearTimeout(net.retry);
  if (net.client) {
    try { if (net.client.isConnected()) net.client.disconnect(); } catch (e) {}
    net.client = null;
  }
  net.connected = false;
  if (hard) { net.code = null; net.started = false; }
}
function afterConnect() {
  if (net.isHost) {
    net.hostId = G.myId;
    publishLobby();
    clearInterval(net.lobbyTimer);
    net.lobbyTimer = setInterval(publishLobby, 2200);
  } else {
    let tries = 0;
    const sendJoin = () => {
      publish({ t: 'join', id: G.myId, name: G.myName });
      if (++tries > 24) clearInterval(net.joinTimer);
    };
    sendJoin();
    clearInterval(net.joinTimer);
    net.joinTimer = setInterval(sendJoin, 800);
  }
}
function publishLobby() {
  publish({ t: 'lobby', roster: net.roster, size: net.size, hostId: net.hostId, started: net.started });
}

function handleNet(d) {
  switch (d.t) {
    case 'join':
      if (!net.isHost) return;
      hostAccept(d);
      break;
    case 'lobby':
      if (net.isHost) return;
      net.roster = d.roster || [];
      net.size = d.size || net.size;
      net.hostId = d.hostId;
      net.started = !!d.started;
      clearInterval(net.joinTimer); net.joinTimer = null;
      renderRoom();
      break;
    case 'start':
      if (net.isHost) return;
      clearInterval(net.joinTimer); net.joinTimer = null;
      net.started = true;
      startOnlineMatch(d.roster || net.roster, d.size || net.size, false);
      break;
    case 'i':
      if (!net.isHost) return;
      hostInput(d);
      break;
    case 's':
      if (net.isHost) return;
      applyState(d);
      break;
    case 'end':
      if (net.isHost) return;
      if (G.phase === 'play') endMatch(d.winner);
      break;
    case 'rematch':
      if (!net.isHost) return;
      hostStart();
      break;
    case 'bye':
      if (!net.isHost) return;
      hostDrop(d.id);
      break;
    case 'busy':
      toast('이미 게임이 시작됐어요');
      showLobby();
      break;
    case 'full':
      toast('방이 가득 찼어요');
      showLobby();
      break;
    default: break;
  }
}

function hostAccept(d) {
  if (net.started) { publish({ t: 'busy' }); return; }
  if (net.roster.find((r) => r.id === d.id)) { publishLobby(); return; }
  const cap = net.size * 2;
  if (net.roster.filter((r) => !r.ai).length >= cap) { publish({ t: 'full' }); return; }
  const humansBlue = net.roster.filter((r) => r.team === 'blue' && !r.ai).length;
  const humansRed = net.roster.filter((r) => r.team === 'red' && !r.ai).length;
  const team = humansBlue <= humansRed ? 'blue' : 'red';
  net.roster.push({ id: d.id, name: (d.name || '친구').slice(0, 6), team, ai: false });
  publishLobby();
  renderRoom();
  toast('친구 입장!');
}
function hostDrop(id) {
  const i = net.roster.findIndex((r) => r.id === id);
  if (i >= 0) net.roster.splice(i, 1);
  publishLobby();
  renderRoom();
  if (net.started) {
    const p = G.players.find((q) => q.id === id);
    if (p) { p.isAI = true; p.isRemote = false; p.name = p.name + '(봇)'; }
  }
}

/* ---- 방 만들기 / 입장 ---- */
function createRoom(size) {
  net.isHost = true; net.ep = 0; net.started = false;
  net.size = size; net.fails = 0;
  const digits = String(Math.floor(100 + Math.random() * 900));
  net.code = BROKERS[0].tag + digits;
  net.roster = [{ id: G.myId, name: G.myName, team: 'blue', ai: false }];
  net.hostId = G.myId;
  G.online = true; G.isHost = true; G.mode = 'online'; G.teamSize = size;
  showRoom();
  netConnect();
}
function joinRoom(raw) {
  let code = String(raw || '').trim().toUpperCase().replace(/[^0-9A-Z]/g, '');
  if (!code) return;
  let ep = 0;
  if (/^[ABC]/.test(code[0])) {
    ep = BROKERS.findIndex((b) => b.tag === code[0]);
    code = code.slice(0, 4);
  } else {
    code = 'A' + code.replace(/[^0-9]/g, '').slice(0, 3);
  }
  if (code.length < 4) { toast('방 코드를 확인해 주세요'); return; }
  net.isHost = false; net.ep = ep < 0 ? 0 : ep; net.code = code;
  net.started = false; net.roster = []; net.hostId = null; net.fails = 0;
  G.online = true; G.isHost = false; G.mode = 'online';
  showRoom();
  netConnect();
}

/* ---- 온라인 경기 ---- */
function buildFullRoster() {
  const roster = net.roster.filter((r) => !r.ai).slice();
  const names = AI_NAMES;
  for (const team of ['blue', 'red']) {
    let have = roster.filter((r) => r.team === team).length;
    let k = 0;
    while (have < net.size) {
      roster.push({
        id: 'bot_' + team + '_' + have + '_' + Math.random().toString(36).slice(2, 5),
        name: 'AI ' + names[(have + k + (team === 'red' ? 2 : 0)) % names.length],
        team, ai: true,
      });
      have++; k++;
    }
  }
  return roster;
}
function startOnlineMatch(roster, size, isHost) {
  G.online = true; G.isHost = isHost; G.mode = 'online'; G.demo = false;
  G.teamSize = size; net.size = size;
  G.players = [];
  const byTeam = { blue: [], red: [] };
  roster.forEach((r) => { byTeam[r.team === 'red' ? 'red' : 'blue'].push(r); });
  ['blue', 'red'].forEach((team) => {
    byTeam[team].forEach((r, i) => {
      const mine = r.id === G.myId;
      G.players.push(makePlayer({
        id: r.id, name: mine ? G.myName : (r.name || '친구'),
        team, slot: i, isAI: !!r.ai, isLocal: mine && isHost, isRemote: !mine && !r.ai,
      }));
    });
  });
  beginPlay();
}
function hostStart() {
  if (!net.isHost) return;
  const roster = buildFullRoster();
  net.started = true;
  publish({ t: 'start', roster, size: net.size });
  renderRoom();
  startOnlineMatch(roster, net.size, true);
}
function sendInput() {
  if (net.isHost || !net.connected) return;
  const p = net.pend;
  p.x = Math.round((ui.stickX || 0) * 100) / 100;
  const now = performance.now();
  if (now - net.lastTx < 45) return;
  net.lastTx = now;
  publish({ t: 'i', id: G.myId, x: p.x, j: p.j, s: p.s, d: p.d, f: p.f });
  p.j = p.s = p.d = p.f = false;
  p.x = 0;
}
function hostInput(d) {
  const p = G.players.find((q) => q.id === d.id);
  if (!p) return;
  p.lastI = performance.now();
  p.input.x = clamp(+d.x || 0, -1, 1);
  p.input.jump = p.input.jump || !!d.j;
  p.input.smash = p.input.smash || !!d.s;
  p.input.dive = p.input.dive || !!d.d;
  p.input.feint = p.input.feint || !!d.f;
}
function sendState() {
  if (!net.connected) return;
  const fx = G.pendingFx.splice(0, G.pendingFx.length);
  publish({
    t: 's',
    p: G.players.map((p) => [
      p.id,
      Math.round((p.x / geo.W) * 1000),
      Math.round((p.y / geo.floor) * 1000),
      p.facing,
      (p.onGround ? 1 : 0) | (p.isDiving ? 2 : 0) | (p.smashPose > 0 ? 4 : 0) | (p.feintArm > 0 ? 8 : 0),
    ]),
    b: [Math.round((G.ball.x / geo.W) * 1000), Math.round((G.ball.y / geo.floor) * 1000), G.ball.fiery ? 1 : 0],
    sc: [G.score.blue, G.score.red],
    fx: fx.length ? fx : undefined,
  });
}
function applyState(d) {
  const prevScore = G.score.blue + ':' + G.score.red;
  (d.p || []).forEach((a) => {
    const p = G.players.find((q) => q.id === a[0]);
    if (!p) return;
    p.nx = a[1] / 1000; p.ny = a[2] / 1000;
    p.nf = a[3] || 1; p.nflags = a[4] || 0;
  });
  if (d.b) {
    G.ball.nx = d.b[0] / 1000; G.ball.ny = d.b[1] / 1000;
    G.ball.fiery = !!d.b[2];
  }
  if (d.sc) { G.score.blue = d.sc[0]; G.score.red = d.sc[1]; }
  if (prevScore !== G.score.blue + ':' + G.score.red) { refreshHud(); sfx.score(); }
  (d.fx || []).forEach((e) => {
    const x = (e.x != null ? e.x : 0.5) * geo.W, y = (e.y != null ? e.y : 0.5) * geo.floor;
    if (e.k === 'smash') { spawnParticles(x, y, 16, 'spark'); pop(x, y - 26, '⚡ 강스매시!', '#fde047'); G.shake = 12; }
    else if (e.k === 'save') { spawnParticles(x, y, 10, 'spark'); }
    else if (e.k === 'dive') { sfx.dive(); }
    else if (e.k === 'serve') { showCenter('서브!'); }
  });
  if (net.started === false) net.started = true;
}

/* =========================================================================
   UI 연결
   ========================================================================= */
function showLobby() {
  $('lobby').classList.remove('hidden');
  $('room').classList.add('hidden');
  $('result').classList.add('hidden');
  $('hud').classList.add('hidden');
  $('controls').classList.add('hidden');
  G.phase = 'demo';
  if (G.players.length !== 4 || !G.demo) setupDemo();
}
function showRoom() {
  $('lobby').classList.add('hidden');
  $('room').classList.remove('hidden');
  $('result').classList.add('hidden');
  $('hud').classList.add('hidden');
  $('controls').classList.add('hidden');
  $('room-code').textContent = net.code || '----';
  $('btn-room-start').classList.toggle('hidden', !net.isHost);
  $('room-wait').classList.toggle('hidden', net.isHost);
  document.querySelectorAll('#seg-size-room button').forEach((b) => {
    b.classList.toggle('on', +b.dataset.size === net.size);
    b.disabled = !net.isHost;
  });
  renderRoom();
}
function renderRoom() {
  const size = net.size || 2;
  ['blue', 'red'].forEach((team) => {
    const ul = $(team === 'blue' ? 'list-blue' : 'list-red');
    const cnt = $(team === 'blue' ? 'cnt-blue' : 'cnt-red');
    ul.innerHTML = '';
    const members = net.roster.filter((r) => r.team === team && !r.ai);
    members.forEach((r) => {
      const li = document.createElement('li');
      if (r.id === G.myId) li.className = 'me';
      const nm = document.createElement('span'); nm.className = 'nm';
      nm.textContent = (r.id === G.myId ? '나 (' + G.myName + ')' : r.name);
      li.appendChild(nm);
      if (r.id === net.hostId) { const i = document.createElement('i'); i.textContent = '방장'; li.appendChild(i); }
      ul.appendChild(li);
    });
    for (let i = members.length; i < size; i++) {
      const li = document.createElement('li'); li.className = 'bot';
      const nm = document.createElement('span'); nm.className = 'nm';
      nm.textContent = '➕ 빈 자리 · AI';
      li.appendChild(nm); ul.appendChild(li);
    }
    cnt.textContent = members.length + '/' + size;
  });
  $('room-hint').textContent = net.isHost
    ? '친구에게 코드를 알려 주세요. 빈 자리는 AI가 채웁니다.'
    : (net.started ? '곧 시작됩니다!' : '호스트가 시작하기를 기다리는 중…');
}

function setSeg(sel, attr, val) {
  document.querySelectorAll(sel + ' button').forEach((b) => b.classList.toggle('on', b.dataset[attr] === String(val)));
}

function wireUI() {
  $('nick').addEventListener('input', () => { G.myName = ($('nick').value.trim() || '지훈').slice(0, 6); });
  G.myName = ($('nick').value.trim() || '지훈').slice(0, 6);

  document.querySelectorAll('.tab').forEach((t) => {
    t.addEventListener('click', () => {
      document.querySelectorAll('.tab').forEach((x) => x.classList.toggle('active', x === t));
      $('panel-ai').classList.toggle('hidden', t.dataset.tab !== 'ai');
      $('panel-online').classList.toggle('hidden', t.dataset.tab !== 'online');
    });
  });

  document.querySelectorAll('#seg-size-ai button').forEach((b) => {
    b.addEventListener('click', () => { G.teamSize = +b.dataset.size; setSeg('#seg-size-ai', 'size', G.teamSize); });
  });
  document.querySelectorAll('#seg-diff button').forEach((b) => {
    b.addEventListener('click', () => { G.diff = b.dataset.diff; setSeg('#seg-diff', 'diff', G.diff); });
  });
  document.querySelectorAll('#seg-size-online button').forEach((b) => {
    b.addEventListener('click', () => {
      G.teamSize = +b.dataset.size; setSeg('#seg-size-online', 'size', G.teamSize);
      $('btn-create').textContent = `🔧 방 만들기 (${G.teamSize} vs ${G.teamSize})`;
    });
  });
  document.querySelectorAll('#seg-size-room button').forEach((b) => {
    b.addEventListener('click', () => {
      if (!net.isHost) return;
      net.size = +b.dataset.size;
      setSeg('#seg-size-room', 'size', net.size);
      publishLobby(); renderRoom();
    });
  });

  $('btn-ai-start').addEventListener('click', () => {
    sfx.init();
    G.myName = ($('nick').value.trim() || '지훈').slice(0, 6);
    const mode = document.querySelector('.tab.active').dataset.tab;
    if (mode === 'online') return;
    startAiMatch(G.teamSize, G.diff);
  });
  $('btn-create').addEventListener('click', () => {
    sfx.init();
    G.myName = ($('nick').value.trim() || '지훈').slice(0, 6);
    createRoom(G.teamSize);
  });
  $('btn-join').addEventListener('click', () => {
    sfx.init();
    G.myName = ($('nick').value.trim() || '지훈').slice(0, 6);
    joinRoom($('join-code').value);
  });
  $('btn-copy').addEventListener('click', () => {
    const url = location.origin + location.pathname + '?room=' + net.code;
    const done = () => {
      $('copy-toast').classList.add('on');
      setTimeout(() => $('copy-toast').classList.remove('on'), 2200);
    };
    if (navigator.share) { navigator.share({ title: '⚡ 피카츄 배구 2 vs 2', url }).catch(() => {}); }
    if (navigator.clipboard) navigator.clipboard.writeText(url).then(done).catch(() => {});
  });
  $('btn-room-start').addEventListener('click', () => { sfx.init(); hostStart(); });
  $('btn-room-leave').addEventListener('click', () => { netDisconnect(true); showLobby(); });
  $('btn-exit').addEventListener('click', () => {
    if (G.online) { publish({ t: 'bye', id: G.myId }); netDisconnect(true); }
    else netDisconnect(true);
    showLobby();
  });
  $('btn-again').addEventListener('click', () => {
    $('result').classList.add('hidden');
    if (G.online) {
      if (G.isHost) hostStart();
      else { publish({ t: 'rematch' }); showRoom(); }
    } else {
      startAiMatch(G.teamSize, G.diff);
    }
  });
  $('btn-back').addEventListener('click', () => {
    if (G.online) { publish({ t: 'bye', id: G.myId }); }
    $('result').classList.add('hidden');
    if (G.online) { netDisconnect(true); }
    showLobby();
  });
  $('btn-sound').addEventListener('click', () => {
    sfx.init();
    sfx.enabled = !sfx.enabled;
    $('btn-sound').textContent = sfx.enabled ? '🔊' : '🔇';
  });
}

/* ---------------- 시작 ---------------- */
function boot() {
  cv = $('cv');
  ctx = cv.getContext('2d');
  layout();
  setupDemo();
  bindKeys();
  bindTouch();
  wireUI();
  window.addEventListener('resize', () => { layout(); });
  window.addEventListener('orientationchange', () => setTimeout(layout, 220));
  const room = new URLSearchParams(location.search).get('room');
  if (room) { G.myName = '친구'; joinRoom(room); }
  requestAnimationFrame(loop);
}

window.PIKA = {
  G, geo, net, ui, startAiMatch, createRoom, joinRoom, hostStart,
  state: () => ({
    phase: G.phase, score: { ...G.score }, online: G.online, isHost: G.isHost, stats: { ...G.stats },
    portrait, S,
    players: G.players.map((p) => ({ id: p.id, name: p.name, team: p.team, x: Math.round(p.x), y: Math.round(p.y), ai: p.isAI, local: p.isLocal })),
    ball: { x: Math.round(G.ball.x), y: Math.round(G.ball.y), fiery: G.ball.fiery },
  }),
};

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
else boot();
