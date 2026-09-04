package dev.wangyl.aecttsdemo

import android.content.Context
import android.os.Debug
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.Locale

/**
 * TTS 系列管理器：SYSTEM(系统 Google TTS) vs SHERPA_MATCHA(本地 Matcha+Vocos)。
 *
 * 统一输出 16kHz mono PCM16，并采集业界通用合成性能指标：
 *   - 合成耗时 synthMs：文本 → 完整 PCM 的墙钟时间
 *   - 音频时长 audioMs：输出 PCM 的播放时长
 *   - RTF (Real-Time Factor)：synthMs / audioMs，<1 才能实时；业界口径见 README
 *   - 峰值内存 peakRssMB：合成前后 (native heap + Java heap) 的最大值
 *     （SYSTEM 引擎是独立进程，本进程 RSS 变化小是正常现象）
 */
object TtsManager {

    private const val TAG = "TtsManager"

    enum class Family(val tag: String, val label: String) {
        SYSTEM("sys", "系统TTS"),
        SHERPA_MATCHA("matcha", "Matcha+Vocos"),
    }

    data class Metrics(
        val family: Family,
        val synthMs: Long,
        val audioMs: Long,
        val rtf: Float,
        val peakRssMB: Double,
        val pcm16kBytes: Int,
        val modelSampleRate: Int,
    ) {
        fun shortDesc(): String =
            String.format(
                Locale.US, "%s: 合成%dms / 音频%dms / RTF=%.2f / 峰值内存%.0fMB / 输出%.0fKB@16k",
                family.label, synthMs, audioMs, rtf, peakRssMB, pcm16kBytes / 1024.0
            )
    }

    @Volatile
    var lastMetrics: Metrics? = null
        private set

    @Volatile
    var sherpaReady: Boolean = false
        private set

    private var tts: OfflineTts? = null

    fun shortStatus(): String = lastMetrics?.shortDesc() ?: "尚未合成"

    // =========================================================================
    // 统一入口
    // =========================================================================
    suspend fun synthesize(context: Context, family: Family, text: String): ByteArray =
        when (family) {
            Family.SYSTEM -> systemSynthesize(context, text)
            Family.SHERPA_MATCHA -> sherpaSynthesize(context, text)
        }

    // =========================================================================
    // SYSTEM：系统 Google TTS（原 AecEngine.synthesizeSpeech 逻辑迁移至此）
    // =========================================================================
    private suspend fun systemSynthesize(context: Context, text: String): ByteArray {
        val rssBefore = footprintMB()
        val t0 = System.currentTimeMillis()
        val out = File(context.cacheDir, "tts_sys.wav")
        out.delete()

        val initLatch = java.util.concurrent.CountDownLatch(1)
        val initOk = java.util.concurrent.atomic.AtomicBoolean(false)
        val tts = android.speech.tts.TextToSpeech(context) { status ->
            initOk.set(status == android.speech.tts.TextToSpeech.SUCCESS)
            initLatch.countDown()
        }
        try {
            check(initLatch.await(10, java.util.concurrent.TimeUnit.SECONDS) && initOk.get()) {
                "TTS init failed/timeout"
            }
            var setOk = tts.setLanguage(java.util.Locale.CHINA)
            if (setOk < android.speech.tts.TextToSpeech.LANG_AVAILABLE) {
                setOk = tts.setLanguage(java.util.Locale.SIMPLIFIED_CHINESE)
            }
            if (setOk < android.speech.tts.TextToSpeech.LANG_AVAILABLE) {
                setOk = tts.setLanguage(java.util.Locale.getDefault())
            }
            val latch = java.util.concurrent.CountDownLatch(1)
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == "demo") latch.countDown() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == "demo") latch.countDown() }
            })
            val r = tts.synthesizeToFile(text, null, out, "demo")
            check(r == android.speech.tts.TextToSpeech.SUCCESS) { "synthesizeToFile failed: $r" }
            val timeoutSec = (android.speech.tts.TextToSpeech.getMaxSpeechInputLength() / 100 + 20).toLong()
            val done = latch.await(timeoutSec, java.util.concurrent.TimeUnit.SECONDS) && out.length() > 44
            check(done) { "TTS output missing/empty" }
        } finally {
            tts.shutdown()
        }
        val synthMs = System.currentTimeMillis() - t0
        val wav = out.readBytes()
        out.delete()

        val (pcm16k, srcRate) = WavUtil.extractPcmResampled(wav, targetRate = 16000)
        recordMetrics(Family.SYSTEM, synthMs, pcm16k, srcRate, rssBefore)
        return pcm16k
    }

    // =========================================================================
    // SHERPA_MATCHA：本地 Matcha(声学) + Vocos(声码器)，22050Hz → 重采样 16k
    // =========================================================================
    private suspend fun sherpaSynthesize(context: Context, text: String): ByteArray {
        ensureSherpa(context)
        val engine = tts ?: error("sherpa tts not loaded")
        val rssBefore = footprintMB()
        val modelSr = engine.sampleRate()
        val t0 = System.currentTimeMillis()
        val audio = engine.generate(text, sid = 0, speed = 1.0f)
        val synthMs = System.currentTimeMillis() - t0
        val floats = audio.samples
        Log.i(TAG, "[MATCHA] generated ${floats.size} frames @$modelSr in ${synthMs}ms")

        val pcm16k = resampleFloatTo16kPcm(floats, modelSr)
        recordMetrics(Family.SHERPA_MATCHA, synthMs, pcm16k, modelSr, rssBefore)
        return pcm16k
    }

    fun ensureSherpa(context: Context) {
        if (tts != null) return
        synchronized(this) {
            if (tts != null) return
            val dir = copyTtsAssets(context)
            val vocos = dir.listFiles { f -> f.name.startsWith("vocos") }?.firstOrNull()?.absolutePath
                ?: error("vocos missing in $dir")
            val acoustic = dir.listFiles { f -> f.name.startsWith("model-steps") }?.firstOrNull()?.absolutePath
                ?: error("matcha acoustic missing in $dir")
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    matcha = OfflineTtsMatchaModelConfig(
                        acousticModel = acoustic,
                        vocoder = vocos,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        lexicon = File(dir, "lexicon.txt").absolutePath,
                        dictDir = File(dir, "dict").absolutePath,
                        // 忽高忽低对策: noiseScale 默认0.667→0.45, 降低逐句基频/能量随机抖动
                        noiseScale = 0.45f,
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                ruleFsts = listOf("date.fst", "number.fst", "phone.fst")
                    .joinToString(",") { File(dir, it).absolutePath },
                maxNumSentences = 1,
                // 句末没气对策: silenceScale 默认0.2→0.6, 句尾停顿恢复到60%, 不再硬砍
                silenceScale = 0.6f,
            )
            val loadT0 = System.currentTimeMillis()
            tts = OfflineTts(assetManager = null, config = cfg).also {
                Log.i(TAG, "sherpa matcha loaded in ${System.currentTimeMillis() - loadT0}ms, sr=${it.sampleRate()}, speakers=${it.numSpeakers()}")
            }
            sherpaReady = true
        }
    }

    private fun copyTtsAssets(context: Context): File {
        val outDir = File(context.filesDir, "tts")
        if (File(outDir, "model-steps-3.onnx").exists() && File(outDir, "vocos-22khz-univ.onnx").exists()) {
            return outDir
        }
        val am = context.assets
        fun copyRec(assetPath: String, out: File) {
            val children = am.list(assetPath) ?: emptyArray()
            if (children.isEmpty()) {
                out.parentFile?.mkdirs()
                if (!out.exists() || out.length() == 0L) {
                    am.open(assetPath).use { input ->
                        java.io.FileOutputStream(out).use { input.copyTo(it) }
                    }
                }
            } else {
                out.mkdirs()
                for (c in children) copyRec("$assetPath/$c", File(out, c))
            }
        }
        copyRec("tts", outDir)
        Log.i(TAG, "tts assets copied to ${outDir.absolutePath}")
        return outDir
    }

    // =========================================================================
    // 指标
    // =========================================================================
    private fun footprintMB(): Double =
        (Debug.getNativeHeapAllocatedSize() +
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0

    private fun recordMetrics(
        family: Family,
        synthMs: Long,
        pcm16k: ByteArray,
        modelSr: Int,
        rssBefore: Double,
    ) {
        val audioMs = (pcm16k.size.toLong() * 1000) / (2 * 16000)
        val m = Metrics(
            family = family,
            synthMs = synthMs,
            audioMs = audioMs,
            rtf = if (audioMs > 0) synthMs.toFloat() / audioMs else Float.MAX_VALUE,
            peakRssMB = maxOf(rssBefore, footprintMB()),
            pcm16kBytes = pcm16k.size,
            modelSampleRate = modelSr,
        )
        lastMetrics = m
        Log.i(TAG, m.shortDesc())
    }

    /** Float PCM @srcRate → 16kHz mono PCM16 LE 字节（线性重采样，够 demo 对比用）。 */
    fun resampleFloatTo16kPcm(x: FloatArray, srcRate: Int): ByteArray {
        if (srcRate == 16000) {
            return toPcm16(x)
        }
        val outFrames = (x.size.toLong() * 16000 / srcRate).toInt()
        val y = FloatArray(outFrames)
        for (i in 0 until outFrames) {
            val pos = i.toDouble() * srcRate / 16000
            val i0 = pos.toInt().coerceAtMost(x.size - 1)
            val i1 = (i0 + 1).coerceAtMost(x.size - 1)
            val frac = (pos - i0).toFloat()
            y[i] = x[i0] * (1 - frac) + x[i1] * frac
        }
        return toPcm16(y)
    }

    private fun toPcm16(x: FloatArray): ByteArray {
        val out = ByteArray(x.size * 2)
        java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().apply {
                for (i in x.indices) {
                    put((x[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                }
            }
        return out
    }
}

/** WAV 解析 + 重采样工具（SYSTEM 分支从 48k WAV 取 PCM 用）。 */
object WavUtil {
    data class Pcm16k(val bytes: ByteArray, val srcRate: Int)

    fun extractPcmResampled(wav: ByteArray, targetRate: Int = 16000): Pcm16k {
        fun u16(o: Int) = (wav[o].toInt() and 0xFF) or (wav[o + 1].toInt() and 0xFF shl 8)
        fun i32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        require(wav.size > 44 && String(wav, 0, 4, Charsets.US_ASCII) == "RIFF") { "not a wav" }
        var pos = 12
        var sampleRate = 22050
        var channels = 1
        var bits = 16
        var pcm: ByteArray = ByteArray(0)
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = i32(pos + 4)
            if (id == "fmt ") {
                channels = u16(pos + 10)
                sampleRate = i32(pos + 12)
                bits = u16(pos + 22)
            } else if (id == "data") {
                pcm = wav.copyOfRange(pos + 8, minOf(pos + 8 + size, wav.size))
            }
            pos += 8 + size + (size and 1)
        }
        Log.i("WavUtil", "wav: rate=$sampleRate ch=$channels bits=$bits pcm=${pcm.size}B")
        if (bits != 16) return Pcm16k(pcm, sampleRate)
        val frames = pcm.size / (2 * channels)
        if (sampleRate == targetRate && channels == 1) return Pcm16k(pcm, sampleRate)

        // 多声道混单 + 线性重采样
        val mono = ShortArray(frames)
        var o = 0
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) {
                acc += ((pcm[o++].toInt() and 0xFF) or (pcm[o].toInt() shl 8))
                o++
            }
            mono[f] = (acc / channels).toShort()
        }
        if (sampleRate == targetRate) {
            val out = ByteArray(frames * 2)
            java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer().put(mono)
            return Pcm16k(out, sampleRate)
        }
        val outFrames = (frames.toLong() * targetRate / sampleRate).toInt()
        val out = ByteArray(outFrames * 2)
        val bb = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outFrames) {
            val src = (i.toLong() * sampleRate / targetRate).toInt().coerceAtMost(frames - 1)
            bb.putShort(mono[src])
        }
        Log.i("WavUtil", "resampled $sampleRate→$targetRate, $frames→$outFrames frames")
        return Pcm16k(out, sampleRate)
    }
}
