/* ============================================================
   전파 신호 감지기 — 직접 만든 단일 페이지 앱
   · 주변 BLE 광고 신호 수집 (자동 전체 스캔 / 기기 선택)
   · 기준 저장 → 새 신호·세기 급변 알림
   · 제조사 ID / 서비스 UUID / 광고 데이터로 발신지 판별
   · 주파수·대역 조회
   ============================================================ */
(function () {
'use strict';

/* ---------------- 유틸 ---------------- */
var $ = function (s, r) { return (r || document).querySelector(s); };
var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
function esc(v) {
  return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}
function hex2(n) { return ('0' + (n & 255).toString(16)).slice(-2).toUpperCase(); }
function hex4(n) { return ('000' + (n & 0xffff).toString(16).toUpperCase()).slice(-4); }
function bytesOf(dv) {
  if (!dv) return null;
  try {
    var u = new Uint8Array(dv.buffer, dv.byteOffset, dv.byteLength);
    return Array.prototype.slice.call(u);
  } catch (e) { return null; }
}
function toHex(arr, max) {
  if (!arr || !arr.length) return '';
  var a = max ? arr.slice(0, max) : arr;
  var s = a.map(hex2).join(' ');
  return max && arr.length > max ? s + ' …(' + arr.length + '바이트)' : s;
}
function hash32(str) {
  var h = 2166136261 >>> 0;
  for (var i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = Math.imul(h, 16777619) >>> 0; }
  return h >>> 0;
}
function num(v) { return (typeof v === 'number' && isFinite(v)) ? v : null; }
function shortUuid(u) {
  if (!u) return '';
  var s = String(u).toLowerCase();
  var m = s.match(/^0000([0-9a-f]{4})-0000-1000-8000-00805f9b34fb$/);
  if (m) return m[1];
  m = s.match(/^([0-9a-f]{4})$/);
  if (m) return m[1];
  return s;
}
function fmtInt(n) { return String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ','); }
function nowStr(ts) {
  var d = new Date(ts);
  return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2) + ':' + ('0' + d.getSeconds()).slice(-2);
}

/* ---------------- 저장소 ---------------- */
var LS_BASE = 'sigscan.baseline.v2';
var LS_SET = 'sigscan.settings.v1';
var LS_LOG = 'sigscan.alerts.v1';
function storeGet(k, def) {
  try { var v = localStorage.getItem(k); return v ? JSON.parse(v) : def; } catch (e) { return def; }
}
function storeSet(k, v) {
  try { localStorage.setItem(k, JSON.stringify(v)); return true; } catch (e) { return false; }
}

/* ---------------- 제조사 ID (블루투스 SIG 회사 식별자) ---------------- */
var COMPANY = {
  0x0001: { n: 'Nokia', d: '노키아 휴대폰·액세서리' },
  0x0002: { n: 'Intel', d: '인텔 무선 모듈(노트북·미니PC)' },
  0x0006: { n: 'Microsoft', d: '마이크로소프트(서피스·엑스박스·마우스)' },
  0x000F: { n: 'Broadcom', d: '브로드컴 무선 칩' },
  0x001D: { n: 'Qualcomm', d: '퀄컴·CSR 블루투스 칩' },
  0x004C: { n: 'Apple', d: '애플(아이폰·아이패드·에어팟·애플워치·맥·에어태그)', k: 'phone', kind: '애플 기기(아이폰·아이패드·맥·에어팟·워치 중 하나)' },
  0x0057: { n: 'Harman', d: '하만·JBL 스피커·이어폰', k: 'audio', kind: 'JBL·하만 스피커/이어폰' },
  0x0059: { n: 'Nordic Semiconductor', d: '노르딕 BLE 칩(개발 보드·비콘·웨어러블에 흔함)', k: 'iot', kind: '노르딕 칩을 쓴 기기(개발 보드·비콘·웨어러블)' },
  0x005D: { n: 'Realtek', d: '리얼텍 무선 모듈' },
  0x0075: { n: 'Samsung', d: '삼성전자(갤럭시·버즈·워치·스마트태그)', k: 'phone', kind: '삼성 갤럭시 기기(폰·버즈·워치·태그 중 하나)' },
  0x0087: { n: 'Garmin', d: '가민 스포츠 워치·사이클 컴퓨터', k: 'watch', kind: '가민 워치·사이클 컴퓨터' },
  0x009E: { n: 'Bose', d: '보스 스피커·이어폰', k: 'audio', kind: '보스 스피커/이어폰' },
  0x00C4: { n: 'LG Electronics', d: 'LG전자(톤프리·워치·가전)', k: 'audio', kind: 'LG 기기(톤프리·워치·가전)' },
  0x012D: { n: 'Sony', d: '소니(WH/WF 이어폰·플레이스테이션)', k: 'audio', kind: '소니 기기(이어폰·헤드폰·플스)' },
  0x0157: { n: 'Huami', d: '샤오미 미밴드·아마즈핏', k: 'watch', kind: '미밴드·아마즈핏 밴드' },
  0x0171: { n: 'Amazon', d: '아마존(에코·파이어TV)', k: 'iot', kind: '아마존 기기(에코·파이어TV)' },
  0x02D5: { n: 'Tile', d: '타일 블루투스 위치추적기', k: 'tag', kind: '타일 위치추적기' },
  0x02E5: { n: 'Espressif', d: 'ESP32 계열 개발 보드', k: 'iot', kind: 'ESP32 계열 보드' },
  0x038F: { n: 'Xiaomi', d: '샤오미(미밴드·버즈·홍미·샤오미TV)', k: 'iot', kind: '샤오미 기기(미밴드·버즈·TV 등)' },
  0x0499: { n: 'Ruuvi', d: '루비 온도·습도 센서', k: 'sensor', kind: '온도·습도 센서' }
};

/* ---------------- 기기 이름 패턴 ---------------- */
var NAME_RULES = [
  { re: /airpods/i, s: 8, cat: 'audio', kind: '에어팟(AirPods) 무선 이어폰', ico: '🎧', label: 'AirPods' },
  { re: /airtag/i, s: 8, cat: 'tag', kind: '에어태그(AirTag) 위치 태그', ico: '🏷️', label: 'AirTag' },
  { re: /beats/i, s: 7, cat: 'audio', kind: '비츠(Beats) 이어폰/헤드폰', ico: '🎧', label: 'Beats' },
  { re: /freebuds|huawei/i, s: 7, cat: 'audio', kind: '화웨이 이어폰', ico: '🎧', label: 'FreeBuds' },
  { re: /galaxy\s*buds|^buds/i, s: 7, cat: 'audio', kind: '갤럭시 버즈(Galaxy Buds)', ico: '🎧', label: 'Galaxy Buds' },
  { re: /buds/i, s: 6, cat: 'audio', kind: '무선 이어폰(버즈 계열)', ico: '🎧', label: 'Buds' },
  { re: /soundbar|speaker|스피커|jbl|bose|sonos|marshall|boom/i, s: 6, cat: 'audio', kind: '블루투스 스피커', ico: '🔊', label: 'Speaker' },
  { re: /headphone|헤드폰|wh-?1000|wf-?1000/i, s: 6, cat: 'audio', kind: '헤드폰/이어폰', ico: '🎧', label: 'Headphone' },
  { re: /galaxy\s*(s\d|a\d|note|z\s*flip|z\s*fold|fold|flip)/i, s: 8, cat: 'phone', kind: '삼성 갤럭시 스마트폰', ico: '📱', label: 'Galaxy' },
  { re: /^sm-[a-z0-9]/i, s: 8, cat: 'phone', kind: '삼성 갤럭시 스마트폰', ico: '📱', label: 'SM-' },
  { re: /iphone|ipad|ipod/i, s: 8, cat: 'phone', kind: '아이폰/아이패드', ico: '📱', label: 'iPhone' },
  { re: /macbook|imac|mac\s*mini/i, s: 7, cat: 'pc', kind: '맥(컴퓨터)', ico: '💻', label: 'Mac' },
  { re: /galaxy\s*watch|watch\s*\d|^watch|워치|smart\s*watch/i, s: 7, cat: 'watch', kind: '스마트워치', ico: '⌚', label: 'Watch' },
  { re: /mi\s*band|miband|amazfit|band\s*\d|^band/i, s: 7, cat: 'watch', kind: '밴드형 활동량 측정기', ico: '⌚', label: 'Band' },
  { re: /garmin|fenix|forerunner|edge\s*\d/i, s: 7, cat: 'watch', kind: '가민 워치·사이클 컴퓨터', ico: '⌚', label: 'Garmin' },
  { re: /fitbit/i, s: 7, cat: 'watch', kind: '핏빗 활동량 측정기', ico: '⌚', label: 'Fitbit' },
  { re: /smart\s*tag|smarttag|스마트태그/i, s: 8, cat: 'tag', kind: '삼성 스마트태그(SmartTag)', ico: '🏷️', label: 'SmartTag' },
  { re: /^tile/i, s: 7, cat: 'tag', kind: '타일(Tile) 위치추적기', ico: '🏷️', label: 'Tile' },
  { re: /micro:bit|microbit/i, s: 9, cat: 'iot', kind: '마이크로비트(micro:bit) 교육용 보드', ico: '🔧', label: 'micro:bit' },
  { re: /esp32|esp8266|nodemcu|arduino/i, s: 8, cat: 'iot', kind: 'ESP32·아두이노 개발 보드', ico: '🔧', label: 'ESP32' },
  { re: /^hm-?10|hc-?0[568]|jdy-?\d/i, s: 8, cat: 'iot', kind: 'BLE 시리얼 모듈(HM-10 계열)', ico: '🔧', label: 'HM-10' },
  { re: /\[tv\]|^tv$|bravia|mibox|chromecast|android\s*tv|webos|smart\s*tv|\btv\b/i, s: 6, cat: 'tv', kind: 'TV·셋톱박스', ico: '📺', label: 'TV' },
  { re: /printer|프린터|hp\s*office|epson|brother/i, s: 7, cat: 'printer', kind: '프린터', ico: '🖨️', label: 'Printer' },
  { re: /keyboard|mouse|키보드|마우스/i, s: 7, cat: 'pc', kind: '키보드·마우스', ico: '⌨️', label: 'Keyboard' },
  { re: /car|obd|bmw|kia|hyundai|tesla|benz|audi/i, s: 6, cat: 'car', kind: '자동차·차량 액세서리', ico: '🚗', label: 'Car' },
  { re: /drone|dji|mavic/i, s: 6, cat: 'drone', kind: '드론', ico: '🛸', label: 'Drone' },
  { re: /redmi|poco|xiaomi|mi\s*(box|tv|band|speaker)/i, s: 6, cat: 'iot', kind: '샤오미 기기', ico: '🔧', label: 'Xiaomi' }
];

var CAT = {
  phone: { ico: '📱', cls: 'b-phone', title: '휴대폰·태블릿' },
  audio: { ico: '🎧', cls: 'b-audio', title: '음향기기' },
  tag: { ico: '🏷️', cls: 'b-tag', title: '위치 태그' },
  pc: { ico: '💻', cls: 'b-pc', title: '컴퓨터·주변기기' },
  watch: { ico: '⌚', cls: 'b-iot', title: '웨어러블' },
  iot: { ico: '🔧', cls: 'b-iot', title: '개발 보드·IoT' },
  tv: { ico: '📺', cls: 'b-pc', title: 'TV·셋톱' },
  printer: { ico: '🖨️', cls: 'b-pc', title: '프린터' },
  car: { ico: '🚗', cls: 'b-pc', title: '차량' },
  drone: { ico: '🛸', cls: 'b-pc', title: '드론' },
  sensor: { ico: '🌡️', cls: 'b-iot', title: '센서' },
  beacon: { ico: '📡', cls: 'b-tag', title: '비콘' },
  remote: { ico: '🎮', cls: 'b-pc', title: '조종·리모컨' },
  glasses: { ico: '🕶️', cls: 'b-pc', title: '안경형 기기' },
  network: { ico: '📶', cls: 'b-pc', title: '네트워크 장비' },
  light: { ico: '💡', cls: 'b-iot', title: '조명' },
  unknown: { ico: '❓', cls: 'b-unknown', title: '알 수 없음' }
};

/* ---------------- GATT 서비스 UUID ---------------- */
var SERVICES = {
  '180f': { n: '배터리 상태', s: 0.5, cat: 'unknown' },
  '180a': { n: '기기 정보(제조사·모델명)', s: 0.5, cat: 'unknown' },
  '1800': { n: '일반 접속(GAP)', s: 0, cat: 'unknown' },
  '1801': { n: '일반 속성(GATT)', s: 0, cat: 'unknown' },
  '180d': { n: '심박 측정', s: 3, cat: 'watch', kind: '심박 센서·스마트워치' },
  '1809': { n: '체온계', s: 3, cat: 'sensor', kind: '체온 센서' },
  '1810': { n: '혈압계', s: 3, cat: 'sensor', kind: '혈압계' },
  '181a': { n: '환경 센서(온·습도)', s: 3, cat: 'sensor', kind: '온습도 센서' },
  '1812': { n: 'HID(키보드·마우스)', s: 3.5, cat: 'pc', kind: '키보드·마우스·게임패드' },
  '1814': { n: '러닝 속도·케이던스', s: 3, cat: 'sensor', kind: '러닝 센서' },
  '1816': { n: '사이클 속도·케이던스', s: 3, cat: 'sensor', kind: '사이클 센서' },
  '1818': { n: '사이클 파워 미터', s: 3, cat: 'sensor', kind: '파워 미터' },
  '1826': { n: '피트니스 머신', s: 2.5, cat: 'sensor', kind: '실내 운동기구' },
  'fe9f': { n: 'Google Fast Pair(빠른 페어링)', s: 3, cat: 'audio', kind: '이어폰·헤드폰(빠른 페어링 지원)' },
  'fd5a': { n: '삼성 스마트태그 서비스', s: 4, cat: 'tag', kind: '삼성 스마트태그(SmartTag)' },
  'feaa': { n: 'Eddystone 비콘', s: 3.5, cat: 'beacon', kind: 'Eddystone 비콘' },
  'fe59': { n: '노르딕 펌웨어 업데이트(DFU)', s: 1.5, cat: 'iot' },
  'ffe0': { n: 'HM-10·AT-09 시리얼 모듈', s: 3, cat: 'iot', kind: 'BLE 시리얼 모듈' },
  'fff0': { n: '텍사스인스트루먼트 계열 모듈', s: 2, cat: 'iot' },
  '181c': { n: '사용자 데이터', s: 0.5, cat: 'unknown' },
  '181d': { n: '체성분 측정', s: 2.5, cat: 'sensor', kind: '체성분계' },
  'fd6f': { n: '접촉 알림(레거시)', s: 0.5, cat: 'unknown' }
};

/* ---------------- 기기 종류(appearance) ---------------- */
var APPEAR = {
  1: { cat: 'phone', kind: '휴대폰', n: '휴대폰' },
  2: { cat: 'pc', kind: '컴퓨터·노트북', n: '컴퓨터' },
  3: { cat: 'watch', kind: '시계·스마트워치', n: '시계' },
  4: { cat: 'watch', kind: '시계', n: '시계' },
  5: { cat: 'pc', kind: '화면·디스플레이 기기', n: '디스플레이' },
  6: { cat: 'remote', kind: '무선 리모컨·조종 장치', n: '리모컨' },
  7: { cat: 'glasses', kind: '안경형 기기', n: '안경' },
  8: { cat: 'tag', kind: '위치 태그', n: '태그' },
  9: { cat: 'tag', kind: '키링형 태그', n: '키링' },
  10: { cat: 'audio', kind: '미디어 플레이어', n: '미디어' },
  11: { cat: 'iot', kind: '바코드 스캐너', n: '스캐너' },
  12: { cat: 'sensor', kind: '온도계', n: '온도계' },
  13: { cat: 'watch', kind: '심박 센서', n: '심박' },
  14: { cat: 'sensor', kind: '혈압계', n: '혈압' },
  15: { cat: 'pc', kind: '키보드·마우스', n: 'HID' },
  16: { cat: 'sensor', kind: '혈당계', n: '혈당' },
  17: { cat: 'sensor', kind: '러닝·보행 센서', n: '러닝' },
  18: { cat: 'sensor', kind: '사이클 센서', n: '사이클' },
  19: { cat: 'remote', kind: '조종 장치', n: '조종' },
  20: { cat: 'network', kind: '네트워크 장비', n: '네트워크' },
  21: { cat: 'sensor', kind: '센서', n: '센서' },
  22: { cat: 'light', kind: '조명', n: '조명' }
};

/* ---------------- 상태 ---------------- */
var state = {
  scanning: false,
  mode: null,            // 'lescan' | 'picker'
  devices: new Map(),    // key -> rec
  alerts: [],
  baseline: {},
  baselineAt: 0,
  settings: { sound: true, vibe: true, mode: 'both', jump: 15 },
  available: null,
  bannerTimer: null,
  hideStale: false,
  radarCount: 0,
  dirty: true,
  sweeping: false
};

var bt = navigator.bluetooth || null;
var activeScan = null;
var audioCtx = null;

/* ---------------- 알림음 ---------------- */
function beep(kind) {
  if (!state.settings.sound) return;
  try {
    if (!audioCtx) {
      var AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return;
      audioCtx = new AC();
    }
    if (audioCtx.state === 'suspended') audioCtx.resume();
    var t0 = audioCtx.currentTime;
    var notes = kind === 'jump' ? [660, 990] : [880, 1320];
    notes.forEach(function (f, i) {
      var o = audioCtx.createOscillator();
      var g = audioCtx.createGain();
      o.type = 'sine';
      o.frequency.value = f;
      var st = t0 + i * 0.13;
      g.gain.setValueAtTime(0, st);
      g.gain.linearRampToValueAtTime(0.18, st + 0.015);
      g.gain.exponentialRampToValueAtTime(0.0008, st + 0.13);
      o.connect(g); g.connect(audioCtx.destination);
      o.start(st); o.stop(st + 0.15);
    });
  } catch (e) {}
}
function buzz(kind) {
  if (!state.settings.vibe) return;
  try {
    if (!navigator.vibrate) return;
    navigator.vibrate(kind === 'jump' ? [60, 70, 60] : [110, 60, 110]);
  } catch (e) {}
}

/* ---------------- 광고 데이터 해석 ---------------- */
function eddyUrl(d) {
  var schemes = ['http://www.', 'https://www.', 'http://', 'https://'];
  var exp = { 0: '.com/', 1: '.org/', 2: '.edu/', 3: '.net/', 4: '.info/', 5: '.biz/', 6: '.gov/',
              7: '.com', 8: '.org', 9: '.edu', 10: '.net', 11: '.info', 12: '.biz', 13: '.gov' };
  var s = schemes[d[2]] || '';
  for (var i = 3; i < d.length; i++) {
    var c = d[i];
    s += (c <= 13 && exp[c] != null) ? exp[c] : String.fromCharCode(c);
  }
  return s;
}

function decodeAdv(rec) {
  var out = { lines: [], appleType: null, beacon: null, eddy: null, fastpair: null, smarttag: false };
  var b = rec.bytes;
  if (b && b.length >= 3 && rec.companyId === 0x004C) {
    var t = b[2];
    var map = { 0x02: 'iBeacon', 0x05: 'AirDrop', 0x07: '에어팟·홈팟', 0x09: 'AirPlay',
                0x0C: 'Handoff(기기 이어받기)', 0x0F: '근처 상호작용', 0x10: '근처 정보', 0x12: '나의 찾기' };
    out.appleType = map[t] || ('타입 0x' + hex2(t));
    if (t === 0x02 && b.length >= 25 && b[3] === 0x15) {
      var uuid = b.slice(4, 20).map(hex2).join('');
      var major = (b[20] << 8) | b[21];
      var minor = (b[22] << 8) | b[23];
      var mp = b[24] > 127 ? b[24] - 256 : b[24];
      out.beacon = { uuid: uuid, major: major, minor: minor, measured: mp };
      out.lines.push('iBeacon 비콘 신호 · major ' + major + ' / minor ' + minor);
      out.lines.push('1m 기준 세기 ' + mp + 'dBm');
    } else {
      out.lines.push('애플 연속성 신호 — ' + out.appleType);
    }
  }
  var sd = rec.serviceData;
  if (sd) {
    Object.keys(sd).forEach(function (u) {
      var k = shortUuid(u), d = sd[u] || [];
      if (k === 'feaa' && d.length) {
        var ft = d[0];
        if (ft === 0x00 && d.length >= 17) {
          out.eddy = { type: 'UID', ns: d.slice(2, 12).map(hex2).join(''), inst: d.slice(12, 18).map(hex2).join('') };
          out.lines.push('Eddystone UID 비콘 · 네임스페이스 ' + out.eddy.ns.slice(0, 8) + '…');
        } else if (ft === 0x10 && d.length >= 4) {
          var url = eddyUrl(d);
          out.eddy = { type: 'URL', url: url };
          out.lines.push('Eddystone URL 비콘 → ' + url);
        } else if (ft === 0x20) {
          out.lines.push('Eddystone TLM (비콘 상태 정보)');
        } else {
          out.lines.push('Eddystone 비콘 신호 (0xFEAA)');
        }
      } else if (k === 'fe9f' && d.length >= 3) {
        var model = ((d[0] << 16) | (d[1] << 8) | d[2]).toString(16).toUpperCase();
        while (model.length < 6) model = '0' + model;
        out.fastpair = model;
        out.lines.push('Google Fast Pair · 모델 ID 0x' + model);
      } else if (k === 'fd5a') {
        out.smarttag = true;
        out.lines.push('삼성 스마트태그(SmartTag) 신호');
      } else {
        out.lines.push('서비스 데이터 ' + k.toUpperCase() + ' — ' + d.length + '바이트');
      }
    });
  }
  return out;
}

/* ---------------- 발신지 판별 (분류는 캐시, 거리는 매번) ---------------- */
function classify(rec) {
  var ck = [rec.name, rec.companyId, rec.appearance, rec.txPower,
            (rec.uuids || []).join(','),
            rec.bytes ? rec.bytes.length + ':' + rec.bytes.slice(0, 6).join('.' ) : '0',
            rec.serviceData ? Object.keys(rec.serviceData).join(',') : ''].join('~');
  if (rec._ck === ck && rec._cls) return rec._cls;

  var dec = decodeAdv(rec);
  var cands = [];
  var reasons = [];
  var cname = null, cdesc = null, cdat = null;

  if (rec.companyId != null) {
    var c = COMPANY[rec.companyId];
    if (c) {
      cname = c.n; cdesc = c.d; cdat = c;
      reasons.push('제조사 ID 0x' + hex4(rec.companyId) + ' — ' + c.n);
    } else {
      cname = '등록되지 않은 제조사';
      reasons.push('제조사 ID 0x' + hex4(rec.companyId) + ' — 표준 목록에 없음');
    }
  }
  (rec.uuids || []).forEach(function (u) {
    var k = shortUuid(u), s = SERVICES[k];
    if (s) {
      if (s.s >= 1.5 && s.kind) cands.push({ s: s.s, cat: s.cat || 'unknown', kind: s.kind, ico: (CAT[s.cat] || CAT.unknown).ico });
      reasons.push('서비스 UUID 0x' + k.toUpperCase() + ' — ' + s.n);
    } else if (/^[0-9a-f]{8}-/.test(k)) {
      reasons.push('사용자 정의 서비스 UUID — ' + k.slice(0, 8) + '…');
    }
  });
  if (rec.name) {
    NAME_RULES.forEach(function (r) {
      if (r.re.test(rec.name)) {
        cands.push({ s: r.s, cat: r.cat, kind: r.kind, ico: r.ico });
        reasons.push('기기 이름에 "' + r.label + '" 포함');
      }
    });
  }
  if (rec.appearance != null) {
    var a = APPEAR[rec.appearance >> 6];
    if (a) {
      cands.push({ s: 1.5, cat: a.cat, kind: a.kind, ico: (CAT[a.cat] || CAT.unknown).ico });
      reasons.push('기기 종류 값 ' + rec.appearance + ' — ' + a.n);
    }
  }
  if (dec.beacon) {
    cands.push({ s: 4, cat: 'beacon', kind: 'iBeacon 비콘(위치 기반 서비스용)', ico: '📡' });
    reasons.push('애플 iBeacon 형식 광고');
  }
  if (dec.eddy) {
    cands.push({ s: 4, cat: 'beacon', kind: 'Eddystone 비콘', ico: '📡' });
    reasons.push('Eddystone 비콘 형식 광고');
  }
  if (dec.fastpair) {
    cands.push({ s: 2.5, cat: 'audio', kind: '이어폰·헤드폰(빠른 페어링)', ico: '🎧' });
    reasons.push('Google Fast Pair 페어링 신호');
  }
  if (dec.smarttag) {
    cands.push({ s: 4, cat: 'tag', kind: '삼성 스마트태그(SmartTag)', ico: '🏷️' });
    reasons.push('삼성 스마트태그 서비스 데이터');
  }
  if (!cands.length && cdat) {
    cands.push({ s: 1, cat: cdat.k || 'unknown', kind: cdat.kind || (cdat.n + ' 기기'), ico: (CAT[cdat.k || 'unknown'] || CAT.unknown).ico });
  }

  var best = null;
  cands.forEach(function (c) { if (!best || c.s > best.s) best = c; });

  var score = (cname ? 2 : 0) + (best ? best.s : 0) +
              (dec.appleType ? 1 : 0) + (dec.beacon ? 1 : 0);
  var conf = score >= 7 ? '높음' : (score >= 3.5 ? '보통' : '낮음');
  var confCls = score >= 7 ? '' : (score >= 3.5 ? 'mid' : 'low');

  var cat = best ? best.cat : (cdat && cdat.k ? cdat.k : 'unknown');
  var kind = best ? best.kind : '기기 종류를 알 수 없음';
  var ico = best ? best.ico : (CAT[cat] ? CAT[cat].ico : '❓');

  if (dec.appleType && cdat && cdat.n === 'Apple') {
    kind = kind.indexOf('애플') === 0 || cat === 'audio' || cat === 'tag' ? kind : ('애플 기기 · ' + dec.appleType);
  }

  var cls = {
    cat: cat, kind: kind, ico: ico, conf: conf, confCls: confCls,
    company: cname || '제조사 미상', companyDesc: cdesc || '',
    reasons: reasons, dec: dec, score: score
  };
  rec._ck = ck;
  rec._cls = cls;
  return cls;
}

function analyze(rec) {
  var c = classify(rec);
  return {
    cat: c.cat, kind: c.kind, ico: c.ico, conf: c.conf, confCls: c.confCls,
    company: c.company, companyDesc: c.companyDesc, reasons: c.reasons,
    dec: c.dec, score: c.score, dist: distanceOf(rec, c.dec)
  };
}

function distanceOf(rec, dec) {
  var rssi = rec.rssiSmooth;
  if (rssi == null) return null;
  var ref = -59;
  if (dec && dec.beacon && dec.beacon.measured != null) ref = dec.beacon.measured;
  else if (rec.txPower != null) ref = -59 + rec.txPower;
  var m = Math.pow(10, (ref - rssi) / 20);
  if (!isFinite(m)) return null;
  return Math.max(0.05, Math.min(150, m));
}
function distText(m) {
  if (m == null) return '거리 미상';
  if (m < 1) return Math.round(m * 100) + 'cm';
  if (m < 10) return m.toFixed(1) + 'm';
  return Math.round(m) + 'm';
}
function distLabel(m) {
  if (m == null) return '';
  if (m < 1) return '바로 옆';
  if (m < 3) return '가까움';
  if (m < 8) return '중간';
  if (m < 20) return '다소 멂';
  return '아주 멂';
}

/* ---------------- 기기 레코드 ---------------- */
function buildSig(rec) {
  var p = [];
  if (rec.name) p.push('n:' + rec.name);
  if (rec.companyId != null) p.push('c:' + rec.companyId);
  if (rec.uuids && rec.uuids.length) p.push('u:' + shortUuid(rec.uuids[0]));
  if (rec.bytes && rec.bytes.length) p.push('d:' + rec.bytes.slice(0, 12).join(','));
  return p.length ? p.join('|') : '';
}

function ingest(inp) {
  var t = Date.now();
  var uuids = (inp.uuids || []).map(function (u) { return String(u).toLowerCase(); });
  var b = inp.bytes || null;
  var companyId = (b && b.length >= 2) ? (b[0] | (b[1] << 8)) : null;
  var name = (inp.name || '').trim();
  var id = inp.id || '';
  var sigTmp = buildSig({ name: name, companyId: companyId, uuids: uuids, bytes: b });
  var key = id ? ('id:' + id) : ('sg:' + sigTmp);
  if (key === 'sg:') key = 'anon:' + hash32(name + '|' + t);

  var rec = state.devices.get(key);
  var rssi = num(inp.rssi);

  if (!rec) {
    rec = {
      key: key, id: id, name: name, companyId: companyId, uuids: uuids,
      bytes: b, serviceData: inp.serviceData || null,
      txPower: num(inp.txPower), appearance: num(inp.appearance),
      rssi: rssi, rssiSmooth: rssi,
      firstSeen: t, lastSeen: t, count: 1,
      test: !!inp.test, isNew: false, jump: 0, open: false,
      device: inp.device || null, gatt: null, lastJump: 0, alertAt: 0,
      fromBase: false, bySig: false
    };
    rec.sig = buildSig(rec);
    state.devices.set(key, rec);
    onFirstSight(rec);
  } else {
    rec.lastSeen = t;
    rec.count++;
    if (rssi != null) {
      rec.rssi = rssi;
      rec.rssiSmooth = rec.rssiSmooth == null ? rssi : (rec.rssiSmooth * 0.6 + rssi * 0.4);
    }
    if (name && !rec.name) { rec.name = name; rec.sig = buildSig(rec); }
    if (companyId != null && rec.companyId == null) { rec.companyId = companyId; rec.bytes = b; rec.sig = buildSig(rec); }
    else if (b && (!rec.bytes || b.length > rec.bytes.length)) { rec.bytes = b; rec.sig = buildSig(rec); }
    if (uuids.length) {
      var merged = rec.uuids.slice();
      uuids.forEach(function (u) { if (merged.indexOf(u) < 0) merged.push(u); });
      if (merged.length !== rec.uuids.length) { rec.uuids = merged; rec.sig = buildSig(rec); }
    }
    if (inp.serviceData && !rec.serviceData) rec.serviceData = inp.serviceData;
    if (inp.device && !rec.device) rec.device = inp.device;
    if (inp.txPower != null && rec.txPower == null) rec.txPower = num(inp.txPower);
    if (inp.appearance != null && rec.appearance == null) rec.appearance = num(inp.appearance);
    checkJump(rec, t);
  }
  state.dirty = true;
  scheduleRender();
  return rec;
}

function baselineMatch(rec) {
  var keys = Object.keys(state.baseline);
  if (!keys.length) return null;
  if (state.baseline[rec.key]) return { entry: state.baseline[rec.key], bySig: false };
  if (rec.sig) {
    for (var i = 0; i < keys.length; i++) {
      if (state.baseline[keys[i]].sig === rec.sig) return { entry: state.baseline[keys[i]], bySig: true };
    }
  }
  return null;
}

function onFirstSight(rec) {
  var m = baselineMatch(rec);
  if (!m) {
    // 기준이 없어도 테스트 신호는 알림이 어떻게 보이는지 바로 보여 준다
    if (rec.test || Object.keys(state.baseline).length) {
      rec.isNew = true;
      addAlert('new', rec);
    }
  } else {
    rec.fromBase = true;
    if (m.bySig) rec.bySig = true;
    if (rec.rssiSmooth != null && m.entry.rssi != null) {
      var d = rec.rssiSmooth - m.entry.rssi;
      if (Math.abs(d) >= state.settings.jump) {
        rec.jump = Math.round(d);
        if (state.settings.mode === 'both') addAlert('jump', rec, Math.round(d));
        rec.lastJump = Date.now();
      }
    }
  }
}

function checkJump(rec, t) {
  if (state.settings.mode !== 'both') return;
  if (t - rec.lastJump < 45000) return;
  if (!Object.keys(state.baseline).length) return;
  var m = baselineMatch(rec);
  if (!m || m.entry.rssi == null || rec.rssiSmooth == null) return;
  var d = rec.rssiSmooth - m.entry.rssi;
  if (Math.abs(d) >= state.settings.jump) {
    rec.jump = Math.round(d);
    rec.lastJump = t;
    addAlert('jump', rec, Math.round(d));
  }
}

/* ---------------- 알림 ---------------- */
function addAlert(type, rec, diff) {
  var a = analyze(rec);
  var dTxt = distText(a.dist);
  var title, body;
  if (type === 'new') {
    title = rec.test ? '테스트 신호 도착!' : '새로운 전파 신호 감지!';
    body = '<b>' + esc(rec.name || '(이름 없는 신호)') + '</b> — ' + esc(a.company) +
           '<br>' + esc(a.kind) + ' · ' + esc(dTxt) + ' 거리로 보임';
  } else {
    title = '익숙한 신호의 세기가 변했어요';
    body = '<b>' + esc(rec.name || a.kind) + '</b> — 기준보다 ' +
           (diff > 0 ? '<b>' + diff + 'dBm 더 세짐</b>(가까워짐)' : '<b>' + Math.abs(diff) + 'dBm 약해짐</b>(멀어짐)') +
           '<br>' + esc(a.kind) + ' · 지금 ' + esc(dTxt);
  }
  var entry = {
    t: Date.now(), type: type, key: rec.key,
    name: rec.name || a.kind, kind: a.kind, company: a.company,
    dist: a.dist, diff: diff || 0, test: rec.test, conf: a.conf
  };
  state.alerts.unshift(entry);
  if (state.alerts.length > 40) state.alerts.length = 40;
  storeSet(LS_LOG, state.alerts.slice(0, 40));
  rec.alertAt = Date.now();
  showBanner(title, body, (rec.test ? '🧪 테스트 신호 · ' : '') +
    esc(a.company) + ' · 2.4GHz 블루투스 LE' + (a.conf === '낮음' ? ' · 추정 낮음' : ' · 추정 ' + a.conf));
  beep(type === 'jump' ? 'jump' : 'new');
  buzz(type === 'jump' ? 'jump' : 'new');
  if (!$('#tab-scan').hidden) renderNow();
}

function showBanner(title, bodyHtml, srcHtml) {
  var b = $('#banner');
  $('#bannerTitle').textContent = title;
  $('#bannerBody').innerHTML = bodyHtml;
  $('#bannerSrc').innerHTML = srcHtml || '';
  b.classList.add('show');
  clearTimeout(state.bannerTimer);
  state.bannerTimer = setTimeout(hideBanner, 9000);
}
function hideBanner() {
  clearTimeout(state.bannerTimer);
  $('#banner').classList.remove('show');
}

/* ---------------- 렌더링 ---------------- */
var renderPending = false, lastRender = 0, lastStaleSig = '';
function staleSig() {
  var t = Date.now(), s = String(state.devices.size);
  state.devices.forEach(function (r) { s += (t - r.lastSeen > 45000) ? '1' : '0'; });
  return s;
}
function scheduleRender() {
  if (renderPending) return;
  renderPending = true;
  var wait = Math.max(0, 450 - (Date.now() - lastRender));
  setTimeout(function () { renderPending = false; renderNow(); }, wait);
}

function renderNow() {
  lastRender = Date.now();
  state.dirty = false;
  lastStaleSig = staleSig();
  renderStatus();
  renderList();
  renderRadarStatic();
}

function visibleDevices() {
  var arr = [];
  var t = Date.now();
  state.devices.forEach(function (r) {
    if (state.hideStale && t - r.lastSeen > 45000) return;
    arr.push(r);
  });
  arr.sort(function (a, b) {
    var an = a.isNew ? 1 : 0, bn = b.isNew ? 1 : 0;
    if (an !== bn) return bn - an;
    return (b.rssiSmooth || -999) - (a.rssiSmooth || -999);
  });
  return arr;
}

function renderStatus() {
  var chips = [];
  var support = !!bt;
  if (state.available === false) support = false;
  chips.push('<span class="chip ' + (support ? 'ok' : 'bad') + '">' +
    (support ? '● 블루투스 사용 가능' : '✕ 블루투스 미지원') + '</span>');

  if (state.scanning) {
    chips.push('<span class="chip live">' + (state.mode === 'lescan' ? '전체 자동 스캔 중' : '기기 선택 모드') + '</span>');
  } else {
    chips.push('<span class="chip">대기 중</span>');
  }
  var bn = Object.keys(state.baseline).length;
  chips.push('<span class="chip ' + (bn ? '' : 'warn') + '">기준 ' + bn + '개</span>');
  if (support && (!bt || typeof bt.requestLEScan !== 'function')) {
    chips.push('<span class="chip warn">⚙️ 전체 스캔 설정 필요</span>');
  }
  var nn = 0;
  state.devices.forEach(function (r) { if (r.isNew) nn++; });
  if (nn) chips.push('<span class="chip bad">🚨 새 신호 ' + nn + '</span>');
  var jn = 0;
  state.devices.forEach(function (r) { if (r.jump) jn++; });
  if (jn) chips.push('<span class="chip warn">세기 변화 ' + jn + '</span>');
  $('#statusrow').innerHTML = chips.join('');

  $('#rcState').textContent = state.scanning
    ? (state.mode === 'lescan' ? '자동 스캔 중' : '기기 선택됨')
    : (state.mode === 'picker' ? '기기 선택 모드' : '대기 중');
  $('#brand').classList.toggle('live', state.scanning);
  var cnt = 0, tn = Date.now();
  state.devices.forEach(function (r) { if (tn - r.lastSeen < 60000) cnt++; });
  state.radarCount = cnt;
  $('#rcNum').textContent = cnt;
  $('#devCount').textContent = state.devices.size + '개';

  var hint = $('#hint');
  if (!hint) return;
  var bn2 = Object.keys(state.baseline).length;
  if (!state.devices.size) {
    hint.className = 'hint';
    hint.innerHTML = '';
  } else if (!bn2) {
    hint.className = 'hint';
    hint.innerHTML = '① 지금 잡힌 신호 ' + state.devices.size + '개를 <b>현재 신호를 기준으로 저장</b> → ② 그 뒤 처음 나타나는 신호를 바로 알려 드려요.';
  } else {
    hint.className = 'hint';
    hint.innerHTML = '기준 ' + bn2 + '개와 비교 중이에요. ' + (nn
      ? '그중 <b>새 신호 ' + nn + '개</b>가 나타났어요!'
      : '새로운 신호가 나타나면 바로 알려 드릴게요.');
  }
}

function confBadge(a) {
  return '<span class="badge conf ' + a.confCls + '">추정 ' + a.conf + '</span>';
}

function renderList() {
  var list = $('#devlist');
  var arr = visibleDevices();
  $('#emptyState').style.display = arr.length ? 'none' : 'block';
  var btn = $('#btnHideStale');
  btn.style.display = state.devices.size ? 'inline-flex' : 'none';
  btn.textContent = state.hideStale ? '오래된 신호 보기' : '오래된 신호 숨기기';

  var html = arr.map(function (r) {
    var a = analyze(r);
    var stale = (Date.now() - r.lastSeen > 45000);
    var pct = r.rssiSmooth == null ? 8 : Math.max(4, Math.min(100, (r.rssiSmooth + 100) * (100 / 70)));
    var badges = '';
    if (r.test) badges += '<span class="badge test">테스트</span>';
    if (r.isNew) badges += '<span class="badge new">신규</span>';
    if (r.jump) badges += '<span class="badge jump">세기 ' + (r.jump > 0 ? '▲' : '▼') + Math.abs(r.jump) + 'dBm</span>';
    if (r.fromBase && !r.isNew && !r.jump) badges += '<span class="badge base">기준</span>';
    if (stale) badges += '<span class="badge stale">오래됨</span>';

    var nm = r.name || '(이름 없는 신호)';
    var flags = 'new' + (r.test ? ' test' : '') + (r.jump ? ' warnrssi' : '') + (stale ? ' stale' : '') + (r.open ? ' open' : '');
    var icon = a.ico || (CAT[a.cat] ? CAT[a.cat].ico : '❓');
    var iconCls = (CAT[a.cat] || CAT.unknown).cls;

    var meta = [];
    meta.push('📶 ' + distText(a.dist) + (distLabel(a.dist) ? ' · ' + distLabel(a.dist) : ''));
    meta.push(r.count + '회');
    if (a.dec.beacon) meta.push('iBeacon');
    else if (a.dec.eddy) meta.push('Eddystone');
    else if (a.dec.fastpair) meta.push('Fast Pair');
    if (r.companyId != null) meta.push('회사 0x' + hex4(r.companyId));

    var detail = '<div class="detail">' +
      '<dl>' +
      '<dt>추정 발신지</dt><dd>' + esc(a.company) + ' · ' + esc(a.kind) + ' ' + confBadge(a) + '</dd>' +
      (a.companyDesc ? '<dt>제조사 설명</dt><dd>' + esc(a.companyDesc) + '</dd>' : '') +
      '<dt>대략 거리</dt><dd>' + distText(a.dist) + (distLabel(a.dist) ? ' (' + distLabel(a.dist) + ')' : '') + ' — 신호 세기로 계산한 어림값</dd>' +
      '<dt>신호 세기</dt><dd>' + (r.rssiSmooth == null ? '측정값 없음' : r.rssiSmooth.toFixed(0) + ' dBm (마지막 ' + (r.rssi == null ? '-' : r.rssi.toFixed(0)) + ' dBm)') + '</dd>' +
      '<dt>주소 ID</dt><dd>' + esc(r.id || '(브라우저가 숨김)') + '</dd>' +
      (r.appearance != null ? '<dt>기기 종류 값</dt><dd>' + r.appearance + '</dd>' : '') +
      (r.txPower != null ? '<dt>송신 세기</dt><dd>' + r.txPower + ' dBm</dd>' : '') +
      '<dt>서비스 UUID</dt><dd>' + (r.uuids && r.uuids.length ? r.uuids.map(function (u) {
        var k = shortUuid(u), s = SERVICES[k];
        return '<span style="display:inline-block;margin:0 6px 4px 0">0x' + esc(k.toUpperCase()) + (s ? ' <span style="color:#a5f3fc">' + esc(s.n) + '</span>' : '') + '</span>';
      }).join('') : '없음(광고에 미포함)') + '</dd>' +
      '<dt>광고 데이터</dt><dd>' + (r.bytes && r.bytes.length ? '<div class="hex">' + toHex(r.bytes, 40) + '</div>' : '없음') + '</dd>' +
      (r.gatt ? '<dt>직접 읽은 정보</dt><dd>' + r.gatt + '</dd>' : '') +
      '<dt>처음 / 마지막</dt><dd>' + nowStr(r.firstSeen) + ' / ' + nowStr(r.lastSeen) + '</dd>' +
      '</dl>' +
      (a.dec.lines.length ? '<div style="margin-top:7px"><b style="color:#a5f3fc">광고 해석</b><ul class="reasons">' +
        a.dec.lines.map(function (l) { return '<li>' + esc(l) + '</li>'; }).join('') + '</ul></div>' : '') +
      '<div style="margin-top:7px"><b style="color:#a5f3fc">판별 근거</b><ul class="reasons">' +
        (a.reasons.length ? a.reasons.map(function (l) { return '<li>' + esc(l) + '</li>'; }).join('') : '<li>광고에 식별 정보가 거의 없어요</li>') +
      '</ul></div>' +
      '</div>';

    return '<li class="dev ' + flags + '" data-key="' + esc(r.key) + '">' +
      '<div class="top">' +
        '<div class="ico ' + iconCls + '">' + icon + '</div>' +
        '<div class="mid">' +
          '<div class="nm"><span class="t">' + esc(nm) + '</span>' + badges + '</div>' +
          '<div class="sub">' + esc(a.company) + '<span class="sep">·</span>' + esc(a.kind) + '</div>' +
          '<div class="meta">' + meta.map(function (m) { return '<span>' + esc(m) + '</span>'; }).join('') + '</div>' +
        '</div>' +
      '</div>' +
      '<div class="rssi"><div class="bar"><i style="width:' + pct.toFixed(0) + '%"></i></div>' +
        '<span class="dbm">' + (r.rssiSmooth == null ? '—' : r.rssiSmooth.toFixed(0) + ' dBm') + '</span></div>' +
      '<div class="acts">' +
        '<button class="btn small" data-act="more">' + (r.open ? '접기' : '상세 보기') + '</button>' +
        (r.device && r.device.gatt ? '<button class="btn small" data-act="gatt">🔍 기기 정보 읽기</button>' : '') +
        '<button class="btn small ghost" data-act="hide" style="margin-left:auto">지우기</button>' +
      '</div>' +
      detail +
    '</li>';
  }).join('');

  list.innerHTML = html;
}

/* ---------------- 레이더 ---------------- */
var canvas = $('#radar'), ctx = canvas.getContext('2d');
var dpr = Math.min(window.devicePixelRatio || 1, 2.5);
var sweepAngle = -Math.PI / 2;
var rafId = null, lastFrame = 0;
var bgLayer = document.createElement('canvas');
var glowCache = {};

function sizeCanvas() {
  var w = canvas.clientWidth || 300;
  var px = Math.round(w * dpr);
  if (canvas.width === px && canvas.height === px) return;
  canvas.width = px;
  canvas.height = px;
  bgLayer.width = px;
  bgLayer.height = px;
  paintBase();
}
function renderRadarStatic() { if (!rafId) { sizeCanvas(); drawRadar(); } }

/* 배경판은 한 번만 그려 두고 매 프레임 복사한다 */
function paintBase() {
  var W = canvas.width, H = canvas.height;
  if (!W || !H) return;
  var b = bgLayer.getContext('2d');
  var cx = W / 2, cy = H / 2, R = Math.min(W, H) / 2 - 2;
  b.clearRect(0, 0, W, H);
  var bg = b.createRadialGradient(cx, cy, R * 0.05, cx, cy, R);
  bg.addColorStop(0, 'rgba(14,116,144,.30)');
  bg.addColorStop(0.55, 'rgba(8,32,48,.55)');
  bg.addColorStop(1, 'rgba(4,8,14,.9)');
  b.beginPath(); b.arc(cx, cy, R, 0, Math.PI * 2); b.fillStyle = bg; b.fill();
  for (var i = 1; i <= 4; i++) {
    b.beginPath();
    b.arc(cx, cy, R * i / 4, 0, Math.PI * 2);
    b.strokeStyle = i === 4 ? 'rgba(34,211,238,.35)' : 'rgba(34,211,238,.13)';
    b.lineWidth = (i === 4 ? 2 : 1) * dpr;
    b.stroke();
  }
  b.strokeStyle = 'rgba(34,211,238,.10)';
  b.lineWidth = 1 * dpr;
  [0, Math.PI / 2].forEach(function (a) {
    b.beginPath();
    b.moveTo(cx - Math.cos(a) * R, cy - Math.sin(a) * R);
    b.lineTo(cx + Math.cos(a) * R, cy + Math.sin(a) * R);
    b.stroke();
  });
}

/* 색깔별 발광 점 이미지를 한 번 만들어 재사용 */
function glow(rgb) {
  var k = rgb.join(',');
  if (glowCache[k]) return glowCache[k];
  var s = document.createElement('canvas'), S = 96;
  s.width = S; s.height = S;
  var c = s.getContext('2d');
  var g = c.createRadialGradient(S / 2, S / 2, 0, S / 2, S / 2, S / 2);
  g.addColorStop(0, 'rgba(' + k + ',1)');
  g.addColorStop(0.18, 'rgba(' + k + ',.85)');
  g.addColorStop(0.45, 'rgba(' + k + ',.22)');
  g.addColorStop(1, 'rgba(' + k + ',0)');
  c.fillStyle = g; c.beginPath(); c.arc(S / 2, S / 2, S / 2, 0, Math.PI * 2); c.fill();
  glowCache[k] = s;
  return s;
}

function drawRadar() {
  if (!ctx) return;
  var want = Math.round((canvas.clientWidth || 300) * dpr);
  if (want && canvas.width !== want) {
    canvas.width = want; canvas.height = want;
    bgLayer.width = want; bgLayer.height = want;
    paintBase();
  }
  var W = canvas.width, H = canvas.height;
  if (!W || !H) return;
  if (bgLayer.width !== W) { bgLayer.width = W; bgLayer.height = H; paintBase(); }
  var cx = W / 2, cy = H / 2, R = Math.min(W, H) / 2 - 2;
  ctx.clearRect(0, 0, W, H);
  ctx.drawImage(bgLayer, 0, 0);

  // 스윕
  var grad = null;
  if (ctx.createConicGradient) {
    try { grad = ctx.createConicGradient(sweepAngle, cx, cy); } catch (e) { grad = null; }
  }
  if (grad) {
    grad.addColorStop(0, 'rgba(34,211,238,.32)');
    grad.addColorStop(0.13, 'rgba(34,211,238,.03)');
    grad.addColorStop(1, 'rgba(34,211,238,0)');
    ctx.beginPath(); ctx.moveTo(cx, cy); ctx.arc(cx, cy, R, 0, Math.PI * 2); ctx.closePath();
    ctx.fillStyle = grad; ctx.fill();
    ctx.beginPath(); ctx.moveTo(cx, cy);
    ctx.lineTo(cx + Math.cos(sweepAngle) * R, cy + Math.sin(sweepAngle) * R);
    ctx.strokeStyle = 'rgba(103,232,249,.55)'; ctx.lineWidth = 1.6 * dpr; ctx.stroke();
  } else {
    for (var k = 0; k < 26; k++) {
      var a0 = sweepAngle - k * 0.028;
      ctx.beginPath(); ctx.moveTo(cx, cy);
      ctx.arc(cx, cy, R, a0 - 0.028, a0); ctx.closePath();
      ctx.fillStyle = 'rgba(34,211,238,' + (0.30 * (1 - k / 26)).toFixed(3) + ')';
      ctx.fill();
    }
  }

  // 블립 (신호 세기 → 중심에서의 거리, key 해시 → 방향)
  var t = Date.now();
  var arr = visibleDevices().slice(0, 26);
  arr.forEach(function (r) {
    var a = analyze(r);
    var ang = (hash32(r.key) / 4294967296) * Math.PI * 2;
    var frac = a.dist == null ? 0.86 : Math.min(0.95, 0.16 + (a.dist / 25) * 0.82);
    var rad = R * frac;
    var x = cx + Math.cos(ang) * rad, y = cy + Math.sin(ang) * rad;
    var da = Math.abs(((sweepAngle - ang + Math.PI * 3) % (Math.PI * 2)) - Math.PI);
    var bright = 0.42 + 0.58 * Math.max(0, 1 - da / 1.4);
    var col = r.isNew ? [244, 82, 107] : (r.test ? [167, 139, 250] : (a.cat === 'audio' ? [167, 139, 250] : [34, 211, 238]));
    var pulse = r.isNew ? (0.5 + 0.5 * Math.sin(t / 220)) : 0;
    var base = 4 + (r.rssiSmooth == null ? 0 : Math.max(0, (r.rssiSmooth + 100) / 26)) + pulse * 3;
    var size = base * 4.6 * dpr;
    ctx.globalAlpha = Math.min(1, 0.45 + 0.55 * bright);
    ctx.drawImage(glow(col), x - size / 2, y - size / 2, size, size);
    ctx.globalAlpha = 1;
  });
}

function loop(ts) {
  rafId = requestAnimationFrame(loop);
  if (document.hidden) return;
  if (ts - lastFrame < 33) return;
  lastFrame = ts;
  sweepAngle += (state.scanning ? 0.055 : 0.016);
  if (sweepAngle > Math.PI * 4) sweepAngle -= Math.PI * 2;
  drawRadar();
}

/* ---------------- 스캔 ---------------- */
function setBtBlocked(msg, sub) {
  $('#blocker').innerHTML = '<div class="blocker"><b>' + esc(msg) + '</b><br>' +
    '<span style="color:#9fb2cc">' + (sub || '') + '</span></div>';
}

function handleBtError(err) {
  var n = err && err.name || '';
  var m = err && err.message || String(err);
  if (n === 'NotFoundError' || /No device selected|chooser/i.test(m)) {
    toast('기기를 고르지 않아 스캔을 멈췄어요');
  } else if (n === 'NotAllowedError') {
    toast('블루투스 권한이 거부됐어요. 크롬 설정에서 권한을 허용해 주세요');
  } else if (n === 'SecurityError') {
    toast('보안 연결(https)에서만 블루투스를 쓸 수 있어요');
  } else if (n === 'InvalidStateError') {
    toast('블루투스가 꺼져 있어요. 휴대폰 설정에서 블루투스를 켜 주세요');
  } else if (n === 'NotSupportedError') {
    $('#guideFlags').open = true;
    toast('전체 자동 스캔을 쓸 수 없어요 — chrome://flags 설정을 확인해 주세요');
  } else {
    toast('블루투스 오류: ' + m);
  }
}

function advToInput(e, device) {
  var sd = null;
  if (e.serviceData && typeof e.serviceData.forEach === 'function') {
    sd = {};
    e.serviceData.forEach(function (v, k) { sd[k] = bytesOf(v) || []; });
  }
  return {
    id: (device && device.id) || '',
    name: (e.name || (device && device.name) || ''),
    rssi: e.rssi,
    txPower: e.txPower,
    appearance: e.appearance,
    uuids: e.uuids ? Array.prototype.slice.call(e.uuids) : [],
    bytes: bytesOf(e.manufacturerData),
    serviceData: sd,
    device: device || null,
    test: false
  };
}

function onAdv(e) {
  var d = e.device || null;
  ingest(advToInput(e, d));
}

function startLEScan() {
  return bt.requestLEScan({ acceptAllAdvertisements: true, keepRepeatedDevices: true }).then(function (scan) {
    activeScan = scan;
    state.mode = 'lescan';
    state.scanning = true;
    scan.addEventListener('advertisementreceived', onAdv);
    setScanUi();
    renderNow();
    toast('주변 신호를 자동으로 모으는 중이에요');
    return 'lescan';
  });
}

function startPickerScan(silent) {
  var opts = {
    acceptAllDevices: true,
    optionalServices: ['device_information', 'battery_service', 'generic_access', 'generic_attribute', 'heart_rate', 'human_interface_device']
  };
  return bt.requestDevice(opts).then(function (dev) {
    state.mode = 'picker';
    state.scanning = true;
    setScanUi();
    ingest({
      id: dev.id, name: dev.name || '(이름 없음)', rssi: null,
      uuids: [], bytes: null, serviceData: null, device: dev, test: false
    });
    if (!silent) toast('기기 "' + (dev.name || '이름 없음') + '"을(를) 목록에 추가했어요');
    // 광고 스트림을 지원하면 계속 세기를 받아 둔다
    if (typeof dev.watchAdvertisements === 'function') {
      dev.addEventListener('advertisementreceived', function (ev) { onAdv(ev); });
      dev.watchAdvertisements().then(function () {
        toast('이 기기의 신호 세기를 계속 받고 있어요');
      }).catch(function () { /* 실험 기능이 꺼져 있으면 조용히 넘어감 */ });
    }
    renderNow();
    return 'picker';
  });
}

function startScan() {
  if (!bt) return;
  if (state.mode === 'lescan' && activeScan && activeScan.active) return;
  if (!state.scanning && activeScan && activeScan.active === false) activeScan = null;

  if (typeof bt.requestLEScan === 'function') {
    startLEScan().catch(function (err) {
      if (err && (err.name === 'NotSupportedError' || /not supported|not implemented|NotSupported/i.test(err.message || ''))) {
        $('#guideFlags').open = true;
        toast('자동 전체 스캔이 꺼져 있어 기기 선택으로 시작합니다');
        return startPickerScan(true).catch(handleBtError);
      }
      handleBtError(err);
    });
  } else {
    $('#guideFlags').open = true;
    startPickerScan(true).catch(handleBtError);
  }
}

function stopScan() {
  if (activeScan) {
    try { activeScan.stop(); } catch (e) {}
    activeScan = null;
  }
  state.scanning = false;
  setScanUi();
  renderNow();
  toast('스캔을 멈췄어요');
}

function setScanUi() {
  var b = $('#btnScan');
  b.classList.remove('stop');
  if (state.scanning && state.mode === 'lescan') {
    b.textContent = '⏹ 스캔 중지';
    b.classList.add('stop');
  } else if (state.mode === 'picker') {
    b.textContent = '📡 다른 기기 추가';
  } else {
    b.textContent = '📡 스캔 시작';
  }
}

/* ---------------- 기준 저장 ---------------- */
function saveBaseline() {
  var base = {};
  var n = 0;
  state.devices.forEach(function (r) {
    if (r.test) return;
    base[r.key] = { name: r.name, sig: r.sig, rssi: r.rssiSmooth, cat: analyze(r).cat, ts: Date.now() };
    n++;
  });
  if (!n) { toast('기준으로 저장할 신호가 없어요. 먼저 스캔해 주세요'); return; }
  state.baseline = base;
  state.baselineAt = Date.now();
  storeSet(LS_BASE, { at: state.baselineAt, map: base });
  state.devices.forEach(function (r) {
    r.isNew = false; r.jump = 0; r.fromBase = !r.test; r.bySig = false;
  });
  hideBanner();
  toast('현재 신호 ' + n + '개를 기준으로 저장했어요');
  renderNow();
}

function clearBaseline() {
  state.baseline = {};
  state.baselineAt = 0;
  storeSet(LS_BASE, { at: 0, map: {} });
  state.devices.forEach(function (r) { r.isNew = false; r.jump = 0; r.fromBase = false; });
  toast('기준을 지웠어요. 이제부터 잡히는 신호는 비교하지 않아요');
  renderNow();
}

/* ---------------- 테스트 신호 ---------------- */
var TEST_POOL = [
  { name: 'AirPods Pro', bytes: [0x4C, 0x00, 0x07, 0x19, 0x01, 0x02, 0x03], rssi: -62, txPower: 4, uuids: [] },
  { name: 'Galaxy Buds2 Pro', bytes: [0x75, 0x00, 0x01, 0x0A, 0x11], rssi: -70, txPower: 0, uuids: [] },
  { name: 'Galaxy S23', bytes: [0x75, 0x00, 0x42, 0x09], rssi: -55, txPower: 2, uuids: [] },
  { name: 'ESP32-S3', bytes: [0xE5, 0x02, 0x01, 0x00, 0x00], rssi: -84, txPower: -3, uuids: ['0000fff0-0000-1000-8000-00805f9b34fb'] },
  { name: 'SmartTag2', bytes: [0x75, 0x00, 0xA1], rssi: -78, txPower: 0, uuids: ['0000fd5a-0000-1000-8000-00805f9b34fb'], sd: { '0000fd5a-0000-1000-8000-00805f9b34fb': [0x01, 0x02, 0x03, 0x04] } },
  { name: 'micro:bit [zupag]', bytes: [0x59, 0x00, 0x01], rssi: -66, txPower: 0, uuids: [] },
  { name: 'WH-1000XM5', bytes: [0x2D, 0x01, 0x0C], rssi: -58, txPower: 4, uuids: ['0000fe9f-0000-1000-8000-00805f9b34fb'], sd: { '0000fe9f-0000-1000-8000-00805f9b34fb': [0x9E, 0x5D, 0x01, 0x00] } },
  { name: 'iBeacon-교실', bytes: [0x4C, 0x00, 0x02, 0x15, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x00, 0x0B, 0x00, 0x2A, 0xC5], rssi: -71, txPower: 0, uuids: [] },
  { name: '도서관 태그', bytes: [0x00, 0x08, 0x22], rssi: -82, txPower: -2, uuids: ['0000feaa-0000-1000-8000-00805f9b34fb'], sd: { '0000feaa-0000-1000-8000-00805f9b34fb': [0x10, 0xE8, 0x03, 0x6C, 0x69, 0x62, 0x72, 0x61, 0x72, 0x79] } },
  { name: '(이름 없음)', bytes: [0xC4, 0x00, 0x07], rssi: -88, txPower: 0, uuids: [] }
];

function spawnTest() {
  var pool = TEST_POOL.slice();
  var t = Date.now();
  // 아직 없는 신호를 우선 고른다
  for (var i = pool.length - 1; i > 0; i--) {
    var j = Math.floor(Math.random() * (i + 1));
    var tmp = pool[i]; pool[i] = pool[j]; pool[j] = tmp;
  }
  var pick = pool[0];
  for (var k = 0; k < pool.length; k++) {
    var p = pool[k];
    var existing = false;
    state.devices.forEach(function (r) { if (r.name === p.name && r.test) existing = true; });
    if (!existing) { pick = p; break; }
  }
  var id = 'test-' + hash32(pick.name) + '-' + Math.floor(Math.random() * 900 + 100);
  var rssi = pick.rssi + Math.round((Math.random() - 0.5) * 8);
  ingest({
    id: id, name: pick.name, rssi: rssi, txPower: pick.txPower,
    uuids: pick.uuids || [], bytes: pick.bytes || null,
    serviceData: pick.sd || null, device: null, test: true
  });
  toast('테스트 신호 "' + pick.name + '"을(를) 잡았어요 (실제 전파 아님)');
  if ($('#tab-scan').hidden) switchTab('scan');
}

/* ---------------- 기기 정보 읽기 (GATT) ---------------- */
function gattRead(rec) {
  if (!rec.device || !rec.device.gatt) { toast('이 신호는 기기 객체가 없어 직접 읽을 수 없어요'); return; }
  if (rec.gattBusy) return;
  rec.gattBusy = true;
  toast('기기에 연결해 정보를 읽는 중…');
  var done = false;
  var timer = setTimeout(function () {
    if (done) return;
    done = true;
    try { if (rec.device.gatt.connected) rec.device.gatt.disconnect(); } catch (e) {}
    rec.gattBusy = false;
    toast('응답이 없어 연결을 끊었어요 (기기가 연결을 막는 경우가 있어요)');
  }, 12000);

  rec.device.gatt.connect().then(function (server) {
    return server.getPrimaryServices().then(function (list) {
      var lines = ['서비스 ' + list.length + '개: ' + list.map(function (s) { return '0x' + shortUuid(s.uuid).toUpperCase(); }).join(', ')];
      return server.getPrimaryService('device_information').then(function (di) {
        var targets = [['manufacturer_name_string', '제조사'], ['model_number_string', '모델명'], ['firmware_revision_string', '펌웨어']];
        return Promise.all(targets.map(function (t) {
          return di.getCharacteristic(t[0]).then(function (ch) { return ch.readValue(); }).then(function (v) {
            return t[1] + ': ' + new TextDecoder().decode(v);
          }).catch(function () { return null; });
        })).then(function (rows) {
          rows.forEach(function (r) { if (r) lines.push(r); });
          return lines;
        });
      }).catch(function () { return lines; });
    });
  }).then(function (lines) {
    if (done) return;
    done = true;
    clearTimeout(timer);
    try { if (rec.device.gatt.connected) rec.device.gatt.disconnect(); } catch (e) {}
    rec.gattBusy = false;
    rec.gatt = lines.map(esc).join('<br>');
    rec.open = true;
    toast('기기에서 정보를 읽었어요');
    renderNow();
  }).catch(function (err) {
    if (done) return;
    done = true;
    clearTimeout(timer);
    rec.gattBusy = false;
    handleBtError(err);
  });
}

/* ---------------- 주파수 대역 ---------------- */
var BANDS = [
  { n: 'AM 라디오 (중파 방송)', f: 530e3, t: 1710e3, what: '국내 AM 방송',
    items: ['라디오 방송국', '구형 무선 장비'], tip: '낮은 주파수라 멀리까지 퍼지고, 소리가 뭉개지기 쉬워요.' },
  { n: '단파(HF) 방송·아마추어 무선', f: 2.3e6, t: 30e6, what: '국제 방송·원거리 통신',
    items: ['국제 단파 방송', '아마추어 무선', '선박·항공 장거리 통신'] },
  { n: 'HF RFID (도서관·산업용)', f: 6.765e6, t: 6.795e6, what: '짧은 거리 태그 인식',
    items: ['산업용 RFID 태그'], tip: 'NFC와 비슷하지만 조금 다른 규격이에요.' },
  { n: '13.56MHz — NFC·교통카드·출입카드', f: 13.553e6, t: 13.567e6, what: '손을 대면 인식되는 근거리 통신',
    items: ['티머니 같은 교통카드', '휴대폰 NFC 결제', '아파트·학교 출입카드', '도서관 책 태그'],
    tip: '1~10cm 안에서만 읽혀요. 이 감지기(블루투스)와는 다른 방식이에요.' },
  { n: '27MHz — 생활무선·무선 조종', f: 26.957e6, t: 27.283e6, what: '장난감·무선 마이크',
    items: ['RC 자동차·드론 조종기', '무선 마이크', 'CB 무전기'] },
  { n: '40MHz — 무선 조종·베이비 모니터', f: 40.66e6, t: 40.7e6, what: '생활 무선기기',
    items: ['무선 온도계', '베이비 모니터', '무선 조종 장난감'] },
  { n: '49MHz — 무선 장난감·베이비 모니터', f: 49.0e6, t: 49.9e6, what: '생활 무선기기',
    items: ['무선 장난감', '베이비 모니터', '구형 무선 마이크'] },
  { n: 'FM 라디오 방송', f: 87.5e6, t: 108e6, what: '음악·뉴스 라디오',
    items: ['FM 라디오 방송국', '차량 라디오'] },
  { n: '항공 항법 (VOR·ILS)', f: 108e6, t: 118e6, what: '비행기 위치·착륙 유도',
    items: ['공항 항법 장비'] },
  { n: '항공 통신 (비행기·관제탑)', f: 118e6, t: 137e6, what: '조종사와 관제사 무전',
    items: ['항공 무전', '공항 관제'] },
  { n: '아마추어 무선 2m 밴드', f: 144e6, t: 148e6, what: '햄(아마추어) 무선',
    items: ['핸디 무전기', '아마추어 무선국'] },
  { n: '해상 VHF (선박·해경)', f: 156e6, t: 163e6, what: '바다 위 무전',
    items: ['선박 무전기', '해양경찰 통신'] },
  { n: '지상파 DMB', f: 174e6, t: 216e6, what: '이동 중 TV·라디오 방송',
    items: ['DMB 방송', '구형 지상파 TV'] },
  { n: '315MHz — 무선 리모컨·차 키', f: 315e6, t: 316e6, what: '짧은 명령 신호',
    items: ['차량 문 열림 키', '도어벨', '무선 스위치'] },
  { n: '433MHz — 무선 리모컨·차 키·센서', f: 433e6, t: 434.79e6, what: '생활 무선기기의 대표 주파수',
    items: ['차량 키·경보기', '현관 도어벨', '무선 온도계·스마트홈 센서', 'RC 장난감'],
    tip: '우리 주변에서 아주 흔한 신호예요. 문 열림, 도어벨, 센서가 대부분 이 대역을 씁니다.' },
  { n: '446MHz — 생활 무전기', f: 446e6, t: 446.2e6, what: '가까운 거리 무전',
    items: ['어린이 무전기', '등산용 무전기'] },
  { n: '440~450MHz — 생활무선(무선 마이크·무전기)', f: 440e6, t: 450e6, what: '행사장 무선 장비',
    items: ['무선 마이크', '무전기', '무선 리모컨'] },
  { n: '지상파 UHF TV (470~698MHz)', f: 470e6, t: 698e6, what: 'TV 방송·방송용 무선 마이크',
    items: ['지상파 TV 방송', '방송국 무선 마이크'] },
  { n: '800MHz 이동통신', f: 824e6, t: 894e6, what: '휴대폰 기지국(구형 CDMA·LTE)',
    items: ['휴대폰 기지국', '구형 2G·3G'] },
  { n: '900MHz ISM — UHF RFID·LoRa', f: 900e6, t: 928e6, what: '물류 태그·장거리 저전력 통신',
    items: ['물류·재고 RFID', 'LoRa 센서', '무선 마이크'] },
  { n: '920MHz — 국내 RFID/USN', f: 917e6, t: 923.5e6, what: '국내 물류·검침용',
    items: ['도서관 책 태그', '주차장 입출차 태그', '스마트 검침기'] },
  { n: 'GPS L1 (위성 위치)', f: 1575.42e6, t: 1575.42e6, what: '위성 위치 신호',
    items: ['GPS 위성', '내비게이션 수신기'], tip: '위성에서 오는 아주 약한 신호예요.' },
  { n: '1.8GHz 이동통신 (LTE)', f: 1800e6, t: 1900e6, what: '휴대폰 데이터·통화',
    items: ['휴대폰 기지국', 'LTE 단말'] },
  { n: '2.1GHz 이동통신 (LTE·3G)', f: 1920e6, t: 2200e6, what: '휴대폰 데이터·통화',
    items: ['휴대폰 기지국', 'LTE·WCDMA 단말'] },
  { n: '블루투스·BLE 채널 (2402~2480MHz)', f: 2402e6, t: 2480e6, what: '이 감지기가 실제로 잡는 신호',
    items: ['이어폰·스피커', '스마트워치·밴드', '위치 태그', '키보드·마우스', '개발 보드'],
    tip: '위쪽 스캔 화면에서 잡히는 신호가 바로 이 대역이에요.' },
  { n: '와이파이 2.4GHz (채널 1~13)', f: 2412e6, t: 2472e6, what: '집·학교 와이파이',
    items: ['공유기', '노트북·휴대폰', '스마트홈 기기'],
    tip: '블루투스와 같은 2.4GHz를 나눠 쓰기 때문에 서로 간섭이 생길 수 있어요.' },
  { n: '2.4GHz 대역 (와이파이·블루투스·전자레인지)', f: 2400e6, t: 2500e6, what: '가장 붐비는 대역',
    items: ['와이파이', '블루투스·BLE', 'Zigbee 스마트홈', '전자레인지', '무선 카메라', 'RC 장난감'],
    tip: '전자레인지도 이 근처(2.45GHz)에서 전파를 내요. 요리할 때 와이파이가 느려지는 이유예요.' },
  { n: '2.6GHz 이동통신 (LTE)', f: 2500e6, t: 2690e6, what: '휴대폰 데이터',
    items: ['휴대폰 기지국'] },
  { n: '5G 3.5GHz 대역', f: 3300e6, t: 3800e6, what: '빠른 5G 통신',
    items: ['5G 기지국', '5G 휴대폰'] },
  { n: '5GHz 대역 (와이파이·무선 영상)', f: 4900e6, t: 5925e6, what: '빠른 와이파이·영상 전송',
    items: ['와이파이 공유기', '무선 영상 송신기', 'FPV 드론'] },
  { n: '와이파이 5GHz (실내용 5150~5350MHz)', f: 5150e6, t: 5350e6, what: '집·학교 와이파이',
    items: ['공유기(5GHz)', '노트북·휴대폰'] },
  { n: '와이파이 5GHz 상단 (5725~5850MHz)', f: 5725e6, t: 5850e6, what: '와이파이·무선 영상',
    items: ['공유기', '무선 영상 송신기', '무선 도어벨'] },
  { n: '6GHz — 와이파이 6E·7', f: 5925e6, t: 7125e6, what: '최신 와이파이',
    items: ['와이파이 6E·7 공유기'] },
  { n: '24GHz — 차량 레이더·도플러 센서', f: 24e9, t: 24.25e9, what: '물체 감지·속도 측정',
    items: ['차량 충돌 경고', '자동문 센서', '속도 측정기'] },
  { n: '28GHz — 5G 밀리미터파', f: 26.5e9, t: 29.5e9, what: '초고속 근거리 5G',
    items: ['5G 밀리미터파 기지국'], tip: '멀리 가지 못하고 벽도 잘 못 뚫어요.' },
  { n: '60GHz — 무선 VR·초근거리 통신', f: 57e9, t: 64e9, what: '아주 빠른 근거리 전송',
    items: ['무선 VR 기기', '초고속 근거리 통신'] },
  { n: '77GHz — 자동차 레이더', f: 76e9, t: 81e9, what: '차량 충돌 방지',
    items: ['자동차 전방 레이더'] }
];

function lookupFreq(hz) {
  var hits = BANDS.filter(function (b) { return hz >= b.f && hz <= b.t; });
  hits.sort(function (a, b) { return (a.t - a.f) - (b.t - b.f); });
  var near = null;
  if (!hits.length) {
    BANDS.forEach(function (b) {
      var d = hz < b.f ? (b.f - hz) : (hz - b.t);
      if (!near || d < near.d) near = { b: b, d: d };
    });
  }
  return { hits: hits, near: near };
}

function parseFreqInput(raw, unit) {
  var s = String(raw || '').trim().replace(/[, ]/g, '');
  var m = s.match(/^([0-9]*\.?[0-9]+)(ghz|mhz|khz|hz|g|m|k)?$/i);
  if (!m) return null;
  var v = parseFloat(m[1]);
  if (!isFinite(v) || v <= 0) return null;
  var u = (m[2] || '').toLowerCase();
  if (u === 'ghz' || u === 'g') return v * 1e9;
  if (u === 'mhz' || u === 'm') return v * 1e6;
  if (u === 'khz' || u === 'k') return v * 1e3;
  if (u === 'hz') return v;
  if (unit === 'GHz') return v * 1e9;
  if (unit === 'MHz') return v * 1e6;
  if (unit === 'kHz') return v * 1e3;
  return null; // 자동
}

function trimZeros(s) { return s.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, ''); }
function freqLabel(hz) {
  if (hz >= 1e9) return trimZeros((hz / 1e9).toFixed(3)) + 'GHz';
  if (hz >= 1e6) return trimZeros((hz / 1e6).toFixed(hz % 1e6 === 0 ? 0 : 2)) + 'MHz';
  if (hz >= 1e3) return trimZeros((hz / 1e3).toFixed(hz % 1e3 === 0 ? 0 : 1)) + 'kHz';
  return Math.round(hz) + 'Hz';
}
function wavelength(hz) {
  var l = 299792458 / hz;
  if (l >= 1000) return (l / 1000).toFixed(1) + 'km';
  if (l >= 1) return l.toFixed(l < 10 ? 2 : 1) + 'm';
  if (l >= 0.01) return (l * 100).toFixed(1) + 'cm';
  return (l * 1000).toFixed(1) + 'mm';
}

function renderFreq(raw, unit) {
  var box = $('#freqResult');
  var rawTxt = String(raw || '').trim();
  if (!rawTxt) {
    box.innerHTML = '<div class="tiny">주파수를 넣거나 위의 버튼을 눌러 보세요.</div>';
    return;
  }
  var hz = parseFreqInput(rawTxt, unit);
  var note = '';
  if (hz == null) {
    // 자동: MHz와 GHz 두 해석 중 대역에 맞는 쪽을 고른다
    var asMHz = parseFreqInput(rawTxt, 'MHz');
    var asGHz = parseFreqInput(rawTxt, 'GHz');
    var rM = asMHz == null ? { hits: [] } : lookupFreq(asMHz);
    var rG = asGHz == null ? { hits: [] } : lookupFreq(asGHz);
    if (rM.hits.length && !rG.hits.length) { hz = asMHz; note = '입력값을 MHz로 해석했어요'; }
    else if (rG.hits.length && !rM.hits.length) { hz = asGHz; note = '입력값을 GHz로 해석했어요'; }
    else if (rG.hits.length) { hz = asGHz; note = '입력값을 GHz로 해석했어요'; }
    else if (rM.hits.length) { hz = asMHz; note = '입력값을 MHz로 해석했어요'; }
    else { hz = asMHz || 1e9; note = ''; }
  }
  if (hz == null || !isFinite(hz)) {
    box.innerHTML = '<div class="tiny">숫자로 된 주파수를 넣어 주세요. 예: 2.4 / 2400 / 13.56MHz</div>';
    return;
  }

  var r = lookupFreq(hz);
  var html = '<div class="fnum">' + esc(freqLabel(hz)) + ' · ' + esc(fmtInt(hz)) + 'Hz</div>';

  if (r.hits.length) {
    var main = r.hits[0];
    html += '<div class="fname">' + esc(main.n) + '</div>';
    html += '<div class="fdesc">' + esc(main.what) + '</div>';
    html += '<div class="lines">';
    html += '<div class="line"><span class="k">쓰는 곳</span><span>' + main.items.map(esc).join(' · ') + '</span></div>';
    if (main.tip) html += '<div class="line"><span class="k">알아두기</span><span>' + esc(main.tip) + '</span></div>';
    html += '</div>';
    var others = r.hits.slice(1, 4);
    if (others.length) {
      html += '<div class="more"><b>같이 걸치는 대역</b><br>' +
        others.map(function (b) { return '· ' + esc(b.n) + ' <span style="color:#4a5a72">' + esc(freqLabel(b.f)) + '~' + esc(freqLabel(b.t)) + '</span>'; }).join('<br>') +
        '</div>';
    }
  } else {
    html += '<div class="fname">등록된 용도가 없는 주파수예요</div>';
    html += '<div class="fdesc">' + esc(freqLabel(hz)) + '는 일상 기기에서 흔히 쓰지 않는 대역이에요.</div>';
    if (r.near) {
      html += '<div class="lines"><div class="line"><span class="k">가까운 대역</span><span>' +
        esc(r.near.b.n) + ' <span style="color:#4a5a72">(' + esc(freqLabel(r.near.b.f)) + '~' + esc(freqLabel(r.near.b.t)) +
        ' · 약 ' + esc(freqLabel(r.near.d)) + ' 떨어짐)</span></span></div></div>';
    }
  }
  html += '<div class="wl">파장은 약 <b>' + esc(wavelength(hz)) + '</b> — 주파수가 높을수록 파장이 짧고 잘 휘지 않아요.</div>';
  if (note) html += '<div class="more">' + esc(note) + '</div>';
  box.innerHTML = html;
}

/* ---------------- 탭 / 설정 / 이벤트 ---------------- */
function switchTab(name) {
  ['scan', 'freq', 'help'].forEach(function (t) {
    $('#tab-' + t).hidden = (t !== name);
  });
  $$('.tabbar .tb').forEach(function (b) {
    b.classList.toggle('on', b.getAttribute('data-tab') === name);
  });
  if (name === 'scan') { sizeCanvas(); renderNow(); }
  window.scrollTo(0, 0);
}

function toast(msg) {
  var el = $('#toast');
  el.textContent = msg;
  el.classList.add('on');
  clearTimeout(el._t);
  el._t = setTimeout(function () { el.classList.remove('on'); }, 2600);
}

function applySettings() {
  $('#swSound').classList.toggle('on', state.settings.sound);
  $('#swVibe').classList.toggle('on', state.settings.vibe);
  $$('#alertModeRow button').forEach(function (b) { b.classList.toggle('on', b.getAttribute('data-mode') === state.settings.mode); });
  $$('#jumpRow button').forEach(function (b) { b.classList.toggle('on', +b.getAttribute('data-jump') === state.settings.jump); });
  storeSet(LS_SET, state.settings);
}

function openLog() {
  var m = document.getElementById('logMask');
  if (!m) {
    m = document.createElement('div');
    m.id = 'logMask';
    m.className = 'sheet-mask';
    m.innerHTML = '<div class="sheet"><h3>🗒 감지 기록 <button data-close="1" aria-label="닫기">✕</button></h3>' +
      '<div id="logBody" style="max-height:56vh;overflow:auto"></div></div>';
    document.body.appendChild(m);
    m.addEventListener('click', function (e) {
      if (e.target === m || (e.target.getAttribute && e.target.getAttribute('data-close'))) m.classList.remove('on');
    });
  }
  var body = m.querySelector('#logBody');
  if (!state.alerts.length) {
    body.innerHTML = '<div class="tiny" style="padding:10px 0">아직 감지 기록이 없어요. 기준을 저장한 뒤 새 신호가 잡히면 여기에 쌓여요.</div>';
  } else {
    body.innerHTML = state.alerts.map(function (a) {
      var label = a.type === 'new' ? '새 신호' : '세기 변화';
      var col = a.type === 'new' ? '#f4526b' : '#fbbf24';
      return '<div style="padding:9px 0;border-bottom:1px solid rgba(120,170,220,.1)">' +
        '<div style="display:flex;gap:8px;align-items:baseline">' +
        '<span style="font-size:14px">' + (a.type === 'new' ? '🚨' : '⚠️') + '</span>' +
        '<b style="font-size:13px">' + esc(a.name) + '</b>' +
        '<span style="font-size:10.5px;font-weight:800;color:' + col + '">' + label + '</span>' +
        '<span style="margin-left:auto;font-size:11px;color:#6d7f99">' + nowStr(a.t) + '</span>' +
        '</div>' +
        '<div style="font-size:11.5px;color:#9fb2cc;margin-top:2px">' + esc(a.company) + ' · ' + esc(a.kind) +
        (a.dist != null ? ' · ' + esc(distText(a.dist)) : '') +
        (a.type === 'jump' ? ' · ' + (a.diff > 0 ? '+' : '') + a.diff + 'dBm' : '') +
        (a.test ? ' · 테스트' : '') + '</div></div>';
    }).join('');
  }
  m.classList.add('on');
}

function wire() {
  // 탭
  $$('.tabbar .tb').forEach(function (b) {
    b.addEventListener('click', function () { switchTab(b.getAttribute('data-tab')); });
  });

  // 스캔 버튼
  $('#btnScan').addEventListener('click', function () {
    if (state.scanning) stopScan();
    else startScan();
  });

  $('#btnBaseline').addEventListener('click', saveBaseline);
  $('#btnTest').addEventListener('click', spawnTest);
  $('#btnClearBase').addEventListener('click', clearBaseline);
  $('#btnClearList').addEventListener('click', function () {
    state.devices.clear();
    hideBanner();
    toast('목록을 비웠어요 (기준은 그대로)');
    renderNow();
  });
  $('#btnShowLog').addEventListener('click', openLog);
  $('#btnBannerClose').addEventListener('click', hideBanner);  $('#btnHideStale').addEventListener('click', function () {
    state.hideStale = !state.hideStale;
    renderNow();
  });

  // 기기 카드
  $('#devlist').addEventListener('click', function (e) {
    var btn = e.target.closest ? e.target.closest('[data-act]') : null;
    var card = e.target.closest ? e.target.closest('.dev') : null;
    if (!card) return;
    var key = card.getAttribute('data-key');
    var rec = state.devices.get(key);
    if (!rec) return;
    if (!btn) { rec.open = !rec.open; renderNow(); return; }
    var act = btn.getAttribute('data-act');
    if (act === 'more') { rec.open = !rec.open; renderNow(); }
    else if (act === 'hide') {
      state.devices.delete(key);
      toast('"' + (rec.name || '이름 없는 신호') + '"을(를) 목록에서 지웠어요');
      renderNow();
    } else if (act === 'gatt') { gattRead(rec); }
  });

  // 설정
  var mask = $('#settingsMask');
  $('#btnSettings').addEventListener('click', function () { mask.classList.add('on'); });
  $('#btnSettingsClose').addEventListener('click', function () { mask.classList.remove('on'); });
  mask.addEventListener('click', function (e) { if (e.target === mask) mask.classList.remove('on'); });
  $('#swSound').addEventListener('click', function () {
    state.settings.sound = !state.settings.sound;
    applySettings();
    if (state.settings.sound) beep('new');
  });
  $('#swVibe').addEventListener('click', function () {
    state.settings.vibe = !state.settings.vibe;
    applySettings();
    if (state.settings.vibe) buzz('new');
  });
  $$('#alertModeRow button').forEach(function (b) {
    b.addEventListener('click', function () { state.settings.mode = b.getAttribute('data-mode'); applySettings(); });
  });
  $$('#jumpRow button').forEach(function (b) {
    b.addEventListener('click', function () { state.settings.jump = +b.getAttribute('data-jump'); applySettings(); });
  });

  // 주파수
  var fi = $('#freqInput'), fu = $('#freqUnit');
  fi.addEventListener('input', function () { renderFreq(fi.value, fu.value); });
  fu.addEventListener('change', function () { renderFreq(fi.value, fu.value); });
  $('#freqChips').addEventListener('click', function (e) {
    var b = e.target.closest ? e.target.closest('button') : null;
    if (!b) return;
    fi.value = b.getAttribute('data-v');
    renderFreq(fi.value, fu.value);
  });
}

/* ---------------- 시작 ---------------- */
function init() {
  window.__sig = { state: state, analyze: analyze, lookupFreq: lookupFreq, parseFreqInput: parseFreqInput, errors: [] };
  window.addEventListener('error', function (e) {
    window.__sig.errors.push(String(e.message) + ' @' + (e.filename || '') + ':' + (e.lineno || ''));
  });

  // 저장된 값 복원
  var s = storeGet(LS_SET, null);
  if (s) { for (var k in s) if (k in state.settings) state.settings[k] = s[k]; }
  var b = storeGet(LS_BASE, null);
  if (b && b.map) { state.baseline = b.map; state.baselineAt = b.at || 0; }
  var lg = storeGet(LS_LOG, []);
  if (Array.isArray(lg)) state.alerts = lg.slice(0, 40);

  applySettings();
  wire();
  sizeCanvas();
  loop(0);

  if (!bt) {
    setBtBlocked('이 브라우저는 블루투스를 지원하지 않아요',
      '안드로이드 크롬에서 이 주소를 열어 주세요. 아이폰 사파리는 웹 블루투스 기능이 없어 쓸 수 없어요. 아래 주파수 조회와 테스트 신호는 그대로 쓸 수 있어요.');
  } else {
    if (location.protocol !== 'https:' && location.hostname !== 'localhost') {
      setBtBlocked('보안 연결(https)이 아니에요', '블루투스는 https 주소에서만 동작해요.');
    }
    if (typeof bt.getAvailability === 'function') {
      bt.getAvailability().then(function (a) {
        state.available = a;
        if (!a) {
          setBtBlocked('이 기기에서는 블루투스를 쓸 수 없어요',
            '블루투스가 꺼져 있거나, 브라우저가 블루투스 접근을 지원하지 않는 환경이에요. 휴대폰 설정에서 블루투스를 켜고 다시 열어 보세요.');
        }
        renderNow();
      }).catch(function () {});
    }
    if (typeof bt.requestLEScan !== 'function') {
      // 전체 스캔을 쓰려면 설정이 필요하다는 것만 칩으로 알리고, 안내 카드는 스캔 시도 때 펼친다
    }
  }

  setScanUi();
  renderFreq('2.4GHz', 'auto');
  renderNow();

  // 화면 복귀 시 갱신
  document.addEventListener('visibilitychange', function () { if (!document.hidden) { sizeCanvas(); renderNow(); } });
  window.addEventListener('resize', function () { sizeCanvas(); renderNow(); });

  // 45초가 지나 '오래됨' 표시가 바뀌었을 때만 다시 그린다 (화면을 건드리지 않도록)
  setInterval(function () {
    if ($('#tab-scan').hidden) return;
    if (staleSig() !== lastStaleSig) renderNow();
  }, 5000);
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
else init();

})();
