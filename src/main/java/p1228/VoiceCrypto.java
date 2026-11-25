package p1228;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

// ===== VoiceCrypto (Discord Voice のトランスポート暗号と RTP) =====
// 現行の Discord Voice が要求する rtpsize 系モードのみを実装する。
// 旧来の xsalsa20_poly1305* は 2024-11-18 に廃止済みのため実装しない。
//   - aead_aes256_gcm_rtpsize        : JCE の AES/GCM をそのまま使う
//   - aead_xchacha20_poly1305_rtpsize: HChaCha20 (自前実装) でサブ鍵を
//     導出し、JCE の ChaCha20-Poly1305 (RFC 8439) を内側に使う
// ワイヤ形式は [RTPヘッダ 12B][暗号文][認証タグ 16B][nonce カウンタ 4B]。
// nonce は 4B カウンタ (big-endian) + 残りゼロ埋め (GCM は 12B,
// XChaCha は 24B)。AAD は RTP ヘッダそのもの。
// DAVE (フレーム単位 E2EE) はこの層の内側で別途適用される (DaveSession)。
public final class VoiceCrypto {

    private VoiceCrypto() {}

    public static final String MODE_AES_GCM = "aead_aes256_gcm_rtpsize";
    public static final String MODE_XCHACHA = "aead_xchacha20_poly1305_rtpsize";

    // ----- 1. HChaCha20 (draft-irtf-cfrg-xchacha-03 §2.2) -----
    // ChaCha の quarter-round を 20 ラウンド回し、初期状態を加算せずに
    // 状態の 0..3 行目と 12..15 行目を取り出して little-endian で返す。
    public static byte[] hchacha20(byte[] key32, byte[] nonce16) {
        if (key32.length != 32 || nonce16.length != 16) {
            throw new IllegalArgumentException("key must be 32 bytes, nonce 16 bytes");
        }
        int[] state = new int[16];
        state[0] = 0x61707865;
        state[1] = 0x3320646e;
        state[2] = 0x79622d32;
        state[3] = 0x6b206574;
        ByteBuffer keyBuf = ByteBuffer.wrap(key32).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 8; i++) state[4 + i] = keyBuf.getInt(i * 4);
        ByteBuffer nonceBuf = ByteBuffer.wrap(nonce16).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) state[12 + i] = nonceBuf.getInt(i * 4);
        for (int round = 0; round < 10; round++) {
            quarterRound(state, 0, 4, 8, 12);
            quarterRound(state, 1, 5, 9, 13);
            quarterRound(state, 2, 6, 10, 14);
            quarterRound(state, 3, 7, 11, 15);
            quarterRound(state, 0, 5, 10, 15);
            quarterRound(state, 1, 6, 11, 12);
            quarterRound(state, 2, 7, 8, 13);
            quarterRound(state, 3, 4, 9, 14);
        }
        ByteBuffer out = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) out.putInt(state[i]);
        for (int i = 0; i < 4; i++) out.putInt(state[12 + i]);
        return out.array();
    }

    private static void quarterRound(int[] x, int a, int b, int c, int d) {
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
    }

    // ----- 2. XChaCha20-Poly1305 (AEAD, 24B nonce) -----
    // 戻り値は 暗号文 || タグ16B (libsodium の combined 形式)。
    public static byte[] xchachaEncrypt(byte[] key32, byte[] nonce24, byte[] aad, byte[] plaintext)
            throws GeneralSecurityException {
        if (nonce24.length != 24) throw new IllegalArgumentException("nonce must be 24 bytes");
        byte[] subkey = hchacha20(key32, Arrays.copyOfRange(nonce24, 0, 16));
        byte[] ietfNonce = new byte[12];
        System.arraycopy(nonce24, 16, ietfNonce, 4, 8);
        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(subkey, "ChaCha20"), new IvParameterSpec(ietfNonce));
        if (aad != null && aad.length > 0) cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    public static byte[] xchachaDecrypt(byte[] key32, byte[] nonce24, byte[] aad, byte[] ciphertextAndTag)
            throws GeneralSecurityException {
        if (nonce24.length != 24) throw new IllegalArgumentException("nonce must be 24 bytes");
        byte[] subkey = hchacha20(key32, Arrays.copyOfRange(nonce24, 0, 16));
        byte[] ietfNonce = new byte[12];
        System.arraycopy(nonce24, 16, ietfNonce, 4, 8);
        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(subkey, "ChaCha20"), new IvParameterSpec(ietfNonce));
        if (aad != null && aad.length > 0) cipher.updateAAD(aad);
        return cipher.doFinal(ciphertextAndTag);
    }

    // ----- 3. AES-256-GCM (AEAD, 12B nonce) -----
    public static byte[] aesGcmEncrypt(byte[] key32, byte[] nonce12, byte[] aad, byte[] plaintext)
            throws GeneralSecurityException {
        if (nonce12.length != 12) throw new IllegalArgumentException("nonce must be 12 bytes");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key32, "AES"), new GCMParameterSpec(128, nonce12));
        if (aad != null && aad.length > 0) cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    public static byte[] aesGcmDecrypt(byte[] key32, byte[] nonce12, byte[] aad, byte[] ciphertextAndTag)
            throws GeneralSecurityException {
        if (nonce12.length != 12) throw new IllegalArgumentException("nonce must be 12 bytes");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key32, "AES"), new GCMParameterSpec(128, nonce12));
        if (aad != null && aad.length > 0) cipher.updateAAD(aad);
        return cipher.doFinal(ciphertextAndTag);
    }

    // ----- 4. rtpsize パケット組み立て -----
    // 1セッション分の送信状態 (nonce カウンタ) を保持する。
    public static final class RtpEncryptor {
        private final String mode;
        private final byte[] secretKey;
        private int nonceCounter = 0;

        public RtpEncryptor(String mode, byte[] secretKey) {
            if (!MODE_AES_GCM.equals(mode) && !MODE_XCHACHA.equals(mode)) {
                throw new IllegalArgumentException("unsupported encryption mode: " + mode);
            }
            if (secretKey == null || secretKey.length != 32) {
                throw new IllegalArgumentException("secret_key must be 32 bytes");
            }
            this.mode = mode;
            this.secretKey = secretKey.clone();
        }

        public String mode() {
            return mode;
        }

        // RTP ヘッダ (AAD) と DAVE 適用済みフレームから送信用パケットを作る。
        public synchronized byte[] encryptPacket(byte[] rtpHeader12, byte[] frame) throws GeneralSecurityException {
            nonceCounter++;
            int nonceSize = MODE_AES_GCM.equals(mode) ? 12 : 24;
            byte[] nonce = new byte[nonceSize];
            ByteBuffer.wrap(nonce).order(ByteOrder.BIG_ENDIAN).putInt(0, nonceCounter);
            byte[] encrypted = MODE_AES_GCM.equals(mode)
                    ? aesGcmEncrypt(secretKey, nonce, rtpHeader12, frame)
                    : xchachaEncrypt(secretKey, nonce, rtpHeader12, frame);
            ByteBuffer packet = ByteBuffer.allocate(rtpHeader12.length + encrypted.length + 4);
            packet.put(rtpHeader12);
            packet.put(encrypted);
            packet.put(Arrays.copyOfRange(nonce, 0, 4));
            return packet.array();
        }

        // 受信側 (単体テストと将来の受信用)。末尾 4B を nonce カウンタとして読む。
        public static byte[] decryptPacket(String mode, byte[] secretKey, byte[] packet) throws GeneralSecurityException {
            byte[] header = Arrays.copyOfRange(packet, 0, 12);
            byte[] suffix = Arrays.copyOfRange(packet, packet.length - 4, packet.length);
            byte[] body = Arrays.copyOfRange(packet, 12, packet.length - 4);
            if (MODE_AES_GCM.equals(mode)) {
                byte[] nonce = new byte[12];
                System.arraycopy(suffix, 0, nonce, 0, 4);
                return aesGcmDecrypt(secretKey, nonce, header, body);
            }
            byte[] nonce = new byte[24];
            System.arraycopy(suffix, 0, nonce, 0, 4);
            return xchachaDecrypt(secretKey, nonce, header, body);
        }
    }

    // ----- 5. RTP ヘッダ -----
    // 固定部 12B: V=2/P=0/X=0/CC=0, M=0/PT=120 (Discord の Opus 固定値)。
    public static byte[] rtpHeader(int sequence, int timestamp, int ssrc) {
        ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
        header.put((byte) 0x80);
        header.put((byte) 0x78);
        header.putShort((short) (sequence & 0xFFFF));
        header.putInt(timestamp);
        header.putInt(ssrc);
        return header.array();
    }
}
