const BOARD_SIZE = 15;
const CANVAS_SIZE = 600;
const PADDING = 30;
const CELL_SIZE = (CANVAS_SIZE - PADDING * 2) / (BOARD_SIZE - 1);

const EMPTY = 0;
const BLACK = 1;
const WHITE = 2;

let board = Array.from({ length: BOARD_SIZE }, () => Array(BOARD_SIZE).fill(EMPTY));
let currentTurn = BLACK;
let history = [];
let isGameOver = false;
let gameMode = 'online'; // 'online', 'pve', 'pvp'

// Online multiplayer variables
let myRole = BLACK; // BLACK (Host) or WHITE (Guest)
let currentRoomId = null;
let mqttClient = null;
const myPlayerId = 'p_' + Math.random().toString(36).substr(2, 9);
const MQTT_BROKER = 'broker.emqx.io';
const MQTT_PORT = 8084;

// DOM Elements
const canvas = document.getElementById('omokCanvas');
const ctx = canvas.getContext('2d');
const turnStone = document.getElementById('turnStone');
const turnText = document.getElementById('turnText');
const gameHint = document.getElementById('gameHint');
const undoBtn = document.getElementById('undoBtn');
const restartBtn = document.getElementById('restartBtn');
const winnerModal = document.getElementById('winnerModal');
const winnerText = document.getElementById('winnerText');
const winnerDesc = document.getElementById('winnerDesc');
const modalRestartBtn = document.getElementById('modalRestartBtn');
const modeTabs = document.querySelectorAll('.mode-tab');

// Online DOM Elements
const onlinePanel = document.getElementById('onlinePanel');
const roomCreateSection = document.getElementById('roomCreateSection');
const roomActiveSection = document.getElementById('roomActiveSection');
const createRoomBtn = document.getElementById('createRoomBtn');
const joinRoomInput = document.getElementById('joinRoomInput');
const joinRoomBtn = document.getElementById('joinRoomBtn');
const currentRoomIdSpan = document.getElementById('currentRoomId');
const networkStatusSpan = document.getElementById('networkStatus');
const myStoneTextSpan = document.getElementById('myStoneText');
const copyLinkBtn = document.getElementById('copyLinkBtn');
const copySuccessMsg = document.getElementById('copySuccessMsg');

// Star points (화점) coordinates
const starPoints = [
  { r: 3, c: 3 },
  { r: 3, c: 11 },
  { r: 7, c: 7 },
  { r: 11, c: 3 },
  { r: 11, c: 11 }
];

function init() {
  board = Array.from({ length: BOARD_SIZE }, () => Array(BOARD_SIZE).fill(EMPTY));
  currentTurn = BLACK;
  history = [];
  isGameOver = false;
  winnerModal.classList.remove('active');
  updateTurnDisplay();
  drawBoard();
}

function updateTurnDisplay() {
  turnStone.className = 'stone-icon ' + (currentTurn === BLACK ? 'black' : 'white');
  turnText.textContent = (currentTurn === BLACK ? '흑돌 차례' : '백돌 차례');

  if (gameMode === 'online') {
    if (currentTurn === myRole) {
      gameHint.textContent = '내 차례입니다! 착수하세요';
      gameHint.style.color = '#2563eb';
    } else {
      gameHint.textContent = '상대방 차례입니다...';
      gameHint.style.color = '#64748b';
    }
  } else if (gameMode === 'pve') {
    if (currentTurn === BLACK) {
      gameHint.textContent = '내 차례 (흑돌)';
      gameHint.style.color = '#2563eb';
    } else {
      gameHint.textContent = 'AI 생각 중...';
      gameHint.style.color = '#64748b';
    }
  } else {
    gameHint.textContent = (currentTurn === BLACK ? '흑돌 차례' : '백돌 차례');
    gameHint.style.color = '#333';
  }
}

function drawBoard() {
  // Wood texture background
  ctx.fillStyle = '#deb887';
  ctx.fillRect(0, 0, CANVAS_SIZE, CANVAS_SIZE);

  // Border line
  ctx.strokeStyle = '#6b4f2c';
  ctx.lineWidth = 2;
  ctx.strokeRect(PADDING - 10, PADDING - 10, CANVAS_SIZE - (PADDING - 10) * 2, CANVAS_SIZE - (PADDING - 10) * 2);

  // Grid lines
  ctx.strokeStyle = '#4a3728';
  ctx.lineWidth = 1;
  for (let i = 0; i < BOARD_SIZE; i++) {
    const pos = PADDING + i * CELL_SIZE;

    // Horizontal
    ctx.beginPath();
    ctx.moveTo(PADDING, pos);
    ctx.lineTo(CANVAS_SIZE - PADDING, pos);
    ctx.stroke();

    // Vertical
    ctx.beginPath();
    ctx.moveTo(pos, PADDING);
    ctx.lineTo(pos, CANVAS_SIZE - PADDING);
    ctx.stroke();
  }

  // Draw star points
  ctx.fillStyle = '#4a3728';
  starPoints.forEach(pt => {
    const x = PADDING + pt.c * CELL_SIZE;
    const y = PADDING + pt.r * CELL_SIZE;
    ctx.beginPath();
    ctx.arc(x, y, 4, 0, Math.PI * 2);
    ctx.fill();
  });

  // Draw stones
  for (let r = 0; r < BOARD_SIZE; r++) {
    for (let c = 0; c < BOARD_SIZE; c++) {
      if (board[r][c] !== EMPTY) {
        drawStone(r, c, board[r][c]);
      }
    }
  }

  // Highlight last move
  if (history.length > 0) {
    const last = history[history.length - 1];
    const x = PADDING + last.c * CELL_SIZE;
    const y = PADDING + last.r * CELL_SIZE;
    ctx.fillStyle = '#ef4444';
    ctx.beginPath();
    ctx.arc(x, y, 4, 0, Math.PI * 2);
    ctx.fill();
  }
}

function drawStone(r, c, color) {
  const x = PADDING + c * CELL_SIZE;
  const y = PADDING + r * CELL_SIZE;
  const radius = CELL_SIZE * 0.44;

  ctx.save();
  ctx.shadowColor = 'rgba(0, 0, 0, 0.4)';
  ctx.shadowBlur = 4;
  ctx.shadowOffsetX = 2;
  ctx.shadowOffsetY = 2;

  ctx.beginPath();
  ctx.arc(x, y, radius, 0, Math.PI * 2);

  const grad = ctx.createRadialGradient(
    x - radius * 0.3,
    y - radius * 0.3,
    radius * 0.1,
    x,
    y,
    radius
  );

  if (color === BLACK) {
    grad.addColorStop(0, '#555555');
    grad.addColorStop(1, '#111111');
  } else {
    grad.addColorStop(0, '#ffffff');
    grad.addColorStop(1, '#d8d8d8');
  }

  ctx.fillStyle = grad;
  ctx.fill();
  ctx.restore();
}

function handleBoardClick(e) {
  if (isGameOver) return;

  // In online mode, only currentTurn's owner can move
  if (gameMode === 'online') {
    if (!currentRoomId) {
      alert('방을 먼저 만들거나 참여해 주세요!');
      return;
    }
    if (currentTurn !== myRole) {
      return; // Not my turn
    }
  }

  // In PvE mode, user is Black, wait for AI if White
  if (gameMode === 'pve' && currentTurn === WHITE) return;

  const rect = canvas.getBoundingClientRect();
  const scale = CANVAS_SIZE / rect.width;
  const clientX = (e.clientX || (e.touches && e.touches[0].clientX)) - rect.left;
  const clientY = (e.clientY || (e.touches && e.touches[0].clientY)) - rect.top;

  const canvasX = clientX * scale;
  const canvasY = clientY * scale;

  const c = Math.round((canvasX - PADDING) / CELL_SIZE);
  const r = Math.round((canvasY - PADDING) / CELL_SIZE);

  if (r >= 0 && r < BOARD_SIZE && c >= 0 && c < BOARD_SIZE) {
    if (board[r][c] === EMPTY) {
      if (gameMode === 'online') {
        sendMqttMessage({ type: 'move', r, c, color: myRole, sender: myPlayerId });
      }
      makeMove(r, c, currentTurn);
    }
  }
}

function makeMove(r, c, color) {
  board[r][c] = color;
  history.push({ r, c, color });
  drawBoard();

  if (checkWin(r, c, color)) {
    endGame(color);
    return;
  }

  if (history.length === BOARD_SIZE * BOARD_SIZE) {
    endGame(EMPTY); // Draw
    return;
  }

  currentTurn = (color === BLACK ? WHITE : BLACK);
  updateTurnDisplay();

  if (gameMode === 'pve' && currentTurn === WHITE && !isGameOver) {
    setTimeout(aiTurn, 300);
  }
}

function checkWin(r, c, color) {
  const directions = [
    [0, 1],   // Horizontal
    [1, 0],   // Vertical
    [1, 1],   // Diagonal \
    [1, -1]   // Diagonal /
  ];

  for (let [dr, dc] of directions) {
    let count = 1;

    let nr = r + dr;
    let nc = c + dc;
    while (nr >= 0 && nr < BOARD_SIZE && nc >= 0 && nc < BOARD_SIZE && board[nr][nc] === color) {
      count++;
      nr += dr;
      nc += dc;
    }

    nr = r - dr;
    nc = c - dc;
    while (nr >= 0 && nr < BOARD_SIZE && nc >= 0 && nc < BOARD_SIZE && board[nr][nc] === color) {
      count++;
      nr -= dr;
      nc -= dc;
    }

    if (count >= 5) return true;
  }
  return false;
}

function endGame(winner) {
  isGameOver = true;
  if (winner === BLACK) {
    if (gameMode === 'online') {
      winnerText.textContent = (myRole === BLACK ? '승리했습니다! 🎉' : '패배했습니다... 😭');
      winnerDesc.textContent = (myRole === BLACK ? '축하합니다! 5목을 완성했습니다.' : '상대방(흑돌)이 5목을 완성했습니다.');
    } else if (gameMode === 'pve') {
      winnerText.textContent = '승리했습니다! 🎉';
      winnerDesc.textContent = '플레이어가 5목을 완성했습니다.';
    } else {
      winnerText.textContent = '흑돌 승리! 🎉';
      winnerDesc.textContent = '흑돌이 5목을 완성했습니다.';
    }
  } else if (winner === WHITE) {
    if (gameMode === 'online') {
      winnerText.textContent = (myRole === WHITE ? '승리했습니다! 🎉' : '패배했습니다... 😭');
      winnerDesc.textContent = (myRole === WHITE ? '축하합니다! 5목을 완성했습니다.' : '상대방(백돌)이 5목을 완성했습니다.');
    } else if (gameMode === 'pve') {
      winnerText.textContent = 'AI 승리! 🤖';
      winnerDesc.textContent = '인공지능이 5목을 완성했습니다.';
    } else {
      winnerText.textContent = '백돌 승리! 🤖';
      winnerDesc.textContent = '백돌이 5목을 완성했습니다.';
    }
  } else {
    winnerText.textContent = '무승부! 🤝';
    winnerDesc.textContent = '판이 가득 찼습니다.';
  }
  winnerModal.classList.add('active');
}

// AI Logic
function aiTurn() {
  if (isGameOver) return;
  const bestMove = getBestMove(WHITE);
  if (bestMove) {
    makeMove(bestMove.r, bestMove.c, WHITE);
  }
}

function getBestMove(aiColor) {
  const opponentColor = (aiColor === WHITE ? BLACK : WHITE);
  let bestScore = -Infinity;
  let candidates = [];

  if (history.length <= 1) {
    const center = 7;
    const offsets = [[-1, 0], [1, 0], [0, -1], [0, 1], [-1, -1], [1, 1], [-1, 1], [1, -1]];
    for (let [dr, dc] of offsets) {
      const nr = center + dr;
      const nc = center + dc;
      if (board[nr][nc] === EMPTY) {
        return { r: nr, c: nc };
      }
    }
  }

  for (let r = 0; r < BOARD_SIZE; r++) {
    for (let c = 0; c < BOARD_SIZE; c++) {
      if (board[r][c] !== EMPTY) continue;

      let hasNeighbor = false;
      for (let dr = -2; dr <= 2 && !hasNeighbor; dr++) {
        for (let dc = -2; dc <= 2; dc++) {
          const nr = r + dr;
          const nc = c + dc;
          if (nr >= 0 && nr < BOARD_SIZE && nc >= 0 && nc < BOARD_SIZE && board[nr][nc] !== EMPTY) {
            hasNeighbor = true;
            break;
          }
        }
      }
      if (!hasNeighbor) continue;

      const attackScore = evaluateSpot(r, c, aiColor);
      const defenseScore = evaluateSpot(r, c, opponentColor);
      const centerBonus = (7 - Math.abs(r - 7)) + (7 - Math.abs(c - 7));
      const totalScore = attackScore * 1.1 + defenseScore + centerBonus;

      if (totalScore > bestScore) {
        bestScore = totalScore;
        candidates = [{ r, c }];
      } else if (totalScore === bestScore) {
        candidates.push({ r, c });
      }
    }
  }

  if (candidates.length > 0) {
    return candidates[Math.floor(Math.random() * candidates.length)];
  }
  return { r: 7, c: 7 };
}

function evaluateSpot(r, c, color) {
  let score = 0;
  const directions = [[0, 1], [1, 0], [1, 1], [1, -1]];

  for (let [dr, dc] of directions) {
    let count = 1;
    let openEnds = 0;

    let nr = r + dr;
    let nc = c + dc;
    while (nr >= 0 && nr < BOARD_SIZE && nc >= 0 && nc < BOARD_SIZE && board[nr][nc] === color) {
      count++;
      nr += dr;
      nc += dc;
    }
    if (nr >= 0 && nr < BOARD_SIZE && nc >= 0 && nc < BOARD_SIZE && board[nr][nc] === EMPTY) {
      openEnds++;
    }

    nr = r - dr;
    nc = c - dc;
    while (nr >= 0 && nr < BOARD_SIZE && nc >= 0 && nc < BOARD_SIZE && board[nr][nc] === color) {
      count++;
      nr -= dr;
      nc -= dc;
    }
    if (nr >= 0 && nr < BOARD_SIZE && nc >= 0 && nc < BOARD_SIZE && board[nr][nc] === EMPTY) {
      openEnds++;
    }

    if (count >= 5) score += 100000;
    else if (count === 4) {
      score += openEnds === 2 ? 10000 : 2000;
    } else if (count === 3) {
      score += openEnds === 2 ? 1500 : 300;
    } else if (count === 2) {
      score += openEnds === 2 ? 100 : 20;
    }
  }
  return score;
}

function undoMove() {
  if (history.length === 0 || isGameOver) return;

  if (gameMode === 'online') {
    if (history.length > 0) {
      const last = history[history.length - 1];
      if (last.color !== myRole) {
        alert('상대방의 수는 무를 수 없습니다!');
        return;
      }
      sendMqttMessage({ type: 'undo', sender: myPlayerId });
      performUndo();
    }
    return;
  }

  if (gameMode === 'pve') {
    if (history.length >= 2) {
      const m1 = history.pop();
      board[m1.r][m1.c] = EMPTY;
      const m2 = history.pop();
      board[m2.r][m2.c] = EMPTY;
      currentTurn = BLACK;
    } else if (history.length === 1) {
      const m = history.pop();
      board[m.r][m.c] = EMPTY;
      currentTurn = BLACK;
    }
  } else {
    performUndo();
  }

  updateTurnDisplay();
  drawBoard();
}

function performUndo() {
  if (history.length === 0) return;
  const last = history.pop();
  board[last.r][last.c] = EMPTY;
  currentTurn = last.color;
  updateTurnDisplay();
  drawBoard();
}

// ---------------- ONLINE MULTIPLAYER (MQTT) ---------------- //

function connectMqtt(roomId, onConnectedCallback) {
  if (mqttClient && mqttClient.isConnected()) {
    mqttClient.disconnect();
  }

  const clientId = 'omok_' + Math.random().toString(16).substr(2, 8);
  mqttClient = new Paho.MQTT.Client(MQTT_BROKER, MQTT_PORT, clientId);

  mqttClient.onConnectionLost = (responseObject) => {
    if (responseObject.errorCode !== 0) {
      console.log('Connection lost:', responseObject.errorMessage);
      networkStatusSpan.textContent = '재연결 중...';
      networkStatusSpan.className = 'status-badge waiting';
    }
  };

  mqttClient.onMessageArrived = (message) => {
    try {
      const data = JSON.parse(message.payloadString);
      handleMqttMessage(data);
    } catch (e) {
      console.error('Failed to parse message', e);
    }
  };

  mqttClient.connect({
    useSSL: true,
    timeout: 10,
    keepAliveInterval: 30,
    cleanSession: true,
    onSuccess: () => {
      console.log('Connected to MQTT broker!');
      const topic = `omok2120/room/${roomId}`;
      mqttClient.subscribe(topic, { qos: 1 });
      if (onConnectedCallback) onConnectedCallback();
    },
    onFailure: (err) => {
      console.error('MQTT Connect failed', err);
      alert('온라인 서버 연결에 실패했습니다. 다시 시도해 주세요.');
    }
  });
}

function sendMqttMessage(payload) {
  if (!mqttClient || !mqttClient.isConnected() || !currentRoomId) return;
  const message = new Paho.MQTT.Message(JSON.stringify(payload));
  message.destinationName = `omok2120/room/${currentRoomId}`;
  message.qos = 1;
  mqttClient.send(message);
}

function handleMqttMessage(data) {
  if (data.sender === myPlayerId) return; // Ignore own messages

  if (data.type === 'join') {
    // Guest joined
    networkStatusSpan.textContent = '친구 연결됨 🟢';
    networkStatusSpan.className = 'status-badge connected';
    // Reply with ready state to acknowledge
    sendMqttMessage({ type: 'ready', sender: myPlayerId });
  } else if (data.type === 'ready') {
    networkStatusSpan.textContent = '게임 중 🟢';
    networkStatusSpan.className = 'status-badge connected';
  } else if (data.type === 'move') {
    if (board[data.r][data.c] === EMPTY) {
      makeMove(data.r, data.c, data.color);
    }
  } else if (data.type === 'undo') {
    performUndo();
  } else if (data.type === 'restart') {
    init();
  }
}

function startOnlineRoom(roomId, role) {
  currentRoomId = roomId;
  myRole = role;

  currentRoomIdSpan.textContent = roomId;
  myStoneTextSpan.textContent = (role === BLACK ? '흑돌 (선공)' : '백돌 (후공)');
  networkStatusSpan.textContent = (role === BLACK ? '친구 대기 중 ⏳' : '연결 중... ⏳');
  networkStatusSpan.className = 'status-badge waiting';

  roomCreateSection.style.display = 'none';
  roomActiveSection.style.display = 'flex';

  init();

  connectMqtt(roomId, () => {
    if (role === WHITE) {
      // Send join notice to host
      sendMqttMessage({ type: 'join', sender: myPlayerId });
      networkStatusSpan.textContent = '접속 완료 🟢';
      networkStatusSpan.className = 'status-badge connected';
    }
  });
}

// Event Listeners
canvas.addEventListener('click', handleBoardClick);
canvas.addEventListener('touchstart', (e) => {
  e.preventDefault();
  handleBoardClick(e);
}, { passive: false });

undoBtn.addEventListener('click', undoMove);

restartBtn.addEventListener('click', () => {
  if (gameMode === 'online') {
    sendMqttMessage({ type: 'restart', sender: myPlayerId });
  }
  init();
});

modalRestartBtn.addEventListener('click', () => {
  if (gameMode === 'online') {
    sendMqttMessage({ type: 'restart', sender: myPlayerId });
  }
  init();
});

// Mode tab selection
modeTabs.forEach(tab => {
  tab.addEventListener('click', () => {
    modeTabs.forEach(t => t.classList.remove('active'));
    tab.classList.add('active');

    gameMode = tab.dataset.mode;
    if (gameMode === 'online') {
      onlinePanel.style.display = 'block';
      undoBtn.style.display = 'block';
    } else if (gameMode === 'pve') {
      onlinePanel.style.display = 'none';
      undoBtn.style.display = 'block';
    } else {
      onlinePanel.style.display = 'none';
      undoBtn.style.display = 'block';
    }
    init();
  });
});

// Online Room Handlers
createRoomBtn.addEventListener('click', () => {
  const randomRoom = Math.floor(1000 + Math.random() * 9000).toString();
  startOnlineRoom(randomRoom, BLACK);
});

joinRoomBtn.addEventListener('click', () => {
  const inputCode = joinRoomInput.value.trim();
  if (!inputCode) {
    alert('방 번호를 입력해 주세요!');
    return;
  }
  startOnlineRoom(inputCode, WHITE);
});

copyLinkBtn.addEventListener('click', () => {
  if (!currentRoomId) return;
  const inviteUrl = window.location.origin + window.location.pathname + '?room=' + currentRoomId;

  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(inviteUrl).then(() => {
      showCopyToast();
    }).catch(() => {
      fallbackCopy(inviteUrl);
    });
  } else {
    fallbackCopy(inviteUrl);
  }
});

function fallbackCopy(text) {
  const tempInput = document.createElement('input');
  tempInput.value = text;
  document.body.appendChild(tempInput);
  tempInput.select();
  document.execCommand('copy');
  document.body.removeChild(tempInput);
  showCopyToast();
}

function showCopyToast() {
  copySuccessMsg.classList.add('active');
  setTimeout(() => {
    copySuccessMsg.classList.remove('active');
  }, 2500);
}

// Auto join if room query param exists
function checkUrlRoomParam() {
  const urlParams = new URLSearchParams(window.location.search);
  const room = urlParams.get('room');
  if (room) {
    // Activate online tab
    modeTabs.forEach(t => {
      if (t.dataset.mode === 'online') t.classList.add('active');
      else t.classList.remove('active');
    });
    gameMode = 'online';
    onlinePanel.style.display = 'block';
    startOnlineRoom(room, WHITE);
  }
}

// Start
init();
checkUrlRoomParam();
