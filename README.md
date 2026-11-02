# 视频格式转换

一款 Android 本地视频格式转换 App：选择视频，指定目标容器 / 编码 / 分辨率 / 码率，转换完成后保存到相册。

个人自用项目，MVP 只做核心格式转换，不做体积压缩、剪辑、字幕或云端功能。

## 当前进度

- [x] 选择本地视频（SAF），读取时长、分辨率、编码等元数据
- [x] 配置目标容器、视频/音频编码、分辨率、质量
- [x] 兼容时直接流拷贝（无损、秒出）；否则走硬件转码
- [x] 前台服务 + 通知进度 + 随时取消
- [x] 输出写入相册 `Movies/视频转换`（MediaStore，先 `IS_PENDING` 后转正），目录名可在设置中修改
- [x] 扫描目录，列出系统解码器打不开的视频及原因，并为其选择输出格式
- [x] 自编译 FFmpeg 兜底：MKV / MOV / AVI / FLV / TS / WebM 输出、冷门编码与失败文件转码（M3.4）
- [x] 应用内开源许可页：GPL-3.0 / Apache-2.0 全文、复制与官网跳转
- [x] 任务队列：串行转换、查看进度、取消当前、移除待处理、清空队列（M4）
- [x] 历史记录：成功 / 失败 / 取消结果持久化，支持再次转换、删除与清空（M4）
- [x] 设置项：输出目录名（M4）
- [x] 真机格式矩阵回归：全部源容器与目标容器/编码组合自动遍历（M5）
- [x] 性能基线：1080p 硬件 / 软件转码耗时与实时倍率记录（M5）
- [x] 发布打包：支持 `keystore.properties` 自动签名（M5）

## 格式支持

| 输出容器 | 状态 | 可用编码（矩阵） |
| --- | --- | --- |
| MP4 | 可用（H.264 / H.265 走硬件，AV1 走 FFmpeg） | H.264、H.265、AV1 |
| MKV | 可用 | H.264、H.265、VP9、AV1 |
| WebM | 可用 | VP8、VP9、AV1 |
| MOV | 可用 | H.264、H.265 |
| AVI | 可用 | MPEG-4 |
| FLV | 可用 | H.264 |
| TS | 可用 | H.264、H.265、MPEG-2 |

转换方式按以下优先级自动选择：

1. 目标与源编码、音频兼容且未要求缩放 → **流拷贝**（不重新编码）
2. 目标为 MP4 且编码为 H.264 / H.265 → **Media3 硬件转码**
3. 其余（其他容器、AV1/VP8/VP9/MPEG 编码、扫描确认不可解码的源）→ **FFmpeg 软件转码**
4. Media3 运行期失败（编码器初始化失败等）→ 自动回退 FFmpeg，并在通知中提示

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

## 持续集成

`.github/workflows/ci.yml` 在 push / PR 时运行两个任务：

- **Lint 与单元测试**：Kotlin 编译、Lint、单元测试，不需要 FFmpeg 原生库，通常几分钟内完成。
- **构建 Debug APK**：先跑上面的检查，再交叉编译 FFmpeg。`ffmpeg/downloads`、`ffmpeg/src`、`ffmpeg/prebuilt`
  按 `scripts/` 下构建脚本的哈希缓存；首次完整构建约 8 分钟，缓存命中后只需几分钟，脚本或版本号变更会自动重编。
  产物以 `app-debug` artifact 形式保留 14 天。

仪器测试（`connectedAndroidTest`）需要真机或模拟器，暂未纳入 CI。

## FFmpeg 原生库

兜底转码使用自编译的 GPL 版 FFmpeg，含 x264 / x265 / LAME / Opus / Vorbis / VP8-9 / SVT-AV1。
源码与产物均不纳入仓库（`ffmpeg/downloads`、`ffmpeg/src`、`ffmpeg/build`、`ffmpeg/prebuilt`、`ffmpeg/toolchain` 已忽略）。

```bash
# 1. 下载并校验源码（SHA-256 已固定），解压到 ffmpeg/src/
scripts/ffmpeg-download.sh

# 2. 交叉编译（默认 arm64-v8a，多 ABI 用空格分隔）
scripts/ffmpeg-build.sh
ABIS="arm64-v8a armeabi-v7a" scripts/ffmpeg-build.sh
```

常用开关（环境变量）：

| 变量 | 说明 |
| --- | --- |
| `ABIS` | 目标 ABI，默认 `arm64-v8a` |
| `JOBS` | 并行度，默认 CPU 核数 |
| `CLEAN=1` | 清理所选 ABI 的构建目录与产物后重编 |
| `FORCE=1` | 忽略已存在的静态库，全部重编 |
| `DEPS_ONLY=1` / `FFMPEG_ONLY=1` | 只编第三方依赖 / 只编 FFmpeg |
| `NO_DOWNLOAD=1` | 源码缺失时不自动下载 |
| `NDK` / `CMAKE` / `NINJA` / `MAKE` | 手动指定工具路径 |
| `HOSTCC` | 主机 C 编译器，显式指定时跳过自动探测 |

产物位于 `ffmpeg/prebuilt/<abi>/`：`include/` 头文件、`lib/*.a` 静态库、`BUILD_INFO.txt`
（记录版本、configure 参数与链接要求）。JNI 层把这些静态库链接成一个 `libffmpeg_jni.so`，链接时必须带
`-Wl,-z,max-page-size=16384` 以满足 Android 15+ 的 16KB 页要求；x265 依赖 `c++_static` 运行时。
`app/build.gradle.kts` 通过 `externalNativeBuild` 调用 `ffmpeg/jni/CMakeLists.txt`，
因此**构建 APK 前必须先运行 `scripts/ffmpeg-build.sh`**，否则 CMake 会提示缺少静态库。

脚本自动定位 NDK r28+、SDK 自带 CMake/Ninja 与 NDK 自带 make；主机没有 `pkg-config` 时使用
`scripts/lib/pkg-config-shim`。x86 / x86_64 ABI 额外需要 `nasm`。

FFmpeg 的 tablegen 代码生成器需要在本机运行，因此即使交叉编译也要有原生 C 编译器（gcc/cc/clang）。
Windows 没装 MSVC/MSYS2 时，可下载便携版 [w64devkit](https://github.com/skeeto/w64devkit/releases)，
解压到 `ffmpeg/toolchain/w64devkit/`，脚本会自动发现；也可用 `HOSTCC=/path/to/gcc` 指定。

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

## 真机回归与性能

`scripts/run-device-tests.sh` 会把 `test-media/` 推到设备的 `/data/local/tmp/vc-matrix`，测试启动时通过 UiAutomation 以 shell 身份复制到应用内部目录再读取（绕过 Android 15 的 FUSE 限制），然后运行仪器测试；设备上没有 Debug APK 时会自动安装。

```bash
scripts/run-device-tests.sh          # 格式矩阵 + 性能基线
scripts/run-device-tests.sh matrix   # 只跑格式矩阵
scripts/run-device-tests.sh perf     # 只跑性能基线
FORCE=1 scripts/run-device-tests.sh  # 重新推送素材
```

- `FormatMatrixTest`：每个源样本转为 MP4/H.264；格式矩阵中每个容器、视频编码、音频编码各至少覆盖一次。
- `PerformanceTest`：1080p 素材转 720p，分别走 Media3 硬件转码与 FFmpeg 软件转码，结果写入 logcat（TAG `PerformanceTest`）。

参考数据（TALIH-PD3U2，Android 15，arm64-v8a）：

| 路径 | 素材 | 耗时 | 实时倍率 |
| --- | --- | --- | --- |
| Media3 硬件转码 1080p→720p | 15s | 2218 ms | 6.77x |
| FFmpeg 软件转码 1080p→720p | 15s | 5895 ms | 2.55x |

## 发布打包

```bash
./gradlew assembleRelease
```

未配置签名时产出 `app/build/outputs/apk/release/app-release-unsigned.apk`。在仓库根目录创建 `keystore.properties`（已 gitignore）后会自动签名：

```properties
storeFile=release.jks
storePassword=********
keyAlias=video-convert
keyPassword=********
```

`storeFile` 相对仓库根目录。release 暂不开启 R8（Media3 与 FFmpeg JNI 使用较多反射）；如启用需先补充 keep 规则。

## 项目结构

```
app/          单模块应用（ui / domain / engine / data / service）
ffmpeg/       自编译静态库、JNI 源码与 CMake 构建脚本
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
  - [x] M3.4 FFmpeg 构建与 JNI 封装
- [x] M4 任务体系：队列、历史、重试、设置项
- [x] M5 收尾：真机格式矩阵回归、性能、打包

## 许可证

本项目以 **GNU 通用公共许可证第 3 版或更高版本**（GPL-3.0-or-later）发布，完整条款见 [LICENSE](LICENSE)。

- 你可以自由使用、修改和再分发本项目的源代码与编译产物，但必须保留版权与许可证声明，并以同一许可证开放对应源代码。
- 本项目计划集成 GPL 版 FFmpeg（含 x264 / x265 / LAME）。分发编译后的 APK 时，须同时提供生成该 APK 所需的完整对应源代码，包括 FFmpeg 的构建脚本与配置。
- 本程序不提供任何担保，详见许可证第 15、16 条。

Copyright (C) 2026 yuarmy
