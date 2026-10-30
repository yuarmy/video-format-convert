# 视频格式转换

一款 Android 本地视频格式转换 App：选择视频，指定目标容器 / 编码 / 分辨率 / 码率，转换完成后保存到相册。

个人自用项目，MVP 只做核心格式转换，不做体积压缩、剪辑、字幕或云端功能。

## 当前进度

- [x] 选择本地视频（SAF），读取时长、分辨率、编码等元数据
- [x] 配置目标容器、视频/音频编码、分辨率、质量
- [x] 兼容时直接流拷贝（无损、秒出）；否则走硬件转码
- [x] 前台服务 + 通知进度 + 随时取消
- [x] 输出写入相册 `Movies/视频转换`（MediaStore，先 `IS_PENDING` 后转正）
- [x] 扫描目录，列出系统解码器打不开的视频及原因，并为其选择输出格式
- [ ] 自编译 FFmpeg 兜底：MKV / MOV / AVI / FLV / TS 输出、冷门编码解码（M3.4）
- [ ] 任务队列、历史记录、重试、设置项（M4）

## 格式支持

| 输出容器 | 状态 | 可用编码（矩阵） |
| --- | --- | --- |
| MP4 | 可用（H.264 / H.265 + AAC） | H.264、H.265、AV1\* |
| MKV | 需要 FFmpeg | H.264、H.265、VP9、AV1 |
| WebM | 需要 FFmpeg | VP8、VP9、AV1 |
| MOV | 需要 FFmpeg | H.264、H.265 |
| AVI | 需要 FFmpeg | MPEG-4 |
| FLV | 需要 FFmpeg | H.264 |
| TS | 需要 FFmpeg | H.264、H.265、MPEG-2 |

\* 矩阵中列出但快路径暂不支持，接入 FFmpeg 后放开。

转换方式按以下优先级自动选择：

1. 目标与源编码、音频兼容且未要求缩放 → **流拷贝**（不重新编码）
2. 目标为 MP4 且编码为 H.264 / H.265 → **Media3 硬件转码**（编码器不可用时自动回退软件编码）
3. 其余 → **FFmpeg 兜底**（M3.4 接入中，当前提示暂不可用）

## 工作原理

- 快路径：AndroidX Media3 Transformer，负责流拷贝与硬件编解码。
- 兜底路径：自编译 GPL 版 FFmpeg（含 x264 / x265 / LAME），通过最小 JNI 封装调用。
- 输出先写应用缓存，再流式写入 MediaStore，完成后清理临时文件。

详细设计（分层、数据模型、路由规则、取消与存储策略、已知限制）见 [docs/design.md](docs/design.md)。

## 环境要求

- JDK 17 及以上（本项目在 JDK 25 上开发）
- Android SDK：compileSdk 37、targetSdk 36、minSdk 26
- NDK r28+ 与 CMake（仅在构建 FFmpeg 时需要）
- 桌面 FFmpeg（仅生成测试素材时需要）

SDK 路径放在 `local.properties` 或环境变量 `ANDROID_HOME`，不要提交到仓库。

## 构建与运行

```bash
# Windows 下若未配置独立 JDK，可用 Android Studio 自带 JBR
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"

./gradlew assembleDebug          # 构建
./gradlew installDebug           # 安装到已连接设备
./gradlew test                   # 单元测试
./gradlew connectedAndroidTest   # 真机/模拟器仪器测试
./gradlew lint                   # 静态检查
```

## 测试素材

```bash
# 生成覆盖格式矩阵的样本到 test-media/（已 gitignore）
scripts/generate-test-media.sh

# 指定输出目录、强制覆盖、指定 ffmpeg 路径
scripts/generate-test-media.sh /tmp/media
FORCE=1 scripts/generate-test-media.sh
FFMPEG=/path/to/ffmpeg scripts/generate-test-media.sh
```

Dolby Vision 样本依赖 [dovi_tool](https://github.com/quietvoid/dovi_tool/releases)，放到 `tools/dovi_tool/dovi_tool.exe`；缺失时脚本自动跳过。

## 项目结构

```
app/          单模块应用（ui / domain / engine / data / service）
ffmpeg/       自编译 .so 与 JNI 源码（M3.4）
scripts/      FFmpeg 构建与测试素材生成脚本
docs/         架构设计文档
```

## 路线图

- [x] M0 环境：JDK、Android Studio、SDK 37、NDK r28、CMake
- [x] M1 骨架：Compose 导航、文件选择、元数据探测、配置页
- [x] M2 快路径：Media3Engine、前台服务、进度/取消、MediaStore 保存
- [ ] M3 全格式：自编译 FFmpeg、JNI 封装、冷门容器与编解码
  - [x] M3.1 系统解码扫描
  - [x] M3.2 失败清单
  - [x] M3.3 失败文件的格式选择
  - [ ] M3.4 FFmpeg 构建与 JNI 封装
- [ ] M4 任务体系：队列、历史、重试、设置项
- [ ] M5 收尾：真机格式矩阵回归、性能、打包

## 许可证

尚未确定。由于计划集成 GPL 版 FFmpeg（含 x264 / x265 / LAME），对外分发二进制时需要遵守 GPL，最终许可证需与 GPL 兼容。
