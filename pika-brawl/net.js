/* ==================== 🌐 피카츄 배구 BRAWL — 온라인 (MQTT) ==================== */
'use strict';
(function () {
  const $ = s => document.getElementById(s);
  const BROKER = 'broker.emqx.io';
  const PORT = 8084;
  const TOPIC = code => 'pikabrawl/v3/room/' + code;
  // 방 코드 첫 글자가 브로커를 가리킨다(A/B/C) — 두 사람이 같은 서버에서 만나도록
  const ENDPOINTS = [
    { tag: 'A', host: 'broker.emqx.io', port: 8084, path: '/mqtt' },
    { tag: 'B', host: 'broker.hivemq.com', port: 8884, path: '/mqtt' },
    { tag: 'C', host: 'test.mosquitto.org', port: 8081, path: '/' }
  ];
  void BROKER; void PORT;

  const S = {
    client: null, code: null, isHost: false, size: 2,
    roster: [], hostId: null, started: false, connected: false,
    joinTimer: null, lobbyTimer: null, sendTimer: null,
    pend: { x: 0, j: 0, a: 0, u: 0 }, lastTx: 0,
    failCount: 0, retry: null, ep: 0, digits: '0000'
  };

  const myId = () => window.Pika.G.myId;

  /* ---------- UI ---------- */
  function showRoom() {
    $('lobby').classList.add('hidden');
    $('screen-room').classList.remove('hidden');
    $('screen-result').classList.add('hidden');
    $('room-code').textContent = S.code || '----';
    $('btn-start').classList.toggle('hidden', !S.isHost);
    $('room-wait').classList.toggle('hidden', S.isHost);
    document.querySelectorAll('#size-seg-online button').forEach(b => {
      b.classList.toggle('active', parseInt(b.dataset.size, 10) === S.size);
      b.disabled = !S.isHost;
    });
    renderRoom();
  }

  function renderRoom() {
    const size = S.size || 2;
    ['blue', 'red'].forEach(team => {
      const ul = $(team === 'blue' ? 'roster-blue' : 'roster-red');
      const cnt = $(team === 'blue' ? 'cnt-blue' : 'cnt-red');
      if (!ul) return;
      ul.innerHTML = '';
      const members = S.roster.filter(r => r.team === team);
      members.forEach(r => {
        const li = document.createElement('li');
        if (r.id === myId()) li.className = 'me';
        if (r.ai) li.classList.add('bot');
        const B = r.brawler || 'pika';
        const ava = window.Brawl.BRAWLERS[B].emoji;
        li.innerHTML = '<span class="ava">' + ava + '</span><span class="nm"></span>' +
          (r.id === S.hostId ? '<span class="role">방장</span>' : r.ai ? '<span class="role">BOT</span>' : '');
        li.querySelector('.nm').textContent = (r.id === myId() ? '지훈 (나)' : r.name) || '플레이어';
        ul.appendChild(li);
      });
      for (let i = members.length; i < size; i++) {
        const li = document.createElement('li');
        li.className = 'bot';
        li.innerHTML = '<span class="ava">➕</span><span class="nm">빈 자리 · AI 봇</span>';
        ul.appendChild(li);
      }
      if (cnt) cnt.textContent = members.length + '/' + size;
    });
    $('room-hint').textContent = S.isHost
      ? '친구에게 코드를 알려 주세요. 빈 자리는 AI 봇으로 채워집니다.'
      : (S.started ? '게임이 곧 시작됩니다!' : '호스트가 시작하기를 기다리는 중…');
  }

  /* ---------- 연결 ---------- */
  function connect() {
    disconnect(false);
    const ep = ENDPOINTS[S.ep] || ENDPOINTS[0];
    const cid = 'pk_' + Math.random().toString(36).slice(2, 10);
    const cli = ep.path
      ? new Paho.MQTT.Client(ep.host, ep.port, ep.path, cid)
      : new Paho.MQTT.Client(ep.host, ep.port, cid);
    S.client = cli;
    cli.onConnectionLost = () => {
      S.connected = false;
      if (S.code) {
        Pika.toast('연결 끊김 · 재연결 중');
        scheduleReconnect();
      }
    };
    cli.onMessageArrived = m => {
      let d;
      try { d = JSON.parse(m.payloadString); } catch (e) { return; }
      if (d && d.from === myId()) return;
      handle(d);
    };
    cli.connect({
      useSSL: true, timeout: 10, keepAliveInterval: 30, cleanSession: true,
      onSuccess: () => {
        S.connected = true;
        S.failCount = 0;
        cli.subscribe(TOPIC(S.code), { qos: 0 });
        afterConnect();
      },
      onFailure: err => {
        console.warn('MQTT fail', err);
        S.connected = false;
        S.failCount++;
        if (S.failCount >= 2 && S.isHost && S.ep < ENDPOINTS.length - 1) {
          S.ep++;
          S.failCount = 0;
          S.code = ENDPOINTS[S.ep].tag + S.digits;
          $('room-code').textContent = S.code;
          Pika.toast('서버 변경 · 새 코드 ' + S.code);
        } else if (S.failCount >= 4) {
          Pika.toast('연결 실패 · 잠시 후 다시');
          return;
        }
        scheduleReconnect();
      }
    });
  }

  function scheduleReconnect() {
    clearTimeout(S.retry);
    S.retry = setTimeout(() => { if (S.code) connect(); }, 1800);
  }

  function afterConnect() {
    if (S.isHost) {
      S.hostId = myId();
      publishLobby(true);
      S.lobbyTimer = setInterval(() => publishLobby(true), 2000);
    } else {
      let tries = 0;
      const sendJoin = () => {
        publish({ t: 'join', id: myId(), name: '지훈', brawler: Pika.G.myBrawler }, 1);
        if (++tries > 20) clearInterval(S.joinTimer);
      };
      sendJoin();
      clearInterval(S.joinTimer);
      S.joinTimer = setInterval(sendJoin, 900);
    }
  }

  function disconnect(hard) {
    clearInterval(S.joinTimer); clearInterval(S.lobbyTimer); clearInterval(S.sendTimer);
    S.joinTimer = S.lobbyTimer = S.sendTimer = null;
    clearTimeout(S.retry);
    if (S.client) {
      try { if (S.client.isConnected()) S.client.disconnect(); } catch (e) {}
      S.client = null;
    }
    S.connected = false;
    if (hard) S.code = null;
  }

  /* ---------- publish ---------- */
  function publish(obj, qos) {
    if (!S.client || !S.connected || !S.code) return;
    obj.from = myId();
    try {
      const m = new Paho.MQTT.Message(JSON.stringify(obj));
      m.destinationName = TOPIC(S.code);
      m.qos = qos || 0;
      S.client.send(m);
    } catch (e) {}
  }

  /* ---------- 수신 ---------- */
  function handle(d) {
    switch (d.t) {
      case 'join':
        if (!S.isHost) return;
        hostAccept(d);
        break;
      case 'lobby':
        if (S.isHost) return;
        S.roster = d.roster || [];
        S.size = d.size || S.size;
        S.hostId = d.hostId;
        S.started = !!d.started;
        clearInterval(S.joinTimer); S.joinTimer = null;
        renderRoom();
        break;
      case 'start':
        if (S.isHost) return;
        clearInterval(S.joinTimer);
        S.started = true;
        Pika.beginOnline(d.roster || S.roster, d.size || S.size, false);
        S.sendTimer = setInterval(flushInput, 45);
        break;
      case 'i':
        if (!S.isHost) return;
        window.Pika.setRemoteInput(d.id, d);
        break;
      case 's':
        if (S.isHost) return;
        window.Pika.applyState(d);
        break;
      case 'bye':
        if (!S.isHost) return;
        hostDrop(d.id);
        break;
      case 'busy':
        Pika.toast('이미 게임이 시작됐어요');
        break;
      case 'full':
        Pika.toast('방이 가득 찼어요 (관전)');
        break;
      default: break;
    }
  }

  function hostAccept(d) {
    if (S.started) { publish({ t: 'busy' }, 1); return; }
    const cap = S.size * 2;
    const exist = S.roster.find(r => r.id === d.id);
    if (exist) { publishLobby(true); return; }
    if (S.roster.length >= cap) { publish({ t: 'full' }, 1); return; }
    const blue = S.roster.filter(r => r.team === 'blue').length;
    const red = S.roster.filter(r => r.team === 'red').length;
    const team = blue <= red ? 'blue' : 'red';
    S.roster.push({ id: d.id, name: d.name || '친구', brawler: d.brawler || 'pika', team });
    publishLobby(true);
    renderRoom();
    Pika.toast('친구 입장!');
  }

  function hostDrop(id) {
    const i = S.roster.findIndex(r => r.id === id);
    if (i >= 0) S.roster.splice(i, 1);
    publishLobby(true);
    renderRoom();
    if (S.started) {
      const p = Pika.G.players.find(q => q.id === id);
      if (p) { p.isAI = true; p.isLocal = false; p.name = p.name + ' (봇)'; }
    }
  }

  function publishLobby(retain) {
    publish({ t: 'lobby', roster: S.roster, size: S.size, hostId: S.hostId, started: S.started }, 1);
    void retain;
  }

  /* ---------- 공개 API ---------- */
  function createRoom() {
    S.isHost = true;
    S.size = Pika.G.size || 2;
    S.ep = 0;
    S.digits = String(Math.floor(100 + Math.random() * 900));
    S.code = ENDPOINTS[0].tag + S.digits;
    S.roster = [{ id: myId(), name: '지훈', brawler: Pika.G.myBrawler, team: 'blue' }];
    S.hostId = myId();
    S.started = false;
    showRoom();
    connect();
  }

  function joinRoom(code) {
    S.isHost = false;
    let raw = String(code).trim().toUpperCase().replace(/[^0-9A-Z]/g, '');
    const head = raw.charAt(0);
    const idx = ENDPOINTS.findIndex(e => e.tag === head);
    if (idx >= 0) {
      S.ep = idx;
      S.code = raw.slice(0, 4);
    } else {
      S.ep = 0;
      S.code = 'A' + raw.replace(/[^0-9]/g, '').slice(0, 3);
    }
    if (S.code.length < 4) { Pika.toast('코드를 다시 확인해 주세요'); return; }
    S.started = false;
    S.roster = [];
    $('room-code').textContent = S.code;
    showRoom();
    connect();
  }

  function setSize(n) {
    if (!S.isHost) return;
    S.size = n;
    if (Pika.G) Pika.G.size = n;
    publishLobby(true);
    renderRoom();
  }

  function buildFullRoster() {
    const roster = S.roster.slice();
    const names = ['번개맨', '스파크', '전기쥐', '자석이', '찌릿'];
    for (const team of ['blue', 'red']) {
      let have = roster.filter(r => r.team === team).length;
      let k = 0;
      while (have < S.size) {
        const b = window.Brawl.BKEYS[(have + k + (team === 'red' ? 1 : 0)) % 3];
        roster.push({
          id: 'bot_' + team + '_' + have + '_' + Math.random().toString(36).slice(2, 5),
          name: 'AI ' + names[(have + k) % names.length],
          brawler: b, team, ai: true
        });
        have++; k++;
      }
    }
    return roster;
  }

  function hostStart() {
    if (!S.isHost) return;
    const roster = buildFullRoster();
    S.started = true;
    publish({ t: 'start', roster, size: S.size }, 1);
    Pika.G.size = S.size;
    Pika.beginOnline(roster, S.size, true);
  }

  function sendInput(inp) {
    S.pend.x = inp.x;
    S.pend.j = S.pend.j || inp.j;
    S.pend.a = S.pend.a || inp.a;
    S.pend.u = S.pend.u || inp.u;
    const now = performance.now();
    if (now - S.lastTx > 45) {
      S.lastTx = now;
      flushInput();
    }
  }
  function flushInput() {
    if (S.isHost || !S.connected) { S.pend.j = S.pend.a = S.pend.u = 0; return; }
    publish({ t: 'i', id: myId(), x: Math.round(S.pend.x * 100) / 100, j: S.pend.j, a: S.pend.a, u: S.pend.u });
    S.pend.j = S.pend.a = S.pend.u = 0;
  }

  function leave() {
    if (S.code) publish({ t: 'bye', id: myId() }, 0);
    disconnect(true);
    S.roster = []; S.started = false; S.isHost = false;
    clearInterval(S.sendTimer); S.sendTimer = null;
    if (Pika.G) { Pika.G.online = false; Pika.G.isHost = false; Pika.G.onlineView = false; }
  }

  window.Net = {
    createRoom, joinRoom, hostStart, setSize, leave, publish, sendInput,
    get code() { return S.code; },
    get isHost() { return S.isHost; },
    get connected() { return S.connected; },
    get roster() { return S.roster; },
    get size() { return S.size; },
    renderRoom
  };
})();
