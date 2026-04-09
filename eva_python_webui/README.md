# Eva Python WebUI

一个简化版 Python WebUI，只保留双路 WebSocket 收图、录制、存储与基础预览。

## 功能

- 提供 `ws://<host>:9002/ws/cameraL`
- 提供 `ws://<host>:9002/ws/cameraR`
- Web 页面控制开始/停止录制
- 录制时按会话保存 JPG：
  - `recordings/rec_<timestamp>/L/*.jpg`
  - `recordings/rec_<timestamp>/R/*.jpg`
- 页面展示 L/R 最新预览
- 页面列出历史会话，并支持下载 ZIP

## 安装

```bash
cd eva_python_webui
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## 运行

```bash
python app.py
```

默认监听：

- Web UI: `http://0.0.0.0:9002/`
- WebSocket:
  - `/ws/cameraL`
  - `/ws/cameraR`

## ESP32 侧

把固件里的：

- `SERVER_HOST`
- `SERVER_PORT`
- `CAM_WS_PATH`

分别指向这台机器和对应路径即可。
