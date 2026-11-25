package p1228;

import java.util.Arrays;
import java.util.function.Consumer;

// ===== DaveSession (DAVE プロトコルの状態機械) =====
// Voice Gateway の DAVE 用 opcode (21〜31) と libdave セッションの間の
// 変換を担う。振る舞いは @discordjs/voice の DAVESession 実装に合わせた:
//   - OP24 (epoch=1) でセッション再初期化 → 新しい Key Package を OP26 で送る
//   - OP25 で external sender を設定する
//   - OP27 の proposals は operation_type バイト込みで libdave へ渡し、
//     返った commit||welcome を OP28 で送る
//   - OP29/OP30 は transition_id (uint16 BE) を剥がして libdave へ渡し、
//     成功したら OP23、失敗したら OP31 + 再初期化で復旧する
//   - OP21 は pending transition として保持し、tid=0 は即時実行、
//     それ以外は OP23 を返す。OP22 で実際に切り替える
// 暗号化の可否は「交渉済みバージョン > 0 かつ鍵ラチェット確立済み」で
// 決まる。それ以外 (バージョン 0 へのダウングレード含む) は素通しになる。
public final class DaveSession {

    public interface Sink {
        void sendKeyPackage(byte[] keyPackage);

        void sendCommitWelcome(byte[] commitWelcome);

        void sendReadyForTransition(int transitionId);

        void sendInvalidCommitWelcome(int transitionId);
    }

    private final DaveNative nativeLib;
    private final long groupId;
    private final String selfUserId;
    private final Sink sink;
    private final Consumer<String> logger;

    private DaveNative.Session session;
    private DaveNative.Encryptor encryptor;
    private byte[] externalSender;
    private int protocolVersion;
    private int pendingTransitionId = -1;
    private int pendingTransitionVersion = -1;
    private int lastTransitionId = -1;
    private boolean downgraded = false;
    private boolean reinitializing = false;
    private boolean ready = false;
    private int ssrc = 0;

    public DaveSession(DaveNative nativeLib, int protocolVersion, long groupId, String selfUserId,
                       Sink sink, Consumer<String> logger) {
        this.nativeLib = nativeLib;
        this.protocolVersion = protocolVersion;
        this.groupId = groupId;
        this.selfUserId = selfUserId;
        this.sink = sink;
        this.logger = logger != null ? logger : line -> {};
    }

    public synchronized void setSsrc(int ssrc) {
        this.ssrc = ssrc;
        if (encryptor != null && ssrc != 0) nativeLib.encryptorAssignSsrc(encryptor, ssrc);
    }

    public synchronized int protocolVersion() {
        return protocolVersion;
    }

    public synchronized boolean isReady() {
        return ready && protocolVersion > 0;
    }

    // セッションと encryptor を用意し、Key Package を送る (OP26)。
    public synchronized void init() {
        if (protocolVersion <= 0) return;
        session = nativeLib.createSession(protocolVersion, groupId, selfUserId);
        encryptor = nativeLib.createEncryptor();
        if (ssrc != 0) nativeLib.encryptorAssignSsrc(encryptor, ssrc);
        emitKeyPackage();
    }

    private void emitKeyPackage() {
        byte[] keyPackage = nativeLib.getKeyPackage(session);
        if (keyPackage != null && keyPackage.length > 0) sink.sendKeyPackage(keyPackage);
    }

    // OP24 (epoch=1) や OP31 復旧時の再初期化。recognized users は
    // libdave 側で reset をまたいで保持される。
    public synchronized void reinit() {
        if (session == null) {
            init();
            return;
        }
        reinitializing = true;
        ready = false;
        nativeLib.resetSession(session, protocolVersion, groupId, selfUserId);
        if (externalSender != null) nativeLib.setExternalSender(session, externalSender);
        refreshRatchet();
        emitKeyPackage();
    }

    public synchronized void onExternalSender(byte[] payload) {
        externalSender = payload.clone();
        if (session != null) nativeLib.setExternalSender(session, payload);
    }

    public synchronized void addRecognizedUser(String userId) {
        if (session != null && userId != null && !userId.isBlank()) nativeLib.addRecognizedUser(session, userId);
    }

    public synchronized void removeRecognizedUser(String userId) {
        if (session != null && userId != null && !userId.isBlank()) nativeLib.removeRecognizedUser(session, userId);
    }

    // OP27: ペイロードは [operation_type][MLSMessage...] の形で届く。
    public synchronized void onProposals(byte[] payload) {
        if (session == null) return;
        byte[] commitWelcome = nativeLib.processProposals(session, payload);
        if (commitWelcome != null && commitWelcome.length > 0) sink.sendCommitWelcome(commitWelcome);
    }

    // OP29: ペイロードは [transition_id: uint16 BE][commit] の形で届く。
    public synchronized void onCommit(byte[] payload) {
        if (session == null || payload.length < 2) return;
        int transitionId = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        byte[] commit = Arrays.copyOfRange(payload, 2, payload.length);
        DaveNative.CommitOutcome outcome;
        try {
            outcome = nativeLib.processCommit(session, commit);
        } catch (Exception e) {
            logger.accept("[dave] processCommit error: " + e.getMessage());
            outcome = DaveNative.CommitOutcome.FAILED;
        }
        if (outcome == DaveNative.CommitOutcome.FAILED) {
            recoverFromInvalidTransition(transitionId);
            return;
        }
        refreshRatchet();
        if (transitionId == 0) {
            reinitializing = false;
            lastTransitionId = 0;
        } else {
            pendingTransitionId = transitionId;
            pendingTransitionVersion = protocolVersion;
            sink.sendReadyForTransition(transitionId);
        }
    }

    // OP30: ペイロードは [transition_id: uint16 BE][welcome] の形で届く。
    public synchronized void onWelcome(byte[] payload) {
        if (session == null || payload.length < 2) return;
        int transitionId = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        byte[] welcome = Arrays.copyOfRange(payload, 2, payload.length);
        boolean success;
        try {
            success = nativeLib.processWelcome(session, welcome);
        } catch (Exception e) {
            logger.accept("[dave] processWelcome error: " + e.getMessage());
            success = false;
        }
        if (!success) {
            recoverFromInvalidTransition(transitionId);
            return;
        }
        refreshRatchet();
        if (transitionId == 0) {
            reinitializing = false;
            lastTransitionId = 0;
        } else {
            pendingTransitionId = transitionId;
            pendingTransitionVersion = protocolVersion;
            sink.sendReadyForTransition(transitionId);
        }
    }

    private void recoverFromInvalidTransition(int transitionId) {
        if (reinitializing) return;
        logger.accept("[dave] invalid commit/welcome for transition " + transitionId + "; reinitializing");
        reinitializing = true;
        sink.sendInvalidCommitWelcome(transitionId);
        reinit();
    }

    // OP21: 戻り値は OP23 を送るべきか。
    public synchronized boolean onPrepareTransition(int transitionId, int version) {
        pendingTransitionId = transitionId;
        pendingTransitionVersion = version;
        if (transitionId == 0) {
            executeTransition(0);
            return false;
        }
        if (version == 0 && encryptor != null) {
            nativeLib.encryptorSetPassthroughMode(encryptor, true);
        }
        return true;
    }

    // OP22
    public synchronized boolean onExecuteTransition(int transitionId) {
        return executeTransition(transitionId);
    }

    private boolean executeTransition(int transitionId) {
        if (pendingTransitionId < 0 || transitionId != pendingTransitionId) {
            logger.accept("[dave] execute transition for unexpected id " + transitionId
                    + " (pending=" + pendingTransitionId + ")");
            return false;
        }
        int oldVersion = protocolVersion;
        protocolVersion = pendingTransitionVersion;
        pendingTransitionId = -1;
        pendingTransitionVersion = -1;
        if (protocolVersion == 0) {
            downgraded = true;
            if (encryptor != null) nativeLib.encryptorSetPassthroughMode(encryptor, true);
        } else {
            if (encryptor != null) nativeLib.encryptorSetPassthroughMode(encryptor, false);
            if (downgraded) downgraded = false;
            refreshRatchet();
        }
        reinitializing = false;
        lastTransitionId = transitionId;
        logger.accept("[dave] transition executed v" + oldVersion + " -> v" + protocolVersion
                + " (id=" + transitionId + ")");
        return true;
    }

    // OP24
    public synchronized void onPrepareEpoch(long epoch, int version) {
        if (epoch == 1) {
            protocolVersion = version;
            if (session == null) init();
            else reinit();
        }
    }

    private void refreshRatchet() {
        if (session == null || encryptor == null) {
            ready = false;
            return;
        }
        try {
            ready = nativeLib.refreshRatchet(session, encryptor, selfUserId);
        } catch (Exception e) {
            logger.accept("[dave] key ratchet refresh failed: " + e.getMessage());
            ready = false;
        }
    }

    // Opus フレームへ DAVE 暗号化を適用する。未確立・バージョン0・
    // 無音フレームは素通し (プロトコルの passthrough 規則どおり)。
    public synchronized byte[] encrypt(byte[] opusFrame) {
        if (protocolVersion == 0 || !ready || encryptor == null) return opusFrame;
        if (Arrays.equals(opusFrame, AudioFrames.SILENCE_FRAME)) return opusFrame;
        return nativeLib.encryptFrame(encryptor, ssrc, opusFrame);
    }

    public synchronized void close() {
        if (encryptor != null) {
            encryptor.close();
            encryptor = null;
        }
        if (session != null) {
            session.close();
            session = null;
        }
        ready = false;
    }
}
