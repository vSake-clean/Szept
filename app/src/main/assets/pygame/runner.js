// Szept: uruchamia grę w Pythonie (pygame) w telefonie — Pyodide (Python w WebAssembly) + pygame-ce.
// Pliki Pythona i gry podaje sama aplikacja (adres /py/… i /game/…); gra nie ma dostępu do internetu.
"use strict";

const bridge = window.SzeptGame || {
  // poza aplikacją (np. test w przeglądarce)
  score(v, label) { console.log("SCORE", v, label); },
  ready(w, h) { console.log("READY", w, h); },
  exit() { console.log("EXIT"); },
  error(t) { console.log("ERROR", t); },
};
const params = new URLSearchParams(location.search);
const canvas = document.getElementById("canvas");
const msg = document.getElementById("msg");
const statusEl = document.getElementById("status");

function status(t) { statusEl.textContent = t; }
function hideMsg() { msg.style.display = "none"; }

function showError(text) {
  msg.style.display = "flex";
  msg.innerHTML = "";
  const h = document.createElement("div");
  h.textContent = "Gra zakończyła się błędem";
  h.style.fontWeight = "bold";
  const pre = document.createElement("pre");
  pre.textContent = text;
  const b = document.createElement("button");
  b.textContent = "Zamknij";
  b.onclick = () => bridge.exit();
  msg.append(h, pre, b);
  try { bridge.error(String(text).slice(0, 4000)); } catch (e) {}
}

// --- obraz dopasowany do ekranu (zachowane proporcje) ---
let lastSize = "";
function fit() {
  const w = canvas.width, h = canvas.height;
  if (!w || !h) return;
  const s = Math.min(window.innerWidth / w, window.innerHeight / h);
  canvas.style.width = Math.floor(w * s) + "px";
  canvas.style.height = Math.floor(h * s) + "px";
  const key = w + "x" + h;
  // 300×150 to domyślny rozmiar płótna, zanim gra ustawi własny — wtedy jeszcze nie obracamy ekranu
  if (key !== lastSize && key !== "300x150") { lastSize = key; try { bridge.ready(w, h); } catch (e) {} }
}
window.addEventListener("resize", fit);
setInterval(fit, 400);

// --- tempo klatek: Python czeka na następną klatkę ekranu (requestAnimationFrame) ---
let lastFrame = 0;
window.szeptMinFrame = 0;
window.szeptFrame = function (minMs) {
  return new Promise((resolve) => {
    const step = (t) => {
      if (t - lastFrame >= (minMs || 0) - 2) { lastFrame = t; resolve(); }
      else requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  });
};
window.szeptScore = (v, label) => { try { bridge.score(Number(v), String(label || "")); } catch (e) {} };
window.szeptExit = () => { try { bridge.exit(); } catch (e) {} };

// gra nie widzi długiego przytrzymania, zaznaczania i przewijania strony
for (const ev of ["touchstart", "touchmove", "touchend"]) {
  document.addEventListener(ev, (e) => { if (e.target === canvas) e.preventDefault(); }, { passive: false });
}

async function main() {
  const id = (params.get("id") || "gra").replace(/[^A-Za-z0-9_-]/g, "_");
  const kind = params.get("kind") === "zip" ? "zip" : "py";
  const args = (params.get("args") || "").split(" ").filter((a) => a);
  let py;
  try {
    if (typeof WebAssembly === "undefined" || typeof BigInt === "undefined") {
      throw new Error("Systemowa przeglądarka (Android System WebView) jest za stara do gier w Pythonie — zaktualizuj ją w Sklepie Play.");
    }
    status("Uruchamiam Pythona…");
    py = await loadPyodide({ indexURL: "/py/", fullStdLib: false, stdout: (t) => console.log(t), stderr: (t) => console.warn(t) });
    status("Ładuję pygame…");
    await py.loadPackage("pygame-ce", { messageCallback: () => {}, errorCallback: (t) => console.warn(t) });
    status("Ładuję grę…");
    // zapisy gier (rekordy itp.) w pamięci WebView — zostają po zamknięciu
    const home = "/home/pyodide";
    try {
      py.FS.mkdirTree(home);
      py.FS.mount(py.FS.filesystems.IDBFS, {}, home);
      await new Promise((r) => py.FS.syncfs(true, () => r()));
    } catch (e) { console.warn("IDBFS", e); }
    const save = () => { try { py.FS.syncfs(false, () => {}); } catch (e) {} };
    setInterval(save, 4000);
    document.addEventListener("visibilitychange", save);
    window.szeptSave = save;

    const res = await fetch("/game/" + (kind === "zip" ? "game.zip" : "main.py"));
    if (!res.ok) throw new Error("Brak pliku gry (" + res.status + ")");
    py.FS.mkdirTree("/game");
    if (kind === "zip") py.unpackArchive(await res.arrayBuffer(), "zip", { extractDir: "/game" });
    else py.FS.writeFile("/game/main.py", new Uint8Array(await res.arrayBuffer()));

    py.canvas.setCanvas2D(canvas);
    const boot = await (await fetch("boot.py")).text();
    py.globals.set("SZEPT_GAME_ID", id);
    py.globals.set("SZEPT_ARGS", py.toPy(args));
    hideMsg();
    canvas.focus();
    await py.runPythonAsync(boot);
    save();
    bridge.exit();
  } catch (e) {
    const text = String(e && e.message ? e.message : e);
    console.error(text);
    showError(text);
  }
}

main();
