#!/usr/bin/env python3
"""
AEC 效果定量验证：五种模式录音中 TTS 回声的能量对比。

模式说明:
  MIC         无处理基线 → 回声能量 = E_mic
  VC_NO_AEC   VOICE_COMMUNICATION 音源但 AEC 关
  VC_AEC      硬件 AcousticEchoCanceler
  WEBRTC_AEC  软件 AECM(参考信号显式喂入)
  AI_DENOISE  GTCRN 人声降噪(无参考盲分离)

报告各模式在 TTS 播放窗内 300-3400Hz 语音频段 RMS 相对 MIC 的残留比。
"""
import sys
import wave
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

plt.rcParams["font.family"] = ["WenQuanYi Zen Hei", "Noto Sans CJK SC", "sans-serif"]

MODES = ["MIC", "VC_NO_AEC", "VC_AEC", "WEBRTC_AEC", "AI_DENOISE"]


def load_wav(path):
    with wave.open(path, "rb") as w:
        sr = w.getframerate()
        ch = w.getnchannels()
        sw = w.getsampwidth()
        raw = w.readframes(w.getnframes())
    assert sw == 2, f"expect 16bit, got {sw*8}bit"
    x = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
    if ch > 1:
        x = x.reshape(-1, ch).mean(axis=1)
    return x, sr


def speech_band_rms(x, sr, f1=300.0, f2=3400.0):
    """300-3400Hz 语音频段 RMS（TTS 回声的主要能量区）"""
    if len(x) == 0:
        return 0.0
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1.0 / sr)
    mask = (freqs >= f1) & (freqs <= f2)
    filtered = np.fft.irfft(spec * mask, n=len(x))
    return float(np.sqrt(np.mean(filtered ** 2)) + 1e-12)


def analyze(run_dir):
    waves, srs = {}, None
    for m in MODES:
        p = f"{run_dir}/rec_{m}.wav"
        try:
            waves[m], srs = load_wav(p)
        except Exception as e:
            print(f"[WARN] cannot load {p}: {e}")
    if "MIC" not in waves or srs is None:
        print("[ERROR] need at least rec_MIC.wav as baseline")
        sys.exit(1)

    # 取共同长度，找 TTS 播放窗（MIC 基线里能量最高的连续 2s）
    L = min(len(w) for w in waves.values())
    mic = waves["MIC"][:L]
    win = int(0.5 * srs)
    if len(mic) > win:
        energy = np.convolve(mic ** 2, np.ones(win), mode="valid")
        peak = int(np.argmax(energy))
        start = max(0, peak - win // 2)
    else:
        start = 0
    seg_len = min(win * 4, L - start)
    seg = lambda w: w[start: start + seg_len]

    print(f"\n=== AEC 定量对比 (窗口 {start/srs:.2f}s 起 {seg_len/srs:.1f}s, {srs}Hz) ===")
    print(f"{'mode':<14} {'语音频段RMS':>12} {'相对MIC':>10}")
    base = speech_band_rms(seg(mic), srs)
    result = {}
    for m in MODES:
        if m not in waves:
            continue
        e = speech_band_rms(seg(waves[m][:L]), srs)
        result[m] = e
        print(f"{m:<14} {e:>12.5f} {e/base:>9.1%}")

    if "VC_AEC" in result:
        print(f"\n>>> 硬件AEC残留率   (VC_AEC/MIC)     = {result['VC_AEC']/base:.1%}")
    if "WEBRTC_AEC" in result:
        print(f">>> 软件AECM残留率  (WEBRTC_AEC/MIC) = {result['WEBRTC_AEC']/base:.1%}")
    if "AI_DENOISE" in result:
        print(f">>> GTCRN降噪残留率 (AI_DENOISE/MIC) = {result['AI_DENOISE']/base:.1%}")

    # 语谱图
    avail = [m for m in MODES if m in waves]
    fig, axes = plt.subplots(len(avail), 1, figsize=(10, 2.6 * len(avail)), sharex=True)
    if len(avail) == 1:
        axes = [axes]
    for ax, m in zip(axes, avail):
        w = waves[m][:L]
        ax.specgram(w, NFFT=512, Fs=srs, noverlap=384, cmap="magma")
        ax.set_title(f"{m}  (speech-band RMS={result.get(m, 0):.4f})")
        ax.set_ylabel("Hz")
    axes[-1].set_xlabel("s")
    fig.suptitle("TTS 回声消除五方案对比（亮纹=TTS 回声能量）")
    fig.tight_layout()
    out = f"{run_dir}/aec_compare.png"
    fig.savefig(out, dpi=110)
    print(f"[saved] {out}")


if __name__ == "__main__":
    analyze(sys.argv[1] if len(sys.argv) > 1 else ".")
