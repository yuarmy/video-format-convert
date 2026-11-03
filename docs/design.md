# 视频格式转换 App 设计文档

- 状态：随实现更新（当前覆盖 M1–M5）
- 日期：2026-11-02
- 适用代码：`app/src/main/java/com/yuarmy/videoconvert`

## 1. 目标与非目标

目标：把本地视频转换成用户指定的容器 / 编码 / 分辨率 / 码率，结果保存进相册。

非目标（MVP 明确不做）：按目标体积压缩、音轨提取、裁剪合并、GIF、字幕、批量转换、云端处理。

## 2. 技术选型

| 项目 | 选择 | 说明 |
| --- | --- | --- |
| 语言 / UI | Kotlin + Jetpack Compose（Material 3） | 单 Activity，无 XML 布局 |
| 构建 | Gradle 9.6.0 + AGP 9.5.0 | AGP 9 内置 Kotlin，不应用 `kotlin-android` |
| SDK | minSdk 26 / targetSdk 36 / compileSdk 37 | Compose 1.12、Lifecycle 2.11 要求 compileSdk 37 |
| 快路径引擎 | Media3 Transformer 1.11.1 | 硬件编解码 + 流拷贝 |
| 兜底引擎 | 自编译 GPL FFmpeg 9 + 最小 JNI | 复用 fftools CLI，不做 ffmpeg-kit 依赖 |
| 状态管理 | ViewModel + StateFlow | UI 无业务逻辑 |
| DI | 手工构造 | MVP 不引入 Hilt |

## 3. 架构总览

```
┌─────────────────────────────── ui ────────────────────────────────┐
│ HomeScreen  ConvertScreen  QueueScreen  HistoryScreen             │
│ ScanScreen  SettingsScreen  LicenseScreen                         │
│        │         │            │           │                       │
│        └── ViewModel（StateFlow，UI 只读状态、发事件）             │
└───────────────┬──────────────────────────┬────────────────────────┘
                │ submit(ConversionSpec)   │ scan
                ▼                          ▼
┌──────── service ─────────┐   ┌──────── data ──────────────────────┐
│ ConversionController     │   │ VideoDecodeScanner  MediaProbe     │
│  （进程级队列 + StateFlow）│   │ MediaStoreSaver     HistoryStore   │
│ ConversionService        │   │ SettingsStore                     │
│  （前台服务 + 唤醒锁）   │   └────────────────────────────────────┘
└───────────┬──────────────┘
            │ EngineRouter.route(spec)
            ▼
┌──────────────── engine ────────────────┐
│ ConversionEngine（统一接口）           │
│  ├─ Media3Engine（remux / 硬件转码）   │
│  └─ FfmpegEngine（流拷贝 / 软件转码）  │
│ EngineRouter（能力探测 + 路由决策）    │
└────────────────────────────────────────┘
            │
            ▼
      domain/model（VideoInfo、ConversionSpec、FormatCatalog、JobState）
```

模块职责：

| 模块 | 职责 |
| --- | --- |
| `ui/` | Compose 页面、导航与状态订阅（配置页用 ViewModel，队列/历史页直接订阅 StateFlow） |
| `service/` | 前台服务承载长任务；`ConversionController` 是 UI 与服务之间的进程级状态桥 |
| `engine/` | `ConversionEngine` 统一接口、Media3 实现、路由与能力探测 |
| `data/` | 元数据探测、目录扫描、MediaStore 写入、历史与设置持久化 |
| `domain/model/` | 与平台无关的模型与格式矩阵 |

单模块起步；FFmpeg JNI 明显膨胀后再拆 `:ffmpeg`。

## 4. 核心模型

```kotlin
data class VideoInfo(
    val uri: Uri, val displayName: String, val sizeBytes: Long?,
    val durationMs: Long?, val width: Int?, val height: Int?,
    val rotationDegrees: Int, val bitrate: Long?,
    val videoMime: String?, val audioMime: String?, val frameRate: Float?,
) {
    val orientedWidth: Int?   // 已按旋转角换算
    val orientedHeight: Int?
}

data class ConversionSpec(
    val source: VideoInfo,
    val container: ContainerOption,
    val videoCodec: CodecOption,
    val audioCodec: CodecOption,
    val resolution: ResolutionOption, // maxHeight = null 表示保持原始
    val quality: Quality,
)
```

`FormatCatalog` 是唯一的格式矩阵来源：7 种容器（MP4/MKV/WebM/MOV/AVI/FLV/TS）、各自的视频/音频编码集合、5 档分辨率。新增格式必须同时更新矩阵、路由与 `scripts/` 测试素材。

`JobState` 描述一次转换的生命周期：`Idle → Preparing → Running → Saving → Success / Failed / Cancelled`。

## 5. 转换链路

### 5.1 时序

1. 用户选择视频（SAF `OpenDocument`）→ 导航到配置页。
2. `ConvertViewModel` 用 `MediaProbe.probe` 读取元数据，默认选中 MP4 + H.264 + AAC + 原始分辨率 + 自动质量。
3. 每次配置变化，`EngineRouter.route(spec)` 重新计算路由；UI 展示提示（流拷贝 / 不可用原因）。
4. 点击开始 → `ConversionController.submit` 将任务入队并启动 `ConversionService`（`startForegroundService`）。
5. 服务 `startForeground` + 获取 `PARTIAL_WAKE_LOCK`，循环 `takeNext` 串行取出任务，调用引擎。
6. 引擎把结果写到 `cacheDir/conversions/<timestamp>.<ext>`。
7. 成功后 `MediaStoreSaver.save` 写入相册，发布 `Success(outputUri, displayName)`；终态同时写入历史。
8. 队列耗尽后 `finally` 删除临时文件、释放唤醒锁、`stopForeground` + `stopSelf`。

### 5.2 路由决策

`ConversionPlanner.plan(spec)` 先判断视频/音频轨道能否原样拷贝（目标编码与源编码一致、
质量=自动、不需要缩放；Dolby Vision 源额外要求 `.mp4` + H.265），再由 `EngineRouter.route` 选引擎：

| 顺序 | 条件 | 结果 |
| --- | --- | --- |
| 1 | 目标容器 ≠ MP4 | `Ffmpeg(remux = plan.remux)` |
| 2 | `sourceUndecodable=true`（扫描确认系统解码器不可用） | `Ffmpeg(remux = plan.remux)` |
| 3 | `plan.remux=true` | `Media3(remux=true)` 流拷贝 |
| 4 | 目标编码 ∈ {H.264, H.265} 且有硬件编码器（需要音频时有 AAC 编码器） | `Media3(remux=false)` 硬件转码 |
| 5 | 其余 MP4 输出（AV1、无硬件编码器） | `Ffmpeg(remux=false)` |
| 6 | FFmpeg 原生库无法加载（非 arm64-v8a） | `Unsupported` |

路由为 `Unsupported` 时配置页可用，但点击开始只弹提示，不会启动服务。
Media3 运行期失败且用户未取消时，服务自动改用 FFmpeg 重试一次，并把提示写入通知。

### 5.3 Media3 引擎

- **流拷贝**：不设置编码 mime，也不设 encoder factory，Transformer 直接重封装。
- **转码**：设置视频 mime（H.264/H.265）与音频 mime（AAC），`DefaultEncoderFactory` 开启 `setEnableFallback(true)`；编码器初始化失败时 Media3 自动换软编，`onFallbackApplied` 回调让进度提示显示“硬件编码不可用，已切换软件编码”。
- **缩放**：仅在目标高度小于源高度时，给 `EditedMediaItem` 挂 `Presentation.createForHeight(maxHeight)`。
- **码率**：质量非自动时按 `宽 × 高 × 帧率 × bpp` 估算，`bpp` 为 0.12 / 0.06 / 0.025（高 / 均衡 / 小体积），限制在 300 kbps–20 Mbps。分辨率保持原始时按源宽高计算。
- **进度**：主线程每 200 ms 轮询 `Transformer.getProgress`，仅当状态为 `PROGRESS_STATE_AVAILABLE` 时上报。
- **结果**：`Transformer.Listener` 的 `onCompleted` / `onError` 通过 `CompletableDeferred` 回到协程。
- **取消**：协程取消 → `NonCancellable` 中调用 `transformer.cancel()` → 删除临时文件 → 保持 `CancellationException` 上抛。

### 5.4 FFmpeg 引擎

- **JNI 封装**：`ffmpeg/jni/CMakeLists.txt` 把 FFmpeg 静态库与 fftools 源码（`ffmpeg.c` 以 `main=ffmpeg_main` 改名编译）链接成 `libffmpeg_jni.so`；`FfmpegNative.run()` 阻塞执行一条命令，`cancel()` 置位 native 信号标志中断转码。
- **输入**：`ContentResolver.openFileDescriptor(uri, "r")` 在整个转换期间保持 fd 打开，命令行传 `/proc/self/fd/N`。
- **FUSE 兜底**：Android 11+ 在 FFmpeg 重新打开 `/proc/self/fd/N` 时会按本进程身份再次鉴权，SAF 授权的文件可能返回 `EACCES`。引擎先用 `FileInputStream` 实测该路径，打不开就流式拷贝到 `cacheDir/inputs/` 再转，结束后删除；拷贝期间检查取消标志。
- **输出**：仍写 `cacheDir/conversions/<timestamp>.<ext>`；Dolby Vision 强制 `.mp4`，避免丢失 `dvvC` 配置盒。
- **命令**：`FfmpegCommand` 按容器 / 编码 / 质量生成参数。视频 H.264→libx264、H.265→libx265、VP8→libvpx、VP9→libvpx-vp9、AV1→libsvtav1、MPEG-4/2→内置编码器；音频 AAC/MP3/Opus/Vorbis/FLAC/AC-3/PCM 同理。可拷贝的轨道用 `-c:v copy` / `-c:a copy`，缩放用 `-vf scale=-2:H,setsar=1`，MP4/MOV 加 `+faststart`，H.265 打 `hvc1` 标签。
- **质量**：H.264 / H.265 / VP8 / VP9 / AV1 用 CRF（高质量 / 均衡 / 小体积三档），MPEG-4/2 按目标像素量估算码率。
- **Dolby Vision**：源 mime 为 `video/dolby-vision`、目标 MP4 + H.265 + 自动质量时视频流拷贝，附 `-bsf:v dovi_rpu -strict unofficial`，且不使用 `-shortest`。
- **进度**：JNI 把 `av_log` 回调到 Kotlin，解析统计行的 `time=` / `speed=`，按源时长换算百分比与 ETA；源时长未知时从 `Duration:` 行补齐。
- **取消**：`FfmpegNative.cancel()` 置位信号标志，命令尽快返回非零码，引擎删除半成品并返回 `Cancelled`。
- **加载失败**：非 arm64-v8a 设备 `System.loadLibrary` 失败，`FfmpegNative.available=false`，路由直接返回 `Unsupported`，不会在转换中途崩溃。

## 6. 解码兼容性扫描（M3.1–M3.3）

用途：找出系统解码器打不开的视频，提前告知用户，并给这类文件提供可用的输出选项。

### 6.1 流程

1. `OpenDocumentTree` 选择目录，`takePersistableUriPermission` 拿持久读权限；初始目录固定为 `primary:DCIM`。
2. `listVideos` 用 `DocumentsContract` 广度优先遍历，按 documentId 去重，MIME 为 `video/*` 或扩展名在白名单内即视为视频。
3. 对每个视频执行 `check`：
   - `MediaExtractor` 打开并找第一条视频轨道（失败 → 容器不支持 / 无视频轨道）；
   - `MediaCodecList.findDecoderForFormat` 找解码器（失败 → 缺少解码器）；
   - `createByCodecName` + `configure` + `start`（失败 → 初始化失败）；
   - 投喂最多 8 个样本、2 秒超时，收到首个输出缓冲区即判定可解码（否则 → 解码失败）。
4. 每发现一个失败立即回调，UI 实时累加；支持中途取消，取消后展示已扫描部分并标注“结果可能不完整”。
5. 点击失败项 → 配置页，携带 `scan=true` 与失败原因。

### 6.2 扫描模式下的特殊处理

- 探测失败不阻塞：`MediaProbe.probe` 抛异常时改用 `probeLenient`，只尽力从 `MediaExtractor` 取轨道信息，保证能进配置页。
- 配置页顶部显示扫描警告卡片；路由带 `sourceUndecodable=true`，由 FFmpeg 接管（源编码与目标一致时可直接流拷贝，否则软件转码）。
- 失败分类（`DecodeFailureKind`）：`CONTAINER_UNSUPPORTED`、`NO_VIDEO_TRACK`、`DECODER_MISSING`、`DECODER_INIT_FAILED`、`DECODE_FAILED`。

## 7. 输出与存储

- 引擎只写 App 私有缓存，不直接写外部存储。
- `MediaStoreSaver` 在 Android 10+ 使用分区存储：先 `insert` 且 `IS_PENDING=1`，流式拷贝后置 0；任何异常都删除占位记录。目标目录为 `Movies/<设置中的输出目录名>`，默认 `视频转换`。
- Android 8–9 走旧路径：写入同一目录后用 `MediaScannerConnection` 通知相册，重名自动追加 `(1)`、`(2)`。
- 输出文件名：`<源文件名>_converted.<容器扩展名>`，非法字符替换、截断到 80 字符。
- 转换结束无论成功失败都删除缓存临时文件。

## 8. 生命周期、前台服务与取消

- 长任务只在 `android:foregroundServiceType="mediaProcessing"` 的前台服务中执行（`ConversionService`），通知带进度与“取消”按钮。
- 服务启动时获取 4 小时上限的 `PARTIAL_WAKE_LOCK`，队列耗尽或服务销毁时释放。
- 取消路径：通知按钮 / 队列页按钮 → `ACTION_CANCEL` → 取消当前任务的子协程 + `engine.cancel()`；队列循环继续处理后续任务。
- 队列正常耗尽时服务自行退出；异常销毁且未正常结束时，`onServiceStopped` 把当前任务记为 `Cancelled` 并写入历史，避免任务永远停留在“进行中”。
- `ConversionController` 是进程内单例（`StateFlow<QueueUiState>` + 内存队列），进程被杀后未完成任务不恢复；历史通过 `HistoryStore` 落盘。

## 9. 任务队列、历史与设置（M4）

### 9.1 队列

- `ConversionController` 持有 `ArrayDeque<QueueJob>`，通过 `StateFlow<QueueUiState>` 暴露 `current / pending / lastFinished`。
- `submit` 入队后确保前台服务运行；服务 `startLoop` 串行 `takeNext`，每个任务在子协程中执行并 `join()`，因此取消只影响当前任务，队列继续。
- `takeNext` 与 `onLoopExhausted` 在同一把锁内判断队列是否为空，避免“刚入队、服务已退出”的竞态。
- 循环退出前用 `stopSelfResult(lastStartId)` 并复查待处理队列，并发提交带来的新 startId 会让服务继续运行，不会把新任务一起停掉。
- 首页与队列页调用 `resume`，尝试重启因异常中断、但进程仍存活的服务。
- 通知标题为当前文件名，副标题显示剩余队列数，操作按钮为“取消当前”。

### 9.2 历史

- `HistoryStore`（SharedPreferences + org.json）保留最近 100 条，`StateFlow` 驱动历史页。
- 每条记录包含状态、完成时间、源文件名、目标摘要、输出 URI/文件名、错误信息与 `SpecSnapshot`。
- `SpecSnapshot` 只保存格式矩阵中的 id（容器 / 编码 / 分辨率 / 质量），读取时经 `FormatCatalog` 还原；矩阵变更导致 id 失效时，“再次转换”提示不可用。
- 历史页支持单条删除、清空（二次确认）；失败 / 取消记录可“再次转换”，入队后跳转队列页。

### 9.3 设置

- `SettingsStore` 持久化输出目录名，`MediaStoreSaver` 写入 `Movies/<目录名>`。
- 目录名会替换非法字符 `\/:*?"<>|` 为 `_`、去掉首尾点、截断到 40 字符，空值回落到 `视频转换`。
- 设置页同时提供“上报服务”“转换历史”和“开源许可”入口。

### 9.4 上报服务（可选）

- `ReportManager` 是 Application 级单例，`VideoConvertApp.onCreate` 调用 `install()`；已登录时在 IO 协程自动建立 MQTT 连接，无需打开页面。
- 登录：设置页填服务器地址与注册码 → `LoginApi` 用 `HttpURLConnection` 调 `POST /api/device/v1/login` → 保存 `deviceId` 与服务端签发的 MQTT 凭据（SharedPreferences）。同一 `installId` 重复登录返回同一设备。
- 设备信息：连接成功（含自动重连）后发布一条 `device_info`，覆盖 App 版本、厂商、机型、Android 版本、SDK、ABI。
- 转换结果：挂接在 `ConversionController.recordHistory()`，所有终态（成功 / 失败 / 取消 / 中断）都会生成 `conversion_result`，包含源文件名与元数据、目标配置、起止时间、耗时与错误。
- 可靠性：先写入 `ReportOutbox`（SharedPreferences，上限 200 条，超出丢最旧）再发布；QoS1 收到 PUBACK 后删除条目，服务端用 `eventId` 幂等。断网或进程被杀后，下次连接自动补发。
- 状态：`ReportUiState(loggedIn, deviceId, connection, busy, error)` 驱动设置页文案；连接失败由 Paho 自动重连，不阻塞转换。
- 退出登录清除凭据与待发队列（避免旧账号数据串号），保留 `installId`。

## 10. 错误处理与回退

| 场景 | 行为 |
| --- | --- |
| 路由不可用 | 配置页显示原因，禁止提交 |
| 硬件编码器缺失 | 路由阶段直接选 `Ffmpeg(remux=false)` |
| Media3 运行期失败 | 未取消且 FFmpeg 可用时自动重试一次，通知提示“已切换 FFmpeg 软件编码” |
| 解码器初始化失败 | 扫描阶段记录为失败项；转换时由 FFmpeg 接管 |
| 服务启动失败 | `submit` 捕获异常，把活动任务与待处理任务全部标记 `Failed` 并写入历史 |
| 保存失败 | 删除 MediaStore 占位记录，发布 `Failed` |
| 用户取消 | 发布 `Cancelled`，清理临时文件与通知 |

## 11. 测试策略

- 仪器测试位于 `app/src/androidTest`：`Media3EngineTest` 覆盖探测、流拷贝、硬件转码、服务与相册写入；`FfmpegEngineTest` 覆盖 native 库加载与编码、命令构建、进度回调、取消、服务级 MKV 输出；`VideoDecodeScannerTest` 覆盖正常文件通过、损坏容器判定与扫描失败源路由；`ConversionQueueTest` 覆盖连续入队、串行完成与历史落盘。
- 测试素材由 `scripts/` 用桌面 ffmpeg 生成，覆盖各容器/编码组合；Dolby Vision 样本依赖 `tools/dovi_tool`，缺失时脚本跳过。
- `FormatMatrixTest` 在真机上做格式矩阵回归：全部源容器转 MP4/H.264，以及格式矩阵中每个容器、视频编码与音频编码的抽样组合。素材由 `scripts/run-device-tests.sh` 推到 `/data/local/tmp/vc-matrix`，测试用 `UiAutomation.executeShellCommand` 以 shell 身份复制到应用内部目录，不经过 SAF，也不受分区存储限制。
- `PerformanceTest` 用 1080p 素材分别跑 Media3 硬件转码与 FFmpeg 软件转码，把耗时与实时倍率写入 logcat，不做绝对速度断言。
- 回归命令：`./gradlew test`、`./gradlew connectedAndroidTest`、`./gradlew lint`；格式矩阵与性能基线用 `scripts/run-device-tests.sh [matrix|perf|all]`。
- 真机回归关注：格式矩阵逐项可转换、取消后无残留文件/通知、输出能在相册播放。

## 12. FFmpeg 接入（M3.4）

- **构建**：`scripts/ffmpeg-download.sh` 下载并校验源码，`scripts/ffmpeg-build.sh` 交叉编译静态库到 `ffmpeg/prebuilt/<abi>/`；`app/build.gradle.kts` 的 `externalNativeBuild` 调用 `ffmpeg/jni/CMakeLists.txt` 链接出 `libffmpeg_jni.so`。当前产物只覆盖 arm64-v8a，因此 `abiFilters` 也只保留该 ABI。
- **复用 fftools CLI**：`ffmpeg.c` 编译时把 `main` 改名为 `ffmpeg_main`，JNI 直接调用。FFmpeg 9 的该路径不会 `exit()`；但 `ffmpeg_cleanup` 不会清零 `nb_input_files` / `nb_output_files` / `nb_filtergraphs` / `nb_decoders`，JNI 每次运行前手动复位，避免第二次转换状态错乱。
- **取消**：CMake 在配置阶段去掉 `ffmpeg.c` 中 `received_sigterm` / `received_nb_signals` 的 `static`，JNI 取消时置 2（大于 `transcode_init_done`），让 AVIO 中断回调与转码循环立即退出。
- **日志与进度**：`av_log_set_callback` 收到的日志用 `av_log_format_line2` 格式化后回调 Kotlin；FFmpeg 工作线程通过 `AttachCurrentThread` + pthread key 析构自动 detach。进度解析 `time=` / `speed=` 统计行。
- **链接**：静态库互相引用，使用 `-Wl,--start-group/--end-group`；x265 需要 `c++_static` / `c++abi`；`-Wl,-Bsymbolic` 解决 NEON 汇编数据符号在 .so 中的可抢占重定位；`-Wl,-z,max-page-size=16384` 满足 16KB 页要求。
- **输入与输出**：`content://` 优先通过 `/proc/self/fd/N` 交给 FFmpeg，FUSE 拒绝时拷贝到缓存；输出写缓存临时文件并复用 `MediaStoreSaver`，结束后清理。

## 13. 已知限制

- FFmpeg 原生库只有 arm64-v8a，其他 ABI 设备只能走 Media3 快路径。
- x265 为 8-bit 构建，10-bit HDR / 部分 Dolby Vision 源转码时会降到 8-bit；Dolby Vision 原样封装需目标为 MP4 + H.265 + 自动质量。
- 软件转码速度受设备 CPU 限制，4K 长视频可能明显发热。
- 队列只存在于进程内：进程被杀后未完成任务丢失（历史仍保留，可手动“再次转换”）。
- 历史最多保留 100 条；若用户在相册中删除输出文件，记录中的 URI 会失效。
- 上报为局域网明文 HTTP/MQTT，凭据与内容可被嗅探；公网必须换 TLS。
- 待发队列上限 200 条，超出丢弃最旧记录；进程被系统杀死时，正在生成的消息可能未及落盘。
- 保存阶段会再完整拷贝一次输出文件，大文件有额外 IO 开销。

## 14. 目录结构

```
app/src/main/java/com/yuarmy/videoconvert/
  VideoConvertApp.kt  安装历史 / 设置存储
  data/        MediaProbe、VideoDecodeScanner、MediaStoreSaver、
               ConversionHistory（HistoryStore）、SettingsStore
  domain/model VideoInfo、ConversionSpec、FormatCatalog、JobState、CodecNames
  engine/      ConversionEngine、ConversionPlanner、EngineRouter、
               Media3Engine、FfmpegEngine、FfmpegNative、FfmpegCommand、FfmpegProgressParser
  service/     ConversionController（队列）、ConversionService（串行消费）
  report/      DeviceInfo、ReportSettings、LoginApi、ReportPayload、ReportOutbox、MqttReporter、ReportManager
  ui/          navigation、home、convert、job（队列）、history、scan、settings、license、common、theme
app/src/androidTest/  Media3EngineTest、FfmpegEngineTest、VideoDecodeScannerTest、
                      ConversionQueueTest、FormatMatrixTest、PerformanceTest
scripts/       FFmpeg 构建脚本、测试素材生成脚本、真机回归脚本
ffmpeg/        自编译静态库、JNI 源码与 CMake 构建定义
```
