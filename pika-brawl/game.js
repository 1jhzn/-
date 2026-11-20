/* ==================== ⚡ 피카츄 배구 BRAWL — 게임 엔진 ==================== */
'use strict';
(function () {
  const $ = s => document.getElementById(s);
  const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
  const lerp = (a, b, t) => a + (b - a) * t;
  const rnd = (a, b) => a + Math.random() * (b - a);
  const TAU = Math.PI * 2;
  const { BRAWLERS, BKEYS, drawBrawler, drawBall } = window.Brawl;

  /* 튜닝 파라미터 (URL 쿼리로 덮어쓸 수 있음 — 개발/밸런싱용) */
  const Q = new URLSearchParams(location.search);
  const num = (k, d) => { const v = parseFloat(Q.get(k)); return isNaN(v) ? d : v; };
  const WIN_SCORE = num('win', 10);
  const TUNE = {
    spikeT: num('spikeT', 0.26),
    spikeF0: num('spikeF0', 0.28),
    spikeF1: num('spikeF1', 0.58),
    pad: num('pad', 0.09),
    rx: num('rx', 1),
    bump: num('bump', 1.15)
  };
  window.__TUNE = TUNE;
  const TEAMCOL = {
    blue: { main: '#2f8dff', dark: '#1258b8', glow: '#8ec8ff', name: '파랑팀' },
    red: { main: '#ff3b55', dark: '#b2122c', glow: '#ffa3b0', name: '빨강팀' }
  };
  const other = t => (t === 'blue' ? 'red' : 'blue');
  const AI_NAMES = ['번개맨', '스파크', '전기쥐', '번개소년', '자석이', '찌릿찌릿'];

  /* ---------------- 저장 ---------------- */
  const STORE = 'pikaBrawl.v1';
  const Save = {
    data: { trophies: 0, wins: 0, streak: 0, best: 0 },
    load() {
      try { Object.assign(this.data, JSON.parse(localStorage.getItem(STORE) || '{}')); } catch (e) {}
    },
    save() { try { localStorage.setItem(STORE, JSON.stringify(this.data)); } catch (e) {} }
  };
  Save.load();

  /* ---------------- 사운드 ---------------- */
  const Sfx = {
    ctx: null, master: null, muted: false, bgmTimer: null, step: 0,
    ensure() {
      if (this.ctx) return this.ctx;
      const AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return null;
      this.ctx = new AC();
      this.master = this.ctx.createGain();
      this.master.gain.value = 0.55;
      this.master.connect(this.ctx.destination);
      return this.ctx;
    },
    resume() { const c = this.ensure(); if (c && c.state === 'suspended') c.resume(); },
    tone(f, dur, type, vol, slide) {
      if (this.muted) return;
      const c = this.ensure(); if (!c) return;
      const o = c.createOscillator(), g = c.createGain();
      o.type = type || 'square';
      o.frequency.setValueAtTime(f, c.currentTime);
      if (slide) o.frequency.exponentialRampToValueAtTime(Math.max(30, slide), c.currentTime + dur);
      g.gain.setValueAtTime(vol == null ? 0.16 : vol, c.currentTime);
      g.gain.exponentialRampToValueAtTime(0.0008, c.currentTime + dur);
      o.connect(g); g.connect(this.master);
      o.start(); o.stop(c.currentTime + dur + 0.02);
    },
    noise(dur, vol, hp) {
      if (this.muted) return;
      const c = this.ensure(); if (!c) return;
      const n = Math.floor(c.sampleRate * dur);
      const buf = c.createBuffer(1, n, c.sampleRate);
      const d = buf.getChannelData(0);
      for (let i = 0; i < n; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / n);
      const src = c.createBufferSource(); src.buffer = buf;
      const f = c.createBiquadFilter(); f.type = 'highpass'; f.frequency.value = hp || 700;
      const g = c.createGain(); g.gain.value = vol == null ? 0.2 : vol;
      src.connect(f); f.connect(g); g.connect(this.master); src.start();
    },
    hit(power) { this.tone(200 + power * 40, 0.07, 'triangle', 0.2, 120); },
    spike() { this.noise(0.14, 0.22, 500); this.tone(520, 0.12, 'sawtooth', 0.18, 160); },
    jump() { this.tone(420, 0.07, 'sine', 0.1, 640); },
    ult(kind) {
      this.noise(0.3, 0.25, 300);
      if (kind === 'fire') this.tone(180, 0.35, 'sawtooth', 0.24, 70);
      else if (kind === 'water') this.tone(700, 0.35, 'sine', 0.22, 200);
      else this.tone(900, 0.3, 'square', 0.22, 180);
    },
    score() { [523, 659, 784, 1046].forEach((f, i) => setTimeout(() => this.tone(f, 0.14, 'square', 0.16), i * 70)); },
    lose() { [400, 320, 240].forEach((f, i) => setTimeout(() => this.tone(f, 0.2, 'triangle', 0.16), i * 110)); },
    start() { this.resume(); this.bgm(); },
    bgm() {
      if (this.muted) return;
      this.ensure(); if (!this.ctx || this.bgmTimer) return;
      const mel = [523, 659, 784, 659, 523, 587, 659, 523, 440, 523, 659, 587];
      this.bgmTimer = setInterval(() => {
        if (this.muted) return;
        const f = mel[this.step % mel.length];
        this.tone(f, 0.14, 'triangle', 0.045);
        if (this.step % 4 === 0) this.tone(f / 2, 0.2, 'sine', 0.05);
        this.step++;
      }, 190);
    },
    stopBgm() { if (this.bgmTimer) { clearInterval(this.bgmTimer); this.bgmTimer = null; } }
  };

  /* ---------------- 상태 ---------------- */
  const G = {
    phase: 'menu',
    mode: 'ai',
    size: 2,
    diff: 'normal',
    myBrawler: 'pika',
    players: [],
    ball: { x: 0, y: 0, vx: 0, vy: 0, r: 10, trail: [] },
    score: { blue: 0, red: 0 },
    myIndex: 0,
    myId: 'p_' + Math.random().toString(36).slice(2, 8),
    frame: 0, time: 0, shake: 0, freeze: 0,
    effects: [], pops: [],
    online: false, isHost: false, onlineView: false, pendingFx: [],
    winner: null, lastServe: 'blue',
    input: { x: 0, jump: false, atk: false, ult: false },
    keys: {},
    rally: 0,
    demo: { x: 0, y: 0, vx: 0, vy: 0 },
    mvp: ''
  };
  const myTeam = () => (G.players[G.myIndex] ? G.players[G.myIndex].team : 'blue');

  /* ---------------- 캔버스/레이아웃 ---------------- */
  let cv, ctx, CW = 400, CH = 700, DPR = 1, floorY = 0, netX = 0, netTop = 0, netThick = 8, portrait = true;
  let CHc = 700, VY = 0, worldTop = 0, worldBottom = 700; // 코트(월드) 세로 크기와 화면 오프셋
  const P = {};

  function computeParams() {
    const base = Math.min(CW, CHc * 1.55);
    P.base = base;
    P.pr = clamp(base * 0.045, 10, 30);
    P.br = clamp(base * 0.024, 6, 17);
    P.gB = CHc * 3.0;
    P.gP = CHc * 5.2;
    P.run = base * 1.30;
    P.jumpV = CHc * 1.72;
    P.pad = base * TUNE.pad;
  }
  function layout() {
    const rect = cv.getBoundingClientRect();
    CW = Math.max(240, Math.round(rect.width));
    CH = Math.max(300, Math.round(rect.height));
    DPR = Math.min(2, window.devicePixelRatio || 1);
    cv.width = Math.round(CW * DPR);
    cv.height = Math.round(CH * DPR);
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    portrait = CH > CW * 1.05;
    // 세로 화면에서는 코트를 가로로 넓은 띠 모양으로 배치해 빈 하늘을 없앤다
    CHc = portrait ? Math.min(CH * 0.62, CW * 0.95) : CH;
    VY = portrait ? (CH - CHc) * 0.60 : 0;
    worldTop = -VY;
    worldBottom = CH - VY;
    floorY = CHc * (portrait ? 0.82 : 0.845);
    netX = CW / 2;
    netThick = Math.max(5, CW * 0.011);
    computeParams();
    netTop = floorY - clamp(P.base * 0.19, 34, CHc * 0.34);
    G.players.forEach(p => { p.homeX = homeXFor(p); });
    G.ball.r = P.br;
    G.ball.y = Math.min(G.ball.y, floorY - P.br);
    G.demo.y = Math.min(G.demo.y, floorY - P.br);
  }

  function halfBounds(team) {
    const pr = P.pr;
    return team === 'blue'
      ? { min: pr, max: netX - netThick / 2 - pr }
      : { min: netX + netThick / 2 + pr, max: CW - pr };
  }
  function homeXFor(p) {
    const mates = G.players.filter(q => q.team === p.team);
    const n = Math.max(1, mates.length);
    const i = Math.max(0, mates.indexOf(p));
    const fracs = n === 1 ? [0.5] : n === 2 ? [0.36, 0.68] : [0.30, 0.54, 0.80];
    const d = fracs[clamp(i, 0, fracs.length - 1)];
    const half = netX;
    return p.team === 'blue' ? netX - half * d : netX + half * d;
  }

  /* ---------------- 플레이어 ---------------- */
  function makePlayer(o) {
    const brawler = o.brawler || 'pika';
    const p = {
      id: o.id || 'p' + Math.random().toString(36).slice(2, 7),
      name: o.name || '플레이어',
      team: o.team === 'red' ? 'red' : 'blue',
      brawler,
      isAI: !!o.isAI,
      isLocal: !!o.isLocal,
      isView: !!o.isView,
      x: 0, y: 0, vx: 0, vy: 0,
      onGround: true, facing: o.team === 'red' ? -1 : 1,
      ult: 0, atkCd: 0, hitCd: 0, jumpCd: 0, poseT: 0,
      input: { x: 0, jump: false, atk: false, ult: false },
      homeX: 0, pts: 0, animT: rnd(0, 6), aiErr: 0, aiT: 0
    };
    return p;
  }
  function placePlayer(p) {
    p.homeX = homeXFor(p);
    p.x = p.homeX;
    p.y = floorY - P.pr;
    p.vy = 0; p.onGround = true;
  }
  function buildTeam(size, team, opts) {
    const out = [];
    for (let i = 0; i < size; i++) {
      const o = Object.assign({ team }, opts(i));
      out.push(makePlayer(o));
    }
    return out;
  }

  /* ---------------- 데모(로비 배경) ---------------- */
  function setupDemo() {
    G.players = [];
    G.players.push(makePlayer({ name: '지훈', team: 'blue', brawler: G.myBrawler, isAI: false }));
    G.players.push(makePlayer({ name: '번개맨', team: 'blue', brawler: 'squirtle', isAI: true }));
    G.players.push(makePlayer({ name: '파이리왕', team: 'red', brawler: 'charmander', isAI: true }));
    G.players.push(makePlayer({ name: '스파크', team: 'red', brawler: 'pika', isAI: true }));
    G.players.forEach(placePlayer);
    G.demo.x = netX * 0.7; G.demo.y = floorY - CHc * 0.5; G.demo.vx = CW * 0.12; G.demo.vy = -CHc * 0.2;
  }

  /* ---------------- 매치 시작 ---------------- */
  function startLocalMatch() {
    G.mode = 'ai'; G.online = false; G.isHost = false;
    G.players = [];
    const n = G.size;
    const autoBot = /[?&]bot=1/.test(location.search);
    const me = makePlayer({ id: G.myId, name: '지훈', team: 'blue', brawler: G.myBrawler, isLocal: !autoBot, isAI: autoBot });
    G.players.push(me);
    const mates = [];
    for (let i = 1; i < n; i++) {
      const b = BKEYS[i % BKEYS.length];
      mates.push(makePlayer({ name: AI_NAMES[i % AI_NAMES.length], team: 'blue', brawler: b, isAI: true }));
    }
    G.players = G.players.concat(mates);
    for (let i = 0; i < n; i++) {
      const b = BKEYS[(i + 2) % BKEYS.length];
      G.players.push(makePlayer({ name: 'AI ' + AI_NAMES[(i + 2) % AI_NAMES.length], team: 'red', brawler: b, isAI: true }));
    }
    G.myIndex = 0;
    beginPlay();
  }

  function beginPlay() {
    G.players.forEach(placePlayer);
    G.score = { blue: 0, red: 0 };
    G.winner = null;
    G.frame = 0; G.rally = 0; G.effects = []; G.pops = [];
    G.phase = 'play';
    G.myIndex = Math.max(0, G.players.findIndex(p => p.isLocal));
    $('lobby').classList.add('hidden');
    $('screen-room').classList.add('hidden');
    $('screen-result').classList.add('hidden');
    $('hud').classList.remove('hidden');
    $('controls').classList.remove('hidden');
    updateScoreboard();
    updateHud();
    serve('blue');
    Sfx.start();
  }

  function serve(team) {
    const b = G.ball;
    b.x = team === 'blue' ? netX * 0.66 : netX * 1.34;
    b.y = CHc * 0.10;
    b.vx = 0; b.vy = 0;
    b.trail = [];
    G.lastServe = team;
    G.freeze = 0.75;
    toast('서브!');
  }

  /* ---------------- 점수 ---------------- */
  function updateScoreboard() {
    $('score-blue').textContent = G.score.blue;
    $('score-red').textContent = G.score.red;
  }
  function pulse(el) {
    if (!el) return;
    el.classList.remove('pop');
    void el.offsetWidth;
    el.classList.add('pop');
  }
  function onPoint(scorer, x, y) {
    G.score[scorer]++;
    updateScoreboard();
    const el = $(scorer === 'blue' ? 'score-blue' : 'score-red');
    pulse(el && el.parentElement);
    floatText(x, Math.max(40, y - 30), '+1 ' + (scorer === 'blue' ? '파랑' : '빨강'), TEAMCOL[scorer].glow);
    spawnBurst(x, y, 26, TEAMCOL[scorer].main, P.base * 0.5);
    G.shake = Math.min(22, G.shake + 12);
    Sfx.score();
    const lastId = G.lastHitBy[scorer];
    const hp = G.players.find(p => p.id === lastId);
    if (hp) hp.pts++;
    G.players.filter(p => p.team === scorer).forEach(p => { p.ult = Math.min(100, p.ult + 10); });
    G.freeze = 1.05;
    G.pendingFx.push({ k: 'score', x: Math.round(x), y: Math.round(y) });
    if (G.score[scorer] >= WIN_SCORE) {
      G.winner = scorer;
      setTimeout(() => endMatch(scorer), 700);
    } else {
      setTimeout(() => { if (G.phase === 'play' && !G.winner) serve(scorer); }, 1050);
    }
  }
  G.lastHitBy = { blue: null, red: null };

  function endMatch(scorer) {
    if (G.phase === 'over') return;
    G.phase = 'over';
    const win = scorer === myTeam();
    if (win) {
      Save.data.trophies += 8;
      Save.data.wins += 1;
      Save.data.streak += 1;
      Save.data.best = Math.max(Save.data.best, Save.data.streak);
    } else {
      Save.data.trophies = Math.max(0, Save.data.trophies - 3);
      Save.data.streak = 0;
    }
    Save.save();
    refreshStats();
    $('res-badge').textContent = win ? '승리!' : '패배…';
    $('res-badge').className = 'res-badge' + (win ? '' : ' lose');
    $('res-score').textContent = G.score.blue + ' : ' + G.score.red;
    $('res-trophy').textContent = win
      ? '🏆 +8 트로피  ·  총 ' + Save.data.trophies
      : '🏆 -3 트로피  ·  총 ' + Save.data.trophies;
    const best = G.players.slice().sort((a, b) => b.pts - a.pts)[0];
    $('res-mvp').textContent = best ? 'MVP ' + BRAWLERS[best.brawler].emoji + ' ' + best.name + ' · ' + best.pts + '득점' : '';
    $('screen-result').classList.remove('hidden');
    $('controls').classList.add('hidden');
    if (win) Sfx.score(); else Sfx.lose();
    if (win) {
      spawnBurst(CW / 2, CHc * 0.4, 50, '#ffe14d', P.base * 0.9);
      G.shake = 16;
    }
  }

  function refreshStats() {
    $('trophies').textContent = Save.data.trophies;
    $('wins').textContent = Save.data.wins;
    $('streak').textContent = Save.data.streak;
  }

  /* ---------------- 입력 ---------------- */
  const keys = G.keys;
  const KEYMAP = {
    ArrowLeft: 'left', KeyA: 'left', ArrowRight: 'right', KeyD: 'right',
    ArrowUp: 'jump', KeyW: 'jump', Space: 'atk', Enter: 'atk',
    ShiftLeft: 'ult', ShiftRight: 'ult', KeyJ: 'atk', KeyK: 'ult'
  };
  window.addEventListener('keydown', e => {
    const k = KEYMAP[e.code];
    if (!k) return;
    e.preventDefault();
    if (keys[e.code]) return;
    keys[e.code] = true;
    if (k === 'jump') G.input.jump = true;
    if (k === 'atk') G.input.atk = true;
    if (k === 'ult') G.input.ult = true;
    Sfx.resume();
  });
  window.addEventListener('keyup', e => { if (KEYMAP[e.code]) { keys[e.code] = false; e.preventDefault(); } });

  function readKeyboard() {
    let x = 0;
    if (keys.ArrowLeft || keys.KeyA) x -= 1;
    if (keys.ArrowRight || keys.KeyD) x += 1;
    if (x) G.input.x = x;
    else if (!stick.active) G.input.x = 0;
  }

  const stick = { active: false, id: null, cx: 0, cy: 0, r: 1, up: false };
  function initStick() {
    const el = $('stick'), knob = $('knob');
    const R = () => el.getBoundingClientRect().width / 2;
    el.addEventListener('pointerdown', e => {
      const r = el.getBoundingClientRect();
      stick.active = true; stick.id = e.pointerId;
      stick.cx = r.left + r.width / 2; stick.cy = r.top + r.height / 2;
      stick.r = r.width / 2;
      el.setPointerCapture(e.pointerId);
      Sfx.resume();
      move(e);
    });
    function move(e) {
      if (!stick.active || e.pointerId !== stick.id) return;
      let dx = e.clientX - stick.cx, dy = e.clientY - stick.cy;
      const len = Math.hypot(dx, dy) || 1;
      const max = stick.r * 0.72;
      const k = len > max ? max / len : 1;
      dx *= k; dy *= k;
      knob.style.transform = 'translate(calc(-50% + ' + dx + 'px), calc(-50% + ' + dy + 'px))';
      G.input.x = clamp(dx / max, -1, 1);
      const upNow = dy < -max * 0.55;
      el.classList.toggle('jumping', upNow);
      if (upNow && !stick.up) G.input.jump = true;
      stick.up = upNow;
    }
    el.addEventListener('pointermove', move);
    const end = e => {
      if (e.pointerId != null && e.pointerId !== stick.id) return;
      stick.active = false; stick.id = null; stick.up = false;
      knob.style.transform = 'translate(-50%, -50%)';
      el.classList.remove('jumping');
      G.input.x = 0;
    };
    el.addEventListener('pointerup', end);
    el.addEventListener('pointercancel', end);
    el.addEventListener('pointerleave', e => { if (e.buttons === 0) end(e); });
  }

  function bindAction(id, key) {
    const el = $(id);
    el.addEventListener('pointerdown', e => {
      e.preventDefault();
      el.classList.add('press');
      G.input[key] = true;
      Sfx.resume();
    });
    const up = () => el.classList.remove('press');
    el.addEventListener('pointerup', up);
    el.addEventListener('pointercancel', up);
    el.addEventListener('pointerleave', up);
  }

  /* ---------------- 물리 ---------------- */
  function updatePlayer(p, dt) {
    if (p.isView) return;
    const ip = p.input;
    const B = BRAWLERS[p.brawler];
    const speed = P.run * B.speed;
    if (p.isLocal) {
      ip.x = G.input.x;
      if (G.input.jump) { ip.jump = true; G.input.jump = false; }
      if (G.input.atk) { ip.atk = true; G.input.atk = false; }
      if (G.input.ult) { ip.ult = true; G.input.ult = false; }
    }
    // 이동
    p.vx = clamp(ip.x, -1, 1) * speed;
    if (Math.abs(ip.x) > 0.15) p.facing = ip.x > 0 ? 1 : -1;
    p.x += p.vx * dt;
    const bnd = halfBounds(p.team);
    p.x = clamp(p.x, bnd.min, bnd.max);
    // 점프
    p.jumpCd = Math.max(0, p.jumpCd - dt);
    if (ip.jump && p.onGround && p.jumpCd <= 0) {
      p.vy = -P.jumpV * B.jump;
      p.onGround = false;
      p.jumpCd = 0.12;
      Sfx.jump();
    }
    // 중력
    p.vy += P.gP * dt;
    p.y += p.vy * dt;
    if (p.y >= floorY - P.pr) { p.y = floorY - P.pr; p.vy = 0; p.onGround = true; }
    else p.onGround = false;
    // 공격/궁극기
    p.atkCd = Math.max(0, p.atkCd - dt);
    p.hitCd = Math.max(0, p.hitCd - dt);
    p.poseT = Math.max(0, p.poseT - dt);
    p.animT += dt;
    if (ip.atk && p.atkCd <= 0) tryStrike(p);
    if (ip.ult && p.ult >= 100) doUlt(p);
    ip.jump = false; ip.atk = false; ip.ult = false;
    // 궁극기 게이지 자연 충전
    if (p.ult < 100) p.ult = Math.min(100, p.ult + dt * 2.2);
  }

  function ballReach(p) {
    return Math.hypot(G.ball.x - p.x, G.ball.y - p.y) < P.pr + P.br + P.pad;
  }

  function tryStrike(p) {
    if (!ballReach(p)) { p.atkCd = 0.26; p.poseT = 0.1; return; }
    strike(p, false);
    p.atkCd = 0.3; p.poseT = 0.2;
  }

  function strike(p, isUlt) {
    const b = G.ball;
    const dir = p.team === 'blue' ? 1 : -1;
    const B = BRAWLERS[p.brawler];
    const power = B.power * (isUlt ? 1.25 : 1);
    const below = b.y > netTop + P.br * 1.2;
    const targetFloor = floorY - P.br;
    let T, tx, ty;
    if (below) {
      // 네트보다 낮으면 띄워 넘긴다
      T = isUlt ? 0.58 : 0.68;
      tx = netX + dir * netX * 0.55;
      ty = floorY - CHc * 0.42;
    } else {
      T = (isUlt ? 0.215 : TUNE.spikeT);
      tx = netX + dir * netX * (TUNE.spikeF0 + Math.random() * (TUNE.spikeF1 - TUNE.spikeF0));
      ty = targetFloor;
    }
    b.vx = ((tx - b.x) / T) * (isUlt ? 1.12 : 1);
    b.vy = (ty - b.y - 0.5 * P.gB * T * T) / T;
    // 파워 보정
    b.vx *= 1 + (power - 1) * 0.35;
    if (below) b.vy = -(Math.abs(b.vy) + CHc * 0.35 * power);
    p.facing = dir;
    G.lastHitBy[p.team] = p.id;
    G.rally++;
    p.hits = (p.hits || 0) + 1;
    p.ult = Math.min(100, p.ult + (isUlt ? 0 : 12));
    p.hitCd = 0.12;
    b.trail = [];
    const kind = isUlt ? B.ultKind : 'hit';
    if (isUlt) {
      Sfx.ult(B.ultKind);
      G.shake = Math.min(24, G.shake + 15);
      floatText(b.x, b.y - P.pr * 2, B.ult + '!', B.ultKind === 'fire' ? '#ff8a3d' : B.ultKind === 'water' ? '#63c9ff' : '#ffe14d');
      spawnBurst(b.x, b.y, 34, kindColor(B.ultKind), P.base * 0.75);
      G.pendingFx.push({ k: 'ult', x: Math.round(b.x), y: Math.round(b.y), v: B.ultKind });
    } else {
      Sfx.spike();
      G.shake = Math.min(16, G.shake + 6);
      spawnBurst(b.x, b.y, 12, '#fff3a8', P.base * 0.4);
      G.pendingFx.push({ k: 'hit', x: Math.round(b.x), y: Math.round(b.y) });
    }
    void kind;
  }

  function kindColor(k) { return k === 'fire' ? '#ff7a2f' : k === 'water' ? '#4fc3f7' : '#ffe14d'; }

  function doUlt(p) {
    if (p.ult < 100) return;
    if (!ballReach(p)) { p.ult = Math.max(70, p.ult - 10); return; }
    p.ult = 0;
    strike(p, true);
    p.poseT = 0.35;
    p.atkCd = 0.35;
  }

  function resolveBodyHit(p) {
    if (p.hitCd > 0) return;
    const b = G.ball;
    const dx = b.x - p.x, dy = b.y - p.y;
    const d = Math.hypot(dx, dy) || 0.001;
    const rr = P.pr + P.br;
    if (d > rr * 1.02) return;
    const nx = dx / d, ny = dy / d;
    b.x = p.x + nx * (rr + 0.5);
    b.y = p.y + ny * (rr + 0.5);
    // 몸에 맞으면 자동 리시브: 위로 띄워 상대편으로 보낸다
    const dir = p.team === 'blue' ? 1 : -1;
    const B = BRAWLERS[p.brawler];
    b.vx = dir * CW * 0.5 * B.power + p.vx * 0.45 + nx * CW * 0.12;
    b.vy = -CHc * TUNE.bump * B.power - Math.max(0, ny) * CHc * 0.1;
    p.hitCd = 0.18;
    p.ult = Math.min(100, p.ult + 4);
    G.lastHitBy[p.team] = p.id;
    G.rally++;
    p.hits = (p.hits || 0) + 1;
    Sfx.hit(1);
    spawnBurst(b.x, b.y, 7, '#ffffff', P.base * 0.3);
    G.pendingFx.push({ k: 'hit', x: Math.round(b.x), y: Math.round(b.y) });
  }

  function updateBall(dt) {
    const b = G.ball;
    if (G.freeze > 0) return;
    b.vy += P.gB * dt;
    const steps = Math.min(5, Math.max(1, Math.ceil((Math.hypot(b.vx, b.vy) * dt) / (P.br * 0.8))));
    const sdt = dt / steps;
    for (let s = 0; s < steps; s++) {
      b.x += b.vx * sdt;
      b.y += b.vy * sdt;
      // 벽
      if (b.x - b.r < 0) { b.x = b.r; b.vx = Math.abs(b.vx) * 0.7; }
      if (b.x + b.r > CW) { b.x = CW - b.r; b.vx = -Math.abs(b.vx) * 0.7; }
      // 네트
      const nx0 = netX - netThick / 2 - b.r, nx1 = netX + netThick / 2 + b.r;
      if (b.y + b.r > netTop && b.x + b.r > nx0 && b.x - b.r < nx1) {
        if (b.y - b.r < netTop - 2) {
          // 네트 상단에 걸림
          b.y = netTop - b.r;
          b.vy = -Math.abs(b.vy) * 0.45;
          b.vx *= 0.85;
        } else if (b.x < netX) { b.x = netX - netThick / 2 - b.r; b.vx = -Math.abs(b.vx) * 0.5; }
        else { b.x = netX + netThick / 2 + b.r; b.vx = Math.abs(b.vx) * 0.5; }
        b.vy *= 0.85;
      }
      // 바닥
      if (b.y + b.r >= floorY) {
        b.y = floorY - b.r;
        const side = b.x < netX ? 'blue' : 'red';
        onPoint(other(side), b.x, b.y);
        return;
      }
    }
    b.trail.push({ x: b.x, y: b.y });
    if (b.trail.length > 12) b.trail.shift();
  }

  /* ---------------- AI ---------------- */
  function predictLanding() {
    const b = G.ball;
    let x = b.x, y = b.y, vx = b.vx, vy = b.vy;
    const dt = 0.03;
    for (let i = 0; i < 90; i++) {
      vy += P.gB * dt;
      x += vx * dt; y += vy * dt;
      if (x < b.r) { x = b.r; vx = Math.abs(vx) * 0.7; }
      if (x > CW - b.r) { x = CW - b.r; vx = -Math.abs(vx) * 0.7; }
      if (y + b.r >= floorY) return { x, t: i * dt };
    }
    return { x, t: 90 * dt };
  }

  function aiUpdate(p, dt) {
    const b = G.ball;
    const ip = p.input;
    const skill = G.diff === 'easy' ? 0.5 : G.diff === 'hard' ? 1.0 : 0.78;
    const ballMySide = p.team === 'blue' ? b.x < netX : b.x > netX;
    const bnd = halfBounds(p.team);
    p.aiT = (p.aiT || 0) - dt;
    p.aiJumpT = (p.aiJumpT || 0) - dt;

    // 반응 지연: 일정 시간마다만 위치를 다시 판단한다
    if (p.aiT <= 0) {
      p.aiT = (0.32 - skill * 0.20) * (0.75 + Math.random() * 0.55) * TUNE.rx;
      const landing = predictLanding();
      const mates = G.players.filter(q => q.team === p.team);
      const spot = ballMySide ? landing.x : b.x;
      let chaser = p, best = 1e9;
      for (const m of mates) {
        // 사람 플레이어는 판단이 느리다고 보고 AI가 더 적극적으로 커버한다
        const d = Math.abs(m.x - spot) + (m.isAI ? 0 : P.pr * 2.2);
        if (d < best) { best = d; chaser = m; }
      }
      let aim;
      if (ballMySide && chaser === p) {
        aim = landing.x + (Math.random() - 0.5) * P.pr * (2.6 - skill * 2);
      } else if (ballMySide) {
        aim = p.homeX + clamp((landing.x - p.homeX) * 0.25, -netX * 0.16, netX * 0.16);
      } else {
        aim = p.homeX;
      }
      p.targetX = clamp(aim, bnd.min, bnd.max);
      p.aggr = ballMySide && chaser === p;
    }

    const targetX = p.targetX == null ? p.homeX : p.targetX;
    const dx = targetX - p.x;
    ip.x = Math.abs(dx) < P.pr * 0.3 ? 0 : clamp(dx / (P.pr * 1.2), -1, 1);

    // 점프: 머리 위로 떨어지는 공에 반응
    const nearX = Math.abs(b.x - p.x) < P.pr * 1.35;
    const above = p.y - b.y;
    if (p.onGround && p.jumpCd <= 0 && p.aiJumpT <= 0) {
      if (nearX && above > -P.pr * 0.5 && above < CHc * 0.30 && b.vy > -CHc * 0.2) {
        ip.jump = true;
        p.aiJumpT = 0.25;
      }
    }

    // 공격: 닿는 순간 바로 때린다
    if (ballReach(p) && p.atkCd <= 0) {
      if (ballMySide || Math.abs(b.x - netX) < netX * 0.18) ip.atk = true;
    }
    if (p.ult >= 100 && ballReach(p) && (ballMySide || Math.abs(b.x - netX) < netX * 0.22)) {
      ip.ult = true;
    }
  }

  /* ---------------- 이펙트 ---------------- */
  function spawnBurst(x, y, n, color, spd) {
    for (let i = 0; i < n; i++) {
      const a = rnd(0, TAU), s = rnd(0.25, 1) * spd;
      G.effects.push({
        x, y, vx: Math.cos(a) * s, vy: Math.sin(a) * s,
        life: rnd(0.25, 0.6), max: 0.6, color, size: rnd(2, 5), kind: 'spark'
      });
    }
  }
  function floatText(x, y, text, color) {
    G.pops.push({ x, y, text, color: color || '#fff', life: 1, max: 1, vy: -60 });
  }
  function toast(text) {
    const el = $('toast');
    el.textContent = text;
    el.classList.add('show');
    clearTimeout(el._t);
    el._t = setTimeout(() => el.classList.remove('show'), 700);
  }
  function updateEffects(dt) {
    for (let i = G.effects.length - 1; i >= 0; i--) {
      const e = G.effects[i];
      e.life -= dt;
      if (e.life <= 0) { G.effects.splice(i, 1); continue; }
      e.x += e.vx * dt; e.y += e.vy * dt;
      e.vy += CHc * 1.2 * dt;
      e.vx *= 0.98;
    }
    for (let i = G.pops.length - 1; i >= 0; i--) {
      const p = G.pops[i];
      p.life -= dt;
      p.y += p.vy * dt;
      p.vy *= 0.94;
      if (p.life <= 0) G.pops.splice(i, 1);
    }
  }

  /* ---------------- 메인 루프 ---------------- */
  let lastT = 0;
  function loop(now) {
    const dt = Math.min(0.05, lastT ? (now - lastT) / 1000 : 0.016);
    lastT = now;
    G.time += dt;
    if (G.phase === 'menu') {
      updateDemo(dt);
    } else {
      if (G.phase === 'play' && !G.onlineView) {
        readKeyboard();
        updateSim(dt);
      }
      if (G.phase === 'play' && G.onlineView) {
        readKeyboard();
        updateSimRemote(dt);
      }
    }
    updateEffects(dt);
    render();
    requestAnimationFrame(loop);
  }

  function updateSim(dt) {
    G.frame++;
    if (G.freeze > 0) {
      G.freeze -= dt;
    } else {
      for (const p of G.players) {
        if (p.isAI) aiUpdate(p, dt);
        updatePlayer(p, dt);
      }
      updateBall(dt);
      for (const p of G.players) resolveBodyHit(p);
    }
    updateHud();
    if (G.online && G.isHost) {
      G.netAcc = (G.netAcc || 0) + dt;
      if (G.netAcc > 0.05) { G.netAcc = 0; broadcastState(); }
    }
  }

  function updateSimRemote(dt) {
    G.frame++;
    if (window.Net) Net.sendInput({ x: G.input.x, j: G.input.jump ? 1 : 0, a: G.input.atk ? 1 : 0, u: G.input.ult ? 1 : 0 });
    G.input.jump = false; G.input.atk = false; G.input.ult = false;
    updateHud();
  }

  function setRemoteInput(id, inp) {
    const p = G.players.find(q => q.id === id);
    if (!p) return;
    p.input.x = clamp(inp.x || 0, -1, 1);
    if (inp.j) p.input.jump = true;
    if (inp.a) p.input.atk = true;
    if (inp.u) p.input.ult = true;
  }

  function updateDemo(dt) {
    const d = G.demo;
    d.vy += P.gB * dt;
    d.x += d.vx * dt; d.y += d.vy * dt;
    if (d.x < P.br || d.x > CW - P.br) d.vx *= -1;
    if (d.y > floorY - P.br) { d.y = floorY - P.br; d.vy = -CHc * 0.9; }
    G.players.forEach(p => { p.animT += dt; });
  }

  /* ---------------- HUD ---------------- */
  let hudAcc = 0;
  function updateHud() {
    const me = G.players[G.myIndex];
    if (!me) return;
    const pct = Math.round(me.ult);
    hudAcc += 1;
    if (hudAcc % 3 === 0) {
      const f = $('my-ult-fill');
      if (f) f.style.width = pct + '%';
      const up = $('ult-pct');
      if (up) up.textContent = pct + '%';
      const b = $('btn-ult');
      if (b) b.classList.toggle('ready', pct >= 100);
    }
  }

  /* ---------------- 렌더 ---------------- */
  function render() {
    ctx.save();
    ctx.clearRect(0, 0, CW, CH);
    ctx.translate(0, VY);
    if (G.shake > 0.2) {
      ctx.translate(rnd(-1, 1) * G.shake, rnd(-1, 1) * G.shake);
      G.shake *= 0.9;
    }
    drawBackground();
    drawCourt();
    if (G.phase === 'menu') {
      drawDemoScene();
    } else {
      drawNet();
      const sorted = G.players.slice().sort((a, b) => a.y - b.y);
      for (const p of sorted) drawPlayer(p);
      drawBallFx();
      drawBall(ctx, G.ball.x, G.ball.y, G.ball.r, G.time, '#ffe14d');
    }
    drawEffects();
    drawPops();
    ctx.restore();
  }

  function drawBackground() {
    const g = ctx.createLinearGradient(0, worldTop, 0, floorY);
    g.addColorStop(0, '#3c2069');
    g.addColorStop(0.45, '#5b2f96');
    g.addColorStop(1, '#2a1349');
    ctx.fillStyle = g;
    ctx.fillRect(0, worldTop, CW, worldBottom - worldTop);
    // 조명 빔
    ctx.save();
    ctx.globalAlpha = 0.16;
    const beams = [[0.18, '#ffe14d'], [0.5, '#8ec8ff'], [0.82, '#ff7bd5']];
    for (const [fx, col] of beams) {
      const gx = CW * fx;
      const grd = ctx.createLinearGradient(gx, worldTop, gx + CW * 0.2, floorY);
      grd.addColorStop(0, col);
      grd.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = grd;
      ctx.beginPath();
      ctx.moveTo(gx - CW * 0.02, worldTop);
      ctx.lineTo(gx + CW * 0.06, worldTop);
      ctx.lineTo(gx + CW * 0.22, floorY);
      ctx.lineTo(gx - CW * 0.16, floorY);
      ctx.closePath(); ctx.fill();
    }
    ctx.restore();
    // 스타디움 벽 무늬
    ctx.save();
    ctx.globalAlpha = 0.055;
    ctx.strokeStyle = '#ffffff';
    ctx.lineWidth = Math.max(12, CW * 0.035);
    const gap = CW * 0.19;
    const spanY = floorY - worldTop;
    for (let x = -CW; x < CW * 2; x += gap) {
      ctx.beginPath();
      ctx.moveTo(x, worldTop);
      ctx.lineTo(x + spanY * 0.55, floorY);
      ctx.stroke();
    }
    ctx.restore();
    // 가운데 ⚡ 워터마크
    ctx.save();
    ctx.globalAlpha = 0.05;
    ctx.fillStyle = '#ffe14d';
    ctx.font = '900 ' + Math.round(CW * 0.55) + 'px system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('⚡', CW / 2, (worldTop + floorY) * 0.5 + CHc * 0.02);
    ctx.restore();
    // 관중석 (바닥 위쪽 벽 전체를 계단식 관중으로 채운다)
    const standBottom = floorY - CHc * (portrait ? 0.26 : 0.40);
    const rowH = Math.max(13, CHc * 0.072);
    const maxRows = portrait ? 10 : 2;
    const rows = Math.max(1, Math.min(maxRows, Math.floor((standBottom - worldTop) / rowH)));
    for (let r = 0; r < rows; r++) {
      const y = standBottom - r * rowH;
      ctx.save();
      ctx.globalAlpha = 1;
      ctx.fillStyle = r % 2 ? 'rgba(24,12,46,.55)' : 'rgba(16,8,32,.7)';
      ctx.fillRect(0, y - rowH * 0.62, CW, rowH * 0.62);
      ctx.restore();
      ctx.save();
      ctx.globalAlpha = 0.75 - r * 0.05;
      const n = Math.max(6, Math.round(CW / Math.max(16, CW * 0.028)));
      for (let i = 0; i < n; i++) {
        const x = (i + 0.5) * (CW / n) + Math.sin(i * 1.7 + r) * 5;
        const yy = y - rowH * 0.38 + Math.sin(i * 3.1 + G.time * 2 + r) * 2.2;
        const rad = Math.max(2.2, CW * 0.0075);
        ctx.fillStyle = (i + r) % 3 === 0 ? '#ffd23b' : (i + r) % 3 === 1 ? '#8ec8ff' : '#ff8fb0';
        ctx.beginPath(); ctx.arc(x, yy, rad, 0, TAU); ctx.fill();
      }
      ctx.restore();
    }
    // 관중석 난간
    ctx.save();
    ctx.globalAlpha = 0.65;
    ctx.fillStyle = 'rgba(255,225,77,.35)';
    ctx.fillRect(0, standBottom, CW, Math.max(2, CW * 0.004));
    ctx.restore();
    // 현수막 깃발
    ctx.save();
    ctx.globalAlpha = 0.55;
    const flagY = standBottom - Math.max(10, CHc * 0.06) - rows * rowH * 0.55;
    const fw = Math.max(9, CW * 0.022);
    for (let i = 0; i < 14; i++) {
      const x = (i + 0.5) * (CW / 14);
      ctx.fillStyle = i % 2 ? '#ffe14d' : '#8ec8ff';
      ctx.beginPath();
      ctx.moveTo(x - fw / 2, flagY);
      ctx.lineTo(x + fw / 2, flagY);
      ctx.lineTo(x, flagY + fw * 0.9 + Math.sin(G.time * 3 + i) * fw * 0.08);
      ctx.closePath(); ctx.fill();
    }
    ctx.restore();
    // 배너
    const bw = Math.min(CW * 0.6, 340), bh = Math.max(18, CHc * 0.06);
    const bx = CW / 2 - bw / 2, by = worldTop + (portrait ? 92 : 84);
    ctx.save();
    ctx.fillStyle = 'rgba(12,6,26,.62)';
    ctx.strokeStyle = 'rgba(255,225,77,.55)';
    ctx.lineWidth = 2;
    roundRect(ctx, bx, by, bw, bh, bh / 2);
    ctx.fill(); ctx.stroke();
    ctx.fillStyle = '#ffe14d';
    ctx.font = '900 ' + Math.round(bh * 0.52) + 'px "Trebuchet MS", system-ui, sans-serif';
    ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText('⚡ PIKA BRAWL ⚡', CW / 2, by + bh / 2 + 1);
    ctx.restore();
  }

  function roundRect(c, x, y, w, h, r) {
    c.beginPath();
    c.moveTo(x + r, y);
    c.arcTo(x + w, y, x + w, y + h, r);
    c.arcTo(x + w, y + h, x, y + h, r);
    c.arcTo(x, y + h, x, y, r);
    c.arcTo(x, y, x + w, y, r);
    c.closePath();
  }

  function drawCourt() {
    const h = worldBottom - floorY;
    const g = ctx.createLinearGradient(0, floorY, 0, worldBottom);
    g.addColorStop(0, '#f0b25a');
    g.addColorStop(0.35, '#d98b34');
    g.addColorStop(1, '#8d4f16');
    ctx.fillStyle = g;
    ctx.fillRect(0, floorY, CW, h);
    // 코트 라인
    ctx.save();
    ctx.strokeStyle = 'rgba(255,255,255,.75)';
    ctx.lineWidth = Math.max(2, CW * 0.006);
    ctx.beginPath(); ctx.moveTo(0, floorY + 1); ctx.lineTo(CW, floorY + 1); ctx.stroke();
    // 팀 색 바닥
    ctx.globalAlpha = 0.22;
    ctx.fillStyle = TEAMCOL.blue.main; ctx.fillRect(0, floorY, netX, h);
    ctx.fillStyle = TEAMCOL.red.main; ctx.fillRect(netX, floorY, netX, h);
    ctx.globalAlpha = 1;
    // 타일
    ctx.globalAlpha = 0.12;
    ctx.strokeStyle = '#3a1e00';
    ctx.lineWidth = 1;
    const step = Math.max(18, CW * 0.045);
    for (let x = 0; x < CW; x += step) { ctx.beginPath(); ctx.moveTo(x, floorY); ctx.lineTo(x - step * 0.5, worldBottom); ctx.stroke(); }
    ctx.restore();
    // 네트 그림자
    ctx.save();
    ctx.globalAlpha = 0.25;
    ctx.fillStyle = '#000';
    ctx.beginPath();
    ctx.ellipse(netX, floorY + 6, netX * 0.12, Math.max(4, h * 0.08), 0, 0, TAU);
    ctx.fill();
    ctx.restore();
  }

  function drawNet() {
    const x = netX, top = netTop;
    const postW = Math.max(3, CW * 0.007);
    // 그물
    ctx.save();
    ctx.strokeStyle = 'rgba(255,255,255,.55)';
    ctx.lineWidth = 1.2;
    const mesh = Math.max(7, CW * 0.016);
    for (let y = top; y < floorY; y += mesh) {
      ctx.beginPath(); ctx.moveTo(x - netThick / 2, y); ctx.lineTo(x + netThick / 2, y); ctx.stroke();
    }
    for (let i = -1; i <= 1; i += 2) {
      ctx.beginPath(); ctx.moveTo(x + i * netThick / 2, top); ctx.lineTo(x + i * netThick / 2, floorY); ctx.stroke();
    }
    // 상단 밴드
    ctx.fillStyle = '#ffe14d';
    ctx.strokeStyle = '#241500';
    ctx.lineWidth = 2;
    roundRect(ctx, x - netThick * 0.9, top - postW * 1.4, netThick * 1.8, postW * 1.6, postW);
    ctx.fill(); ctx.stroke();
    ctx.restore();
  }

  function drawPlayer(p) {
    const B = BRAWLERS[p.brawler];
    const isMe = p === G.players[G.myIndex];
    const pose = p.poseT > 0 ? 'atk' : !p.onGround ? 'jump' : 'idle';
    // 그림자
    ctx.save();
    ctx.globalAlpha = 0.3;
    ctx.fillStyle = '#1a0d00';
    const sh = clamp(1 - (floorY - P.pr - p.y) / (CHc * 0.4), 0.25, 1);
    ctx.beginPath();
    ctx.ellipse(p.x, floorY + 1, P.pr * 0.95 * sh, P.pr * 0.32 * sh, 0, 0, TAU);
    ctx.fill();
    ctx.restore();

    // 궁극기 오라
    if (p.ult >= 100) {
      ctx.save();
      const grd = ctx.createRadialGradient(p.x, p.y, P.pr * 0.6, p.x, p.y, P.pr * 2.4);
      grd.addColorStop(0, 'rgba(255,225,77,.42)');
      grd.addColorStop(1, 'rgba(255,225,77,0)');
      ctx.fillStyle = grd;
      ctx.beginPath(); ctx.arc(p.x, p.y, P.pr * 2.4, 0, TAU); ctx.fill();
      ctx.restore();
    }
    if (isMe) {
      ctx.save();
      ctx.strokeStyle = 'rgba(255,255,255,.75)';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.ellipse(p.x, floorY + 2, P.pr * 0.8, P.pr * 0.26, 0, 0, TAU);
      ctx.stroke();
      ctx.restore();
    }
    drawBrawler(ctx, p.brawler, p.x, p.y, P.pr, {
      facing: p.facing, team: TEAMCOL[p.team].main, t: p.animT,
      pose, glow: p.ult >= 100 ? kindColor(B.ultKind) : null
    });
    // 이름표
    if ((G.size > 1 || G.online) && !(portrait && !p.isLocal && G.size > 1)) {
      ctx.save();
      ctx.font = '800 ' + Math.max(8, P.pr * 0.42) + 'px system-ui, sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'bottom';
      ctx.fillStyle = p.team === 'blue' ? '#bfe0ff' : '#ffc9d2';
      ctx.globalAlpha = 0.9;
      const label = p.isLocal ? '지훈' : p.name;
      ctx.fillText(label, p.x, p.y - P.pr * 2.35);
      ctx.restore();
    }
    // 궁 게이지 링
    if (p.ult > 4 && p.ult < 100) {
      ctx.save();
      ctx.strokeStyle = 'rgba(255,225,77,.85)';
      ctx.lineWidth = Math.max(2, P.pr * 0.12);
      ctx.beginPath();
      ctx.arc(p.x, p.y, P.pr * 1.5, -Math.PI / 2, -Math.PI / 2 + TAU * (p.ult / 100));
      ctx.stroke();
      ctx.restore();
    }
  }

  function drawBallFx() {
    const b = G.ball;
    ctx.save();
    for (let i = 0; i < b.trail.length; i++) {
      const t = i / b.trail.length;
      ctx.globalAlpha = t * 0.4;
      ctx.fillStyle = '#ffe14d';
      ctx.beginPath(); ctx.arc(b.trail[i].x, b.trail[i].y, b.r * (0.3 + t * 0.7), 0, TAU); ctx.fill();
    }
    ctx.globalAlpha = 0.35;
    ctx.fillStyle = '#1a0d00';
    const sh = clamp(1 - (floorY - b.r - b.y) / (CHc * 0.5), 0.2, 1);
    ctx.beginPath(); ctx.ellipse(b.x, floorY + 1, b.r * sh, b.r * 0.3 * sh, 0, 0, TAU); ctx.fill();
    ctx.restore();
  }

  function drawEffects() {
    ctx.save();
    for (const e of G.effects) {
      const a = clamp(e.life / e.max, 0, 1);
      ctx.globalAlpha = a;
      ctx.fillStyle = e.color;
      ctx.beginPath(); ctx.arc(e.x, e.y, e.size * (0.4 + a * 0.8), 0, TAU); ctx.fill();
    }
    ctx.restore();
    // 궁극기 번개
    ctx.save();
    ctx.globalAlpha = 0.5;
    for (const e of G.effects) {
      if (e.kind !== 'spark') continue;
    }
    ctx.restore();
  }

  function drawPops() {
    ctx.save();
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    for (const p of G.pops) {
      const a = clamp(p.life / p.max, 0, 1);
      ctx.globalAlpha = a;
      ctx.font = '900 ' + Math.round(Math.max(14, CW * 0.045)) + 'px "Trebuchet MS", system-ui, sans-serif';
      ctx.lineWidth = 4;
      ctx.strokeStyle = 'rgba(0,0,0,.55)';
      ctx.strokeText(p.text, p.x, p.y);
      ctx.fillStyle = p.color;
      ctx.fillText(p.text, p.x, p.y);
    }
    ctx.restore();
  }

  function drawDemoScene() {
    drawNet();
    const sorted = G.players.slice().sort((a, b) => a.y - b.y);
    for (const p of sorted) {
      p.y = floorY - P.pr + Math.sin(G.time * 2 + p.animT) * 3;
      p.ult = 0;
      drawPlayer(p);
    }
    drawBall(ctx, G.demo.x, G.demo.y, P.br, G.time, '#ffe14d');
  }

  /* ---------------- 로비 UI ---------------- */
  function buildBrawlerCards() {
    const wrap = $('brawler-list');
    wrap.innerHTML = '';
    BKEYS.forEach(k => {
      const B = BRAWLERS[k];
      const el = document.createElement('div');
      el.className = 'bcard' + (k === G.myBrawler ? ' active' : '');
      el.dataset.k = k;
      const cvv = document.createElement('canvas');
      cvv.width = 200; cvv.height = 200;
      el.appendChild(cvv);
      const nm = document.createElement('div');
      nm.className = 'bname';
      nm.textContent = B.emoji + ' ' + B.short;
      el.appendChild(nm);
      const ul = document.createElement('div');
      ul.className = 'bult';
      ul.textContent = B.ult;
      el.appendChild(ul);
      const pk = document.createElement('div');
      pk.className = 'pick';
      pk.textContent = '선택';
      el.appendChild(pk);
      el.addEventListener('click', () => {
        G.myBrawler = k;
        G.players.forEach(p => { if (p.isLocal) p.brawler = k; });
        [...wrap.children].forEach(c => c.classList.toggle('active', c.dataset.k === k));
        Sfx.tone(660, 0.08, 'square', 0.1);
        drawCardArt();
      });
      wrap.appendChild(el);
    });
    drawCardArt();
  }
  function drawCardArt() {
    [...$('brawler-list').children].forEach(el => {
      const c = el.querySelector('canvas');
      if (!c) return;
      const g = c.getContext('2d');
      g.clearRect(0, 0, c.width, c.height);
      const k = el.dataset.k;
      g.save();
      g.translate(100, 132);
      window.Brawl.drawBrawler(g, k, 0, 0, 46, { facing: 1, team: k === G.myBrawler ? '#ffe14d' : null, t: 0, pose: 'idle' });
      g.restore();
    });
  }

  function selectMode(mode) {
    G.mode = mode;
    document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.mode === mode));
    $('panel-ai').classList.toggle('hidden', mode !== 'ai');
    $('panel-online').classList.toggle('hidden', mode === 'ai');
  }

  function wireUI() {
    document.querySelectorAll('.tab').forEach(t => t.addEventListener('click', () => selectMode(t.dataset.mode)));
    document.querySelectorAll('#size-seg button').forEach(b => b.addEventListener('click', () => {
      G.size = parseInt(b.dataset.size, 10);
      document.querySelectorAll('#size-seg button').forEach(x => x.classList.toggle('active', x === b));
      document.querySelectorAll('#size-seg-online button').forEach(x => x.classList.toggle('active', x.dataset.size === b.dataset.size));
      if (window.Net) Net.setSize(G.size);
    }));
    document.querySelectorAll('#size-seg-online button').forEach(b => b.addEventListener('click', () => {
      if (!(window.Net && Net.isHost)) return;
      const n = parseInt(b.dataset.size, 10);
      G.size = n;
      document.querySelectorAll('#size-seg-online button').forEach(x => x.classList.toggle('active', x === b));
      document.querySelectorAll('#size-seg button').forEach(x => x.classList.toggle('active', x.dataset.size === b.dataset.size));
      Net.setSize(n);
    }));
    document.querySelectorAll('#diff-seg button').forEach(b => b.addEventListener('click', () => {
      G.diff = b.dataset.diff;
      document.querySelectorAll('#diff-seg button').forEach(x => x.classList.toggle('active', x === b));
    }));
    $('btn-play').addEventListener('click', () => {
      Sfx.resume();
      if (G.mode === 'online') { Net.createRoom(); }
      else startLocalMatch();
    });
    $('btn-create').addEventListener('click', () => { Sfx.resume(); Net.createRoom(); });
    $('btn-join').addEventListener('click', joinFromInput);
    $('join-code').addEventListener('keydown', e => { if (e.key === 'Enter') joinFromInput(); });
    $('btn-exit').addEventListener('click', () => backToLobby(true));
    $('btn-home').addEventListener('click', () => backToLobby(false));
    $('btn-again').addEventListener('click', () => {
      $('screen-result').classList.add('hidden');
      if (G.online) backToLobby(false);
      else startLocalMatch();
    });
    $('btn-sound').addEventListener('click', () => {
      Sfx.muted = !Sfx.muted;
      $('btn-sound').textContent = Sfx.muted ? '🔇' : '🔊';
      if (Sfx.muted) Sfx.stopBgm(); else { Sfx.resume(); Sfx.bgm(); }
    });
    $('btn-start').addEventListener('click', () => { Net.hostStart(); });
    $('btn-room-back').addEventListener('click', () => { Net.leave(); backToLobby(true); });
    $('btn-copy').addEventListener('click', () => {
      const url = location.origin + location.pathname + '?room=' + (Net.code || '');
      const done = () => { $('btn-copy').textContent = '복사됨!'; setTimeout(() => ($('btn-copy').textContent = '링크 복사'), 1400); };
      if (navigator.clipboard) navigator.clipboard.writeText(url).then(done, done); else done();
    });
    bindAction('btn-attack', 'atk');
    bindAction('btn-ult', 'ult');
    initStick();
  }

  function joinFromInput() {
    const code = ($('join-code').value || '').replace(/[^0-9a-zA-Z]/g, '').slice(0, 4);
    if (code.length < 4) { toast('코드 4자리를 입력!'); return; }
    Sfx.resume();
    Net.joinRoom(code);
  }

  function backToLobby(notify) {
    G.phase = 'menu';
    G.online = false; G.isHost = false; G.onlineView = false;
    G.players = [];
    G.winner = null;
    $('hud').classList.add('hidden');
    $('controls').classList.add('hidden');
    $('screen-result').classList.add('hidden');
    $('screen-room').classList.add('hidden');
    $('lobby').classList.remove('hidden');
    Sfx.stopBgm();
    setupDemo();
    refreshStats();
    if (notify && window.Net && Net.connected) Net.leave();
  }

  /* ---------------- 온라인 연결부 ---------------- */
  function hostBroadcastState() { broadcastState(); }
  function broadcastState() {
    if (!G.online) return;
    const b = G.ball;
    const pl = G.players.map(p => [
      Math.round(p.x), Math.round(p.y), p.facing, Math.round(p.ult),
      p.poseT > 0 ? 1 : 0, p.onGround ? 0 : 1
    ]);
    Net.publish({
      t: 's', f: G.frame, sc: [G.score.blue, G.score.red], ph: G.phase,
      b: [Math.round(b.x), Math.round(b.y), Math.round(b.vx), Math.round(b.vy)],
      pl, ef: G.pendingFx.slice(0, 6), fr: G.freeze > 0 ? 1 : 0
    });
    G.pendingFx.length = 0;
  }

  function beginOnline(roster, size, host) {
    G.online = true;
    G.isHost = host;
    G.onlineView = !host;
    G.mode = 'online';
    G.size = size;
    G.players = roster.map(r => makePlayer({
      id: r.id, name: r.name, team: r.team, brawler: r.brawler,
      isAI: !!r.ai, isLocal: r.id === G.myId
    }));
    G.myIndex = Math.max(0, G.players.findIndex(p => p.isLocal));
    beginPlay();
  }

  /* 게스트: 호스트 상태 적용 */
  function applyState(st) {
    if (!G.onlineView) return;
    if (st.sc) { G.score.blue = st.sc[0]; G.score.red = st.sc[1]; updateScoreboard(); }
    const b = G.ball;
    if (st.b) {
      const k = G.onlineView ? 0.45 : 1;
      b.x = lerp(b.x, st.b[0], k);
      b.y = lerp(b.y, st.b[1], k);
      b.vx = st.b[2]; b.vy = st.b[3];
      b.trail.push({ x: b.x, y: b.y });
      if (b.trail.length > 12) b.trail.shift();
    }
    if (st.pl) {
      st.pl.forEach((d, i) => {
        const p = G.players[i];
        if (!p) return;
        p.x = lerp(p.x, d[0], 0.5);
        p.y = lerp(p.y, d[1], 0.5);
        p.facing = d[2];
        p.ult = d[3];
        p.poseT = d[4] ? 0.12 : Math.max(0, p.poseT - 0.02);
        p.onGround = !d[5];
      });
    }
    if (st.ef) {
      for (const e of st.ef) {
        if (e.k === 'hit') spawnBurst(e.x, e.y, 10, '#fff3a8', P.base * 0.4);
        else if (e.k === 'ult') {
          spawnBurst(e.x, e.y, 28, kindColor(e.v), P.base * 0.7);
          Sfx.ult(e.v);
        } else if (e.k === 'score') { spawnBurst(e.x, e.y, 22, '#ffe14d', P.base * 0.5); Sfx.score(); }
      }
    }
    if (st.ph === 'over') {
      if (!G.winner) {
        G.winner = G.score.blue >= WIN_SCORE ? 'blue' : 'red';
        endMatch(G.winner);
      }
    }
  }

  /* ---------------- 부트 ---------------- */
  function boot() {
    cv = $('game');
    ctx = cv.getContext('2d');
    layout();
    window.addEventListener('resize', () => { layout(); });
    window.addEventListener('orientationchange', () => setTimeout(layout, 220));
    document.addEventListener('visibilitychange', () => { if (!document.hidden) { lastT = 0; layout(); } });
    buildBrawlerCards();
    wireUI();
    refreshStats();
    setupDemo();
    const q = new URLSearchParams(location.search);
    const room = q.get('room');
    requestAnimationFrame(loop);
    if (room) {
      $('join-code').value = room;
      setTimeout(() => { selectMode('online'); Net.joinRoom(room); }, 350);
    }
  }

  window.Pika = {
    G, P, beginOnline, applyState, backToLobby, startLocalMatch, refreshStats,
    beginPlay, layout, WIN_SCORE, TEAMCOL, Save, setRemoteInput,
    myTeam, toast, setupDemo, broadcastState, placePlayer, makePlayer, homeXFor,
    dump: () => G.players.map(p => ({
      n: p.name, ai: p.isAI, team: p.team, x: Math.round(p.x), tx: Math.round(p.targetX == null ? -1 : p.targetX),
      y: Math.round(p.y), ground: p.onGround, ult: Math.round(p.ult), hits: p.hits || 0
    })),
    geo: () => ({ CW, CH, CHc, VY, floorY, netX, netTop, pr: P.pr, br: P.br, run: Math.round(P.run) })
  };
  window.addEventListener('DOMContentLoaded', boot);
})();
