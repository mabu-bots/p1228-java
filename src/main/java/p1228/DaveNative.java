package p1228;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

// ===== DaveNative (libdave の FFM バインディング) =====
// Discord 公式の DAVE 実装 libdave (C API, dave.h) を Java 25 の
// Foreign Function & Memory API で直接呼ぶ。MLS (RFC 9420) の自前実装は
// 現実的でないため、公式ネイティブライブラリを使う判断をした
// (経緯は README / CHANGELOG に明記)。
// 配置: lib/native/linux-x86-64/libdave.so を同梱し、探索順は
// システムプロパティ p1228.libdave → jar 隣接の lib/ →
// クラスパスリソース /native/linux-x86-64/libdave.so (一時展開)。
// 実行には --enable-native-access=ALL-UNNAMED (jar のマニフェスト
// Enable-Native-Access でも指定) が必要。
public final class DaveNative {

    public static final int CODEC_OPUS = 1;
    public static final int MEDIA_TYPE_AUDIO = 0;
    public static final int ENCRYPTOR_SUCCESS = 0;

    private static final AtomicReference<DaveNative> INSTANCE = new AtomicReference<>();
    private static final AtomicReference<String> LOAD_ERROR = new AtomicReference<>();

    private final Linker linker;
    private final MethodHandle maxSupportedProtocolVersion;
    private final MethodHandle free;
    private final MethodHandle sessionCreate;
    private final MethodHandle sessionDestroy;
    private final MethodHandle sessionInit;
    private final MethodHandle sessionReset;
    private final MethodHandle sessionSetExternalSender;
    private final MethodHandle sessionAddRecognizedUser;
    private final MethodHandle sessionRemoveRecognizedUser;
    private final MethodHandle sessionProcessProposals;
    private final MethodHandle sessionProcessCommit;
    private final MethodHandle sessionProcessWelcome;
    private final MethodHandle sessionGetMarshalledKeyPackage;
    private final MethodHandle sessionGetKeyRatchet;
    private final MethodHandle commitResultIsFailed;
    private final MethodHandle commitResultIsIgnored;
    private final MethodHandle commitResultDestroy;
    private final MethodHandle welcomeResultDestroy;
    private final MethodHandle keyRatchetDestroy;
    private final MethodHandle encryptorCreate;
    private final MethodHandle encryptorDestroy;
    private final MethodHandle encryptorSetKeyRatchet;
    private final MethodHandle encryptorSetPassthroughMode;
    private final MethodHandle encryptorAssignSsrcToCodec;
    private final MethodHandle encryptorGetMaxCiphertextByteSize;
    private final MethodHandle encryptorHasKeyRatchet;
    private final MethodHandle encryptorEncrypt;

    private DaveNative(Path libraryPath) {
        linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.libraryLookup(libraryPath, Arena.global());
        maxSupportedProtocolVersion = handle(lookup, "daveMaxSupportedProtocolVersion",
                FunctionDescriptor.of(ValueLayout.JAVA_SHORT));
        free = handle(lookup, "daveFree", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        sessionCreate = handle(lookup, "daveSessionCreate", FunctionDescriptor.of(ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        sessionDestroy = handle(lookup, "daveSessionDestroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        sessionInit = handle(lookup, "daveSessionInit", FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        sessionReset = handle(lookup, "daveSessionReset", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        sessionSetExternalSender = handle(lookup, "daveSessionSetExternalSender", FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        sessionAddRecognizedUser = handle(lookup, "daveSessionAddRecognizedUser",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        sessionRemoveRecognizedUser = handle(lookup, "daveSessionRemoveRecognizedUser",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        sessionProcessProposals = handle(lookup, "daveSessionProcessProposals", FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        sessionProcessCommit = handle(lookup, "daveSessionProcessCommit", FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        sessionProcessWelcome = handle(lookup, "daveSessionProcessWelcome", FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        sessionGetMarshalledKeyPackage = handle(lookup, "daveSessionGetMarshalledKeyPackage",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        sessionGetKeyRatchet = handle(lookup, "daveSessionGetKeyRatchet", FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        commitResultIsFailed = handle(lookup, "daveCommitResultIsFailed",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));
        commitResultIsIgnored = handle(lookup, "daveCommitResultIsIgnored",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));
        commitResultDestroy = handle(lookup, "daveCommitResultDestroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        welcomeResultDestroy = handle(lookup, "daveWelcomeResultDestroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        keyRatchetDestroy = handle(lookup, "daveKeyRatchetDestroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        encryptorCreate = handle(lookup, "daveEncryptorCreate", FunctionDescriptor.of(ValueLayout.ADDRESS));
        encryptorDestroy = handle(lookup, "daveEncryptorDestroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        encryptorSetKeyRatchet = handle(lookup, "daveEncryptorSetKeyRatchet",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        encryptorSetPassthroughMode = handle(lookup, "daveEncryptorSetPassthroughMode",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
        encryptorAssignSsrcToCodec = handle(lookup, "daveEncryptorAssignSsrcToCodec",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        encryptorGetMaxCiphertextByteSize = handle(lookup, "daveEncryptorGetMaxCiphertextByteSize",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
        encryptorHasKeyRatchet = handle(lookup, "daveEncryptorHasKeyRatchet",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));
        encryptorEncrypt = handle(lookup, "daveEncryptorEncrypt", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
    }

    private MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = lookup.find(name)
                .orElseThrow(() -> new IllegalStateException("libdave symbol not found: " + name));
        return linker.downcallHandle(symbol, descriptor);
    }

    // ----- 1. ライブラリ探索とロード -----
    public static DaveNative get() {
        DaveNative existing = INSTANCE.get();
        if (existing != null) return existing;
        synchronized (DaveNative.class) {
            existing = INSTANCE.get();
            if (existing != null) return existing;
            try {
                DaveNative created = new DaveNative(locateLibrary());
                INSTANCE.set(created);
                return created;
            } catch (Throwable t) {
                LOAD_ERROR.set(String.valueOf(t.getMessage()));
                throw new IllegalStateException("libdave load failed: " + t.getMessage(), t);
            }
        }
    }

    public static boolean isAvailable() {
        try {
            get();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String loadError() {
        return LOAD_ERROR.get();
    }

    static Path locateLibrary() throws Exception {
        String property = System.getProperty("p1228.libdave");
        if (property != null && !property.isBlank() && Files.isRegularFile(Path.of(property))) {
            return Path.of(property);
        }
        Path adjacent = Path.of("lib", "native", "linux-x86-64", "libdave.so");
        if (Files.isRegularFile(adjacent)) return adjacent.toAbsolutePath();
        try (var stream = DaveNative.class.getResourceAsStream("/native/linux-x86-64/libdave.so")) {
            if (stream != null) {
                Path temp = Files.createTempFile("libdave-", ".so");
                Files.write(temp, stream.readAllBytes());
                temp.toFile().deleteOnExit();
                return temp;
            }
        }
        throw new IllegalStateException("libdave.so not found (property p1228.libdave, ./lib/native, classpath resource)");
    }

    public int maxSupportedProtocolVersion() {
        try {
            return Short.toUnsignedInt((short) maxSupportedProtocolVersion.invokeExact());
        } catch (Throwable t) {
            throw new IllegalStateException("daveMaxSupportedProtocolVersion failed", t);
        }
    }

    private void freeBuffer(MemorySegment pointer) {
        if (pointer == null || MemorySegment.NULL.equals(pointer)) return;
        try {
            free.invokeExact(pointer);
        } catch (Throwable t) {
            throw new IllegalStateException("daveFree failed", t);
        }
    }

    // out パラメータ (uint8_t**, size_t*) で返るバイト列を取り出す共通処理。
    private byte[] readAllocatedBytes(MemorySegment pointerPointer, MemorySegment lengthPointer) {
        MemorySegment pointer = pointerPointer.get(ValueLayout.ADDRESS, 0);
        long length = lengthPointer.get(ValueLayout.JAVA_LONG, 0);
        if (pointer == null || MemorySegment.NULL.equals(pointer) || length <= 0) {
            if (pointer != null && !MemorySegment.NULL.equals(pointer)) freeBuffer(pointer);
            return null;
        }
        try {
            return pointer.reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
        } finally {
            freeBuffer(pointer);
        }
    }

    // ----- 2. セッション -----
    public static final class Session implements AutoCloseable {
        private MemorySegment handle;
        private MemorySegment currentRatchet;

        private Session(MemorySegment handle) {
            this.handle = handle;
        }

        public boolean isOpen() {
            return handle != null && !MemorySegment.NULL.equals(handle);
        }

        @Override
        public synchronized void close() {
            if (!isOpen()) return;
            try {
                DaveNative.get().sessionDestroy.invokeExact(handle);
            } catch (Throwable t) {
                throw new IllegalStateException("daveSessionDestroy failed", t);
            } finally {
                handle = null;
            }
        }
    }

    public Session createSession(int protocolVersion, long groupId, String selfUserId) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment session = (MemorySegment) sessionCreate.invokeExact(
                    MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
            if (session == null || MemorySegment.NULL.equals(session)) {
                throw new IllegalStateException("daveSessionCreate returned NULL");
            }
            Session wrapper = new Session(session);
            sessionInit.invokeExact(session, (short) protocolVersion, groupId, arena.allocateFrom(selfUserId));
            return wrapper;
        } catch (Throwable t) {
            throw new IllegalStateException("DAVE session create/init failed", t);
        }
    }

    public void resetSession(Session session, int protocolVersion, long groupId, String selfUserId) {
        try (Arena arena = Arena.ofConfined()) {
            sessionReset.invokeExact(session.handle);
            sessionInit.invokeExact(session.handle, (short) protocolVersion, groupId, arena.allocateFrom(selfUserId));
        } catch (Throwable t) {
            throw new IllegalStateException("DAVE session reset/init failed", t);
        }
    }

    public void setExternalSender(Session session, byte[] externalSender) {
        try (Arena arena = Arena.ofConfined()) {
            sessionSetExternalSender.invokeExact(session.handle,
                    arena.allocateFrom(ValueLayout.JAVA_BYTE, externalSender), (long) externalSender.length);
        } catch (Throwable t) {
            throw new IllegalStateException("daveSessionSetExternalSender failed", t);
        }
    }

    public void addRecognizedUser(Session session, String userId) {
        try (Arena arena = Arena.ofConfined()) {
            sessionAddRecognizedUser.invokeExact(session.handle, arena.allocateFrom(userId));
        } catch (Throwable t) {
            throw new IllegalStateException("daveSessionAddRecognizedUser failed", t);
        }
    }

    public void removeRecognizedUser(Session session, String userId) {
        try (Arena arena = Arena.ofConfined()) {
            sessionRemoveRecognizedUser.invokeExact(session.handle, arena.allocateFrom(userId));
        } catch (Throwable t) {
            throw new IllegalStateException("daveSessionRemoveRecognizedUser failed", t);
        }
    }

    public byte[] getKeyPackage(Session session) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pointerOut = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment lengthOut = arena.allocate(ValueLayout.JAVA_LONG);
            sessionGetMarshalledKeyPackage.invokeExact(session.handle, pointerOut, lengthOut);
            return readAllocatedBytes(pointerOut, lengthOut);
        } catch (Throwable t) {
            throw new IllegalStateException("daveSessionGetMarshalledKeyPackage failed", t);
        }
    }

    // proposals は OP27 のペイロードそのもの (operation_type バイトを含む)。
    // 戻り値は OP28 で送る commit||welcome (無ければ null)。
    public byte[] processProposals(Session session, byte[] proposalsPayload) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pointerOut = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment lengthOut = arena.allocate(ValueLayout.JAVA_LONG);
            sessionProcessProposals.invokeExact(session.handle,
                    arena.allocateFrom(ValueLayout.JAVA_BYTE, proposalsPayload), (long) proposalsPayload.length,
                    pointerOut, lengthOut);
            return readAllocatedBytes(pointerOut, lengthOut);
        } catch (Throwable t) {
            throw new IllegalStateException("daveSessionProcessProposals failed", t);
        }
    }

    public enum CommitOutcome { SUCCESS, IGNORED, FAILED }

    public CommitOutcome processCommit(Session session, byte[] commit) {
        MemorySegment result;
        try (Arena arena = Arena.ofConfined()) {
            result = (MemorySegment) sessionProcessCommit.invokeExact(session.handle,
                    arena.allocateFrom(ValueLayout.JAVA_BYTE, commit), (long) commit.length);
        } catch (Throwable t) {
            throw new IllegalStateException("daveSessionProcessCommit failed", t);
        }
        if (result == null || MemorySegment.NULL.equals(result)) return CommitOutcome.FAILED;
        try {
            boolean failed = (boolean) commitResultIsFailed.invokeExact(result);
            boolean ignored = (boolean) commitResultIsIgnored.invokeExact(result);
            if (failed) return CommitOutcome.FAILED;
            if (ignored) return CommitOutcome.IGNORED;
            return CommitOutcome.SUCCESS;
        } catch (Throwable t) {
            throw new IllegalStateException("daveCommitResult inspection failed", t);
        } finally {
            try {
                commitResultDestroy.invokeExact(result);
            } catch (Throwable ignored) {
                // 破棄の失敗は処理結果に影響しない
            }
        }
    }

    public boolean processWelcome(Session session, byte[] welcome) {
        MemorySegment result;
        try (Arena arena = Arena.ofConfined()) {
            result = (MemorySegment) sessionProcessWelcome.invokeExact(session.handle,
                    arena.allocateFrom(ValueLayout.JAVA_BYTE, welcome), (long) welcome.length);
        } catch (Throwable t) {
            throw new IllegalStateException("daveSessionProcessWelcome failed", t);
        }
        if (result == null || MemorySegment.NULL.equals(result)) return false;
        try {
            welcomeResultDestroy.invokeExact(result);
        } catch (Throwable ignored) {
            // 破棄の失敗は処理結果に影響しない
        }
        return true;
    }

    // ----- 3. Encryptor -----
    public static final class Encryptor implements AutoCloseable {
        private MemorySegment handle;
        private MemorySegment ratchet;

        private Encryptor(MemorySegment handle) {
            this.handle = handle;
        }

        public boolean isOpen() {
            return handle != null && !MemorySegment.NULL.equals(handle);
        }

        @Override
        public synchronized void close() {
            if (!isOpen()) return;
            try {
                DaveNative.get().encryptorDestroy.invokeExact(handle);
            } catch (Throwable t) {
                throw new IllegalStateException("daveEncryptorDestroy failed", t);
            } finally {
                handle = null;
            }
        }
    }

    public Encryptor createEncryptor() {
        try {
            MemorySegment encryptor = (MemorySegment) encryptorCreate.invokeExact();
            if (encryptor == null || MemorySegment.NULL.equals(encryptor)) {
                throw new IllegalStateException("daveEncryptorCreate returned NULL");
            }
            return new Encryptor(encryptor);
        } catch (Throwable t) {
            throw new IllegalStateException("daveEncryptorCreate failed", t);
        }
    }

    // セッションから自ユーザーの鍵ラチェットを取得して encryptor へ差す。
    // グループ未確立なら false (ラチェットがまだ無い)。
    public boolean refreshRatchet(Session session, Encryptor encryptor, String selfUserId) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ratchet = (MemorySegment) sessionGetKeyRatchet.invokeExact(
                    session.handle, arena.allocateFrom(selfUserId));
            if (ratchet == null || MemorySegment.NULL.equals(ratchet)) return false;
            encryptorSetKeyRatchet.invokeExact(encryptor.handle, ratchet);
            MemorySegment previous = encryptor.ratchet;
            encryptor.ratchet = ratchet;
            if (previous != null && !MemorySegment.NULL.equals(previous)) {
                keyRatchetDestroy.invokeExact(previous);
            }
            return true;
        } catch (Throwable t) {
            throw new IllegalStateException("daveSessionGetKeyRatchet / SetKeyRatchet failed", t);
        }
    }

    public boolean encryptorHasKeyRatchet(Encryptor encryptor) {
        try {
            return (boolean) encryptorHasKeyRatchet.invokeExact(encryptor.handle);
        } catch (Throwable t) {
            throw new IllegalStateException("daveEncryptorHasKeyRatchet failed", t);
        }
    }

    public void encryptorSetPassthroughMode(Encryptor encryptor, boolean passthrough) {
        try {
            encryptorSetPassthroughMode.invokeExact(encryptor.handle, passthrough);
        } catch (Throwable t) {
            throw new IllegalStateException("daveEncryptorSetPassthroughMode failed", t);
        }
    }

    public void encryptorAssignSsrc(Encryptor encryptor, int ssrc) {
        try {
            encryptorAssignSsrcToCodec.invokeExact(encryptor.handle, ssrc, CODEC_OPUS);
        } catch (Throwable t) {
            throw new IllegalStateException("daveEncryptorAssignSsrcToCodec failed", t);
        }
    }

    public byte[] encryptFrame(Encryptor encryptor, int ssrc, byte[] opusFrame) {
        try (Arena arena = Arena.ofConfined()) {
            long capacity = (long) encryptorGetMaxCiphertextByteSize.invokeExact(
                    encryptor.handle, MEDIA_TYPE_AUDIO, (long) opusFrame.length);
            MemorySegment out = arena.allocate(Math.max(capacity, 1));
            MemorySegment written = arena.allocate(ValueLayout.JAVA_LONG);
            int result = (int) encryptorEncrypt.invokeExact(encryptor.handle, MEDIA_TYPE_AUDIO, ssrc,
                    arena.allocateFrom(ValueLayout.JAVA_BYTE, opusFrame), (long) opusFrame.length,
                    out, capacity, written);
            if (result != ENCRYPTOR_SUCCESS) {
                throw new IllegalStateException("daveEncryptorEncrypt failed: result=" + result);
            }
            long length = written.get(ValueLayout.JAVA_LONG, 0);
            if (length <= 0) return new byte[0];
            return out.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
        } catch (Throwable t) {
            throw new IllegalStateException("daveEncryptorEncrypt failed", t);
        }
    }
}
