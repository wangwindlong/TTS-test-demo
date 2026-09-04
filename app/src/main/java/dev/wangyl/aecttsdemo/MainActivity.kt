package dev.wangyl.aecttsdemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var engine: AecEngine
    private val logs = mutableStateListOf("ready.")

    // adb 自动化：am start --es mode VC_AEC --es text "..." --el duration 6000
    private data class AutoRun(val mode: String, val text: String, val durationMs: Long, val family: TtsManager.Family)
    private var pendingAuto: AutoRun? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appendLog(if (granted) "permission granted" else "permission DENIED")
            pendingAuto?.let { if (granted) runAuto(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = AecEngine(this)
        setContent { Screen() }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val mode = intent?.getStringExtra("mode") ?: return
        val text = intent.getStringExtra("text") ?: DEFAULT_TEXT
        val dur = intent.getLongExtra("duration", 6000L)
        val famName = intent.getStringExtra("tts")?.uppercase(Locale.US)
        val family = TtsManager.Family.entries.firstOrNull { it.name == famName } ?: TtsManager.Family.SYSTEM
        val auto = AutoRun(mode, text, dur, family)
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) runAuto(auto)
        else {
            pendingAuto = auto
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun runAuto(auto: AutoRun) {
        appendLog("AUTO run: mode=${auto.mode} tts=${auto.family} dur=${auto.durationMs}ms")
        runOne(auto.mode, auto.text, auto.durationMs, auto.family)
    }

    private fun appendLog(s: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logs.add(0, "[$ts] $s")
        if (logs.size > 200) logs.removeAt(logs.lastIndex)
        android.util.Log.i("AecDemo", s)
    }

    private fun runOne(mode: String, text: String, durationMs: Long, family: TtsManager.Family) {
        mainScope.launch {
            appendLog("▶ start [$mode/$family] …")
            try {
                val r = engine.runExperiment(mode, text, durationMs, ttsFamily = family)
                appendLog(
                    "✔ [$mode/$family] aecAvail=${r.aecAvailable} aecOn=${r.aecEnabled} " +
                            "rec=${r.elapsedMs}ms played=${r.playedBytes}B → ${r.wavFile.name}"
                )
                appendCsv(r, family)
                writeLastRun(r)
            } catch (e: Exception) {
                appendLog("✘ [$mode/$family] ${e.message}")
                writeLastRunRaw("""{"mode":"$mode","error":"${e.message}"}""")
            }
        }
    }

    private val mainScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)

    private fun csvFile(): File =
        File(getExternalFilesDir(null), "aec_runs/runs.csv")

    private fun appendCsv(r: AecEngine.RunResult, family: TtsManager.Family) {
        csvFile().parentFile?.mkdirs()
        if (!csvFile().exists()) {
            csvFile().writeText("timestamp,mode,ttsFamily,aecAvailable,aecEnabled,recordedBytes,playedBytes,elapsedMs,file,synthMs,audioMs,rtf,peakRssMB\n")
        }
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val m = TtsManager.lastMetrics
        csvFile().appendText(
            "$ts,${r.mode},${family.name},${r.aecAvailable},${r.aecEnabled},${r.recordedBytes},${r.playedBytes},${r.elapsedMs},${r.wavFile.name}," +
                    "${m?.synthMs ?: ""},${m?.audioMs ?: ""},${m?.rtf?.let { String.format(Locale.US, "%.3f", it) } ?: ""},${m?.peakRssMB?.let { String.format(Locale.US, "%.1f", it) } ?: ""}\n"
        )
    }

    private fun lastRunFile(): File =
        File(getExternalFilesDir(null), "aec_runs/last_run.json")

    private fun writeLastRun(r: AecEngine.RunResult) {
        lastRunFile().writeText(
            """{"mode":"${r.mode}","aecAvailable":${r.aecAvailable},"aecEnabled":${r.aecEnabled},""" +
                    """"recordedBytes":${r.recordedBytes},"playedBytes":${r.playedBytes},"elapsedMs":${r.elapsedMs},""" +
                    """"file":"${r.wavFile.name}","error":null}"""
        )
    }

    private fun writeLastRunRaw(json: String) = lastRunFile().writeText(json)

    // =========================================================================
    // Compose UI
    // =========================================================================
    private companion object {
        val MODES = listOf(AecEngine.MODE_MIC, AecEngine.MODE_VC, AecEngine.MODE_VC_AEC, AecEngine.MODE_WEBRTC_AEC, AecEngine.MODE_AI_DENOISE)
        const val DEFAULT_TEXT = "这是一段回声消除测试语音，如果你在录音里还能清楚听到这段话，说明回声没有被消除掉。"
    }

    @Composable
    private fun Screen() {
        var mode by remember { mutableStateOf(AecEngine.MODE_VC_AEC) }
        var family by remember { mutableStateOf(TtsManager.Family.SYSTEM) }
        var text by remember { mutableStateOf(DEFAULT_TEXT) }
        var duration by remember { mutableStateOf(6000f) }
        var aecmDelay by remember { mutableStateOf(AecEngine.DEFAULT_AECM_DELAY_MS.toFloat()) }
        var running by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        // ColorOS 灭屏后对非前台 app 静麦：实验期间保持屏幕常亮
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.DisposableEffect(running) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }

        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("AEC 回声消除验证", fontSize = 20.sp)
                    Text(
                        "硬件AEC(MIC/VC_*) vs WebRTC软件AECM(WEBRTC_AEC)，TTS外放+16k录音",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MODES.forEach { m ->
                            FilterChip(
                                selected = mode == m,
                                onClick = { mode = m },
                                label = { Text(m, fontSize = 11.sp, softWrap = false) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        label = { Text("TTS 文本") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("录音时长: ${duration.toInt()} ms", fontSize = 13.sp)
                    Slider(
                        value = duration, onValueChange = { duration = it },
                        valueRange = 3000f..12000f, steps = 8, // 3s..12s，每格1s
                    )

                    if (mode == AecEngine.MODE_WEBRTC_AEC) {
                        Text("AECM delay(参考信号↔录音延迟估计): ${aecmDelay.toInt()} ms", fontSize = 13.sp)
                        Slider(
                            value = aecmDelay, onValueChange = { aecmDelay = it },
                            valueRange = 20f..300f, steps = 27, // 20..300，每格10ms
                        )
                    }

                    // ---- TTS 系列开关 + 性能指标 ----
                    Text("TTS 引擎系列", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TtsManager.Family.entries.forEach { f ->
                            FilterChip(
                                selected = family == f,
                                onClick = { family = f },
                                label = { Text(f.label, fontSize = 12.sp, softWrap = false) }
                            )
                        }
                    }
                    Text(
                        "指标口径: RTF=合成耗时/音频时长(<1=可实时); 内存=进程峰值(native+Java heap), SYSTEM 引擎跑在 com.google.android.tts 独立进程,本进程内存变化小属正常\n" +
                                TtsManager.shortStatus(),
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                running = true
                                scope.launch {
                                    try {
                                        val r = engine.runExperiment(mode, text, duration.toLong(), aecmDelay.toInt(), family)
                                        appendLog(
                                            "✔ [$mode/$family] aecAvail=${r.aecAvailable} aecOn=${r.aecEnabled} rec=${r.elapsedMs}ms"
                                        )
                                        appendCsv(r, family); writeLastRun(r)
                                    } catch (e: Exception) {
                                        appendLog("✘ [$mode/$family] ${e.message}")
                                        writeLastRunRaw("""{"mode":"$mode","error":"${e.message}"}""")
                                    } finally { running = false }
                                }
                            },
                            enabled = !running
                        ) { Text(if (running) "运行中…" else "开始实验") }

                        OutlinedButton(
                            onClick = {
                                running = true
                                scope.launch {
                                    MODES.forEach { m ->
                                        try {
                                            val r = engine.runExperiment(m, text, duration.toLong(), aecmDelay.toInt(), family)
                                            appendLog(
                                                "✔ [$m/$family] aecAvail=${r.aecAvailable} aecOn=${r.aecEnabled} rec=${r.elapsedMs}ms"
                                            )
                                            appendCsv(r, family); writeLastRun(r)
                                        } catch (e: Exception) {
                                            appendLog("✘ [$m/$family] ${e.message}")
                                            writeLastRunRaw("""{"mode":"$m","error":"${e.message}"}""")
                                        }
                                        kotlinx.coroutines.delay(800)
                                    }
                                    running = false
                                }
                            },
                            enabled = !running
                        ) { Text("跑全部5组") }
                    }

                    Text("日志", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    logs.forEach { line ->
                        Text(line, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
