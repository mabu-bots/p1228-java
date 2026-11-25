package p1228;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusSignal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

// ===== AudioFrames (WAV → 48kHz stereo PCM → Opus フレーム) =====
// VOICEVOX が返す WAV (通常 24kHz mono PCM16) を Discord Voice の
// 送信形式 (48kHz / 20ms フレーム = 960 サンプル/チャンネル) に変換する。
// Opus エンコードには純Java移植の Concentus (vendored: lib/concentus-*.jar)
// を使う。Java 標準ライブラリに Opus 実装が無いための例外的な依存で、
// 判断理由は README / CHANGELOG に明記してある。
// Discord の仕様は 48kHz stereo のため、mono は両チャンネルへ複製する。
public final class AudioFrames {

    private AudioFrames() {}

    public static final int TARGET_RATE = 48_000;
    public static final int FRAME_SAMPLES = 960; // 20ms @ 48kHz
    public static final byte[] SILENCE_FRAME = {(byte) 0xF8, (byte) 0xFF, (byte) 0xFE};

    public record PcmAudio(int sampleRate, int channels, short[] samples) {}

    // ----- 1. WAV パース (PCM16 のみ) -----
    public static PcmAudio parseWav(byte[] wav) {
        if (wav == null || wav.length < 44 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F'
                || wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') {
            throw new IllegalArgumentException("not a WAV payload");
        }
        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int channels = -1;
        int sampleRate = -1;
        int bitsPerSample = -1;
        int offset = 12;
        byte[] data = null;
        while (offset + 8 <= wav.length) {
            String id = new String(wav, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long size = Integer.toUnsignedLong(buf.getInt(offset + 4));
            int body = offset + 8;
            if (body + size > wav.length) size = wav.length - body;
            if (id.equals("fmt ")) {
                int format = Short.toUnsignedInt(buf.getShort(body));
                if (format != 1) throw new IllegalArgumentException("WAV is not PCM (format=" + format + ")");
                channels = Short.toUnsignedInt(buf.getShort(body + 2));
                sampleRate = buf.getInt(body + 4);
                bitsPerSample = Short.toUnsignedInt(buf.getShort(body + 14));
            } else if (id.equals("data")) {
                data = new byte[(int) size];
                System.arraycopy(wav, body, data, 0, (int) size);
            }
            offset = (int) (body + size + (size % 2));
        }
        if (data == null || channels <= 0 || sampleRate <= 0 || bitsPerSample != 16) {
            throw new IllegalArgumentException("unsupported WAV (channels=" + channels
                    + ", rate=" + sampleRate + ", bits=" + bitsPerSample + ")");
        }
        int count = data.length / 2;
        short[] samples = new short[count];
        ByteBuffer dataBuf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) samples[i] = dataBuf.getShort(i * 2);
        return new PcmAudio(sampleRate, channels, samples);
    }

    // ----- 2. リサンプル (windowed-sinc, Blackman 窓) -----
    // 任意レート変換。アップサンプル時のイメージとダウンサンプル時の
    // エイリアスを抑えるため、カットオフは min(1, out/in) 側に取る。
    public static short[] resample(short[] input, int fromRate, int toRate) {
        if (fromRate == toRate) return input.clone();
        int outLength = (int) Math.round((double) input.length * toRate / fromRate);
        short[] out = new short[outLength];
        double ratio = (double) fromRate / toRate; // 出力1サンプルあたりの入力サンプル数
        double cutoff = Math.min(1.0, 1.0 / ratio); // 入力レート基準の正規化カットオフ
        int halfTaps = 16;
        for (int i = 0; i < outLength; i++) {
            double center = i * ratio;
            int start = Math.max(0, (int) Math.ceil(center - halfTaps / cutoff));
            int end = Math.min(input.length - 1, (int) Math.floor(center + halfTaps / cutoff));
            double sum = 0;
            double weightSum = 0;
            for (int k = start; k <= end; k++) {
                double x = (k - center) * cutoff;
                double sinc = x == 0 ? 1.0 : Math.sin(Math.PI * x) / (Math.PI * x);
                double t = (k - center) / (halfTaps / cutoff);
                double window = 0.42 + 0.5 * Math.cos(Math.PI * t) + 0.08 * Math.cos(2 * Math.PI * t);
                double w = sinc * window;
                sum += input[k] * w;
                weightSum += w;
            }
            double value = weightSum == 0 ? 0 : sum / weightSum * cutoff;
            out[i] = clampShort(Math.round(value));
        }
        return out;
    }

    private static short clampShort(long value) {
        if (value > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (value < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) value;
    }

    // ----- 3. 48kHz stereo 化 -----
    public static short[] toStereo48k(PcmAudio audio) {
        int frames = audio.samples().length / audio.channels();
        short[] left = new short[frames];
        short[] right = new short[frames];
        for (int i = 0; i < frames; i++) {
            left[i] = audio.samples()[i * audio.channels()];
            right[i] = audio.channels() > 1 ? audio.samples()[i * audio.channels() + 1] : left[i];
        }
        short[] leftResampled = resample(left, audio.sampleRate(), TARGET_RATE);
        short[] rightResampled = audio.channels() > 1
                ? resample(right, audio.sampleRate(), TARGET_RATE) : leftResampled;
        int outFrames = Math.min(leftResampled.length, rightResampled.length);
        short[] stereo = new short[outFrames * 2];
        for (int i = 0; i < outFrames; i++) {
            stereo[i * 2] = leftResampled[i];
            stereo[i * 2 + 1] = rightResampled[i];
        }
        return stereo;
    }

    // ----- 4. Opus エンコード (20ms フレーム列) -----
    public static List<byte[]> encodeWavToOpusFrames(byte[] wav) throws Exception {
        short[] stereo = toStereo48k(parseWav(wav));
        OpusEncoder encoder = new OpusEncoder(TARGET_RATE, 2, OpusApplication.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(64_000);
        encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        encoder.setComplexity(5);
        encoder.setUseVBR(true);
        List<byte[]> frames = new ArrayList<>();
        short[] pcmFrame = new short[FRAME_SAMPLES * 2];
        byte[] outBuffer = new byte[1275];
        int totalFrames = stereo.length / 2;
        for (int pos = 0; pos < totalFrames; pos += FRAME_SAMPLES) {
            int copy = Math.min(FRAME_SAMPLES, totalFrames - pos);
            System.arraycopy(stereo, pos * 2, pcmFrame, 0, copy * 2);
            if (copy < FRAME_SAMPLES) {
                java.util.Arrays.fill(pcmFrame, copy * 2, pcmFrame.length, (short) 0);
            }
            int written = encoder.encode(pcmFrame, 0, FRAME_SAMPLES, outBuffer, 0, outBuffer.length);
            if (written < 0) throw new IllegalStateException("Opus encode failed: " + written);
            frames.add(java.util.Arrays.copyOf(outBuffer, written));
        }
        return frames;
    }
}
