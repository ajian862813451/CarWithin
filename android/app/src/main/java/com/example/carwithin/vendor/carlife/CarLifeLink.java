// Derived from CarNine (c) 2026 xiaolu5hao, Apache-2.0. Modified for CarWithin. See bundled notices.
package com.example.carwithin.vendor.carlife;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * CarLife 车机端（HU）协议栈：连接本机 127.0.0.1 上的 7 个端口，完成 CarLife 2.0 握手
 * （版本协商 → 手机身份证明 → 内容加密协商 RSA/AES → 视频初始化 → 视频流 → 反向触控）。
 *
 * <p>协议依据：OPPO CarLife+ 组件 8.6.8 反编译 + OcarPlay 车载端实现。
 * 关键点：
 * <ul>
 *   <li>七个通道 = 七个 TCP 端口，本端是客户端（车机），手机端（Car+ 内的 CarLife 组件）监听；</li>
 *   <li>8 字节头（CMD/TOUCH/DATA）：u16 长度 | u16 保留 | u32 消息号；</li>
 *   <li>12 字节头（VIDEO/MEDIA/TTS/VR）：u32 长度 | u32 时间戳 | u32 消息号；</li>
 *   <li>加密协商后，CMD / TOUCH / VIDEO / MEDIA 的 body 都是 AES/ECB/PKCS5；帧头始终明文。</li>
 * </ul>
 */
public final class CarLifeLink {

    /** 全局日志出口（由 CarNineEngine 注入）。 */
    private static volatile java.util.function.Consumer<String> SINK;

    public static void setSink(java.util.function.Consumer<String> sink) {
        SINK = sink;
    }

    private static void d(String message) {
        java.util.function.Consumer<String> s = SINK;
        if (s != null) {
            s.accept(message);
        }
    }

    private static final String TAG = "CarLifeLink";

    // ------------------------------------------------------------------ 通道

    enum Channel {
        CMD(7240, 8),
        VIDEO(8240, 12),
        MEDIA(9240, 12),
        TTS(9241, 12),
        VR(9242, 12),
        TOUCH(9340, 8),
        DATA(9440, 8);

        final int port;
        final int headerSize;

        Channel(int port, int headerSize) {
            this.port = port;
            this.headerSize = headerSize;
        }

        boolean shortHeader() {
            return headerSize == 8;
        }
    }

    // ---------------------------------------------------------------- 消息号

    // 车机（本端）→ 手机
    static final int MSG_VERSION = 98305;              // {1:major, 2:minor}
    static final int MSG_RECEIVER_INFO = 98307;        // 车机品牌/型号/版本
    static final int MSG_VIDEO_INIT = 98311;           // {1:w, 2:h, 3:fps}
    static final int MSG_VIDEO_START = 98313;
    static final int MSG_RECEIVER_ID = 98343;          // {1:uuid}
    static final int MSG_PHONE_CHALLENGE = 98376;      // {1:"2.0;"+32hex}
    static final int MSG_VERIFIED_PHONE_RESULT = 98378;// {1:bool}
    static final int MSG_FEATURES = 98386;             // {1:n, 2:{1:key,2:value}}
    static final int MSG_RSA_PUBLIC_KEY = 98411;       // {1:base64(X509)}
    static final int MSG_AES_RECEIVED_ACK = 98413;     // 空 body（加密后 16 字节）

    // 手机 → 车机（本端）
    static final int MSG_MD_VERSION = 65538;           // {1:1} = 版本匹配
    static final int MSG_MD_INFO = 65540;              // {4:brand, 14:model, 20:sdk}
    static final int MSG_VIDEO_INIT_DONE = 65544;      // {1:w, 2:h, 3:fps}
    static final int MSG_MD_AUTHEN_RESPONSE = 65609;   // {1:proof}
    static final int MSG_MD_PHONE_ELIGIBLE = 65611;    // {1:bool}
    static final int MSG_MD_START_ENCRYPT = 65642;
    static final int MSG_MD_AES_KEY = 65644;           // {1:base64(RSA 密文)}
    static final int MSG_MD_ENCRYPT_READY = 65646;

    // 数据面
    static final int MSG_VIDEO_FRAME = 131073;         // 0x20001
    static final int MSG_TOUCH_DOWN = 425986;
    static final int MSG_TOUCH_UP = 425987;
    static final int MSG_TOUCH_MOVE = 425988;

    // --------------------------------------------------------------- 回调

    public interface Callback {
        /** 状态变化（用于 UI 显示）。 */
        void onState(String state);

        /** 手机端返回的实际画布尺寸与帧率。 */
        void onVideoInit(int width, int height, int fps);

        /** 一帧 H.264（Annex-B，已解密）。 */
        void onVideoFrame(byte[] data, long timestampSec);

        /** 会话结束（正常停止或出错）。 */
        void onClosed(String reason);
    }

    // --------------------------------------------------------------- 状态

    private final Callback callback;
    private final int reqWidth;
    private final int reqHeight;
    private final int reqFps;

    private final Map<Channel, Socket> sockets = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    private volatile boolean running;
    private volatile boolean closed;

    // 握手状态
    private volatile boolean versionMatched;
    private volatile boolean mdInfoSeen;
    private volatile boolean challengeIssued;
    private volatile boolean phoneVerified;
    private volatile boolean eligible = true;
    private volatile boolean encryptionAsked;
    private volatile boolean aesAckSent;
    private volatile boolean mdEncryptReady;
    private volatile boolean videoInitSent;
    private volatile boolean videoStarted;
    private String mdBrand = "";
    private String mdModel = "";
    private String mdSdk = "";
    private String challengeHex = "";
    private KeyPair rsaKeyPair;
    private byte[] aesKey;
    private volatile boolean encryptionOn;

    private volatile int canvasWidth;
    private volatile int canvasHeight;
    private volatile int canvasFps;

    private final java.util.ArrayDeque<int[]> touchQueue = new java.util.ArrayDeque<>();
    private volatile boolean touchThreadStarted;
    private volatile String lastStep = "刚连上";
    private int cmdLogCount;
    private final AtomicLong videoBytes = new AtomicLong();
    private final AtomicLong videoFrames = new AtomicLong();

    public CarLifeLink(Callback callback, int width, int height, int fps) {
        this.callback = callback;
        this.reqWidth = width;
        this.reqHeight = height;
        this.reqFps = fps;
    }

    public int getCanvasWidth() {
        return canvasWidth;
    }

    public int getCanvasHeight() {
        return canvasHeight;
    }

    public long getVideoBytes() {
        return videoBytes.get();
    }

    public long getVideoFrames() {
        return videoFrames.get();
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isVideoStarted() {
        return videoStarted;
    }

    public boolean isEncryptionOn() {
        return encryptionOn;
    }

    /**
     * 请求手机重新出一个关键帧：重复发送 HU_VIDEO_START。
     *
     * <p>CarLife 协议没有单独的关键帧请求消息，但官方车机在开始推流时会发 98313；
     * 重复一次是代价最低的试探（手机不支持时只会忽略）。失败不影响现有流。
     */
    public boolean requestKeyframe() {
        if (closed || !videoStarted) {
            return false;
        }
        try {
            sendCmd(MSG_VIDEO_START, new byte[0]);
            d("已请求关键帧（重发 HU_VIDEO_START）");
            return true;
        } catch (Exception e) {
            d("请求关键帧失败: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------ 连接

    /** 依次连接 7 个端口；在 {@code deadlineMs} 之前不断重试（手机端可能还在启动）。 */
    public void connect(long deadlineMs) {
        running = true;
        long deadline = System.nanoTime() + deadlineMs * 1_000_000L;
        int attempt = 0;
        InetAddress loopback;
        try {
            loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (Exception e) {
            close("无效的回环地址: " + e);
            return;
        }

        while (running && !closed) {
            attempt++;
            try {
                closeSockets();
                Socket cmd = open(loopback, Channel.CMD, deadline, 500);
                install(Channel.CMD, cmd);
                for (Channel ch : Channel.values()) {
                    if (ch == Channel.CMD) {
                        continue;
                    }
                    install(ch, open(loopback, ch, deadline, 800));
                }
                state("七个通道全部连通");
                d("七个通道全部连通（第 " + attempt + " 次尝试）");
                startThreads();
                return;
            } catch (SocketTimeoutException e) {
                closeSockets();
                if (System.nanoTime() > deadline) {
                    closed = true;
                    state("等待手机端监听超时");
                    d("等待 127.0.0.1 端口超时（" + deadlineMs + "ms）");
                    callback.onClosed("等待手机端监听超时");
                    return;
                }
            } catch (ConnectException e) {
                closeSockets();
                if (attempt == 1 || attempt % 5 == 0) {
                    state("等待手机端监听（第 " + attempt + " 次尝试）");
                    d("端口未监听，重试中：第 " + attempt + " 次");
                }
                sleep(400);
            } catch (IOException e) {
                closeSockets();
                d("连接异常：" + e);
                sleep(400);
            }
        }
    }

    private synchronized void install(Channel channel, Socket socket) throws IOException {
        if (closed) {
            socket.close();
            throw new IOException("会话已停止");
        }
        sockets.put(channel, socket);
    }

    private Socket open(InetAddress addr, Channel ch, long deadline, int wantMs) throws IOException {
        int budget = budget(deadline, wantMs);
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(addr, ch.port), budget);
        } catch (IOException e) {
            s.close();
            throw e;
        }
        s.setTcpNoDelay(true);
        d("已连接 " + ch + " 端口 " + ch.port);
        return s;
    }

    private static int budget(long deadline, int wantMs) throws SocketTimeoutException {
        long left = (deadline - System.nanoTime()) / 1_000_000L;
        if (left <= 0) {
            throw new SocketTimeoutException("deadline");
        }
        return (int) Math.min(wantMs, left);
    }

    private void startThreads() {
        daemon("carlife-cmd", () -> readLoop(Channel.CMD));
        daemon("carlife-video", () -> readLoop(Channel.VIDEO));
        for (Channel ch : new Channel[]{Channel.MEDIA, Channel.TTS, Channel.VR, Channel.DATA}) {
            daemon("carlife-" + ch.name().toLowerCase(), () -> readLoop(ch));
        }
        // 车机先发版本协商
        daemon("carlife-hello", () -> {
            sleep(120);
            try {
                sendCmd(MSG_VERSION, new Proto.Writer().int32(1, 4).int32(2, 0).toByteArray());
            } catch (IOException e) {
                d("发送版本协商失败：" + e);
            }
        });
    }

    static String nameOf(int id) {
        switch (id) {
            case MSG_MD_VERSION: return "MD_VERSION";
            case MSG_MD_INFO: return "MD_INFO";
            case MSG_MD_PHONE_ELIGIBLE: return "MD_PHONE_ELIGIBLE";
            case MSG_MD_AUTHEN_RESPONSE: return "MD_AUTHEN_RESPONSE";
            case MSG_MD_START_ENCRYPT: return "MD_RSA_PUBLIC_KEY_REQ";
            case MSG_MD_AES_KEY: return "MD_AES_KEY";
            case MSG_MD_ENCRYPT_READY: return "MD_ENCRYPT_READY";
            case MSG_VIDEO_INIT_DONE: return "MD_VIDEO_INIT_DONE";
            case MSG_VIDEO_FRAME: return "VIDEO_FRAME";
            case MSG_VERSION: return "HU_VERSION";
            case MSG_RECEIVER_INFO: return "HU_RECEIVER_INFO";
            case MSG_VIDEO_INIT: return "HU_VIDEO_INIT";
            case MSG_VIDEO_START: return "HU_VIDEO_START";
            case MSG_RECEIVER_ID: return "HU_RECEIVER_ID";
            case MSG_PHONE_CHALLENGE: return "HU_PHONE_CHALLENGE";
            case MSG_VERIFIED_PHONE_RESULT: return "HU_VERIFIED_PHONE_RESULT";
            case MSG_FEATURES: return "HU_FEATURES";
            case MSG_RSA_PUBLIC_KEY: return "HU_RSA_PUBLIC_KEY";
            case MSG_AES_RECEIVED_ACK: return "HU_AES_RECEIVED_ACK";
            default: return "0x" + Integer.toHexString(id);
        }
    }

    private static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ 读循环

    private void readLoop(Channel ch) {
        String why = "手机端关闭了" + ch + "通道";
        try (InputStream in = sockets.get(ch).getInputStream()) {
            while (running && !closed) {
                Frame f = readFrame(in, ch);
                if (f == null) {
                    break;
                }
                dispatch(f);
            }
        } catch (java.net.SocketException e) {
            why = ch + " 通道被本端关闭（" + e.getMessage() + "）";
        } catch (Exception e) {
            why = ch + " 通道异常：" + e;
        }
        if (!closed) {
            d("通道结束：" + why);
            if (ch == Channel.VIDEO) {
                close("视频通道断开：" + why);
                return;
            }
            if (ch == Channel.CMD) {
                close(why + "（最后一步：" + lastStep + "）");
            }
        }
    }

    private void dispatch(Frame f) throws Exception {
        switch (f.channel) {
            case CMD:
                onCmd(f.messageId, f.body);
                break;
            case VIDEO: {
                if (!videoStarted || f.messageId != MSG_VIDEO_FRAME) {
                    return;
                }
                byte[] body = f.body;
                if (encryptionOn && body.length > 0) {
                    body = tryDecrypt(body, "VIDEO");
                }
                if (body.length == 0) {
                    return;
                }
                long n = videoFrames.incrementAndGet();
                long total = videoBytes.addAndGet(body.length);
                if (n <= 3) {
                    d("视频帧 #" + n + " 字节=" + body.length + " 累计=" + total
                            + " 加密=" + encryptionOn);
                }
                callback.onVideoFrame(body, f.timestamp);
                break;
            }
            case MEDIA:
            case TTS:
            case VR:
            case DATA:
                // 本应用不转发音频，只做统计与丢弃
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------ 控制面

    private void onCmd(int id, byte[] body) throws Exception {
        if (cmdLogCount < 60) {
            cmdLogCount++;
            d("← CMD " + id + " (" + nameOf(id) + ") 长度=" + body.length
                    + (encryptionOn ? " 已加密" : ""));
        }
        if (encryptionOn && body.length > 0) {
            body = tryDecrypt(body, "CMD 0x" + Integer.toHexString(id));
        }
        switch (id) {
            case MSG_MD_VERSION: {
                lastStep = "版本协商";
                int v = Proto.Reader.intField(body, 1, 0);
                versionMatched = (v == 1);
                state("版本协商：" + (versionMatched ? "匹配" : "不匹配(" + v + ")"));
                maybeChallenge();
                break;
            }
            case MSG_MD_INFO: {
                lastStep = "手机信息";
                if (mdInfoSeen) {
                    return;
                }
                mdInfoSeen = true;
                mdBrand = nullToEmpty(Proto.Reader.strField(body, 4));
                mdModel = nullToEmpty(Proto.Reader.strField(body, 14));
                mdSdk = nullToEmpty(Proto.Reader.strField(body, 20));
                d("手机信息 brand=" + mdBrand + " model=" + mdModel + " sdk=" + mdSdk);
                maybeChallenge();
                break;
            }
            case MSG_MD_AUTHEN_RESPONSE: {
                lastStep = "身份证明";
                String proof = Proto.Reader.strField(body, 1);
                if (proof == null || proof.isEmpty()) {
                    throw new IOException("手机端证明为空");
                }
                if (!challengeIssued) {
                    throw new IOException("未发送挑战就收到证明");
                }
                boolean ok = expectedProof(mdBrand, mdModel, mdSdk, challengeHex).equalsIgnoreCase(proof);
                phoneVerified = ok;
                state("手机身份证明：" + (ok ? "通过" : "失败"));
                d("proof 校验 " + (ok ? "通过" : "失败"));
                sendCmd(MSG_VERIFIED_PHONE_RESULT, new Proto.Writer().bool(1, ok).toByteArray());
                if (!ok) {
                    close("手机身份证明校验失败");
                    return;
                }
                sendCmd(MSG_RECEIVER_INFO, receiverInfo());
                sendCmd(MSG_RECEIVER_ID, new Proto.Writer()
                        .str(1, UUID.randomUUID().toString()).toByteArray());
                askContentEncryption();
                break;
            }
            case MSG_MD_PHONE_ELIGIBLE: {
                eligible = Proto.Reader.boolField(body, 1);
                state("手机资格：" + (eligible ? "通过" : "被拒绝"));
                if (!eligible) {
                    close("手机端拒绝本次会话");
                }
                break;
            }
            case MSG_MD_START_ENCRYPT: {
                lastStep = "RSA 公钥";
                if (!phoneVerified || !eligible || encryptionAsked || rsaKeyPair != null || mdEncryptReady) {
                    return;
                }
                encryptionAsked = true;
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                rsaKeyPair = kpg.generateKeyPair();
                java.util.Base64.Encoder enc = java.util.Base64.getEncoder();
                sendCmd(MSG_RSA_PUBLIC_KEY, new Proto.Writer()
                        .str(1, enc.encodeToString(rsaKeyPair.getPublic().getEncoded()))
                        .toByteArray());
                state("已发送 RSA-2048 公钥");
                break;
            }
            case MSG_MD_AES_KEY: {
                lastStep = "AES 密钥";
                String wrapped = Proto.Reader.strField(body, 1);
                if (rsaKeyPair == null || wrapped == null) {
                    throw new IOException("缺少协商后的 AES 密钥");
                }
                byte[] cipherText = java.util.Base64.getDecoder().decode(wrapped);
                if (cipherText.length != 256) {
                    throw new IOException("RSA 密文长度异常: " + cipherText.length);
                }
                Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                rsa.init(Cipher.DECRYPT_MODE, rsaKeyPair.getPrivate());
                byte[] key = rsa.doFinal(cipherText);
                if (key.length != 16 && key.length != 24 && key.length != 32) {
                    throw new IOException("AES 密钥长度异常: " + key.length);
                }
                aesKey = key;
                encryptionOn = true;
                rsaKeyPair = null;
                sendCmd(MSG_AES_RECEIVED_ACK, new byte[0]);
                aesAckSent = true;
                state("AES-" + (key.length * 8) + " 已协商，控制与视频已加密");
                d("AES-" + (key.length * 8) + " 密钥已安装");
                break;
            }
            case MSG_MD_ENCRYPT_READY: {
                lastStep = "请求画布";
                if (body.length != 0 || !aesAckSent || mdEncryptReady) {
                    return;
                }
                mdEncryptReady = true;
                videoInitSent = true;
                sendCmd(MSG_VIDEO_INIT, new Proto.Writer()
                        .int32(1, reqWidth).int32(2, reqHeight).int32(3, reqFps).toByteArray());
                state("请求画布 " + reqWidth + "x" + reqHeight + "@" + reqFps);
                break;
            }
            case MSG_VIDEO_INIT_DONE: {
                lastStep = "开始推流";
                int w = Proto.Reader.intField(body, 1, 0);
                int h = Proto.Reader.intField(body, 2, 0);
                int f = Proto.Reader.intField(body, 3, 0);
                if (w <= 0 || h <= 0 || f <= 0 || w > 4096 || h > 4096 || f > 240) {
                    throw new IOException("无效的视频参数 " + w + "x" + h + "@" + f);
                }
                canvasWidth = w;
                canvasHeight = h;
                canvasFps = f;
                state("画布 " + w + "x" + h + " @" + f + "fps");
                d("VIDEO_INIT_DONE " + w + "x" + h + "@" + f);
                callback.onVideoInit(w, h, f);
                if (!videoStarted && mdEncryptReady) {
                    videoStarted = true;
                    sendCmd(MSG_VIDEO_START, new byte[0]);
                    state("已请求开始推流");
                }
                break;
            }
            default:
                d("收到未处理消息 0x"
                        + Integer.toHexString(id) + " body=" + body.length);
                break;
        }
    }

    private void maybeChallenge() {
        if (challengeIssued || !versionMatched || !mdInfoSeen) {
            return;
        }
        try {
            byte[] rnd = new byte[16];
            new SecureRandom().nextBytes(rnd);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : rnd) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            challengeHex = sb.toString();
            challengeIssued = true;
            sendCmd(MSG_PHONE_CHALLENGE, new Proto.Writer().str(1, "2.0;" + challengeHex).toByteArray());
            state("已发送手机身份挑战");
            d("已发送手机身份挑战");
        } catch (IOException e) {
            d("发送挑战失败：" + e);
        }
    }

    private void askContentEncryption() {
        try {
            Proto.Writer feature = new Proto.Writer().str(1, "CONTENT_ENCRYPTION").int32(2, 1);
            Proto.Writer body = new Proto.Writer().int32(1, 1).message(2, feature.toByteArray());
            sendCmd(MSG_FEATURES, body.toByteArray());
            state("已声明内容加密能力");
        } catch (IOException e) {
            d("发送能力声明失败：" + e);
        }
    }

    private byte[] receiverInfo() {
        return new Proto.Writer()
                .str(1, "Android")
                .str(2, android.os.Build.MANUFACTURER)
                .str(3, android.os.Build.MODEL)
                .str(4, String.valueOf(android.os.Build.VERSION.SDK_INT))
                .str(5, "CarLocal")
                .toByteArray();
    }

    /** 手机端证明：lower_hex(MD5("CARLIFE" + brand + model + sdk + challengeHex))。 */
    private static String expectedProof(String brand, String model, String sdk, String challengeHex)
            throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update("CARLIFE".getBytes(StandardCharsets.US_ASCII));
        md.update(brand.getBytes(StandardCharsets.UTF_8));
        md.update(model.getBytes(StandardCharsets.UTF_8));
        md.update(sdk.getBytes(StandardCharsets.UTF_8));
        md.update(challengeHex.getBytes(StandardCharsets.UTF_8));
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(32);
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private void sendCmd(int messageId, byte[] body) throws IOException {
        byte[] wire = encryptionOn ? aesOrThrow(Cipher.ENCRYPT_MODE, body) : body;
        writeFrame(Channel.CMD, messageId, wire);
        d("TX CMD 0x" + Integer.toHexString(messageId)
                + " body=" + wire.length + " 加密=" + encryptionOn);
    }

    // ------------------------------------------------------------ 触控

    /**
     * 回传触控；坐标以手机返回的画布像素为单位。
     * 可以从 UI 线程调用：真正的 socket 写由 carlife-touch 线程执行（否则会抛 NetworkOnMainThreadException）。
     */
    public boolean touch(int action, int x, int y) {
        if (closed || !videoStarted) {
            return false;
        }
        int messageId;
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            messageId = MSG_TOUCH_DOWN;
        } else if (action == android.view.MotionEvent.ACTION_UP) {
            messageId = MSG_TOUCH_UP;
        } else {
            messageId = MSG_TOUCH_MOVE;
        }
        int cw = Math.max(1, canvasWidth);
        int chh = Math.max(1, canvasHeight);
        int px = Math.max(0, Math.min(cw - 1, x));
        int py = Math.max(0, Math.min(chh - 1, y));
        synchronized (touchQueue) {
            if (messageId == MSG_TOUCH_MOVE && touchQueue.size() > 8) {
                return true; // 手势跟手优先：积压时丢弃过时的 MOVE
            }
            touchQueue.add(new int[]{messageId, px, py});
            ensureTouchThread();
            touchQueue.notifyAll();
        }
        return true;
    }

    private void ensureTouchThread() {
        if (touchThreadStarted) {
            return;
        }
        touchThreadStarted = true;
        daemon("carlife-touch", () -> {
            while (running && !closed) {
                int[] cmd;
                synchronized (touchQueue) {
                    if (touchQueue.isEmpty()) {
                        try {
                            touchQueue.wait(200);
                        } catch (InterruptedException ignored) {
                        }
                        continue;
                    }
                    cmd = touchQueue.poll();
                }
                try {
                    byte[] body = new Proto.Writer().int32(1, cmd[1]).int32(2, cmd[2]).toByteArray();
                    byte[] wire = encryptionOn ? aesOrThrow(Cipher.ENCRYPT_MODE, body) : body;
                    writeFrame(Channel.TOUCH, cmd[0], wire);
                } catch (Exception e) {
                    d("触控发送失败：" + e);
                }
            }
        });
    }

    // ------------------------------------------------------------ 帧收发

    private static final class Frame {
        final Channel channel;
        final int messageId;
        final int timestamp;
        final int reserved;
        final byte[] body;

        Frame(Channel channel, int messageId, int timestamp, int reserved, byte[] body) {
            this.channel = channel;
            this.messageId = messageId;
            this.timestamp = timestamp;
            this.reserved = reserved;
            this.body = body;
        }
    }

    private Frame readFrame(InputStream in, Channel ch) throws IOException {
        byte[] head = new byte[ch.headerSize];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        head[0] = (byte) first;
        readFully(in, head, 1, ch.headerSize - 1);

        int bodyLen;
        int timestamp = 0;
        int reserved = 0;
        int messageId;
        if (ch.shortHeader()) {
            bodyLen = ((head[0] & 0xFF) << 8) | (head[1] & 0xFF);
            reserved = ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
            messageId = ((head[4] & 0xFF) << 24) | ((head[5] & 0xFF) << 16)
                    | ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
        } else {
            bodyLen = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16)
                    | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
            timestamp = ((head[4] & 0xFF) << 24) | ((head[5] & 0xFF) << 16)
                    | ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
            messageId = ((head[8] & 0xFF) << 24) | ((head[9] & 0xFF) << 16)
                    | ((head[10] & 0xFF) << 8) | (head[11] & 0xFF);
        }

        int limit = ch.shortHeader() ? 32767 : 2 * 1024 * 1024;
        if (bodyLen < 0 || bodyLen > limit) {
            throw new IOException("帧长度异常 " + bodyLen + " (通道 " + ch + ")");
        }
        byte[] body = new byte[bodyLen];
        if (bodyLen > 0) {
            readFully(in, body, 0, bodyLen);
        }
        return new Frame(ch, messageId, timestamp, reserved, body);
    }

    private void writeFrame(Channel ch, int messageId, byte[] body) throws IOException {
        Socket s = sockets.get(ch);
        if (s == null || !s.isConnected()) {
            throw new IOException("通道未连接 " + ch);
        }
        int limit = ch.shortHeader() ? 32767 : 2 * 1024 * 1024;
        if (body.length > limit) {
            throw new IOException("帧超长 " + body.length);
        }
        byte[] head = new byte[ch.headerSize];
        if (ch.shortHeader()) {
            head[0] = (byte) ((body.length >>> 8) & 0xFF);
            head[1] = (byte) (body.length & 0xFF);
            head[2] = 0;
            head[3] = 0;
            int2(head, 4, messageId);
        } else {
            int2(head, 0, body.length);
            int2(head, 4, (int) (System.currentTimeMillis() / 1000));
            int2(head, 8, messageId);
        }
        synchronized (writeLock) {
            OutputStream out = s.getOutputStream();
            out.write(head);
            out.write(body);
            out.flush();
        }
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int r = in.read(buf, off + read, len - read);
            if (r < 0) {
                throw new EOFException("帧被截断");
            }
            read += r;
        }
    }

    private static void int2(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 24) & 0xFF);
        b[off + 1] = (byte) ((v >>> 16) & 0xFF);
        b[off + 2] = (byte) ((v >>> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    // ------------------------------------------------------------ 加密

    private int decryptFallbacks;

    /** 尝试解密；失败时按明文返回（对端可能未加密某些消息，需要容错）。 */
    private byte[] tryDecrypt(byte[] body, String what) {
        try {
            return aes(Cipher.DECRYPT_MODE, body);
        } catch (Exception e) {
            if (decryptFallbacks < 5) {
                decryptFallbacks++;
                d("解密失败，按明文处理：" + what + " 长度=" + body.length + " " + e);
            }
            return body;
        }
    }

    private byte[] aes(int mode, byte[] in) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(mode, new SecretKeySpec(aesKey, "AES"));
        return c.doFinal(in);
    }

    private byte[] aesOrThrow(int mode, byte[] in) throws IOException {
        try {
            return aes(mode, in);
        } catch (Exception e) {
            throw new IOException("AES " + (mode == Cipher.ENCRYPT_MODE ? "加密" : "解密") + "失败: " + e);
        }
    }

    // ------------------------------------------------------------ 生命周期

    public void stop() {
        close("已停止");
    }

    private synchronized void close(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        running = false;
        closeSockets();
        d("会话结束：" + reason);
        callback.onClosed(reason);
    }

    private void closeSockets() {
        for (Socket s : new ArrayList<>(sockets.values())) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        sockets.clear();
    }

    private void state(String s) {
        callback.onState(s);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------- 极简 protobuf（写）

    static final class Proto {
        private Proto() {}

        static final class Writer {
            private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

            private void varint(long v) {
                while ((v & ~0x7FL) != 0) {
                    out.write((int) ((v & 0x7F) | 0x80));
                    v >>>= 7;
                }
                out.write((int) v);
            }

            private void tag(int field, int wire) {
                varint(((long) field << 3) | wire);
            }

            Writer int32(int field, int v) {
                tag(field, 0);
                varint(v);
                return this;
            }

            Writer bool(int field, boolean v) {
                tag(field, 0);
                varint(v ? 1 : 0);
                return this;
            }

            Writer str(int field, String v) {
                byte[] b = v.getBytes(StandardCharsets.UTF_8);
                tag(field, 2);
                varint(b.length);
                out.write(b, 0, b.length);
                return this;
            }

            Writer message(int field, byte[] b) {
                tag(field, 2);
                varint(b.length);
                out.write(b, 0, b.length);
                return this;
            }

            byte[] toByteArray() {
                return out.toByteArray();
            }
        }

        static final class Reader {
            private Reader() {}

            static Map<Integer, List<Object>> all(byte[] b) {
                Map<Integer, List<Object>> map = new LinkedHashMap<>();
                int p = 0;
                while (p < b.length) {
                    long t = 0;
                    int shift = 0;
                    while (p < b.length) {
                        int x = b[p++] & 0xFF;
                        t |= (long) (x & 0x7F) << shift;
                        if ((x & 0x80) == 0) {
                            break;
                        }
                        shift += 7;
                    }
                    int field = (int) (t >>> 3);
                    int wire = (int) (t & 7);
                    Object value;
                    if (wire == 0) {
                        long v = 0;
                        int sh = 0;
                        while (p < b.length) {
                            int x = b[p++] & 0xFF;
                            v |= (long) (x & 0x7F) << sh;
                            if ((x & 0x80) == 0) {
                                break;
                            }
                            sh += 7;
                        }
                        value = v;
                    } else if (wire == 2) {
                        long len = 0;
                        int sh = 0;
                        while (p < b.length) {
                            int x = b[p++] & 0xFF;
                            len |= (long) (x & 0x7F) << sh;
                            if ((x & 0x80) == 0) {
                                break;
                            }
                            sh += 7;
                        }
                        int n = (int) Math.min(len, b.length - p);
                        byte[] r = new byte[n];
                        System.arraycopy(b, p, r, 0, n);
                        p += n;
                        value = r;
                    } else if (wire == 5) {
                        if (p + 4 > b.length) {
                            break;
                        }
                        p += 4;
                        value = 0L;
                    } else {
                        break;
                    }
                    map.computeIfAbsent(field, k -> new ArrayList<>()).add(value);
                }
                return map;
            }

            static int intField(byte[] b, int field, int def) {
                List<Object> l = all(b).get(field);
                if (l == null || l.isEmpty()) {
                    return def;
                }
                Object v = l.get(0);
                return v instanceof Long ? (int) (long) (Long) v : def;
            }

            static boolean boolField(byte[] b, int field) {
                return intField(b, field, 0) != 0;
            }

            static String strField(byte[] b, int field) {
                List<Object> l = all(b).get(field);
                if (l == null || l.isEmpty()) {
                    return null;
                }
                Object v = l.get(0);
                return v instanceof byte[] ? new String((byte[]) v, StandardCharsets.UTF_8) : null;
            }
        }
    }
}
