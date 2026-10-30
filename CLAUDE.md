# CLAUDE.md

本文件为 Claude Code 在此仓库工作时的指导。

## 项目概述

一款 Android 视频格式转换 App：用户选择本地视频，指定目标容器/编码/分辨率/码率，App 转换后保存到相册。个人自用 / 开源项目，MVP 只做核心格式转换。

- 技术栈：Kotlin + Jetpack Compose（Material 3），单 Activity
- minSdk 26，targetSdk 36，compileSdk 37（Compose 1.12 / Lifecycle 2.11 要求）
- 构建：Gradle 9.6.0 + AGP 9.5.0；AGP 9 内置 Kotlin，不要再应用 `kotlin-android` 插件
- 授权：开源。可以使用 GPL 版 FFmpeg（含 x264/x265/LAME）

## 转换引擎架构（最重要）

采用双引擎，路由优先级严格如下：

1. 目标容器兼容源流且用户未改编码/分辨率/码率 → **remux 流拷贝**（无损、秒出）
2. 目标编码 ∈ {H.264, H.265} 且容器 ∈ {MP4, WebM, TS} → **Media3 Transformer 硬件转码**
3. 其余（MKV/MOV/AVI/FLV/TS 输出、AC3/DTS/MPEG-2/RMVB 等）→ **FFmpeg 兜底**
4. 硬件编码器初始化失败 → 自动回退 FFmpeg 软件编码，并在 UI 提示

统一接口：

```kotlin
interface ConversionEngine {
    suspend fun probe(uri: Uri): VideoInfo
    suspend fun convert(spec: ConversionSpec, onProgress: (Progress) -> Unit): ConversionResult
    fun cancel()
}
```

- Media3 Transformer 版本：1.11.1（`media3-transformer`、`media3-effect`、`media3-common`、`media3-exoplayer`）
- FFmpeg：自编译 GPL 版本，最小 JNI 封装（命令执行、日志、进度、取消），构建脚本放 `scripts/`
- ffmpeg-kit 已退役，不要新增对其的依赖；备选社区 AAR 仅为应急方案，使用前需确认

## 目录结构

```
app/                 # 单模块起步
  src/main/java/.../ui/         Compose 页面与导航
  src/main/java/.../domain/     VideoInfo、ConversionSpec、JobState、格式矩阵
  src/main/java/.../engine/     ConversionEngine、Media3Engine、FfmpegEngine、EngineRouter
  src/main/java/.../data/       SAF/MediaStore、转换历史
  src/main/java/.../service/    前台服务、WorkManager Worker
ffmpeg/              # 自编译 .so + JNI 源码/脚本
scripts/             # FFmpeg 构建脚本、测试素材生成脚本
```

MVP 不拆多模块；FFmpeg JNI 明显膨胀后再拆 `:ffmpeg`。

## 常用命令

```bash
./gradlew assembleDebug          # 构建
./gradlew installDebug           # 安装到已连接设备
./gradlew test                   # 单元测试
./gradlew connectedAndroidTest   # 真机/模拟器仪器测试
./gradlew lint                   # 静态检查
```

- adb：`C:/software/platform-tools/adb.exe`（已连接真机：Android 15 / API 35 / arm64-v8a）
- 桌面 ffmpeg（生成测试素材）：`C:/software/ffmpeg-9.0.1-full_build/bin/ffmpeg.exe`
- Dolby Vision 素材依赖 `tools/dovi_tool/dovi_tool.exe`（不纳入仓库）；缺失时脚本自动跳过 DV 样本
- SDK、NDK 路径通过 `local.properties` 或环境变量 `ANDROID_HOME` 提供，不提交到仓库
- 本机 SDK：`%LOCALAPPDATA%\Android\Sdk`（platform 29/36/37、NDK 28.2/30.0、CMake 4.1.2 已装）
- 本机无独立 JDK，命令行构建时设 `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`（JDK 25）
- SDK 管理使用 `android sdk`（`sdkmanager` 已弃用且本机 wrapper 有权限问题）

## 硬性约束

- FFmpeg 构建必须使用 NDK r28+，并带 `-Wl,-z,max-page-size=16384`（16KB 页对齐，Android 15+ 要求）
- `content://` 不能直接交给 FFmpeg：用 SAF 文件描述符（`/proc/self/fd/N`）或临时拷贝，转换结束清理临时文件
- 输出写入 MediaStore 时必须先 `IS_PENDING=1`，完成后再置 0
- 所有转换必须支持取消，取消后清理临时文件与通知
- 长任务必须运行在 `mediaProcessing` 类型的前台服务中，并持有 wake lock
- 禁止把整个视频文件读入内存；全程流式处理
- 转换前查询 `MediaCodecList` 能力，失败自动回退软件编码
- 新增格式/编码时必须同步更新格式矩阵与 `scripts/` 下的测试素材
- Dolby Vision 封装：FFmpeg 输出必须用 `.mp4` 扩展（`.mov` 会丢 `dvvC` 配置盒），并加 `-bsf:v dovi_rpu -strict unofficial`；`-shortest` 会导致音轨被丢弃

## 代码风格

- 纯 Compose + Material 3，不使用 XML 布局
- 状态管理：ViewModel + StateFlow（或 Compose State），UI 无业务逻辑
- 依赖注入：MVP 用手工 DI，不引入 Hilt
- 注释只写「为什么」，不写「做了什么」
- 依赖版本统一放在 `gradle/libs.versions.toml`
- 新增第三方依赖前先确认维护状态与许可证（GPL 可用，但闭源分发场景不适用）

## 功能范围

MVP：容器/编码/分辨率/码率/帧率转换、remux、任务队列、进度与取消、历史记录、输出目录设置。

暂不做：按目标体积压缩、音轨提取、裁剪/合并、GIF、字幕、批量转换、云端功能。

## 路线图状态

- [x] M0 环境：JDK 25、Android Studio、SDK 37、NDK r28、CMake
- [x] M1 骨架：Compose 导航、文件选择、元数据探测、配置页
- [ ] M2 快路径：Media3Engine、前台服务、进度/取消、MediaStore 保存
- [ ] M3 全格式：自编译 FFmpeg、JNI 封装、冷门容器与编解码
- [ ] M4 任务体系：队列、历史、重试、设置项
- [ ] M5 收尾：真机格式矩阵回归、性能、打包、README
