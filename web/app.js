"use strict";

/* ------------------------------------------------------------- settings */

const settings = {
  serverUrl: localStorage.getItem("settings.serverUrl") || "",
  refreshSec: parseInt(localStorage.getItem("settings.refreshSec") || "30", 10),
};

const TOKEN_KEY = "auth.token";

function apiUrl(path) {
  return (settings.serverUrl.replace(/\/+$/, "")) + path;
}

function authHeaders() {
  const headers = { Accept: "application/json" };
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) headers.Authorization = "Bearer " + token;
  return headers;
}

class AuthRequired extends Error {
  constructor(configured) {
    super("unauthorized");
    this.isAuth = true;
    this.configured = configured;
  }
}

async function api(path, options = {}) {
  const headers = authHeaders();
  if (options.body) headers["Content-Type"] = "application/json";
  const res = await fetch(apiUrl(path), {
    method: options.method || "GET",
    headers,
    body: options.body,
  });
  if (res.status === 401) {
    let configured = true;
    try { configured = (await res.json()).authConfigured !== false; } catch (_) { /* ignore */ }
    showLogin(configured);
    throw new AuthRequired(configured);
  }
  if (!res.ok) {
    let msg = "HTTP " + res.status;
    try { msg = (await res.json()).error || msg; } catch (_) { /* ignore */ }
    throw new Error(msg);
  }
  if (res.status === 204) return null;
  return res.json();
}

/* ---------------------------------------------------------------- icons */

const SENSOR_ICONS = {
  TEMPERATURE: '<svg viewBox="0 0 24 24"><path d="M15 14.55V5a3 3 0 0 0-6 0v9.55a5 5 0 1 0 6 0zM11 18a3 3 0 1 1 0-6 3 3 0 0 1 0 6z" fill="currentColor"/></svg>',
  PRESSURE:    '<svg viewBox="0 0 24 24"><path d="M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm0 18a8 8 0 1 1 8-8 8 8 0 0 1-8 8zm.5-13.5V14l4.5 2.6-.8 1.4L11 15V6.5z" fill="currentColor"/></svg>',
  HUMIDITY:    '<svg viewBox="0 0 24 24"><path d="M12 2.7S5 10.1 5 14.6a7 7 0 0 0 14 0C19 10.1 12 2.7 12 2.7z" fill="currentColor"/></svg>',
  WIND:        '<svg viewBox="0 0 24 24"><path d="M3 8h10.2a3 3 0 1 0-2.8-4H3zm0 8h13.2a3 3 0 1 1-2.8 4H3zm0-4h7.2A3 3 0 1 1 7.4 16H3z" fill="currentColor"/></svg>',
  RAIN:        '<svg viewBox="0 0 24 24"><path d="M17.5 11a4.5 4.5 0 0 0-8.6-1.7A3.5 3.5 0 0 0 6 16h11a3 3 0 0 0 .5-5zM8 18l-1 3m4-3-1 3m4-3-1 3" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linecap="round"/></svg>',
  ALTITUDE:    '<svg viewBox="0 0 24 24"><path d="M14 6 8.5 15H12l-2 5 7.5-10H14z" fill="currentColor"/><path d="M3 19h18" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>',
  VOLTAGE:     '<svg viewBox="0 0 24 24"><path d="M13 2 4 14h6l-1 8 9-12h-6z" fill="currentColor"/></svg>',
  CUSTOM:      '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8" fill="currentColor"/></svg>',
};

function sensorIcon(type) {
  return SENSOR_ICONS[type] || SENSOR_ICONS.CUSTOM;
}

function sensorLabel(type) {
  const labels = {
    TEMPERATURE: "Temperatur", PRESSURE: "Luftdruck", HUMIDITY: "Luftfeuchte",
    WIND: "Wind", RAIN: "Regen", ALTITUDE: "Höhe", VOLTAGE: "Spannung", CUSTOM: "Sensor",
  };
  return labels[type] || type || "Sensor";
}

/* -------------------------------------------------------------- helpers */

function fmtValue(v, digits) {
  if (v === null || v === undefined) return "–";
  const n = typeof v === "number" ? v : parseFloat(v);
  if (Number.isNaN(n)) return String(v);
  const d = digits !== undefined ? digits : (Math.abs(n) >= 100 ? 1 : Math.abs(n) >= 10 ? 1 : 2);
  return n.toLocaleString(undefined, { maximumFractionDigits: d, minimumFractionDigits: 0 });
}

function fmtTime(ms, withDate) {
  if (!ms) return "–";
  const d = new Date(ms);
  const opts = { hour: "2-digit", minute: "2-digit" };
  if (withDate) { opts.day = "2-digit"; opts.month = "2-digit"; }
  return d.toLocaleString(undefined, opts);
}

function ageMs(ms) {
  return ms ? Date.now() - ms : null;
}

function fmtAge(ms) {
  const a = ageMs(ms);
  if (a === null) return "–";
  if (a < 60_000) return "gerade eben";
  if (a < 3_600_000) return "vor " + Math.floor(a / 60_000) + " min";
  if (a < 86_400_000) return "vor " + Math.floor(a / 3_600_000) + " h";
  return "vor " + Math.floor(a / 86_400_000) + " T";
}

function el(tag, cls, html) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (html !== undefined) e.innerHTML = html;
  return e;
}

function escapeHtml(s) {
  return String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

let toastTimer = null;
function toast(msg, isErr) {
  const t = document.getElementById("toast");
  t.textContent = msg;
  t.classList.toggle("err", !!isErr);
  t.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add("hidden"), 3500);
}

/* ---------------------------------------------------------- SVG charts */

function niceDomain(min, max) {
  if (!Number.isFinite(min) || !Number.isFinite(max)) return [0, 1];
  if (min === max) {
    const pad = Math.abs(min) * 0.05 || 1;
    return [min - pad, max + pad];
  }
  const pad = (max - min) * 0.08;
  return [min - pad, max + pad];
}

// Scales shared by chart rendering and hover lookup (viewBox units)
function chartGeo(points, box) {
  const vals = points.map(p => Number(p.v));
  const [lo, hi] = niceDomain(Math.min(...vals), Math.max(...vals));
  const t0 = points[0].t, t1 = points[points.length - 1].t;
  const span = Math.max(t1 - t0, 1);
  const x = t => box.L + ((t - t0) / span) * (box.W - box.L - box.R);
  const y = v => box.T + (1 - (v - lo) / (hi - lo)) * (box.H - box.T - box.B);
  return { ...box, lo, hi, t0, t1, x, y };
}

const SPARK_BOX = { W: 120, H: 34, L: 3, R: 3, T: 3, B: 3 };
const CHART_BOX = { W: 600, H: 240, L: 44, R: 12, T: 14, B: 26 };

function sparkline(points, g) {
  const d = points.map((p, i) => (i ? "L" : "M") + g.x(p.t).toFixed(1) + " " + g.y(Number(p.v)).toFixed(1)).join(" ");
  return `<svg class="sensor-spark" viewBox="0 0 ${g.W} ${g.H}" preserveAspectRatio="none" aria-hidden="true">
    <path d="${d}" fill="none" stroke="var(--accent)" stroke-width="1.6" vector-effect="non-scaling-stroke" stroke-linejoin="round"/>
  </svg>`;
}

function renderSparkline(slot, points, unit) {
  if (!points || points.length < 2) { slot.innerHTML = ""; return; }
  const g = chartGeo(points, SPARK_BOX);
  slot.innerHTML = sparkline(points, g);
  // Cards open the detail view on tap, so touch keeps that and only the mouse hovers
  attachChartHover(slot, points, g, unit, { mouseOnly: true });
}

function renderBigChart(box, points, unit) {
  if (!points || points.length === 0) { box.innerHTML = bigChart(points, unit); return; }
  const g = chartGeo(points, CHART_BOX);
  box.innerHTML = bigChart(points, unit, g);
  attachChartHover(box, points, g, unit, { mouseOnly: false });
}

// Shows the nearest data point (guide line, dot, value + time) under mouse or finger
function attachChartHover(container, points, g, unit, { mouseOnly }) {
  const svg = container.querySelector("svg");
  if (!svg) return;
  const guide = document.createElementNS("http://www.w3.org/2000/svg", "line");
  guide.setAttribute("class", "chart-guide");
  guide.setAttribute("y1", g.T);
  guide.setAttribute("y2", g.H - g.B);
  guide.setAttribute("vector-effect", "non-scaling-stroke");
  svg.appendChild(guide);
  const dot = el("div", "chart-dot hidden");
  const tip = el("div", "chart-tip hidden");
  container.append(dot, tip);
  if (!mouseOnly) svg.style.touchAction = "pan-y";

  const xs = points.map(p => g.x(p.t));
  const hide = () => {
    guide.classList.remove("visible");
    dot.classList.add("hidden");
    tip.classList.add("hidden");
  };
  const show = ev => {
    if (mouseOnly && ev.pointerType !== "mouse") return;
    const r = svg.getBoundingClientRect();
    const vx = ((ev.clientX - r.left) / r.width) * g.W;
    let a = 0, b = xs.length - 1;
    while (b - a > 1) { const m = (a + b) >> 1; if (xs[m] < vx) a = m; else b = m; }
    const i = Math.abs(xs[a] - vx) <= Math.abs(xs[b] - vx) ? a : b;
    const p = points[i];

    guide.setAttribute("x1", xs[i]);
    guide.setAttribute("x2", xs[i]);
    guide.classList.add("visible");

    const c = container.getBoundingClientRect();
    const px = r.left - c.left + (xs[i] / g.W) * r.width;
    const py = r.top - c.top + (g.y(Number(p.v)) / g.H) * r.height;
    dot.style.left = px + "px";
    dot.style.top = py + "px";
    dot.classList.remove("hidden");

    tip.textContent = `${fmtValue(Number(p.v))}${unit ? " " + unit : ""} · ${fmtTime(p.t, true)}`;
    tip.classList.remove("hidden");
    const tw = tip.offsetWidth, th = tip.offsetHeight;
    tip.style.left = Math.min(Math.max(px - tw / 2, 0), c.width - tw) + "px";
    tip.style.top = (py - th - 10 >= 0 ? py - th - 10 : py + 10) + "px";
  };
  svg.addEventListener("pointermove", show);
  svg.addEventListener("pointerdown", show);
  svg.addEventListener("pointerleave", hide);
  svg.addEventListener("pointercancel", hide);
}

function bigChart(points, unit, g) {
  if (!points || points.length === 0) {
    return '<div class="chart-empty">Keine Daten im gewählten Zeitraum</div>';
  }
  const { W, H, L, R, B, lo, hi, t0, t1, x, y } = g;

  const line = points.map((p, i) => (i ? "L" : "M") + x(p.t).toFixed(1) + " " + y(Number(p.v)).toFixed(1)).join(" ");
  const area = line + ` L ${x(t1).toFixed(1)} ${H - B} L ${x(t0).toFixed(1)} ${H - B} Z`;

  let grid = "";
  for (let i = 0; i <= 3; i++) {
    const gv = lo + (hi - lo) * (i / 3);
    const gy = y(gv);
    grid += `<line x1="${L}" y1="${gy.toFixed(1)}" x2="${W - R}" y2="${gy.toFixed(1)}" stroke="var(--border)" stroke-width="1"/>`;
    grid += `<text x="${L - 6}" y="${(gy + 4).toFixed(1)}" text-anchor="end" font-size="11" fill="var(--text-muted)">${fmtValue(gv)}</text>`;
  }

  const last = points[points.length - 1];
  const unitAttr = unit ? ` ${unit}` : "";
  return `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Verlauf${unitAttr}">
    <defs>
      <linearGradient id="areaFill" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="var(--accent)" stop-opacity=".28"/>
        <stop offset="100%" stop-color="var(--accent)" stop-opacity="0"/>
      </linearGradient>
    </defs>
    ${grid}
    <path d="${area}" fill="url(#areaFill)"/>
    <path d="${line}" fill="none" stroke="var(--accent)" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>
    <circle cx="${x(last.t).toFixed(1)}" cy="${y(Number(last.v)).toFixed(1)}" r="4" fill="var(--accent)"/>
    <text x="${L}" y="${H - 6}" font-size="11" fill="var(--text-muted)">${fmtTime(t0, t1 - t0 > 86400000)}</text>
    <text x="${W - R}" y="${H - 6}" text-anchor="end" font-size="11" fill="var(--text-muted)">${fmtTime(t1, true)}</text>
  </svg>`;
}

/* ------------------------------------------------------------ app state */

const state = {
  sensors: [],
  rangeMs: 86400000,
  refreshTimer: null,
  currentSensorId: null,
  loggedIn: false,
};

const $ = id => document.getElementById(id);

/* ------------------------------------------------------------ auth flow */

/** Picks up the one-time token from the OAuth redirect fragment and
 *  immediately removes it from the URL/history. */
function consumeAuthFragment() {
  const hash = location.hash || "";
  if (hash.startsWith("#token=")) {
    const token = hash.substring("#token=".length).split("&")[0];
    if (token) localStorage.setItem(TOKEN_KEY, token);
    history.replaceState(null, "", location.pathname + location.search);
    return "ok";
  }
  if (hash === "#auth-error") {
    history.replaceState(null, "", location.pathname + location.search);
    return "error";
  }
  return null;
}

function showLogin(configured) {
  state.loggedIn = false;
  clearInterval(state.refreshTimer);
  $("login-screen").classList.remove("hidden");
  $("login-error").classList.add("hidden");
  $("login-config-hint").classList.toggle("hidden", configured !== false);
  $("user-chip").classList.add("hidden");
  $("btn-logout").classList.add("hidden");
  document.querySelector("main").classList.add("faded");
}

function showApp(me) {
  state.loggedIn = true;
  $("login-screen").classList.add("hidden");
  document.querySelector("main").classList.remove("faded");
  if (me) {
    $("user-chip").classList.remove("hidden");
    $("btn-logout").classList.remove("hidden");
    $("user-name").textContent = me.name || me.email || "";
    const pic = $("user-pic");
    if (me.picture && /^https:\/\//.test(me.picture)) {
      pic.src = me.picture;
      pic.classList.remove("hidden");
    } else {
      pic.classList.add("hidden");
    }
  }
}

$("btn-login").addEventListener("click", () => {
  // full-page navigation to the server so the callback lands on our origin
  location.href = apiUrl("/auth/login");
});

$("btn-logout").addEventListener("click", async () => {
  try {
    await fetch(apiUrl("/auth/logout"), { method: "POST", headers: authHeaders() });
  } catch (_) { /* best effort */ }
  localStorage.removeItem(TOKEN_KEY);
  location.reload();
});

/* ------------------------------------------------------------ dashboard */

async function loadDashboard() {
  const [devices, sensors, latest] = await Promise.all([
    api("/api/devices"),
    api("/api/sensors"),
    api("/api/measurements/latest"),
  ]);
  state.sensors = sensors;
  renderDevices(devices);
  renderSensors(sensors, latest);
  $("empty-hint").classList.toggle("hidden", sensors.length > 0);
  $("sensors-title").classList.toggle("hidden", sensors.length === 0);
  const latestTs = latest.map(m => m.timestamp).filter(Boolean).sort((a, b) => b - a)[0];
  const line = $("status-line");
  line.className = "status-line ok";
  line.textContent = latestTs
    ? `Letzter Wert ${fmtAge(latestTs)}`
    : `${sensors.length} Sensor${sensors.length === 1 ? "" : "en"} · keine eigenen Daten`;

  await Promise.all(sensors.map(async s => {
    try {
      const data = await api(`/api/measurements?sensorId=${s.id}&window=86400000&limit=500`);
      const slot = document.querySelector(`.sensor-card[data-id="${s.id}"] .sensor-spark-slot`);
      if (slot) renderSparkline(slot, data.points, s.unit || "");
      // Rain arrives per report interval: the card shows the 24 h total instead
      if (s.sensorType === "RAIN") {
        const card = document.querySelector(`.sensor-card[data-id="${s.id}"]`);
        card.querySelector(".sensor-value").innerHTML =
          `${fmtValue(data.stats?.sum ?? 0)}<span class="unit">${escapeHtml(s.unit || "")} / 24 h</span>`;
      }
    } catch (_) { /* sparkline is optional */ }
  }));
}

const PENCIL_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>';

function renderDevices(devices) {
  const root = $("devices");
  root.innerHTML = "";
  for (const d of devices) {
    const card = el("div", "device-card");
    card.innerHTML = `
      <span class="device-dot ${d.online ? "on" : ""}"></span>
      <div>
        <h3>${escapeHtml(d.name || "–")}</h3>
        <p>${escapeHtml(d.deviceType || "SENSOR")} · ${d.sensorCount || 0} Sensor${d.sensorCount === 1 ? "" : "en"}</p>
      </div>
      <div class="device-meta">${d.lastSeen ? fmtAge(d.lastSeen) : ""}</div>
      <button class="icon-btn device-edit" aria-label="Umbenennen">${PENCIL_ICON}</button>`;
    card.querySelector(".device-edit").addEventListener("click", e => {
      e.stopPropagation();
      openRename(d);
    });
    root.appendChild(card);
  }
}

function renderSensors(sensors, latest) {
  const byId = new Map(latest.map(m => [m.sensorId, m]));
  const grid = $("sensor-grid");
  grid.innerHTML = "";
  for (const s of sensors) {
    const m = byId.get(s.id) || {};
    const card = el("button", "sensor-card");
    card.dataset.id = s.id;
    card.innerHTML = `
      <div class="sensor-head">
        <span class="sensor-icon">${sensorIcon(s.sensorType)}</span>
        <span class="sensor-name" title="${escapeHtml(s.deviceName)}">${escapeHtml(s.deviceName)} · ${escapeHtml(sensorLabel(s.sensorType))}</span>
      </div>
      <div class="sensor-value">${fmtValue(m.value)}<span class="unit">${escapeHtml(s.unit || "")}</span></div>
      <div class="sensor-spark-slot"></div>
      <div class="sensor-time">${m.timestamp ? fmtAge(m.timestamp) : "keine Daten"}</div>`;
    card.addEventListener("click", () => openDetail(s.id));
    grid.appendChild(card);
  }
}

/* --------------------------------------------------------------- detail */

async function openDetail(sensorId) {
  state.currentSensorId = sensorId;
  const s = state.sensors.find(x => x.id === sensorId);
  $("detail-icon").innerHTML = sensorIcon(s?.sensorType);
  $("detail-title").textContent = sensorLabel(s?.sensorType) + (s?.deviceName ? " · " + s.deviceName : "");
  $("detail-sub").textContent = s ? `${s.name || s.sensorType} · ${s.deviceName}` : "";
  $("view-dashboard").classList.add("hidden");
  $("view-detail").classList.remove("hidden");
  window.scrollTo(0, 0);
  await loadDetail();
}

async function loadDetail() {
  const id = state.currentSensorId;
  if (id == null) return;
  const data = await api(`/api/measurements?sensorId=${id}&window=${state.rangeMs}&limit=2000`);
  if (state.currentSensorId !== id) return; // user navigated away
  const unit = data.sensor?.unit || "";
  const pts = data.points || [];
  const last = pts.length ? pts[pts.length - 1] : null;
  const isRain = data.sensor?.sensorType === "RAIN";
  $("detail-current").textContent = isRain ? fmtValue(data.stats?.sum ?? 0) : (last ? fmtValue(Number(last.v)) : "–");
  $("detail-unit").textContent = isRain ? `${unit} im Zeitraum` : unit;
  renderBigChart($("chart"), pts, unit);
  $("stat-min").textContent = fmtValue(data.stats?.min);
  $("stat-avg-label").textContent = isRain ? "Σ" : "Ø";
  $("stat-avg").textContent = fmtValue(isRain ? data.stats?.sum : data.stats?.avg);
  $("stat-max").textContent = fmtValue(data.stats?.max);
  $("stat-count").textContent = data.stats?.count ?? "–";
}

$("btn-back").addEventListener("click", () => {
  state.currentSensorId = null;
  $("view-detail").classList.add("hidden");
  $("view-dashboard").classList.remove("hidden");
  refresh().catch(() => {});
});

$("range-tabs").addEventListener("click", e => {
  const btn = e.target.closest("button[data-window]");
  if (!btn) return;
  state.rangeMs = parseInt(btn.dataset.window, 10);
  for (const b of $("range-tabs").children) b.classList.toggle("active", b === btn);
  loadDetail().catch(err => { if (!err.isAuth) toast(err.message, true); });
});

/* ------------------------------------------------- device management */

const devicesDialog = $("devices-dialog");

$("btn-manage-devices").addEventListener("click", () => openManageDevices().catch(err => toast(err.message, true)));

async function openManageDevices() {
  const [linked, available] = await Promise.all([
    api("/api/devices"),
    api("/api/devices/available"),
  ]);
  renderDeviceLists(linked, available);
  devicesDialog.showModal();
}

function renderDeviceLists(linked, available) {
  const linkedRoot = $("linked-devices");
  linkedRoot.innerHTML = "";
  $("linked-empty").classList.toggle("hidden", linked.length > 0);
  for (const d of linked) {
    const row = el("div", "dev-row");
    row.innerHTML = `
      <div class="dev-info">
        <span class="device-dot ${d.online ? "on" : ""}"></span>
        <span class="dev-name">${escapeHtml(d.name)}</span>
        <span class="dev-type">${escapeHtml(d.deviceType || "")}</span>
      </div>
      <button class="small-btn danger" data-unlink="${d.id}">Entfernen</button>`;
    linkedRoot.appendChild(row);
  }

  const availRoot = $("available-devices");
  availRoot.innerHTML = "";
  $("available-empty").classList.toggle("hidden", available.length > 0);
  for (const d of available) {
    const row = el("div", "dev-row");
    row.innerHTML = `
      <div class="dev-info">
        <span class="device-dot ${d.online ? "on" : ""}"></span>
        <span class="dev-name">${escapeHtml(d.name)}</span>
        <span class="dev-type">${escapeHtml(d.deviceType || "")}</span>
      </div>
      <button class="small-btn" data-link="${d.id}">Hinzufügen</button>`;
    availRoot.appendChild(row);
  }
}

devicesDialog.addEventListener("click", async e => {
  const linkId = e.target.dataset?.link;
  const unlinkId = e.target.dataset?.unlink;
  try {
    if (linkId) {
      await api("/api/devices", { method: "POST", body: JSON.stringify({ deviceId: Number(linkId) }) });
      await refreshDeviceLists();
      toast("Gerät verknüpft");
    } else if (unlinkId) {
      await api(`/api/devices/${unlinkId}`, { method: "DELETE" });
      await refreshDeviceLists();
      toast("Verknüpfung entfernt");
    }
  } catch (err) {
    if (!err.isAuth) toast(err.message, true);
  }
});

async function refreshDeviceLists() {
  const [linked, available] = await Promise.all([
    api("/api/devices"),
    api("/api/devices/available"),
  ]);
  renderDeviceLists(linked, available);
}

$("btn-add-device").addEventListener("click", async () => {
  const input = $("new-device-name");
  const name = input.value.trim();
  if (!name) { toast("Name eingeben", true); return; }
  try {
    const dev = await api("/api/devices", { method: "POST", body: JSON.stringify({ name }) });
    input.value = "";
    await refreshDeviceLists();
    toast(dev.created ? `Gerät "${dev.name}" angelegt und verknüpft` : `Gerät "${dev.name}" verknüpft`);
  } catch (err) {
    if (!err.isAuth) toast(err.message, true);
  }
});

$("btn-close-devices").addEventListener("click", () => {
  devicesDialog.close();
  refresh().catch(() => {});
});

/* --------------------------------------------------------------- rename */

const renameDlg = $("rename-dialog");
let renameDevice = null;

function openRename(device) {
  renameDevice = device;
  $("rename-name").value = device.name || "";
  $("rename-hwid").textContent = device.hardwareId || "–";
  renameDlg.showModal();
  $("rename-name").select();
}

$("rename-form").addEventListener("submit", () => {
  setTimeout(async () => {
    if (renameDlg.returnValue !== "save" || !renameDevice) return;
    const name = $("rename-name").value.trim();
    try {
      await api(`/api/devices/${renameDevice.id}`, {
        method: "PUT",
        body: JSON.stringify({ name }),
      });
      toast("Name gespeichert");
      await refresh();
    } catch (err) {
      if (!err.isAuth) toast(err.message, true);
    }
  }, 0);
});

/* -------------------------------------------------------- refresh loop */

async function refresh() {
  const btn = $("btn-refresh");
  btn.classList.add("spinning");
  try {
    if (state.currentSensorId != null) {
      await loadDetail();
    } else {
      await loadDashboard();
    }
  } catch (err) {
    if (err.isAuth) throw err; // login screen already shown
    const line = $("status-line");
    line.className = "status-line err";
    line.textContent = "Server nicht erreichbar";
    throw err;
  } finally {
    btn.classList.remove("spinning");
  }
}

function scheduleRefresh() {
  clearInterval(state.refreshTimer);
  if (settings.refreshSec > 0) {
    state.refreshTimer = setInterval(() => {
      refresh().catch(err => { if (!err.isAuth) toast(err.message, true); });
    }, settings.refreshSec * 1000);
  }
}

$("btn-refresh").addEventListener("click", () => {
  refresh().catch(err => { if (!err.isAuth) toast(err.message, true); });
});

document.addEventListener("visibilitychange", () => {
  if (!document.hidden && state.loggedIn) refresh().catch(() => {});
});

/* ------------------------------------------------------------ settings */

const dlg = $("settings-dialog");

$("btn-settings").addEventListener("click", () => {
  $("set-server").value = settings.serverUrl;
  $("set-refresh").value = String(settings.refreshSec);
  dlg.showModal();
});

$("settings-form").addEventListener("submit", () => {
  setTimeout(() => {
    if (dlg.returnValue !== "save") return;
    settings.serverUrl = $("set-server").value.trim();
    settings.refreshSec = parseInt($("set-refresh").value, 10);
    localStorage.setItem("settings.serverUrl", settings.serverUrl);
    localStorage.setItem("settings.refreshSec", String(settings.refreshSec));
    scheduleRefresh();
    if (state.loggedIn) {
      refresh().then(() => toast("Einstellungen gespeichert")).catch(err => toast(err.message, true));
    }
  }, 0);
});

/* ------------------------------------------------------------- start-up */

if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("sw.js").catch(() => { /* offline shell optional */ });
  });
}

(async function boot() {
  const fragment = consumeAuthFragment();
  try {
    const me = await api("/api/auth/me");
    showApp(me);
    if (fragment === "ok") toast("Angemeldet");
  } catch (err) {
    if (err.isAuth) {
      if (fragment === "error") $("login-error").classList.remove("hidden");
      return; // login screen is up
    }
    // offline / server hiccup: keep the shell alive, SW serves cached data
    showApp(null);
  }
  if (fragment === "error") toast("Google-Anmeldung fehlgeschlagen", true);
  refresh().catch(err => { if (!err.isAuth) toast(err.message, true); });
  scheduleRefresh();
})();
