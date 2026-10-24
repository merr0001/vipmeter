// H.G 진동기 상황판 — 윈도우 프로그램 (Electron)
const { app, BrowserWindow, ipcMain, Notification, Tray, Menu, nativeImage, powerSaveBlocker, shell, session } = require("electron");
const path = require("path"), fs = require("fs"), http = require("http"), os = require("os");

if (!app.requestSingleInstanceLock()) { app.quit(); process.exit(0); }
app.setAppUserModelId("HG.VibBoard");

const DEF = { autoStart: true, fullscreen: false, trayOnClose: true, preventSleep: true, autoConnect: true,
  remote: true, port: 8000, notify: true, beep: true, beepRepeat: 30, batWarn: 20, lostSec: 60, capId: "" };
const CFG_FILE = () => path.join(app.getPath("userData"), "config.json");
let cfg = { ...DEF };
try { cfg = { ...DEF, ...JSON.parse(fs.readFileSync(CFG_FILE(), "utf8")) }; } catch (e) {}
const saveCfg = () => { try { fs.writeFileSync(CFG_FILE(), JSON.stringify(cfg, null, 1)); } catch (e) {} };
const DATA_DIR = () => path.join(app.getPath("documents"), "진동기상황판");
const day = (t = new Date()) => `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, "0")}-${String(t.getDate()).padStart(2, "0")}`;

let win = null, tray = null, quitting = false, psb = null, lastState = "{}", lastBackupDay = "";
const ICON = nativeImage.createFromPath(path.join(__dirname, "icon.png"));

function applyRuntime() {
  try { app.setLoginItemSettings({ openAtLogin: !!cfg.autoStart }); } catch (e) {}
  if (cfg.preventSleep && psb == null) psb = powerSaveBlocker.start("prevent-display-sleep");
  if (!cfg.preventSleep && psb != null) { powerSaveBlocker.stop(psb); psb = null; }
  startServer();
}

function createWindow() {
  win = new BrowserWindow({ width: 1600, height: 960, fullscreen: !!cfg.fullscreen, icon: ICON, autoHideMenuBar: true,
    title: "H.G 진동기 상황판", backgroundColor: "#eef1f5",
    webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true, backgroundThrottling: false } });
  win.setMenuBarVisibility(false);
  win.loadFile(path.join(__dirname, "index.html"));
  win.on("close", (e) => { if (!quitting && cfg.trayOnClose) { e.preventDefault(); win.hide(); } });
  win.webContents.on("before-input-event", (e, i) => {
    if (i.type === "keyDown" && i.key === "F11") { win.setFullScreen(!win.isFullScreen()); e.preventDefault(); }
    if (i.type === "keyDown" && i.key === "Escape" && win.isFullScreen()) win.setFullScreen(false);
    if (i.type === "keyDown" && (i.key === "F5" || (i.control && i.key.toLowerCase() === "r"))) e.preventDefault(); // 새로고침 막기 (연결 유지)
  });
  // 블루투스: 목록 창 없이 캡슐(HG-CAP-…) 자동 선택
  let pick = null, pickTimer = null;
  win.webContents.on("select-bluetooth-device", (event, list, callback) => {
    event.preventDefault();
    if (!pick) { pick = callback; clearTimeout(pickTimer); pickTimer = setTimeout(() => { if (pick) { pick(""); pick = null; } }, 20000); }
    const d = list.find((x) => cfg.capId && x.deviceId === cfg.capId) || list.find((x) => (x.deviceName || "").startsWith("HG-CAP"));
    if (d && pick) { clearTimeout(pickTimer); cfg.capId = d.deviceId; saveCfg(); const p = pick; pick = null; p(d.deviceId); }
  });
  win.webContents.on("did-finish-load", () => {
    if (cfg.autoConnect) setTimeout(() => win.webContents.executeJavaScript("window.autoBle&&autoBle()", true).catch(() => {}), 1500);
  });
}

// ---- 원격 보기: 같은 Wi-Fi의 PC·폰 브라우저에서 http://이PC:8000 ----
let server = null, serverPort = 0;
const clients = new Set();
function startServer() {
  if (!cfg.remote) { if (server) { server.close(); server = null; serverPort = 0; } return; }
  if (server && serverPort === cfg.port) return;
  if (server) server.close();
  server = http.createServer((req, res) => {
    const u = req.url.split("?")[0];
    if (u === "/events") {
      res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", Connection: "keep-alive" });
      res.write(`data: ${lastState}\n\n`); clients.add(res); req.on("close", () => clients.delete(res)); return;
    }
    if (u === "/state") { res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" }); res.end(lastState); return; }
    if (u === "/" || u === "/index.html") {
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      res.end(fs.readFileSync(path.join(__dirname, "index.html"), "utf8").replace("<head>", "<head><script>window.REMOTE=true</script>")); return;
    }
    res.writeHead(404); res.end();
  });
  server.on("error", () => { server = null; serverPort = 0; });
  server.listen(cfg.port, "0.0.0.0", () => { serverPort = cfg.port; });
}
function addrs() {
  const out = [];
  for (const [n, l] of Object.entries(os.networkInterfaces())) for (const a of l || []) if (a.family === "IPv4" && !a.internal) out.push({ name: n, ip: a.address });
  return out;
}

// ---- IPC ----
ipcMain.handle("cfg:get", () => cfg);
ipcMain.handle("cfg:set", (e, c) => { const fsChange = c.fullscreen !== undefined && c.fullscreen !== cfg.fullscreen; cfg = { ...cfg, ...c }; saveCfg(); applyRuntime(); if (fsChange && win) win.setFullScreen(!!cfg.fullscreen); return cfg; });
ipcMain.handle("info", () => ({ version: app.getVersion(), folder: DATA_DIR(), addrs: addrs(), port: serverPort, remote: !!server, viewers: clients.size }));
ipcMain.on("state", (e, json) => {
  lastState = json;
  for (const c of clients) { try { c.write(`data: ${json}\n\n`); } catch (er) {} }
  const d = day();
  if (d !== lastBackupDay) { lastBackupDay = d; try { const b = path.join(DATA_DIR(), "백업"); fs.mkdirSync(b, { recursive: true }); fs.writeFileSync(path.join(b, `상황판_${d}.json`), json); } catch (er) {} }
});
const CSV_HEAD = "시각,호기,캡슐,LOT,스펙,공정,RMS mm/s,주파수 Hz,X/Z %,Y/Z %,2배 진동 %,안정도 %,기울기 흔들림 %,배터리 %,측정 시간 s\r\n";
ipcMain.on("rec", (e, r) => {
  try {
    fs.mkdirSync(DATA_DIR(), { recursive: true });
    const f = path.join(DATA_DIR(), `${day(new Date(r.t))}.csv`);
    const t = new Date(r.t), hh = (x) => String(x).padStart(2, "0");
    const row = [`${day(t)} ${hh(t.getHours())}:${hh(t.getMinutes())}:${hh(t.getSeconds())}`, r.n || "", r.id, r.lot || "", r.spec || "", r.stage || "",
      r.rms, r.hz, r.x ?? "", r.y ?? "", r.h2 ?? "", r.cv ?? "", r.rk ?? "", r.bat == null ? "" : Math.round(r.bat), (r.ms || 0) / 1000].join(",") + "\r\n";
    if (!fs.existsSync(f)) fs.writeFileSync(f, "﻿" + CSV_HEAD);
    fs.appendFileSync(f, row);
  } catch (er) {}
});
ipcMain.on("notify", (e, title, body) => { if (cfg.notify && Notification.isSupported()) { const n = new Notification({ title, body, icon: ICON }); n.on("click", () => { win.show(); win.focus(); }); n.show(); } });
ipcMain.on("open-folder", () => { fs.mkdirSync(DATA_DIR(), { recursive: true }); shell.openPath(DATA_DIR()); });
ipcMain.on("ble-forget", () => { cfg.capId = ""; saveCfg(); });

app.on("second-instance", () => { if (win) { win.show(); win.focus(); } });
app.whenReady().then(() => {
  session.defaultSession.setPermissionCheckHandler(() => true);
  session.defaultSession.setDevicePermissionHandler(() => true);
  createWindow();
  tray = new Tray(ICON.resize({ width: 16, height: 16 }));
  tray.setToolTip("H.G 진동기 상황판");
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: "상황판 열기", click: () => { win.show(); win.focus(); } },
    { label: "기록 폴더 열기", click: () => { fs.mkdirSync(DATA_DIR(), { recursive: true }); shell.openPath(DATA_DIR()); } },
    { type: "separator" },
    { label: "프로그램 끄기", click: () => { quitting = true; app.quit(); } },
  ]));
  tray.on("click", () => { win.show(); win.focus(); });
  applyRuntime();
});
app.on("before-quit", () => { quitting = true; });
app.on("window-all-closed", () => { if (process.platform !== "darwin" && quitting) app.quit(); });
