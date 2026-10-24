const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("hg", {
  getConfig: () => ipcRenderer.invoke("cfg:get"),
  setConfig: (c) => ipcRenderer.invoke("cfg:set", c),
  info: () => ipcRenderer.invoke("info"),
  pushState: (json) => ipcRenderer.send("state", json),
  saveRecord: (r) => ipcRenderer.send("rec", r),
  notify: (title, body) => ipcRenderer.send("notify", title, body),
  openFolder: () => ipcRenderer.send("open-folder"),
  bleForget: () => ipcRenderer.send("ble-forget"),
  onBleStatus: (cb) => ipcRenderer.on("ble-status", (e, s) => cb(s)),
});
