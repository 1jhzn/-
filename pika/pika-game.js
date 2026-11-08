// ==================== ⚡ 피카츄 배구 ONLINE ==================== //

const CANVAS_WIDTH = 800;
const CANVAS_HEIGHT = 450;
const GROUND_Y = 380;
const NET_X = 400;
const NET_TOP = 240;
const NET_WIDTH = 14;

// Audio synth
class SoundFX {
  constructor() {
    this.ctx = null;
    this.enabled = true;
  }
  init() {
    if (!this.ctx) {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (AudioCtx) this.ctx = new AudioCtx();
    }
  }
  beep(freq, duration, type = 'sine') {
    if (!this.enabled || !this.ctx) return;
    try {
      const osc = this.ctx.createOscillator();
      const gain = this.ctx.createGain();
      osc.type = type;
      osc.frequency.setValueAtTime(freq, this.ctx.currentTime);
      gain.gain.setValueAtTime(0.15, this.ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.01, this.ctx.currentTime + duration);
      osc.connect(gain);
      gain.connect(this.ctx.destination);
      osc.start();
      osc.stop(this.ctx.currentTime + duration);
    } catch (e) {}
  }
  hit() { this.beep(350, 0.08, 'triangle'); }
  smash() { this.beep(850, 0.18, 'sawtooth'); }
  dive() { this.beep(220, 0.12, 'triangle'); }
  feint() { this.beep(600, 0.06, 'sine'); }
  save() { this.beep(980, 0.22, 'sine'); }
  score() { this.beep(520, 0.25, 'sine'); }
}
const sfx = new SoundFX();

// Game State
let canvas, ctx;
let gameMode = 'ai'; // 'quick', 'custom', 'ai'
let isHost = true;
let myPlayerId = 'pika_' + Math.random().toString(36).substr(2, 9);
let myNickname = '지훈';
let opponentNickname = '상대방';
let currentRoom = null;
let mqttClient = null;

let p1Score = 0;
let p2Score = 0;
const WINNING_SCORE = 5;
let isGameOver = false;

// Physics Entities
const p1 = {
  x: 180, y: GROUND_Y, vx: 0, vy: 0,
  width: 55, height: 65,
  isGrounded: true, isDiving: false, isSmashing: false, isFeint: false,
  diveTimer: 0,
  facing: 1, // 1: right, -1: left
  color: '#facc15'
};

const p2 = {
  x: 620, y: GROUND_Y, vx: 0, vy: 0,
  width: 55, height: 65,
  isGrounded: true, isDiving: false, isSmashing: false, isFeint: false,
  diveTimer: 0,
  facing: -1,
  color: '#fbbf24'
};

const ball = {
  x: 200, y: 150, vx: 3, vy: 0,
  radius: 18, rotation: 0,
  isFiery: false
};

// Particles (Dust & Sparks)
const particles = [];

function spawnParticles(x, y, count, type = 'spark') {
  for (let i = 0; i < count; i++) {
    const angle = Math.random() * Math.PI * 2;
    const speed = Math.random() * 5 + 1.5;
    particles.push({
      x, y,
      vx: Math.cos(angle) * speed,
      vy: Math.sin(angle) * speed,
      life: 1.0,
      decay: Math.random() * 0.04 + 0.03,
      size: Math.random() * 4 + 2,
      type
    });
  }
}

// Controls State
const keys = {
  left: false, right: false, up: false, down: false,
  smash: false, dive: false, feint: false
};

// DOM References
const lobbyScreen = document.getElementById('lobby-screen');
const waitingScreen = document.getElementById('waiting-screen');
const playScreen = document.getElementById('play-screen');
const gameoverModal = document.getElementById('gameover-modal');

const nicknameInput = document.getElementById('nicknameInput');
const btnQuickMatch = document.getElementById('btn-quick-match');
const btnCustomRoom = document.getElementById('btn-custom-room');
const btnAiPractice = document.getElementById('btn-ai-practice');
const roomDetailBox = document.getElementById('room-detail-box');
const btnCreateRoom = document.getElementById('btn-create-room');
const roomCodeInput = document.getElementById('roomCodeInput');
const btnJoinRoom = document.getElementById('btn-join-room');

const waitingTitle = document.getElementById('waiting-title');
const waitingDesc = document.getElementById('waiting-desc');
const roomShareArea = document.getElementById('room-share-area');
const displayRoomCode = document.getElementById('display-room-code');
const btnCopyInvite = document.getElementById('btn-copy-invite');
const copyToast = document.getElementById('copy-toast');
const btnCancelMatch = document.getElementById('btn-cancel-match');

const p1NameSpan = document.getElementById('p1-name');
const p2NameSpan = document.getElementById('p2-name');
const p1ScoreSpan = document.getElementById('p1-score');
const p2ScoreSpan = document.getElementById('p2-score');

const rotateBtn = document.getElementById('rotate-btn');
const soundBtn = document.getElementById('sound-btn');
const btnRematch = document.getElementById('btn-rematch');
const btnToLobby = document.getElementById('btn-to-lobby');
const modalWinnerText = document.getElementById('modal-winner-text');
const modalWinnerDesc = document.getElementById('modal-winner-desc');

// Initialize
window.addEventListener('DOMContentLoaded', () => {
  canvas = document.getElementById('gameCanvas');
  ctx = canvas.getContext('2d');

  bindControls();
  checkUrlParams();
  requestAnimationFrame(gameLoop);
});

function bindControls() {
  // Sound toggle
  soundBtn.addEventListener('click', () => {
    sfx.init();
    sfx.enabled = !sfx.enabled;
    soundBtn.textContent = sfx.enabled ? '🔊 소리' : '🔇 음소거';
  });

  // Rotation toggle
  let isRotated = false;
  rotateBtn.addEventListener('click', () => {
    isRotated = !isRotated;
    if (isRotated) {
      document.body.classList.add('force-landscape');
      rotateBtn.textContent = '📱 세로';
    } else {
      document.body.classList.remove('force-landscape');
      rotateBtn.textContent = '🔄 회전';
    }
  });

  // Keyboard controls
  window.addEventListener('keydown', (e) => {
    sfx.init();
    if (e.code === 'ArrowLeft' || e.code === 'KeyA') keys.left = true;
    if (e.code === 'ArrowRight' || e.code === 'KeyD') keys.right = true;
    if (e.code === 'ArrowUp' || e.code === 'KeyW' || e.code === 'Space') keys.up = true;
    if (e.code === 'ArrowDown' || e.code === 'KeyS') keys.down = true;
    if (e.code === 'Enter' || e.code === 'KeyZ' || e.code === 'KeyJ') keys.smash = true;
    if (e.code === 'KeyC' || e.code === 'KeyK' || e.code === 'ShiftLeft' || e.code === 'ShiftRight') keys.dive = true;
    if (e.code === 'KeyX' || e.code === 'KeyL') keys.feint = true;
  });

  window.addEventListener('keyup', (e) => {
    if (e.code === 'ArrowLeft' || e.code === 'KeyA') keys.left = false;
    if (e.code === 'ArrowRight' || e.code === 'KeyD') keys.right = false;
    if (e.code === 'ArrowUp' || e.code === 'KeyW' || e.code === 'Space') keys.up = false;
    if (e.code === 'ArrowDown' || e.code === 'KeyS') keys.down = false;
    if (e.code === 'Enter' || e.code === 'KeyZ' || e.code === 'KeyJ') keys.smash = false;
    if (e.code === 'KeyC' || e.code === 'KeyK' || e.code === 'ShiftLeft' || e.code === 'ShiftRight') keys.dive = false;
    if (e.code === 'KeyX' || e.code === 'KeyL') keys.feint = false;
  });

  // Mobile Touch Controls
  const setupTouch = (btnId, keyName) => {
    const el = document.getElementById(btnId);
    if (!el) return;
    const press = (e) => {
      e.preventDefault();
      sfx.init();
      keys[keyName] = true;
    };
    const release = (e) => {
      e.preventDefault();
      keys[keyName] = false;
    };
    el.addEventListener('touchstart', press, { passive: false });
    el.addEventListener('touchend', release, { passive: false });
    el.addEventListener('mousedown', press);
    el.addEventListener('mouseup', release);
    el.addEventListener('mouseleave', release);
  };

  setupTouch('t-left', 'left');
  setupTouch('t-right', 'right');
  setupTouch('t-up', 'up');
  setupTouch('t-down', 'down');
  setupTouch('t-smash', 'smash');
  setupTouch('t-dive', 'dive');
  setupTouch('t-feint', 'feint');

  // Match Action Handlers
  btnQuickMatch.addEventListener('click', startQuickMatch);
  btnAiPractice.addEventListener('click', startAiMatch);
  btnCustomRoom.addEventListener('click', () => {
    roomDetailBox.style.display = roomDetailBox.style.display === 'none' ? 'block' : 'none';
  });

  btnCreateRoom.addEventListener('click', () => {
    const code = Math.floor(1000 + Math.random() * 9000).toString();
    startCustomRoom(code, true);
  });

  btnJoinRoom.addEventListener('click', () => {
    const code = roomCodeInput.value.trim();
    if (!code) return alert('방 번호를 입력하세요!');
    startCustomRoom(code, false);
  });

  btnCopyInvite.addEventListener('click', () => {
    const url = window.location.origin + window.location.pathname + '?room=' + currentRoom;
    navigator.clipboard.writeText(url).then(() => {
      copyToast.classList.add('active');
      setTimeout(() => copyToast.classList.remove('active'), 2500);
    });
  });

  btnCancelMatch.addEventListener('click', () => {
    if (mqttClient) mqttClient.disconnect();
    showScreen('lobby');
  });

  btnRematch.addEventListener('click', () => {
    gameoverModal.classList.remove('active');
    resetMatch();
    if (gameMode !== 'ai') {
      sendMqtt({ type: 'rematch', sender: myPlayerId });
    }
  });

  btnToLobby.addEventListener('click', () => {
    gameoverModal.classList.remove('active');
    if (mqttClient) mqttClient.disconnect();
    showScreen('lobby');
  });
}

function showScreen(name) {
  lobbyScreen.style.display = (name === 'lobby' ? 'flex' : 'none');
  waitingScreen.style.display = (name === 'waiting' ? 'flex' : 'none');
  playScreen.style.display = (name === 'play' ? 'flex' : 'none');
}

// ---------------- GAME MODES ---------------- //

function startAiMatch() {
  gameMode = 'ai';
  isHost = true;
  myNickname = nicknameInput.value.trim() || '지훈';
  opponentNickname = 'AI 피카츄';

  p1NameSpan.textContent = myNickname;
  p2NameSpan.textContent = opponentNickname;

  resetMatch();
  showScreen('play');
}

function startQuickMatch() {
  gameMode = 'quick';
  myNickname = nicknameInput.value.trim() || '지훈';
  showScreen('waiting');
  waitingTitle.textContent = '🇰🇷 한국 퀵 매칭 중...';
  waitingDesc.textContent = '온라인 접속 중인 상대를 찾는 중입니다.';
  roomShareArea.style.display = 'none';

  // Lobby broker connection
  connectMqtt('quick_match_kr', (client) => {
    // Send find message
    const searchId = myPlayerId;
    sendMqtt({ type: 'search', sender: myPlayerId, nick: myNickname });

    // Wait 3 seconds, if no one found, become host of a random room and broadcast
    setTimeout(() => {
      if (waitingScreen.style.display !== 'none' && !currentRoom) {
        const generatedRoom = Math.floor(1000 + Math.random() * 9000).toString();
        startCustomRoom(generatedRoom, true);
      }
    }, 2500);
  });
}

function startCustomRoom(roomCode, host) {
  gameMode = 'custom';
  currentRoom = roomCode;
  isHost = host;
  myNickname = nicknameInput.value.trim() || '지훈';

  showScreen('waiting');
  if (isHost) {
    waitingTitle.textContent = '친구 대기 중... ⏳';
    waitingDesc.textContent = '초대 링크를 친구에게 공유해 주세요!';
    roomShareArea.style.display = 'block';
    displayRoomCode.textContent = roomCode;
  } else {
    waitingTitle.textContent = '방 접속 중... ⏳';
    waitingDesc.textContent = `방 ${roomCode}번에 연결하고 있습니다.`;
    roomShareArea.style.display = 'none';
  }

  connectMqtt(roomCode, () => {
    if (!isHost) {
      sendMqtt({ type: 'join', sender: myPlayerId, nick: myNickname });
    }
  });
}

function checkUrlParams() {
  const urlParams = new URLSearchParams(window.location.search);
  const room = urlParams.get('room');
  if (room) {
    startCustomRoom(room, false);
  }
}

// ---------------- MQTT NETWORK ---------------- //

function connectMqtt(roomKey, onConnected) {
  if (mqttClient && mqttClient.isConnected()) {
    mqttClient.disconnect();
  }

  const clientId = 'pika_' + Math.random().toString(16).substr(2, 8);
  mqttClient = new Paho.MQTT.Client('broker.emqx.io', 8084, clientId);

  mqttClient.onMessageArrived = (message) => {
    try {
      const data = JSON.parse(message.payloadString);
      handleNetworkMessage(data);
    } catch (e) {}
  };

  mqttClient.connect({
    useSSL: true,
    timeout: 10,
    cleanSession: true,
    onSuccess: () => {
      mqttClient.subscribe(`pika_volley_kr/${roomKey}`, { qos: 1 });
      if (onConnected) onConnected(mqttClient);
    },
    onFailure: () => {
      alert('온라인 서버 연결 실패. 잠시 후 다시 시도해 주세요.');
      showScreen('lobby');
    }
  });
}

function sendMqtt(payload) {
  if (!mqttClient || !mqttClient.isConnected()) return;
  const msg = new Paho.MQTT.Message(JSON.stringify(payload));
  const roomKey = (gameMode === 'quick' && !currentRoom) ? 'quick_match_kr' : currentRoom;
  msg.destinationName = `pika_volley_kr/${roomKey}`;
  msg.qos = 1;
  mqttClient.send(msg);
}

function handleNetworkMessage(data) {
  if (data.sender === myPlayerId) return;

  if (data.type === 'join') {
    opponentNickname = data.nick || '친구';
    sendMqtt({ type: 'welcome', sender: myPlayerId, nick: myNickname });
    startOnlineGame();
  } else if (data.type === 'welcome') {
    opponentNickname = data.nick || '방장';
    startOnlineGame();
  } else if (data.type === 'sync_player') {
    const opp = isHost ? p2 : p1;
    opp.x = data.x;
    opp.y = data.y;
    opp.vx = data.vx;
    opp.vy = data.vy;
    opp.isSmashing = data.isSmashing;
  } else if (data.type === 'sync_ball' && !isHost) {
    ball.x = data.x;
    ball.y = data.y;
    ball.vx = data.vx;
    ball.vy = data.vy;
    ball.isFiery = data.isFiery;
  } else if (data.type === 'score' && !isHost) {
    p1Score = data.p1;
    p2Score = data.p2;
    updateScoreUI();
    sfx.score();
  } else if (data.type === 'rematch') {
    gameoverModal.classList.remove('active');
    resetMatch();
  }
}

function startOnlineGame() {
  p1NameSpan.textContent = isHost ? myNickname : opponentNickname;
  p2NameSpan.textContent = isHost ? opponentNickname : myNickname;
  resetMatch();
  showScreen('play');
}

// ---------------- GAME PHYSICS & LOOP ---------------- //

function resetMatch() {
  p1Score = 0;
  p2Score = 0;
  isGameOver = false;
  updateScoreUI();
  resetRound(1);
}

function resetRound(serverPlayer) {
  p1.x = 180; p1.y = GROUND_Y; p1.vx = 0; p1.vy = 0;
  p2.x = 620; p2.y = GROUND_Y; p2.vx = 0; p2.vy = 0;

  ball.x = (serverPlayer === 1 ? 180 : 620);
  ball.y = 120;
  ball.vx = (serverPlayer === 1 ? 2.5 : -2.5);
  ball.vy = -3;
  ball.isFiery = false;
}

function updateScoreUI() {
  p1ScoreSpan.textContent = p1Score;
  p2ScoreSpan.textContent = p2Score;
}

function gameLoop() {
  update();
  render();
  requestAnimationFrame(gameLoop);
}

let syncTimer = 0;

function update() {
  if (playScreen.style.display === 'none' || isGameOver) return;

  // 1. Local Player Control
  const me = isHost ? p1 : p2;
  const minX = isHost ? 40 : NET_X + NET_WIDTH / 2 + 30;
  const maxX = isHost ? NET_X - NET_WIDTH / 2 - 30 : 760;

  // Handle Diving State
  if (me.diveTimer > 0) {
    me.diveTimer--;
    me.isDiving = true;
    me.vx = me.facing * 12;
    spawnParticles(me.x - me.facing * 20, GROUND_Y - 5, 2, 'dust');
  } else {
    me.isDiving = false;

    // Trigger Dive
    if (keys.dive && me.isGrounded) {
      me.diveTimer = 22;
      me.isDiving = true;
      me.vx = me.facing * 12;
      sfx.dive();
      spawnParticles(me.x, GROUND_Y - 5, 6, 'dust');
    } else {
      me.vx = 0;
      if (keys.left) { me.vx = -6.5; me.facing = -1; }
      if (keys.right) { me.vx = 6.5; me.facing = 1; }

      // Jump
      if (keys.up && me.isGrounded) {
        me.vy = -13.8;
        me.isGrounded = false;
        sfx.hit();
      }
    }
  }

  // Action Skill Triggers
  me.isSmashing = keys.smash;
  me.isFeint = keys.feint;

  // Physics apply to me
  me.x += me.vx;
  if (me.x < minX) me.x = minX;
  if (me.x > maxX) me.x = maxX;

  me.vy += 0.65; // Gravity
  me.y += me.vy;
  if (me.y >= GROUND_Y) {
    me.y = GROUND_Y;
    me.vy = 0;
    me.isGrounded = true;
  }

  // 2. Opponent Control (AI or Network)
  if (gameMode === 'ai') {
    updateAi();
  }

  // 3. Network Sync
  if (gameMode !== 'ai') {
    syncTimer++;
    if (syncTimer % 2 === 0) {
      sendMqtt({
        type: 'sync_player',
        sender: myPlayerId,
        x: me.x, y: me.y, vx: me.vx, vy: me.vy,
        isSmashing: me.isSmashing
      });

      if (isHost) {
        sendMqtt({
          type: 'sync_ball',
          sender: myPlayerId,
          x: ball.x, y: ball.y, vx: ball.vx, vy: ball.vy,
          isFiery: ball.isFiery
        });
      }
    }
  }

  // 4. Ball Physics (Simulated by Host or in AI mode)
  if (isHost) {
    updateBallPhysics();
  }
}

function updateAi() {
  const opp = p2;
  const minX = NET_X + NET_WIDTH / 2 + 35;
  const maxX = 740;

  if (opp.diveTimer > 0) {
    opp.diveTimer--;
    opp.isDiving = true;
    opp.vx = opp.facing * 11;
    spawnParticles(opp.x - opp.facing * 20, GROUND_Y - 5, 2, 'dust');
  } else {
    opp.isDiving = false;

    // AI tracks ball when ball is on right side
    let targetX = ball.x + 20;
    if (ball.x < NET_X) {
      targetX = 620; // default position
    }

    opp.vx = 0;
    if (opp.x < targetX - 10) { opp.vx = 5.2; opp.facing = 1; }
    else if (opp.x > targetX + 10) { opp.vx = -5.2; opp.facing = -1; }

    // AI Dive Save: when ball is falling fast low to ground
    const ballDistance = Math.hypot(ball.x - opp.x, ball.y - GROUND_Y);
    if (ball.x > NET_X + 20 && ball.y > 280 && ball.vy > 2.5 && ballDistance > 70 && ballDistance < 160 && opp.isGrounded) {
      opp.facing = (ball.x > opp.x ? 1 : -1);
      opp.diveTimer = 22;
      opp.isDiving = true;
      opp.vx = opp.facing * 12;
      sfx.dive();
      spawnParticles(opp.x, GROUND_Y - 5, 6, 'dust');
    }

    // AI Jump & Smash when ball is nearby
    const dist = Math.hypot(ball.x - opp.x, ball.y - (opp.y - 30));
    if (dist < 85 && opp.isGrounded && ball.y < opp.y - 10) {
      opp.vy = -13.5;
      opp.isGrounded = false;
      opp.isSmashing = (Math.random() > 0.35);
      opp.isFeint = (!opp.isSmashing && Math.random() > 0.6);
    }
  }

  opp.x += opp.vx;
  if (opp.x < minX) opp.x = minX;
  if (opp.x > maxX) opp.x = maxX;

  opp.vy += 0.65;
  opp.y += opp.vy;
  if (opp.y >= GROUND_Y) {
    opp.y = GROUND_Y;
    opp.vy = 0;
    opp.isGrounded = true;
  }
}

function updateBallPhysics() {
  ball.vy += 0.38; // Ball gravity
  ball.x += ball.vx;
  ball.y += ball.vy;
  ball.rotation += ball.vx * 0.05;

  // Wall bounds
  if (ball.x - ball.radius < 0) {
    ball.x = ball.radius;
    ball.vx = Math.abs(ball.vx) * 0.85;
    sfx.hit();
  }
  if (ball.x + ball.radius > CANVAS_WIDTH) {
    ball.x = CANVAS_WIDTH - ball.radius;
    ball.vx = -Math.abs(ball.vx) * 0.85;
    sfx.hit();
  }

  // Net collision
  if (ball.x + ball.radius > NET_X - NET_WIDTH / 2 && ball.x - ball.radius < NET_X + NET_WIDTH / 2) {
    if (ball.y + ball.radius > NET_TOP) {
      if (ball.y < NET_TOP + 10) {
        // Top of net bounce
        ball.y = NET_TOP - ball.radius;
        ball.vy = -Math.abs(ball.vy) * 0.8;
      } else {
        // Side of net bounce
        if (ball.x < NET_X) {
          ball.x = NET_X - NET_WIDTH / 2 - ball.radius;
          ball.vx = -Math.abs(ball.vx) * 0.8;
        } else {
          ball.x = NET_X + NET_WIDTH / 2 + ball.radius;
          ball.vx = Math.abs(ball.vx) * 0.8;
        }
      }
      sfx.hit();
    }
  }

  // Player collisions
  checkPlayerBallCollision(p1, 1);
  checkPlayerBallCollision(p2, -1);

  // Ground hit (Score!)
  if (ball.y + ball.radius >= GROUND_Y) {
    ball.y = GROUND_Y - ball.radius;
    sfx.score();

    if (ball.x < NET_X) {
      // P2 scores
      p2Score++;
      updateScoreUI();
      if (checkWin()) return;
      resetRound(2);
    } else {
      // P1 scores
      p1Score++;
      updateScoreUI();
      if (checkWin()) return;
      resetRound(1);
    }

    if (gameMode !== 'ai') {
      sendMqtt({ type: 'score', sender: myPlayerId, p1: p1Score, p2: p2Score });
    }
  }
}

function checkPlayerBallCollision(player, courtDirection) {
  // 1. Sliding Dive Collision Check (Low & Wide Hitbox)
  if (player.isDiving) {
    const diveHitX = player.x + player.facing * 18;
    const diveHitY = GROUND_Y - 18;
    const diveDist = Math.hypot(ball.x - diveHitX, ball.y - diveHitY);

    if (diveDist < ball.radius + 38 && ball.y > 270) {
      // 🏃 SUPER DIVE SAVE!
      ball.isFiery = false;
      ball.vy = -12.8;
      ball.vx = courtDirection * 3.2;
      ball.y = diveHitY - ball.radius - 4;
      sfx.save();
      spawnParticles(ball.x, ball.y, 10, 'spark');
      return;
    }
  }

  // 2. Normal / Air / Smash Collision Check
  const headX = player.x;
  const headY = player.y - 35;
  const dist = Math.hypot(ball.x - headX, ball.y - headY);

  if (dist < ball.radius + 32) {
    const angle = Math.atan2(ball.y - headY, ball.x - headX);

    if (player.isSmashing && !player.isGrounded) {
      // ⚡ THUNDER SPIKE!
      ball.isFiery = true;
      if (keys.down) {
        // Sharp downward spike
        ball.vx = courtDirection * 9;
        ball.vy = 11.5;
      } else if (keys.up) {
        // Deep long drive spike
        ball.vx = courtDirection * 15;
        ball.vy = 5.2;
      } else {
        // Standard power spike
        ball.vx = courtDirection * 13;
        ball.vy = 8.5;
      }
      sfx.smash();
      spawnParticles(ball.x, ball.y, 16, 'spark');
    } else if (player.isFeint) {
      // 🎯 FEINT DROP SHOT!
      ball.isFiery = false;
      ball.vx = courtDirection * 2.8;
      ball.vy = -4.5;
      sfx.feint();
      spawnParticles(ball.x, ball.y, 6, 'dust');
    } else {
      // Normal hit
      ball.isFiery = false;
      const speed = 8.5;
      ball.vx = Math.cos(angle) * speed + player.vx * 0.3;
      ball.vy = -Math.abs(Math.sin(angle) * speed) - 3.5;
      sfx.hit();
      spawnParticles(ball.x, ball.y, 4, 'dust');
    }

    // Separate ball from head
    ball.x = headX + Math.cos(angle) * (ball.radius + 34);
    ball.y = headY + Math.sin(angle) * (ball.radius + 34);
  }
}

function checkWin() {
  if (p1Score >= WINNING_SCORE || p2Score >= WINNING_SCORE) {
    isGameOver = true;
    const p1Wins = p1Score >= WINNING_SCORE;
    const isMeWinner = (isHost && p1Wins) || (!isHost && !p1Wins);

    modalWinnerText.textContent = isMeWinner ? '승리! 🎉' : '패배... 😭';
    modalWinnerDesc.textContent = `${p1Wins ? p1NameSpan.textContent : p2NameSpan.textContent}님이 ${WINNING_SCORE}점을 먼저 달성했습니다!`;
    gameoverModal.classList.add('active');
    return true;
  }
  return false;
}

// ---------------- RENDERING ---------------- //

function render() {
  if (!ctx) return;

  // 1. Sky Gradient
  const skyGrad = ctx.createLinearGradient(0, 0, 0, 320);
  skyGrad.addColorStop(0, '#38bdf8');
  skyGrad.addColorStop(1, '#bae6fd');
  ctx.fillStyle = skyGrad;
  ctx.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

  // Clouds
  drawCloud(120, 70, 45);
  drawCloud(560, 90, 55);
  drawCloud(340, 50, 35);

  // 2. Sea
  const seaGrad = ctx.createLinearGradient(0, 260, 0, 340);
  seaGrad.addColorStop(0, '#0284c7');
  seaGrad.addColorStop(1, '#0369a1');
  ctx.fillStyle = seaGrad;
  ctx.fillRect(0, 260, CANVAS_WIDTH, 80);

  // Wave ripple
  ctx.strokeStyle = '#e0f2fe';
  ctx.lineWidth = 1.5;
  for (let i = 0; i < CANVAS_WIDTH; i += 60) {
    ctx.beginPath();
    ctx.arc(i + 25, 265, 15, Math.PI, 0);
    ctx.stroke();
  }

  // 3. Sandy Beach Ground
  const sandGrad = ctx.createLinearGradient(0, 340, 0, CANVAS_HEIGHT);
  sandGrad.addColorStop(0, '#fde047');
  sandGrad.addColorStop(0.3, '#eab308');
  sandGrad.addColorStop(1, '#ca8a04');
  ctx.fillStyle = sandGrad;
  ctx.fillRect(0, 340, CANVAS_WIDTH, CANVAS_HEIGHT - 340);

  // Ground line
  ctx.strokeStyle = '#a16207';
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(0, GROUND_Y);
  ctx.lineTo(CANVAS_WIDTH, GROUND_Y);
  ctx.stroke();

  // 4. Volleyball Net
  // Pole
  ctx.fillStyle = '#78350f';
  ctx.fillRect(NET_X - 4, NET_TOP - 10, 8, GROUND_Y - NET_TOP + 10);
  // Net mesh
  ctx.fillStyle = 'rgba(255, 255, 255, 0.85)';
  ctx.fillRect(NET_X - NET_WIDTH / 2, NET_TOP, NET_WIDTH, GROUND_Y - NET_TOP);
  ctx.strokeStyle = '#475569';
  ctx.lineWidth = 1;
  ctx.strokeRect(NET_X - NET_WIDTH / 2, NET_TOP, NET_WIDTH, GROUND_Y - NET_TOP);

  for (let ny = NET_TOP; ny <= GROUND_Y; ny += 14) {
    ctx.beginPath();
    ctx.moveTo(NET_X - NET_WIDTH / 2, ny);
    ctx.lineTo(NET_X + NET_WIDTH / 2, ny);
    ctx.stroke();
  }

  // 5. Draw Pikachus
  drawPikachu(p1, 1);
  drawPikachu(p2, -1);

  // 6. Draw Ball
  drawBall();

  // 7. Draw & Update Particles
  renderParticles();
}

function renderParticles() {
  for (let i = particles.length - 1; i >= 0; i--) {
    const p = particles[i];
    p.x += p.vx;
    p.y += p.vy;
    p.life -= p.decay;
    if (p.life <= 0) {
      particles.splice(i, 1);
      continue;
    }
    ctx.save();
    ctx.globalAlpha = p.life;
    if (p.type === 'spark') {
      ctx.fillStyle = '#fef08a';
      ctx.shadowColor = '#facc15';
      ctx.shadowBlur = 8;
      ctx.fillRect(p.x, p.y, p.size * 1.4, p.size * 1.4);
    } else {
      ctx.fillStyle = '#eab308';
      ctx.beginPath();
      ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.restore();
  }
}

function drawCloud(x, y, r) {
  ctx.fillStyle = 'rgba(255, 255, 255, 0.8)';
  ctx.beginPath();
  ctx.arc(x, y, r, 0, Math.PI * 2);
  ctx.arc(x + r * 0.7, y - r * 0.2, r * 0.8, 0, Math.PI * 2);
  ctx.arc(x + r * 1.4, y, r * 0.65, 0, Math.PI * 2);
  ctx.fill();
}

function drawPikachu(player, defaultFace) {
  ctx.save();
  ctx.translate(player.x, player.y);
  if (player.facing === -1) ctx.scale(-1, 1);

  // Tilt forward if sliding/diving
  if (player.isDiving) {
    ctx.rotate(0.95);
    ctx.translate(14, 12);
  }

  // Shadow
  ctx.fillStyle = 'rgba(0, 0, 0, 0.2)';
  ctx.beginPath();
  ctx.ellipse(0, -3, 24, 7, 0, 0, Math.PI * 2);
  ctx.fill();

  // Tail (Zigzag lightning)
  ctx.strokeStyle = '#ca8a04';
  ctx.lineWidth = 5;
  ctx.beginPath();
  ctx.moveTo(-16, -30);
  ctx.lineTo(-32, -45);
  ctx.lineTo(-24, -55);
  ctx.lineTo(-42, -75);
  ctx.stroke();

  // Body
  ctx.fillStyle = player.color;
  ctx.beginPath();
  ctx.ellipse(0, -28, 22, 26, 0, 0, Math.PI * 2);
  ctx.fill();
  ctx.strokeStyle = '#854d0e';
  ctx.lineWidth = 1.5;
  ctx.stroke();

  // Head
  ctx.beginPath();
  ctx.arc(0, -48, 18, 0, Math.PI * 2);
  ctx.fill();
  ctx.stroke();

  // Ears
  // Left ear
  ctx.beginPath();
  ctx.moveTo(-8, -62);
  ctx.lineTo(-20, -88);
  ctx.lineTo(-4, -64);
  ctx.fill();
  ctx.stroke();
  // Left ear tip (black)
  ctx.fillStyle = '#000';
  ctx.beginPath();
  ctx.moveTo(-14, -76);
  ctx.lineTo(-20, -88);
  ctx.lineTo(-10, -78);
  ctx.fill();

  // Right ear
  ctx.fillStyle = player.color;
  ctx.beginPath();
  ctx.moveTo(8, -62);
  ctx.lineTo(24, -85);
  ctx.lineTo(14, -62);
  ctx.fill();
  ctx.stroke();
  // Right ear tip (black)
  ctx.fillStyle = '#000';
  ctx.beginPath();
  ctx.moveTo(16, -74);
  ctx.lineTo(24, -85);
  ctx.lineTo(20, -72);
  ctx.fill();

  // Eyes
  ctx.fillStyle = '#000';
  ctx.beginPath();
  ctx.arc(7, -50, 3, 0, Math.PI * 2);
  ctx.fill();
  // Eye glint
  ctx.fillStyle = '#fff';
  ctx.beginPath();
  ctx.arc(8, -51, 1.2, 0, Math.PI * 2);
  ctx.fill();

  // Red Cheek
  ctx.fillStyle = '#ef4444';
  ctx.beginPath();
  ctx.arc(9, -43, 5, 0, Math.PI * 2);
  ctx.fill();

  // Nose & Mouth
  ctx.fillStyle = '#000';
  ctx.fillRect(13, -47, 2, 2);
  ctx.beginPath();
  ctx.arc(10, -42, 3, 0, Math.PI * 0.7);
  ctx.stroke();

  // Feet
  ctx.fillStyle = player.color;
  ctx.beginPath();
  ctx.ellipse(-10, -5, 8, 5, 0, 0, Math.PI * 2);
  ctx.ellipse(10, -5, 8, 5, 0, 0, Math.PI * 2);
  ctx.fill();
  ctx.stroke();

  // Smash lightning aura
  if (player.isSmashing) {
    ctx.strokeStyle = '#facc15';
    ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.arc(0, -35, 36, 0, Math.PI * 2);
    ctx.stroke();
  }

  ctx.restore();
}

function drawBall() {
  ctx.save();
  ctx.translate(ball.x, ball.y);
  ctx.rotate(ball.rotation);

  // Fiery Smash Trail
  if (ball.isFiery) {
    ctx.shadowColor = '#ef4444';
    ctx.shadowBlur = 18;
  } else {
    ctx.shadowColor = 'rgba(0,0,0,0.3)';
    ctx.shadowBlur = 4;
  }

  // Outer circle
  ctx.beginPath();
  ctx.arc(0, 0, ball.radius, 0, Math.PI * 2);
  ctx.fillStyle = '#fff';
  ctx.fill();
  ctx.strokeStyle = '#000';
  ctx.lineWidth = 2;
  ctx.stroke();

  // Top half red (Pokeball style)
  ctx.beginPath();
  ctx.arc(0, 0, ball.radius, Math.PI, 0);
  ctx.fillStyle = ball.isFiery ? '#f97316' : '#ef4444';
  ctx.fill();
  ctx.stroke();

  // Center black band
  ctx.fillStyle = '#000';
  ctx.fillRect(-ball.radius, -2, ball.radius * 2, 4);

  // Center button
  ctx.beginPath();
  ctx.arc(0, 0, 5, 0, Math.PI * 2);
  ctx.fillStyle = '#fff';
  ctx.fill();
  ctx.stroke();

  ctx.beginPath();
  ctx.arc(0, 0, 2.5, 0, Math.PI * 2);
  ctx.stroke();

  ctx.restore();
}
