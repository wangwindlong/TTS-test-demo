# AEC TTS Demo — 回声消除五方案对照实验

Android 端验证「播放 TTS + 录音 + 消除 TTS 回声」的对照实验 Demo。
设备：OnePlus 9 (LE2113, ColorOS, Android 15)，经 ADB over TCP `nas.wangyl.work:15555`。

## 五种模式（UI 或 adb 切换）

| 模式 | 捕获音源 | 消除方案 | 参考信号 | 录音文件 |
|------|---------|---------|---------|---------|
| `MIC` | MIC | 无（基线） | — | `rec_MIC.wav` |
| `VC_NO_AEC` | VOICE_COMMUNICATION | 硬件AEC 显式 disable | 系统内部 | `rec_VC_NO_AEC.wav` |
| `VC_AEC` | VOICE_COMMUNICATION | 硬件 `AcousticEchoCanceler` enable | 系统内部 | `rec_VC_AEC.wav` |
| `WEBRTC_AEC` | MIC | 软件 [AECM](https://github.com/theeasiestway/android-webrtc-aecm)（播放帧喂 `farendBuffer`，录音帧 `echoCancellation`） | **应用显式提供** | `rec_WEBRTC_AEC.wav` |
| `AI_DENOISE` | MIC | sherpa-onnx GTCRN 流式人声降噪（豆包式，无参考盲分离） | 不需要 | `rec_AI_DENOISE.wav` + `rec_AI_DENOISE_raw.wav`（原始麦克风旁路对照） |

```bash
# adb 跑单组
adb shell am start -n dev.wangyl.aecttsdemo/.MainActivity --es mode VC_AEC --el duration 8000
# 结果: /sdcard/Android/data/dev.wangyl.aecttsdemo/files/aec_runs/{rec_*.wav,last_run.json,runs.csv}
```

## 实测结论（OnePlus 9，TTS 外放 9.3s，语音频段 300-3400Hz RMS）

| 模式 | RMS | 相对MIC | 语谱图特征 |
|------|-----|--------|-----------|
| MIC | 0.2117 | 100% | 全程清晰语音谐波结构 |
| VC_NO_AEC | 0.0009 | 0.4% | 仅稀疏窄带click残留 |
| VC_AEC | 0.00003 | ~0% | 干净 |
| WEBRTC_AEC | 0.0002 | 0.1% | 语音结构消失，留均匀宽带嘶声底 |
| AI_DENOISE | 0.00001 | ~0% | 几乎全暗，偶发帧丢弃 |

1. **硬件 AEC 有效且干净**（残留 ~0%），消除后无语音结构残留。
2. **ColorOS 的 VOICE_COMMUNICATION 音源 AEC 关不掉**：`AcousticEchoCanceler.enabled=false`
   后回声依然被消（VC_NO_AEC≈VC_AEC），厂商 AEC 在该音源路径上常开。
   想拿「VC 音源但无 AEC」的对照在 ColorOS 上拿不到。
3. **软件 AECM 可用**：不依赖系统、参考信号自己喂，跨平台可控；代价是
   aggressive 模式把近端一起压成宽带残留（语谱图雾状），delay 参数需按设备调。
4. **GTCRN 对"像人声的 TTS"不消**（2026-09-04 旁路对照实测修正）：
   降噪后 RMS 仅为降噪前的 ~85-90%（0.170→0.158 / 0.172→0.150 ...），
   TTS 语音**几乎原样保留**——GTCRN 是人声增强模型，把清晰的 TTS 语音判定为
   "要保留的人声"，只压了少量稳态噪声。这是正确行为，不是模型没启动：
   模型确实全程在跑（旁路文件同步落盘、能量按帧移粒度变化）。
   GTCRN 消的是稳态噪声（风扇/嘶声/音乐性噪声），对同频段的干净语音不误伤。
   **要消"自己的 TTS"只能走 AEC/AECM（有参考信号）；要消"外部噪声"才用 AI 降噪**。
5. 结论：**自家播放源用 AEC（保真），外部噪声用 AI 降噪兜底**——两者是互补
   而非互斥关系；且 AI 降噪对"类人声干扰"无能为力，这是它与 AEC 的本质边界。

## TTS 引擎系列对照（2026-09-04 OnePlus 9 实测）

UI/adb 可切换 `--es tts SYSTEM|SHERPA_MATCHA`，同一文本同一 AEC 模式对照：

| 指标 | SYSTEM(Google TTS) | SHERPA_MATCHA(本地) |
|------|-------------------|---------------------|
| 合成耗时 synthMs | 3263 ms | 2014 ms（首次另需 3.1s 模型加载） |
| 音频时长 audioMs | 9333 ms | 8147 ms（Matcha 语速偏快+静音压缩） |
| **RTF** | **0.35** | **0.25**（8 线程 Snapdragon888，树莓派同模型 RTF≈2.8） |
| 进程峰值内存 | 29MB（合成在 com.google.android.tts 独立进程） | **207MB**（onnxruntime+模型进本进程） |
| 模型体积 | 0（系统组件） | 139MB assets（matcha 28M + vocos 10M + dict/fst 101M） |
| 音质 | 商用级韵律，略机械 | 中性女声，语速快、韵律平 |

- **RTF (Real-Time Factor)** = 合成耗时 / 音频时长，<1 才能实时流式播报；越低越好。
- **MOS** (Mean Opinion Score)：1-5 主观质量分，业界标准；PESQ/POLQA 是其客观近似（需参考信号，demo 不算）。
- 内存口径：`native heap + Java heap` 进程峰值；SYSTEM 引擎跨进程合成，本进程 RSS 变化小属正常。
- 精确测 RTF/内存需 Android Studio Profiler 或 `dumpsys meminfo <pid>`，demo 值为轻量近似。

## ColorOS 实验三坑（可复现性关键）

1. **后台静麦**：非前台 app 用麦克风 1s 后被系统静音
   （logcat: `App op 27 missing, silencing record`）。解法 = `microphone` 类型
   前台服务（`KeepAliveService`）+ 屏幕常亮（`keepScreenOn` + `svc power stayon true`）。
2. **通话音量独立**：`USAGE_VOICE_COMMUNICATION` 播放走 STREAM_VOICE_CALL，
   与媒体音量无关；为 0 时 TTS 静音播放、实验全静音。App 内已自动拉满并恢复。
3. **TTS 必须等 onInit**：`TextToSpeech` 构造异步，init 回调前调用任何方法返回 -1。

## 本地分析

```bash
adb pull /storage/emulated/0/Android/data/dev.wangyl.aecttsdemo/files/aec_runs/ .
# 注意: adb pull 不覆盖已存在文件，先 rm
python3 ../analyze_aec.py aec_runs/   # 输出残留率对比表 + aec_compare.png
```

## 依赖来源

- AECM: `app/libs/libaecm-release.aar`（[theeasiestway/android-webrtc-aecm](https://github.com/theeasiestway/android-webrtc-aecm)，含全 ABI `libAEC.so`）
- GTCRN: voicekit 复用的 `libs/sherpa-classes.jar` + `src/main/jniLibs/`（sherpa-onnx 1.13.6）+ `assets/gtcrn_simple.onnx`（[k2-fsa release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speech-enhancement-models)，523KB）
