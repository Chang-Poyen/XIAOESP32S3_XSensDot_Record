from __future__ import annotations

import asyncio
import io
import shutil
import subprocess
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Dict, Optional
from zipfile import ZIP_DEFLATED, ZipFile

from aiohttp import WSMsgType, web


BASE_DIR = Path(__file__).resolve().parent
RECORDINGS_DIR = BASE_DIR / "recordings"
WS_PORT = 9002


INDEX_HTML = """<!doctype html>
<html lang="zh-Hans">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Eva Python WebUI</title>
  <style>
    :root {
      --bg: #f3f4ef;
      --card: #fffdf7;
      --line: #d4d0c4;
      --ink: #21201c;
      --muted: #6e695d;
      --accent: #0f766e;
      --accent-2: #b45309;
      --danger: #b91c1c;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font-family: "Helvetica Neue", "PingFang SC", "Noto Sans SC", sans-serif;
      color: var(--ink);
      background:
        radial-gradient(circle at top left, rgba(15,118,110,.14), transparent 30%),
        radial-gradient(circle at top right, rgba(180,83,9,.12), transparent 28%),
        var(--bg);
    }
    main {
      max-width: 1180px;
      margin: 0 auto;
      padding: 24px;
    }
    h1 {
      margin: 0 0 16px;
      font-size: 32px;
      letter-spacing: .02em;
    }
    .grid {
      display: grid;
      grid-template-columns: 1.2fr 1fr;
      gap: 18px;
    }
    .card {
      background: var(--card);
      border: 1px solid var(--line);
      border-radius: 18px;
      padding: 18px;
      box-shadow: 0 10px 30px rgba(0,0,0,.05);
    }
    .controls {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 12px;
      margin-bottom: 14px;
    }
    label {
      display: block;
      font-size: 13px;
      color: var(--muted);
      margin-bottom: 6px;
    }
    input, select {
      width: 100%;
      padding: 10px 12px;
      border-radius: 10px;
      border: 1px solid var(--line);
      background: white;
      font-size: 14px;
    }
    button {
      border: 0;
      border-radius: 999px;
      padding: 11px 16px;
      font-size: 14px;
      font-weight: 600;
      cursor: pointer;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      margin-bottom: 14px;
    }
    .primary { background: var(--accent); color: white; }
    .secondary { background: #e7e2d6; color: var(--ink); }
    .danger { background: var(--danger); color: white; }
    .status {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 12px;
    }
    .metric {
      padding: 14px;
      border-radius: 14px;
      background: #faf7ee;
      border: 1px solid var(--line);
    }
    .metric strong {
      display: block;
      font-size: 24px;
      margin-top: 6px;
    }
    .streams {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 16px;
    }
    .stream-frame {
      aspect-ratio: 4 / 3;
      width: 100%;
      border-radius: 16px;
      border: 1px solid var(--line);
      object-fit: cover;
      background: #d8d2c2;
    }
    .subtle {
      color: var(--muted);
      font-size: 13px;
    }
    .session {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 12px 0;
      border-bottom: 1px solid #ebe6d8;
    }
    .session:last-child {
      border-bottom: 0;
      padding-bottom: 0;
    }
    .mono {
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 12px;
      word-break: break-all;
    }
    @media (max-width: 900px) {
      .grid, .streams, .controls, .status {
        grid-template-columns: 1fr;
      }
    }
  </style>
</head>
<body>
  <main>
    <h1>Eva Python WebUI</h1>
    <div class="grid">
      <section class="card">
        <div class="controls">
          <div>
            <label for="framesize">Frame Size</label>
            <select id="framesize">
              <option value="VGA">VGA</option>
              <option value="SVGA" selected>SVGA</option>
              <option value="XGA">XGA</option>
              <option value="SXGA">SXGA</option>
              <option value="UXGA">UXGA</option>
            </select>
          </div>
          <div>
            <label for="quality">JPEG Quality</label>
            <input id="quality" type="number" min="5" max="40" value="12">
          </div>
          <div>
            <label for="fps">FPS</label>
            <input id="fps" type="number" min="5" max="60" value="15">
          </div>
          <div>
            <label for="session_name">Session Name</label>
            <input id="session_name" type="text" placeholder="optional">
          </div>
        </div>
        <div class="actions">
          <button class="primary" id="startBtn">开始录制</button>
          <button class="danger" id="stopBtn">停止录制</button>
          <button class="secondary" id="refreshBtn">刷新状态</button>
        </div>
        <div class="status">
          <div class="metric">
            状态
            <strong id="recordingState">Idle</strong>
            <div class="subtle" id="statusText">等待启动</div>
          </div>
          <div class="metric">
            左路 / 右路
            <strong><span id="countL">0</span> / <span id="countR">0</span></strong>
            <div class="subtle" id="connections">L: false | R: false</div>
          </div>
          <div class="metric">
            当前会话
            <strong id="sessionName">-</strong>
            <div class="subtle mono" id="sessionPath">-</div>
          </div>
        </div>
      </section>
      <section class="card">
        <div class="streams">
          <div>
            <h3>L 预览</h3>
            <img class="stream-frame" id="previewL" alt="L preview">
          </div>
          <div>
            <h3>R 预览</h3>
            <img class="stream-frame" id="previewR" alt="R preview">
          </div>
        </div>
      </section>
    </div>
    <section class="card" style="margin-top: 18px;">
      <h3 style="margin-top: 0;">历史会话</h3>
      <div id="sessions"></div>
    </section>
  </main>
  <script>
    const previewL = document.getElementById("previewL");
    const previewR = document.getElementById("previewR");
    const startBtn = document.getElementById("startBtn");
    const stopBtn = document.getElementById("stopBtn");
    const refreshBtn = document.getElementById("refreshBtn");

    async function postJson(url, body) {
      const res = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body || {})
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `HTTP ${res.status}`);
      }
      return res.json();
    }

    function renderState(state) {
      document.getElementById("recordingState").textContent = state.recording ? "Recording" : "Idle";
      document.getElementById("statusText").textContent = state.status;
      document.getElementById("countL").textContent = state.counts.L;
      document.getElementById("countR").textContent = state.counts.R;
      document.getElementById("connections").textContent = `L: ${state.connected.L} | R: ${state.connected.R}`;
      document.getElementById("sessionName").textContent = state.session_name || "-";
      document.getElementById("sessionPath").textContent = state.session_path || "-";
      previewL.src = `/preview/L.jpg?t=${Date.now()}`;
      previewR.src = `/preview/R.jpg?t=${Date.now()}`;
    }

    function renderSessions(items) {
      const root = document.getElementById("sessions");
      if (!items.length) {
        root.innerHTML = '<div class="subtle">暂无会话</div>';
        return;
      }
      root.innerHTML = items.map(item => `
        <div class="session">
          <div>
            <div><strong>${item.name}</strong></div>
            <div class="subtle">L: ${item.counts.L} | R: ${item.counts.R}</div>
            <div class="subtle mono">${item.path}</div>
          </div>
          <div style="display:flex; gap:8px; flex-wrap:wrap;">
            <button class="secondary" onclick="exportSession('${item.name}')">导出 MP4</button>
            <a href="/api/sessions/${encodeURIComponent(item.name)}/download">
              <button class="secondary">下载 ZIP</button>
            </a>
          </div>
        </div>
      `).join("");
    }

    async function refreshAll() {
      const [stateRes, sessionsRes] = await Promise.all([
        fetch("/api/state"),
        fetch("/api/sessions")
      ]);
      renderState(await stateRes.json());
      renderSessions(await sessionsRes.json());
    }

    startBtn.addEventListener("click", async () => {
      try {
        await postJson("/api/start", {
          framesize: document.getElementById("framesize").value,
          quality: parseInt(document.getElementById("quality").value, 10),
          fps: parseInt(document.getElementById("fps").value, 10),
          session_name: document.getElementById("session_name").value.trim()
        });
        await refreshAll();
      } catch (err) {
        alert(`启动失败: ${err.message}`);
      }
    });

    stopBtn.addEventListener("click", async () => {
      try {
        await postJson("/api/stop", {});
        await refreshAll();
      } catch (err) {
        alert(`停止失败: ${err.message}`);
      }
    });

    refreshBtn.addEventListener("click", refreshAll);

    async function exportSession(name) {
      try {
        await postJson(`/api/sessions/${encodeURIComponent(name)}/export`, { fps: 15 });
        await refreshAll();
        alert(`导出完成: ${name}`);
      } catch (err) {
        alert(`导出失败: ${err.message}`);
      }
    }

    refreshAll();
    setInterval(refreshAll, 1200);
  </script>
</body>
</html>
"""


@dataclass
class SessionState:
    name: str
    path: Path
    counts: Dict[str, int] = field(default_factory=lambda: {"L": 0, "R": 0})


@dataclass
class AppState:
    recordings_dir: Path
    connected: Dict[str, bool] = field(default_factory=lambda: {"L": False, "R": False})
    latest_frame: Dict[str, bytes] = field(default_factory=lambda: {"L": b"", "R": b""})
    latest_frame_type: Dict[str, str] = field(default_factory=lambda: {"L": "image/jpeg", "R": "image/jpeg"})
    clients: Dict[str, Optional[web.WebSocketResponse]] = field(default_factory=lambda: {"L": None, "R": None})
    recording: bool = False
    status: str = "Idle"
    session: Optional[SessionState] = None
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    def to_dict(self) -> dict:
        counts = self.session.counts if self.session else {"L": 0, "R": 0}
        return {
            "recording": self.recording,
            "status": self.status,
            "counts": counts,
            "connected": self.connected,
            "session_name": self.session.name if self.session else None,
            "session_path": str(self.session.path) if self.session else None,
        }


def make_session_name(custom: str | None) -> str:
    if custom:
        safe = "".join(ch if ch.isalnum() or ch in "-_." else "_" for ch in custom.strip())
        if safe:
            return safe
    return datetime.now().strftime("rec_%Y%m%d_%H%M%S")


async def index(_: web.Request) -> web.Response:
    return web.Response(text=INDEX_HTML, content_type="text/html")


async def get_state(request: web.Request) -> web.Response:
    state: AppState = request.app["state"]
    return web.json_response(state.to_dict())


async def list_sessions(request: web.Request) -> web.Response:
    state: AppState = request.app["state"]
    items = []
    for session_dir in sorted(
        (path for path in state.recordings_dir.iterdir() if path.is_dir()),
        reverse=True,
    ):
        left_count = len(list((session_dir / "L").glob("*.jpg")))
        right_count = len(list((session_dir / "R").glob("*.jpg")))
        items.append({
            "name": session_dir.name,
            "path": str(session_dir),
            "counts": {"L": left_count, "R": right_count},
        })
    return web.json_response(items)


async def download_session(request: web.Request) -> web.StreamResponse:
    state: AppState = request.app["state"]
    name = request.match_info["name"]
    session_dir = state.recordings_dir / name
    if not session_dir.exists() or not session_dir.is_dir():
        raise web.HTTPNotFound(text="session not found")

    buffer = io.BytesIO()
    with ZipFile(buffer, "w", compression=ZIP_DEFLATED) as zf:
        for file in sorted(session_dir.rglob("*")):
            if file.is_file():
                zf.write(file, arcname=file.relative_to(session_dir))
    buffer.seek(0)
    return web.Response(
        body=buffer.read(),
        headers={"Content-Disposition": f'attachment; filename="{name}.zip"'},
        content_type="application/zip",
    )


def export_mp4_from_jpegs(frame_dir: Path, out_file: Path, fps: int) -> bool:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        return False

    frames = sorted(frame_dir.glob("*.jpg"))
    if not frames:
        return False

    list_file = frame_dir / "_ffmpeg_inputs.txt"
    list_file.write_text(
        "".join(f"file '{frame.resolve().as_posix()}'\n" for frame in frames),
        encoding="utf-8",
    )
    try:
        if out_file.exists():
            out_file.unlink()
        cmd = [
            ffmpeg,
            "-y",
            "-r",
            str(fps),
            "-f",
            "concat",
            "-safe",
            "0",
            "-i",
            str(list_file),
            "-vf",
            "fps={}".format(fps),
            "-pix_fmt",
            "yuv420p",
            "-c:v",
            "libx264",
            str(out_file),
        ]
        result = subprocess.run(cmd, capture_output=True, text=True)
        return result.returncode == 0 and out_file.exists()
    finally:
        list_file.unlink(missing_ok=True)


async def export_session(request: web.Request) -> web.Response:
    state: AppState = request.app["state"]
    name = request.match_info["name"]
    session_dir = state.recordings_dir / name
    if not session_dir.exists() or not session_dir.is_dir():
        raise web.HTTPNotFound(text="session not found")

    payload = await request.json()
    fps = int(payload.get("fps", 15))

    def run_export() -> dict:
        ok_l = export_mp4_from_jpegs(session_dir / "L", session_dir / "output_L.mp4", fps)
        ok_r = export_mp4_from_jpegs(session_dir / "R", session_dir / "output_R.mp4", fps)
        return {"L": ok_l, "R": ok_r}

    result = await asyncio.to_thread(run_export)
    state.status = f"導出完成 L:{result['L']} R:{result['R']}"
    return web.json_response({"ok": True, "result": result})


async def preview(request: web.Request) -> web.Response:
    state: AppState = request.app["state"]
    label = request.match_info["label"].upper()
    if label not in ("L", "R"):
        raise web.HTTPNotFound()
    data = state.latest_frame[label]
    if not data:
        raise web.HTTPNotFound(text="no frame")
    return web.Response(body=data, content_type=state.latest_frame_type[label])


async def broadcast(state: AppState, message: str) -> None:
    for label, ws in state.clients.items():
        if ws is None or ws.closed:
            continue
        try:
            await ws.send_str(message)
        except Exception:
            state.connected[label] = False
            state.clients[label] = None


async def start_recording(request: web.Request) -> web.Response:
    state: AppState = request.app["state"]
    payload = await request.json()
    framesize = str(payload.get("framesize", "SVGA")).upper()
    quality = int(payload.get("quality", 12))
    fps = int(payload.get("fps", 15))
    session_name = make_session_name(payload.get("session_name"))

    async with state.lock:
        if state.recording:
            return web.json_response({"ok": True, "state": state.to_dict()})
        session_path = state.recordings_dir / session_name
        (session_path / "L").mkdir(parents=True, exist_ok=True)
        (session_path / "R").mkdir(parents=True, exist_ok=True)
        state.session = SessionState(name=session_name, path=session_path)
        state.recording = True
        state.status = f"錄製中… {session_path}"

    await broadcast(state, f"SET:FRAMESIZE={framesize}")
    await broadcast(state, f"SET:QUALITY={quality}")
    await broadcast(state, f"SET:FPS={fps}")
    await broadcast(state, "START")
    return web.json_response({"ok": True, "state": state.to_dict()})


async def stop_recording(request: web.Request) -> web.Response:
    state: AppState = request.app["state"]
    async with state.lock:
        if not state.recording:
            return web.json_response({"ok": True, "state": state.to_dict()})
        state.recording = False
        if state.session is not None:
            state.status = (
                f"錄製結束，L:{state.session.counts['L']} "
                f"R:{state.session.counts['R']}，路徑: {state.session.path}"
            )
    await broadcast(state, "STOP")
    return web.json_response({"ok": True, "state": state.to_dict()})


def save_frame(session: SessionState, label: str, payload: bytes) -> int:
    timestamp_ns = time.time_ns()
    file_path = session.path / label / f"{label}_{timestamp_ns}.jpg"
    file_path.write_bytes(payload)
    session.counts[label] += 1
    return session.counts[label]


async def ws_camera(request: web.Request) -> web.WebSocketResponse:
    state: AppState = request.app["state"]
    label = request.match_info["label"].upper()
    if label not in ("L", "R"):
        raise web.HTTPNotFound()

    ws = web.WebSocketResponse(max_msg_size=16 * 1024 * 1024)
    await ws.prepare(request)

    state.clients[label] = ws
    state.connected[label] = True
    state.status = f"{label} connected"

    try:
        async for msg in ws:
            if msg.type == WSMsgType.BINARY:
                payload = msg.data
                state.latest_frame[label] = payload
                state.latest_frame_type[label] = "image/jpeg"
                if state.recording and state.session is not None:
                    session = state.session
                    try:
                        await asyncio.to_thread(save_frame, session, label, payload)
                    except Exception as exc:
                        state.status = f"寫檔失敗: {exc}"
            elif msg.type == WSMsgType.TEXT:
                state.status = f"{label} text: {msg.data}"
            elif msg.type == WSMsgType.ERROR:
                state.status = f"{label} socket error: {ws.exception()}"
    finally:
        if state.clients[label] is ws:
            state.clients[label] = None
        state.connected[label] = False
        state.status = f"{label} disconnected"

    return ws


def build_app() -> web.Application:
    RECORDINGS_DIR.mkdir(parents=True, exist_ok=True)
    app = web.Application()
    app["state"] = AppState(recordings_dir=RECORDINGS_DIR)
    app.router.add_get("/", index)
    app.router.add_get("/api/state", get_state)
    app.router.add_get("/api/sessions", list_sessions)
    app.router.add_get("/api/sessions/{name}/download", download_session)
    app.router.add_post("/api/sessions/{name}/export", export_session)
    app.router.add_post("/api/start", start_recording)
    app.router.add_post("/api/stop", stop_recording)
    app.router.add_get("/preview/{label}.jpg", preview)
    app.router.add_get("/ws/camera{label}", ws_camera)
    return app


if __name__ == "__main__":
    web.run_app(build_app(), host="0.0.0.0", port=WS_PORT)
