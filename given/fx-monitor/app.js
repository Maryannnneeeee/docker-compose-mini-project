/*
 * fx-monitor — the live view of the rates.
 *
 * It polls the fx-app-spring API through nginx (same origin, so no CORS):
 *   GET  /health                  → liveness dot
 *   GET  /api/rates               → the table
 *   GET  /api/admin/accepting     → toggle state
 *   POST /api/admin/accepting     → flip it
 *
 * If the two admin calls 404 the toggle stays disabled and the banner says so.
 * Everything else still works.
 *
 * Rendering note: rows are built ONCE and then mutated in place. Nothing is ever
 * re-created, so the page never flickers — on an update the only thing that moves is a
 * background flash on the cell that actually changed. The last change stays on screen
 * until the next one replaces it.
 */

const POLL_MS   = 1000;   // how often we ask the API
const HISTORY   = 90;     // points kept per pair for the sparkline (~90s of history)
const SPARK_W   = 190;    // sparkline viewBox size
const SPARK_H   = 38;
const SVG_NS    = 'http://www.w3.org/2000/svg';

const el = {
  body:        document.getElementById('rates-body'),
  updated:     document.getElementById('updated'),
  dot:         document.getElementById('health-dot'),
  banner:      document.getElementById('banner'),
  toggle:      document.getElementById('toggle'),
  toggleState: document.getElementById('toggle-state'),
  pollCount:   document.getElementById('poll-count'),
  updateCount: document.getElementById('update-count'),
  interval:    document.getElementById('interval'),
  since:       document.getElementById('since'),
  apiState:    document.getElementById('api-state'),
};

/** pair -> { tr, rateCell, deltaCell, line, area, rateText, deltaText, whenText, history[], last } */
const rows = new Map();
let polls = 0;
let adminAvailable = null;   // null = not yet probed

/*
 * We poll once a second, but the feed only moves when the orchestrator pushes a batch the API
 * accepts — every ~2s while ACCEPTING is ON, every ~10s once it is OFF. So counting polls
 * measures this page; counting UPDATES measures the system. We report the latter.
 */
let updates = 0;            // polls in which at least one rate actually changed
let lastUpdateAt = null;    // ms wall-clock of the most recent one (drives "Ns ago")
let lastIntervalMs = null;  // gap between the last two — ~2000 when ON, ~10000 when OFF
let lastCapturedAt = null;  // the SERVER's timestamp on the previous batch we saw

/*
 * Measure the interval from the server's own `captured_at` where we can, not from our
 * clock. Our clock only knows when we *noticed* a change, which is quantised to the poll
 * interval — and a browser throttles timers in a background tab, which would make the
 * number quietly wrong. The database timestamps are the truth.
 */
function batchStamp(rates) {
  let newest = null;
  for (const r of rates) {
    if (!r.capturedAt) continue;
    const t = Date.parse(r.capturedAt);
    if (!Number.isNaN(t) && (newest === null || t > newest)) newest = t;
  }
  return newest;
}

const pairOf = r => `${r.baseCode}/${r.quoteCode}`;
const fmt    = n => Number(n).toFixed(4);

/*
 * "As of" comes from capturedAt when the API supplies it and falls back to rateDate
 * otherwise. With a live feed every row shares today's date, so the DATE alone stops
 * being useful — the time is the interesting part.
 */
function asOf(r) {
  if (r.capturedAt) {
    const t = String(r.capturedAt).split('T')[1];
    return t ? t.slice(0, 8) : String(r.capturedAt);
  }
  return r.rateDate ?? '';
}

function showBanner(msg) { el.banner.textContent = msg; el.banner.classList.remove('hidden'); }
function hideBanner()    { el.banner.classList.add('hidden'); }

/* ------------------------------------------------------------------ rows -- */

function svg(tag, attrs) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const k in attrs) node.setAttribute(k, attrs[k]);
  return node;
}

/** Build one row, once. Everything after this is attribute/textContent mutation. */
function createRow(pair) {
  const tr = document.createElement('tr');

  const pairCell = document.createElement('td');
  pairCell.className = 'pair';
  pairCell.textContent = pair;

  // Each mutable cell gets ONE text node, created here and never replaced. Updating
  // `.nodeValue` edits that node in place; assigning `.textContent` would throw the old
  // node away and insert a new one on every tick. Same pixels, more churn.
  const rateCell = document.createElement('td');
  rateCell.className = 'num rate';
  const rateText = document.createTextNode('');
  rateCell.append(rateText);

  const deltaCell = document.createElement('td');
  deltaCell.className = 'num delta';
  const deltaText = document.createTextNode('—');
  deltaCell.append(deltaText);

  const sparkCell = document.createElement('td');
  sparkCell.className = 'spark';
  const chart = svg('svg', {
    viewBox: `0 0 ${SPARK_W} ${SPARK_H}`,
    preserveAspectRatio: 'none',
    'aria-label': `${pair} recent history`,
  });
  const area = svg('polygon', { class: 'spark-area', points: '' });
  const line = svg('polyline', { class: 'spark-line', points: '' });
  chart.append(area, line);
  sparkCell.append(chart);

  const whenCell = document.createElement('td');
  whenCell.className = 'when';
  const whenText = document.createTextNode('');
  whenCell.append(whenText);

  tr.append(pairCell, rateCell, deltaCell, sparkCell, whenCell);
  el.body.append(tr);

  const row = {
    tr, rateCell, deltaCell, line, area,
    rateText, deltaText, whenText,
    history: [], last: undefined,
  };
  rows.set(pair, row);
  return row;
}

/** Re-point the sparkline. Only the `points` attribute changes — no nodes are replaced. */
function drawSpark(row) {
  const h = row.history;
  if (h.length < 2) return;

  const min = Math.min(...h);
  const max = Math.max(...h);
  const span = max - min;
  const stepX = SPARK_W / (h.length - 1);
  const pad = 3;

  const pts = h.map((v, i) => {
    const x = i * stepX;
    // A flat series has no range to scale against — draw it down the middle.
    const y = span === 0
      ? SPARK_H / 2
      : SPARK_H - pad - ((v - min) / span) * (SPARK_H - pad * 2);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });

  row.line.setAttribute('points', pts.join(' '));
  row.area.setAttribute('points', `0,${SPARK_H} ${pts.join(' ')} ${SPARK_W},${SPARK_H}`);
}

/**
 * Restart the background flash. Removing the class, forcing a reflow and re-adding it is
 * what makes the animation replay when the same direction happens twice in a row.
 */
function flash(cell, up) {
  cell.classList.remove('flash-up', 'flash-down');
  void cell.offsetWidth;
  cell.classList.add(up ? 'flash-up' : 'flash-down');
}

/** Returns true if this pair's value actually moved. */
function updateRow(rate) {
  const pair = pairOf(rate);
  const row  = rows.get(pair) || createRow(pair);
  const now  = Number(rate.rate);

  row.history.push(now);
  if (row.history.length > HISTORY) row.history.shift();
  drawSpark(row);

  const when = asOf(rate);
  if (row.whenText.nodeValue !== when) row.whenText.nodeValue = when;

  // First sighting: paint it, but there is no "change" to report yet.
  if (row.last === undefined) {
    row.rateText.nodeValue = fmt(now);
    row.last = now;
    return false;
  }

  // Unchanged: touch nothing. The previous delta stays on screen on purpose.
  if (now === row.last) return false;

  const up = now > row.last;
  const diff = now - row.last;

  row.rateText.nodeValue  = fmt(now);
  row.deltaText.nodeValue = `${up ? '▲' : '▼'} ${diff >= 0 ? '+' : ''}${diff.toFixed(4)}`;
  row.deltaCell.classList.remove('up', 'down');
  row.deltaCell.classList.add(up ? 'up' : 'down');
  flash(row.rateCell, up);

  row.last = now;
  return true;
}

/* ----------------------------------------------------------------- rates -- */

async function pollRates() {
  try {
    const res = await fetch('/api/rates');
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const rates = await res.json();

    const empty = el.body.querySelector('tr.empty');
    if (empty) empty.remove();

    let changed = 0;
    for (const rate of rates) if (updateRow(rate)) changed++;

    if (changed > 0) {
      const now = Date.now();
      const stamp = batchStamp(rates);

      if (stamp !== null && lastCapturedAt !== null) {
        lastIntervalMs = stamp - lastCapturedAt;        // server truth
      } else if (stamp === null && lastUpdateAt !== null) {
        lastIntervalMs = now - lastUpdateAt;            // fallback: our own clock
      }
      if (stamp !== null) lastCapturedAt = stamp;

      lastUpdateAt = now;
      updates++;
      el.updateCount.textContent = updates;
      el.interval.textContent = lastIntervalMs === null
        ? '—'
        : `${(lastIntervalMs / 1000).toFixed(1)} s`;
      el.updated.textContent = `updated ${new Date().toLocaleTimeString()}`;
    }

    paintSince();
    el.apiState.textContent = `${rates.length} pairs`;
    el.dot.className = 'dot up';
  } catch (err) {
    el.apiState.textContent = 'unreachable';
    el.dot.className = 'dot down';
    el.updated.textContent = `last attempt failed: ${err.message}`;
  } finally {
    el.pollCount.textContent = ++polls;
  }
}

/**
 * "3s ago" — climbs visibly while the feed is being declined, which is the whole tell.
 * Turning ACCEPTING off stops updates entirely (a declined batch is never stored), so this
 * is the number that shows it, not the interval.
 */
function paintSince() {
  if (lastUpdateAt === null) { el.since.textContent = '—'; return; }
  const secs = Math.round((Date.now() - lastUpdateAt) / 1000);
  el.since.textContent = secs <= 0 ? 'just now' : `${secs}s ago`;
  el.since.classList.toggle('stale', secs >= 5);
}

/* ------------------------------------------------------------- accepting -- */

function paintToggle(accepting) {
  el.toggle.disabled = false;
  el.toggle.setAttribute('aria-pressed', String(accepting));
  el.toggleState.textContent = accepting ? 'ON' : 'OFF';
  el.toggleState.className = 'toggle-state ' + (accepting ? 'on' : 'off');
}

function paintToggleUnavailable() {
  el.toggle.disabled = true;
  el.toggle.setAttribute('aria-pressed', 'false');
  el.toggleState.textContent = 'not built';
  el.toggleState.className = 'toggle-state na';
}

async function pollAccepting() {
  try {
    const res = await fetch('/api/admin/accepting');
    if (res.status === 404) {
      if (adminAvailable !== false) {
        adminAvailable = false;
        paintToggleUnavailable();
        showBanner('GET /api/admin/accepting returned 404 — the admin endpoint is not available.');
      }
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);

    const body = await res.json();
    if (adminAvailable !== true) { adminAvailable = true; hideBanner(); }
    paintToggle(Boolean(body.accepting));
  } catch (err) {
    if (adminAvailable !== false) {
      adminAvailable = false;
      paintToggleUnavailable();
      showBanner(`Could not read /api/admin/accepting (${err.message}).`);
    }
  }
}

el.toggle.addEventListener('click', async () => {
  const next = el.toggle.getAttribute('aria-pressed') !== 'true';
  el.toggle.disabled = true;
  try {
    const res = await fetch('/api/admin/accepting', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accepting: next }),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const body = await res.json();
    paintToggle(Boolean(body.accepting));
  } catch (err) {
    showBanner(`POST /api/admin/accepting failed (${err.message}).`);
    el.toggle.disabled = false;
  }
});

/* ------------------------------------------------------------------ boot -- */

pollRates();
pollAccepting();
setInterval(pollRates, POLL_MS);
setInterval(pollAccepting, POLL_MS * 2);
setInterval(paintSince, 1000);   // keeps "Ns ago" honest between updates
