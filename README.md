# XIAOESP32S3_XSensDot_Record
## 这是什么?
借助XIAOESP32S3平台和XSensDot来记录动作影像和IMU传感器数据,实时回传至移动端.
## 平台需求
XIAO-ESP32S3 * 2(因为是双路的代码)  
[Movella DOT](https://www.xsens.com/wearables/xsens-dot)  
Android 8.1+（API 27+）
## 使用方法
本栏目将讲述使用方法.  
请使用`git clone`拉取本项目
### 测试文本编写
测试文本存于`./Sample_Data/test.json`
````json
[
  { "text": "第一句測試指令", "delay": 1000 },
  { "text": "第二句測試指令", "delay": 1500 }
]
````
其中`text`内文本会通过系统内建TTS播送,`delay`为延时(ms)
> [!NOTE]
> 实验性功能,未经充分测试
### ESP32烧录
源码文件存于`./compile`内,使用IDE开启`compile.ino`编辑其中`WiFi / Server`内容  
> [!TIP]
> 本项目为双路ESP32Cam录制,如果只要单路录制只需保留CameraL/R其中一个地址即可

更改后即可烧录到esp32  
### Android APP操作
- 启动WebSocket服务器
- 导入测试JSON
- 设置DoT个数(1-8)
- 连接DoT
- 录制/停止
- 导出实验数据
### 实验流程
ESP32上电---APP上启动WS---导入测试json---连接dot---开始录制---停止并保存---导出
## APP/ESP32通信状态说明
本项目中“状态”分为两类:
- APP界面状态（`Eva` 内 `status/dotStatus` 文本）
- ESP32串口日志（`compile.ino` 中 `Serial.println/printf` 输出）

### 1. 正常联机时的状态顺序
1. APP点击`启动 WS`后，状态显示:
   `WS listening on 9002/ws/cameraL & /ws/cameraR`
2. ESP32上电后连接Wi-Fi，串口显示:
   `[WiFi] connecting... OK <ip>`
3. ESP32尝试连接APP的WS服务，串口显示:
   `[WS-CAM] connected`
   随后进入事件回调显示:
   `[WS-CAM] open`
4. 左右相机接入后，APP状态会出现:
   `L connected` / `R connected`
5. 开始录制后，APP状态显示:
   `錄製中… <session_path>`
6. 停止录制后，APP状态显示:
   `錄製結束，L:x R:y，路徑: <session_path>`

### 2. APP侧常见状态含义
- `Idle`: 初始状态，尚未启动WS或无新事件
- `WS listening ...`: 手机端WS服务已启动，等待ESP32连接
- `L connected / R connected`: 对应路径（`/ws/cameraL`、`/ws/cameraR`）已有ESP32接入
- `L disconnected / R disconnected`: 对应相机流断开
- `寫檔失敗: ...`: 接收到图像但保存失败（通常是存储或路径问题）
- `DOT: 掃描中... / DOT connected / DOT init done`: Movella DOT连接流程状态

### 3. ESP32串口常见日志含义
- `[WS-CAM] retry in 1s...`: 尚未连接到APP WS服务（端口/IP/热点不通）
- `[WS-CAM] connected`: TCP/WS连接建立成功
- `[WS-CAM] open`: WebSocket已进入可发送状态（`cam_ws_ready=true`）
- `[WS-CAM] closed (...)`: 连接关闭，同时打印发送统计
- `[CAM-CAP] ...`: 相机采集统计（采集数、队列、失败数）
- `[CAM-SEND] ...`: 图像发送统计（发送数、丢帧、失败）

### 4. 指令与当前行为说明（重要）
- APP在点击`開始錄製/停止並保存`时会向所有WS客户端发送文本:
  `START` / `STOP`
- 当前`compile.ino`并未对`START/STOP`做专门处理；ESP32视频发送以`WS是否已连通`为准（`cam_ws_ready`）
- ESP32当前已实现并处理的文本指令为:
  `SET:FRAMESIZE=...`、`SET:QUALITY=...`、`SET:FPS=...`、`SNAP:HQ`

### 5. 快速排查
- APP已显示`WS listening...`但ESP32一直`retry in 1s...`:
  检查`compile.ino`中的`SERVER_HOST/SERVER_PORT/CAM_WS_PATH`是否与APP一致
- 只有L或R连接:
  检查对应ESP32烧录时的`CAM_WS_PATH`（L机用`/ws/cameraL`，R机用`/ws/cameraR`）
- 已连接但无画面增长:
  看ESP32是否出现`[CAM-SEND] ERROR`或长时间`No frame sent`警告
