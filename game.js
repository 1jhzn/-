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
let gameMode = 'pve'; // 'pve' (vs AI) or 'pvp' (2 players)

const canvas = document.getElementById('omokCanvas');
const ctx = canvas.getContext('2d');
const turnStone = document.getElementById('turnStone');
const turnText = document.getElementById('turnText');
const undoBtn = document.getElementById('undoBtn');
const restartBtn = document.getElementById('restartBtn');
const winnerModal = document.getElementById('winnerModal');
const winnerText = document.getElementById('winnerText');
const winnerDesc = document.getElementById('winnerDesc');
const modalRestartBtn = document.getElementById('modalRestartBtn');
const modeRadios = document.querySelectorAll('input[name="mode"]');

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
}

function drawBoard() {
  // Wood texture background
  ctx.fillStyle = '#deb887';
  ctx.fillRect(0, 0, CANVAS_SIZE, CANVAS_SIZE);

  // Border lines
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
    ctx.fillStyle = last.color === BLACK ? '#ef4444' : '#dc2626';
    ctx.beginPath();
    ctx.arc(x, y, 3.5, 0, Math.PI * 2);
    ctx.fill();
  }
}

function drawStone(r, c, color) {
  const x = PADDING + c * CELL_SIZE;
  const y = PADDING + r * CELL_SIZE;
  const radius = CELL_SIZE * 0.44;

  ctx.save();
  // Shadow
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
  if (gameMode === 'pve' && currentTurn === WHITE) return; // Wait for AI

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

    // Positive direction
    let nr = r + dr;
    let nc = c + dc;
    while (nr >= 0 && nr < BOARD_SIZE && nc >= 0 && nc < BOARD_SIZE && board[nr][nc] === color) {
      count++;
      nr += dr;
      nc += dc;
    }

    // Negative direction
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
    winnerText.textContent = '흑돌 승리! 🎉';
    winnerDesc.textContent = (gameMode === 'pve' ? '플레이어가 승리했습니다!' : '흑돌이 5목을 완성했습니다.');
  } else if (winner === WHITE) {
    winnerText.textContent = '백돌 승리! 🤖';
    winnerDesc.textContent = (gameMode === 'pve' ? '인공지능이 승리했습니다.' : '백돌이 5목을 완성했습니다.');
  } else {
    winnerText.textContent = '무승부! 🤝';
    winnerDesc.textContent = '판이 가득 찼습니다.';
  }
  winnerModal.classList.add('active');
}

// AI Logic (Heuristic evaluation)
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

  // If first move for AI, play near center
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

      // Only check spots near existing stones
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

    // Direction 1
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

    // Direction 2
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

  if (gameMode === 'pve') {
    // Undo both AI and player move
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
    // PvP: Undo single move
    const last = history.pop();
    board[last.r][last.c] = EMPTY;
    currentTurn = last.color;
  }

  updateTurnDisplay();
  drawBoard();
}

// Event Listeners
canvas.addEventListener('click', handleBoardClick);
canvas.addEventListener('touchstart', (e) => {
  e.preventDefault();
  handleBoardClick(e);
}, { passive: false });

undoBtn.addEventListener('click', undoMove);
restartBtn.addEventListener('click', init);
modalRestartBtn.addEventListener('click', init);

modeRadios.forEach(radio => {
  radio.addEventListener('change', (e) => {
    gameMode = e.target.value;
    init();
  });
});

// Start game
init();
