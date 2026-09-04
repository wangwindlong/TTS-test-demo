package dev.wangyl.aecttsdemo

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.theeasiestway.libaecm.AEC
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * AEC 对照实验引擎：硬件 AEC vs WebRTC 软件回声消除(AECM) vs AI 人声降噪(GTCRN)。
 *
 * ── 硬件 AEC（模式 MIC / VC_NO_AEC / VC_AEC）──
 * 系统在捕获路径内置 AEC（ADSP/HAL 层），以下行播放流为参考信号做自适应滤波。
 * 生效三条件：VOICE_COMMUNICATION 音源 + MODE_IN_COMMUNICATION + 外放。
 * 我们无法拿到它内部的参考信号，一切都在黑盒里完成。
 *
 * ── WebRTC AECM（模式 WEBRTC_AEC）──
 * 软件路线：把「即将播放的 TTS 帧」在写入 AudioTrack 前显式喂给 AECM 当参考信号
 * (farendBuffer)，再对麦克风帧做 echoCancellation 得到消除后的音频。
 * 原理：AECM 用鲁棒延迟估计+频域归一化 NLMS 自适应滤波，从近端信号里
 * 减去「参考信号经回声路径(扬声器→空气→麦克风)的估计值」。
 * 与硬件 AEC 的关键区别：参考信号由应用自己提供，不依赖系统黑盒，
 * 因此跨平台(自建 AudioTrack/AudioRecord 或桌面端)行为可控可复现。
 *
 * ── AI 人声降噪（模式 AI_DENOISE，豆包输入法同思路）──
 * 无参考信号的盲源处理：GTCRN 模型（与火山引擎 RTC AINR 同类的 CRN 架构，
 * 仅 0.23M 参数）学习「人声 vs 非人声」的频谱特征，直接从混合信号里
 * 估计干净人声的掩码。不需要回声路径参考，因此连外部音乐/电视声都能滤，
 * 这是 AEC 做不到的；代价是可能连语音一起损伤（无参考保障）。
 *
 * 实验只变「捕获路径与消除算法」这一个变量：
 *   MIC        → 无处理基线（预期：录音里有明显 TTS 回声）
 *   VC_NO_AEC  → 音源对但硬件 AEC 显式 disable（预期：回声依旧）
 *   VC_AEC     → 硬件 AEC enable（预期：TTS 回声被系统消除）
 *   WEBRTC_AEC → MIC + AECM 软件消除（预期：TTS 回声被软件消除）
 *   AI_DENOISE → MIC + GTCRN 降噪（预期：TTS 回声/音乐被 AI 滤成人声）
 */
class AecEngine(private val context: Context) {

    companion object {
        const val TAG = "AecEngine"
        const val MODE_MIC = "MIC"
        const val MODE_VC = "VC_NO_AEC"
        const val MODE_VC_AEC = "VC_AEC"
        const val MODE_WEBRTC_AEC = "WEBRTC_AEC"
        const val MODE_AI_DENOISE = "AI_DENOISE"

        const val SAMPLE_RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2

        /** AECM 帧大小：16k 下 160 样本 = 10ms */
        private const val AECM_FRAME = 160

        /** 播放后保持录音的时长（ms）：捕获消除收敛后的尾帧与自然环境音 */
        private const val TAIL_MS = 1200L

        /** AECM delay 默认值：渲染/捕获链路总延迟估计(ms)，UI/adb 可调 */
        const val DEFAULT_AECM_DELAY_MS = 80
    }

    data class RunResult(
        val mode: String,
        val wavFile: File,
        val aecAvailable: Boolean,
        val aecEnabled: Boolean,
        val recordedBytes: Int,
        val playedBytes: Int,
        val elapsedMs: Long,
    )

    private val busy = AtomicBoolean(false)

    // =========================================================================
    // 对外：跑一组实验（TTS 播放 + 录音 + 指定消除方案）
    // =========================================================================
    @SuppressLint("MissingPermission")
    suspend fun runExperiment(
        mode: String,
        text: String,
        durationMs: Long,
        aecmDelayMs: Int = DEFAULT_AECM_DELAY_MS,
        ttsFamily: TtsManager.Family = TtsManager.Family.SYSTEM,
    ): RunResult = withContext(Dispatchers.IO) {
        check(busy.compareAndSet(false, true)) { "another experiment is running" }
        try {
            // ColorOS 后台静麦防护：实验期间提升为麦克风前台服务
            KeepAliveService.start(context)
            // 等服务真正进入前台（startForeground 是异步生效的）
            delay(400)
            val dir = File(context.getExternalFilesDir(null), "aec_runs").apply { mkdirs() }
            val pcmFile = File(dir, "raw_${mode}_${System.currentTimeMillis()}.pcm")
            val wavFile = File(dir, "rec_${mode}.wav")
            // AI 模式的原始麦克风旁路录音（降噪前），用于对照确认模型确实在跑
            val bypassPcm = if (mode == MODE_AI_DENOISE) File(dir, "raw_AI_bypass_${System.currentTimeMillis()}.pcm") else null
            val bypassWav = if (mode == MODE_AI_DENOISE) File(dir, "rec_${mode}_raw.wav") else null
            val isWebrtc = mode == MODE_WEBRTC_AEC

            // ---- 1. TTS 合成（TtsManager 统一采集性能指标）----
            val ttsAudio = TtsManager.synthesize(context, ttsFamily, text)
            Log.i(TAG, "[$mode] TTS($ttsFamily) synthesized: ${ttsAudio.size} bytes | ${TtsManager.shortStatus()}")
            // 合成原始输出单独存档：试听音质/韵律用（不经扬声器+麦克风链路）
            runCatching {
                val tmpPcm = File(dir, "tts_${ttsFamily}.pcm")
                tmpPcm.writeBytes(ttsAudio)
                pcmToWav(tmpPcm, File(dir, "tts_${ttsFamily}.wav"), SAMPLE_RATE, 1, BYTES_PER_SAMPLE)
                tmpPcm.delete()
            }

            // ---- 2. 音频路由 ----
            // 硬件 AEC：MODE_IN_COMMUNICATION + 外放（AEC 生效先决条件）
            // 软件AECM/AI：NORMAL 模式即可，不依赖系统路由
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val prevMode = audioManager.mode
            // 播放前把对应流音量拉满（否则媒体音量为0时静音播放，实验无效）
            val prevMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val prevVoiceVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            fun boostVolumes() {
                try {
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0
                    )
                } catch (e: Exception) { Log.w(TAG, "set STREAM_MUSIC volume: ${e.message}") }
                try {
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_VOICE_CALL,
                        audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0
                    )
                } catch (e: Exception) { Log.w(TAG, "set STREAM_VOICE_CALL volume: ${e.message}") }
            }
            fun restoreVolumes() {
                try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, prevMusicVol, 0) } catch (_: Exception) {}
                try { audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, prevVoiceVol, 0) } catch (_: Exception) {}
            }
            boostVolumes()
            if (!isWebrtc && mode != MODE_AI_DENOISE) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
                boostVolumes() // MODE_IN_COMMUNICATION 后音量索引可能变化，再拉一次
            }

            // ---- 3. AECM 实例（软件路线专用）----
            var aecm: AEC? = null
            if (isWebrtc) {
                try {
                    aecm = AEC(AEC.SamplingFrequency.FS_16000Hz, AEC.AggressiveMode.AGGRESSIVE)
                    Log.i(TAG, "[WEBRTC_AEC] AECM instance ready, delay=${aecmDelayMs}ms")
                } catch (e: Throwable) {
                    Log.e(TAG, "AECM init failed: ${e.message}")
                    throw IllegalStateException("AECM (libAEC.so) init failed: ${e.message}", e)
                }
            }

            // ---- 3b. GTCRN 在线降噪器（AI 路线专用；模型从 assets 复制到 filesDir）----
            var denoiser: com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser? = null
            if (mode == MODE_AI_DENOISE) {
                try {
                    val modelFile = ensureAssetCopied("gtcrn_simple.onnx")
                    val cfg = com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig(
                        model = com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig(
                            gtcrn = com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig(
                                model = modelFile
                            ),
                            numThreads = 1,
                            debug = false,
                            provider = "cpu"
                        )
                    )
                    denoiser = com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser(assetManager = null, config = cfg)
                    Log.i(TAG, "[AI_DENOISE] GTCRN denoiser ready: sr=${denoiser.sampleRate} frameShift=${denoiser.frameShiftInSamples}")
                } catch (e: Throwable) {
                    Log.e(TAG, "GTCRN init failed: ${e.message}")
                    throw IllegalStateException("GTCRN init failed: ${e.message}", e)
                }
            }

            try {
                // ---- 4. 捕获路径 ----
                // 硬件实验按模式选音源；软件实验用 MIC 音源，保证只有 AECM 在起作用
                val source = when (mode) {
                    MODE_MIC, MODE_WEBRTC_AEC, MODE_AI_DENOISE -> MediaRecorder.AudioSource.MIC
                    MODE_VC, MODE_VC_AEC -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    else -> error("unknown mode: $mode")
                }
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, FORMAT)
                val recorder = AudioRecord(
                    source, SAMPLE_RATE, CHANNEL_IN, FORMAT, minBuf * 2
                )
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }

                // ---- 5. 硬件 AcousticEchoCanceler（只作用于 VC_AEC）----
                var aecAvailable = false
                var aecEnabled = false
                if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                    val aec = AcousticEchoCanceler.create(recorder.audioSessionId)
                    aecAvailable = aec != null
                    if (aec != null) {
                        aec.enabled = (mode == MODE_VC_AEC)   // 唯一实验变量
                        aecEnabled = aec.enabled
                        Log.i(TAG, "[$mode] AEC available=$aecAvailable enabled=$aecEnabled")
                    } else {
                        Log.w(TAG, "[$mode] AcousticEchoCanceler.create returned null")
                    }
                }
                Log.i(TAG, "[$mode] audioSessionId=${recorder.audioSessionId}")

                // ---- 6. 播放流 ----
                // 硬件路线：USAGE_VOICE_COMMUNICATION 让 HAL 把下行流当 far-end 参考
                // 软件/AI 路线：USAGE_MEDIA 即可，参考信号/模型不需要系统参与
                val track = buildPlaybackTrack(ttsAudio.size, webrtc = isWebrtc || mode == MODE_AI_DENOISE)

                // ---- 7. 同时开跑：录音线程逐帧消除并落盘 ----
                val recorded = AtomicInteger(0)
                val stopFlag = AtomicBoolean(false)
                recorder.startRecording()
                val recordJob = Thread {
                    val buf = ByteArray(AECM_FRAME * BYTES_PER_SAMPLE)
                    val shortBuf = ShortArray(AECM_FRAME)
                    // AI 降噪: GTCRN 内部以 frameShiftInSamples(512) 为单位推进，音频攒到该
                    // 粒度才吐出结果；这里用累计缓冲对齐，避免 10ms 帧与模型帧移错位
                    val aiPend = java.util.ArrayDeque<Short>()
                    recorder.read(buf, 0, buf.size) // 丢弃启动瞬间的第一帧残余
                    while (!stopFlag.get() &&
                        recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
                    ) {
                        val n = recorder.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        val frames = n / BYTES_PER_SAMPLE
                        java.nio.ByteBuffer.wrap(buf, 0, frames * BYTES_PER_SAMPLE)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().get(shortBuf, 0, frames)
                        var out: ShortArray = shortBuf.copyOf(frames)
                        aecm?.let {
                            // 软件消除：AECM 只支持 80/160 样本帧
                            val proc = it.echoCancellation(out, null, frames, aecmDelayMs)
                            if (proc != null) out = proc
                        }
                        if (denoiser != null) {
                            // AI 降噪：攒到 frameShift 粒度后整段过 GTCRN；原始麦克风同步落旁路文件
                            for (s in out) aiPend.addLast(s)
                            bypassPcm?.let { appendToFile(it, buf, n) }
                            if (aiPend.size >= denoiser.frameShiftInSamples) {
                                val chunk = ShortArray(denoiser.frameShiftInSamples) { aiPend.removeFirst() }
                                val floats = FloatArray(chunk.size) { chunk[it] / 32768f }
                                val dn = denoiser.run(floats, denoiser.sampleRate)
                                val cleaned = dn.samples
                                val outBytes = ByteArray(cleaned.size * BYTES_PER_SAMPLE)
                                java.nio.ByteBuffer.wrap(outBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    .asShortBuffer().apply {
                                        for (i in cleaned.indices) put((cleaned[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                                    }
                                appendToFile(pcmFile, outBytes, outBytes.size)
                                recorded.addAndGet(outBytes.size)
                            }
                            continue
                        }
                        val outBytes = ByteArray(out.size * BYTES_PER_SAMPLE)
                        java.nio.ByteBuffer.wrap(outBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().put(out)
                        appendToFile(pcmFile, outBytes, outBytes.size)
                        recorded.addAndGet(outBytes.size)
                    }
                    // 收尾：把 AI 路线残留在队列里的样本 flush 出去
                    if (denoiser != null && aiPend.isNotEmpty()) {
                        val rest = ShortArray(aiPend.size) { aiPend.removeFirst() }
                        val floats = FloatArray(rest.size) { rest[it] / 32768f }
                        val dn = denoiser.run(floats, denoiser.sampleRate)
                        val outBytes = ByteArray(dn.samples.size * BYTES_PER_SAMPLE)
                        java.nio.ByteBuffer.wrap(outBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().apply {
                                for (i in dn.samples.indices) put((dn.samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                            }
                        appendToFile(pcmFile, outBytes, outBytes.size)
                        recorded.addAndGet(outBytes.size)
                    }
                }.apply { start() }

                // 播放：逐 10ms 帧写 track；软件路线先把帧喂给 AECM 当参考信号
                track.play()
                var offset = 0
                val frameBytes = AECM_FRAME * BYTES_PER_SAMPLE
                val farend = ShortArray(AECM_FRAME)
                while (offset < ttsAudio.size) {
                    val n = minOf(frameBytes, ttsAudio.size - offset)
                    if (aecm != null && n == frameBytes) {
                        java.nio.ByteBuffer.wrap(ttsAudio, offset, n)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().get(farend)
                        aecm.farendBuffer(farend, AECM_FRAME)
                    }
                    val w = track.write(ttsAudio, offset, n)
                    if (w < 0) break
                    offset += w
                }
                track.stop()
                track.release()

                // ---- 8. 播完后多录一段尾巴，然后停录音 ----
                delay(TAIL_MS)
                recorder.stop()
                stopFlag.set(true)
                recordJob.join()
                recorder.release()

                // ---- 9. PCM → WAV ----
                pcmToWav(pcmFile, wavFile, SAMPLE_RATE, 1, BYTES_PER_SAMPLE)
                pcmFile.delete()
                bypassPcm?.let {
                    bypassWav?.let { bw -> pcmToWav(it, bw, SAMPLE_RATE, 1, BYTES_PER_SAMPLE) }
                    it.delete()
                    Log.i(TAG, "[AI_DENOISE] bypass raw mic saved: ${bypassWav?.name}")
                }

                val played = minOf(offset, ttsAudio.size)
                val elapsed = (recorded.get() * 1000L) / (SAMPLE_RATE * BYTES_PER_SAMPLE)
                Log.i(TAG, "[$mode] done: recorded=${recorded.get()}B ($elapsed ms), played=${played}B")
                RunResult(mode, wavFile, aecAvailable, aecEnabled, recorded.get(), played, elapsed)
            } finally {
                aecm?.close()
                denoiser?.release()
                restoreVolumes()
                if (!isWebrtc && mode != MODE_AI_DENOISE) {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                    audioManager.mode = prevMode
                }
            }
        } finally {
            busy.set(false)
            KeepAliveService.stop(context)
        }
    }

    // =========================================================================
    // TTS 合成已迁移至 TtsManager（SYSTEM / SHERPA_MATCHA 双系列 + 性能指标）
    // =========================================================================

    private fun buildPlaybackTrack(byteCount: Int, webrtc: Boolean): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, FORMAT)
        val bufSize = maxOf(minBuf, byteCount.coerceAtMost(SAMPLE_RATE / 5 * BYTES_PER_SAMPLE))
        val attrs = AudioAttributes.Builder()
            .setUsage(
                if (webrtc) AudioAttributes.USAGE_MEDIA
                else AudioAttributes.USAGE_VOICE_COMMUNICATION  // 下行参考信号的关键
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(FORMAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_OUT)
            .build()
        return AudioTrack(attrs, fmt, bufSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
            .also { check(it.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack init failed" } }
    }

    private fun appendToFile(f: File, buf: ByteArray, n: Int) {
        java.io.FileOutputStream(f, true).use { it.write(buf, 0, n) }
    }

    /** 把 assets 里的模型复制到 filesDir（sherpa newFromFile 需要真实路径）。 */
    private fun ensureAssetCopied(name: String): String {
        val out = File(context.filesDir, name)
        if (!out.exists() || out.length() == 0L) {
            context.assets.open(name).use { input ->
                java.io.FileOutputStream(out).use { input.copyTo(it) }
            }
        }
        return out.absolutePath
    }

    private fun pcmToWav(pcm: File, wav: File, sampleRate: Int, channels: Int, bytesPerSample: Int) {
        val dataLen = pcm.length()
        val totalLen = 36 + dataLen
        java.io.DataOutputStream(java.io.FileOutputStream(wav)).use { out ->
            out.writeBytes("RIFF"); out.writeIntLe(totalLen.toInt()); out.writeBytes("WAVE")
            out.writeBytes("fmt "); out.writeIntLe(16)
            out.writeShortLe(1) // PCM
            out.writeShortLe(channels)
            out.writeIntLe(sampleRate)
            out.writeIntLe(sampleRate * channels * bytesPerSample)
            out.writeShortLe(channels * bytesPerSample)
            out.writeShortLe(bytesPerSample * 8)
            out.writeBytes("data"); out.writeIntLe(dataLen.toInt())
            pcm.inputStream().use { it.copyTo(out) }
        }
    }

    private fun java.io.DataOutputStream.writeIntLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }
    private fun java.io.DataOutputStream.writeShortLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}
