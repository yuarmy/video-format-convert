# 视频格式转换 App 设计文档

- 状态：随实现更新（当前覆盖 M1–M3.3）
- 日期：2026-10-30
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
| 兜底引擎 | 自编译 GPL FFmpeg（M3.4） | 最小 JNI，不做 ffmpeg-kit 依赖 |
| 状态管理 | ViewModel + StateFlow | UI 无业务逻辑 |
| DI | 手工构造 | MVP 不引入 Hilt |

## 3. 架构总览

```
┌──────────────────────────── ui ────────────────────────────┐
│ HomeScreen  ConvertScreen  JobScreen  ScanScreen  Settings │
│        │         │            │           │                │
│        └── ViewModel（StateFlow，UI 只读状态、发事件）      │
└───────────────┬──────────────────────────┬─────────────────┘
                │ submit(ConversionSpec)   │ scan
                ▼                          ▼
┌──────── service ─────────┐   ┌──────── data ────────┐
│ ConversionController     │   │ VideoDecodeScanner   │
│  （进程级 JobState）     │   │ MediaProbe           │
│ ConversionService        │   │ MediaStoreSaver      │
│  （前台服务 + 唤醒锁）   │   └──────────────────────┘
└───────────┬──────────────┘
            │ EngineRouter.route(spec)
            ▼
┌──────────────── engine ────────────────┐
│ ConversionEngine（统一接口）           │
│  ├─ Media3Engine（remux / 转码）       │
│  └─ FfmpegEngine（M3.4，待接入）       │
│ EngineRouter（能力探测 + 路由决策）    │
└────────────────────────────────────────┘
            │
            ▼
      domain/model（VideoInfo、ConversionSpec、FormatCatalog、JobState）
```

模块职责：

| 模块 | 职责 |
| --- | --- |
| `ui/` | Compose 页面、导航、各页面 ViewModel；只做状态渲染与用户事件转发 |
| `service/` | 前台服务承载长任务；`ConversionController` 是 UI 与服务之间的进程级状态桥 |
| `engine/` | `ConversionEngine` 统一接口、Media3 实现、路由与能力探测 |
| `data/` | 元数据探测、目录扫描、MediaStore 写入 |
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
4. 点击开始 → `ConversionController.submit` 保存 `pendingSpec` 并启动 `ConversionService`（`startForegroundService`）。
5. 服务 `startForeground` + 获取 `PARTIAL_WAKE_LOCK`，取出 spec，调用引擎。
6. 引擎把结果写到 `cacheDir/conversions/<timestamp>.<ext>`。
7. 成功后 `MediaStoreSaver.save` 写入相册，发布 `Success(outputUri, displayName)`。
8. `finally` 删除临时文件、释放唤醒锁、`stopForeground` + `stopSelf`。

### 5.2 路由决策

优先级严格按此顺序（`EngineRouter.route`）：

| 顺序 | 条件 | 结果 |
| --- | --- | --- |
| 1 | 目标容器 ≠ MP4 | `Unsupported`：需要 FFmpeg |
| 2 | 目标编码 ∉ {H.264, H.265} | `Unsupported`：快路径只支持这两种 |
| 3 | 质量=自动 且 不需要缩放 且 源视频编码=目标编码 且 音频为 AAC 或无音频 | `Media3(remux=true)` 流拷贝 |
| 4 | `sourceUndecodable=true` | `Unsupported`：解码失败源只能流拷贝，转码待 FFmpeg |
| 5 | 无目标视频编码器 / 无 AAC 编码器 | `Unsupported`：告知将由 FFmpeg 软件编码 |
| 6 | 其余 | `Media3(remux=false)` 硬件转码 |

路由为 `Unsupported` 时配置页可用，但点击开始只弹提示，不会启动服务。

### 5.3 Media3 引擎

- **流拷贝**：不设置编码 mime，也不设 encoder factory，Transformer 直接重封装。
- **转码**：设置视频 mime（H.264/H.265）与音频 mime（AAC），`DefaultEncoderFactory` 开启 `setEnableFallback(true)`；编码器初始化失败时 Media3 自动换软编，`onFallbackApplied` 回调让进度提示显示“硬件编码不可用，已切换软件编码”。
- **缩放**：仅在目标高度小于源高度时，给 `EditedMediaItem` 挂 `Presentation.createForHeight(maxHeight)`。
- **码率**：质量非自动时按 `宽 × 高 × 帧率 × bpp` 估算，`bpp` 为 0.12 / 0.06 / 0.025（高 / 均衡 / 小体积），限制在 300 kbps–20 Mbps。分辨率保持原始时按源宽高计算。
- **进度**：主线程每 200 ms 轮询 `Transformer.getProgress`，仅当状态为 `PROGRESS_STATE_AVAILABLE` 时上报。
- **结果**：`Transformer.Listener` 的 `onCompleted` / `onError` 通过 `CompletableDeferred` 回到协程。
- **取消**：协程取消 → `NonCancellable` 中调用 `transformer.cancel()` → 删除临时文件 → 保持 `CancellationException` 上抛。

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
- 配置页顶部显示扫描警告卡片；路由带 `sourceUndecodable=true`，因此只有目标与源兼容时才允许流拷贝，其余给出“需要 FFmpeg”的原因。
- 失败分类（`DecodeFailureKind`）：`CONTAINER_UNSUPPORTED`、`NO_VIDEO_TRACK`、`DECODER_MISSING`、`DECODER_INIT_FAILED`、`DECODE_FAILED`。

## 7. 输出与存储

- 引擎只写 App 私有缓存，不直接写外部存储。
- `MediaStoreSaver` 在 Android 10+ 使用分区存储：先 `insert` 且 `IS_PENDING=1`，流式拷贝后置 0；任何异常都删除占位记录。目标目录 `Movies/视频转换`。
- Android 8–9 走旧路径：写入 `Movies/视频转换` 后用 `MediaScannerConnection` 通知相册，重名自动追加 `(1)`、`(2)`。
- 输出文件名：`<源文件名>_converted.<容器扩展名>`，非法字符替换、截断到 80 字符。
- 转换结束无论成功失败都删除缓存临时文件。

## 8. 生命周期、前台服务与取消

- 长任务只在 `android:foregroundServiceType="mediaProcessing"` 的前台服务中执行（`ConversionService`），通知带进度与“取消”按钮。
- 服务启动时获取 1 小时上限的 `PARTIAL_WAKE_LOCK`，`finally` 释放。
- 取消路径：通知按钮 / 页面按钮 → `ACTION_CANCEL` → 取消协程 + `engine.cancel()`；服务的 `finally` 在 `NonCancellable` 中清理通知、唤醒锁与临时文件。
- `ConversionController` 是进程内单例（`StateFlow<JobState>` + `pendingSpec`），进程被杀后任务不恢复；这是 M4 任务队列要替换的部分。

## 9. 错误处理与回退

| 场景 | 行为 |
| --- | --- |
| 路由不可用 | 配置页显示原因，禁止提交 |
| 硬件编码器缺失 | 路由阶段直接判 `Unsupported`；Media3 运行期失败还会触发编码器回退 |
| 解码器初始化失败 | Media3 尝试软件编码并提示；扫描阶段则记录为失败项 |
| 服务启动失败 | `submit` 捕获异常，发布 `Failed`，清空 `pendingSpec` |
| 保存失败 | 删除 MediaStore 占位记录，发布 `Failed` |
| 用户取消 | 发布 `Cancelled`，清理临时文件与通知 |

## 10. 测试策略

- 单元/仪器测试位于 `app/src/androidTest`：`Media3EngineTest` 覆盖探测、流拷贝与转码；`VideoDecodeScannerTest` 覆盖正常文件通过、损坏容器被判为 `CONTAINER_UNSUPPORTED`、解码失败源只允许流拷贝。
- 测试素材由 `scripts/` 用桌面 ffmpeg 生成，覆盖各容器/编码组合；Dolby Vision 样本依赖 `tools/dovi_tool`，缺失时脚本跳过。
- 回归命令：`./gradlew test`、`./gradlew connectedAndroidTest`、`./gradlew lint`。
- 真机回归关注：格式矩阵逐项可转换、取消后无残留文件/通知、输出能在相册播放。

## 11. FFmpeg 接入（M3.4，预留）

- 保持 `ConversionEngine` 接口不变，新增 `FfmpegEngine` 实现；`EngineRouter` 在第 1/2/4/5 条分支指向 `EngineRoute.Ffmpeg(reason)`。
- `content://` 不能直接给 FFmpeg：通过 `ContentResolver.openFileDescriptor` 传 `/proc/self/fd/N`，或临时拷贝到缓存。
- 输出仍写缓存临时文件，复用 `MediaStoreSaver`。
- 进度解析 FFmpeg stderr 的 `time=`，取消通过终止进程并删除临时文件。
- 构建使用 NDK r28+，链接参数带 `-Wl,-z,max-page-size=16384`。
- Dolby Vision 输出必须用 `.mp4` 扩展并加 `-bsf:v dovi_rpu -strict unofficial`，且不能加 `-shortest`。
- 需要把“源是否可解码”从导航参数提升为 `ConversionSpec` 或服务入参，否则服务端无法复现扫描模式的路由判断。

## 12. 已知限制

- 目前只有 MP4 输出可转换；MKV/MOV/AVI/FLV/TS/WebM 在矩阵中可选但路由返回不可用，等待 FFmpeg。
- 解码失败的文件目前只能流拷贝到兼容容器，转码同样等待 FFmpeg。
- `ConversionController` 只支持单任务，无队列、无历史、无重试（M4）。
- 保存阶段会再完整拷贝一次输出文件，大文件有额外 IO 开销。
- 前台服务运行期若进程被杀，任务不会恢复。

## 13. 目录结构

```
app/src/main/java/com/yuarmy/videoconvert/
  data/        MediaProbe、VideoDecodeScanner、MediaStoreSaver
  domain/model VideoInfo、ConversionSpec、FormatCatalog、JobState、CodecNames
  engine/      ConversionEngine、Media3Engine、EngineRouter
  service/     ConversionController、ConversionService
  ui/          navigation、home、convert、job、scan、settings、common、theme
app/src/androidTest/  Media3EngineTest、VideoDecodeScannerTest
scripts/       FFmpeg 构建脚本、测试素材生成脚本
ffmpeg/        自编译 .so 与 JNI 源码（M3.4）
```
