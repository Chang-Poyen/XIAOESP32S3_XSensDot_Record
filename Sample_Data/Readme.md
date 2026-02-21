# Sample_Data 说明

本目录用于提供测试脚本与导出结果示例。

## 当前目录文件树

```text
Sample_Data/
├── Readme.md
├── test.json
└── 截圖 2026-02-21 上午10.40.24.png
```

## 文件说明

- `test.json`  
  APP「导入测试 JSON」功能使用的示例脚本文件。  
  结构为数组，每项包含:
  - `text`: TTS 要播报的文本
  - `delay`: 下一句前的等待时间（毫秒）

- `截圖 2026-02-21 上午10.40.24.png`  
  录制完成后导出目录的截图示例。截图中展示的会话目录结构如下：

```text
eva_rec_1771641409864/
├── sensors/
│   ├── dot_D4/22/CD/00/A8/2E.csv
│   └── dot_D4/22/CD/00/A7/EA.csv
├── output_R.mp4
└── output_L.mp4
```

其中：
- `sensors/*.csv`: Movella DOT 传感器数据（按设备地址分文件）
- `output_L.mp4`: 左路相机导出视频
- `output_R.mp4`: 右路相机导出视频
