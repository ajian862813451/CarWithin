package com.example.carwithin;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Faithful port of the upstream ikcp.c reliable-UDP core (skywind3000/kcp), scoped to the
 * single conversation the seven-inch meter expects. Wire format matches the decrypted iOS
 * capture exactly: 24-byte little-endian header conv/cmd/frg/wnd/ts/sn/una/len, conv=3,
 * cmd PUSH=81 uplink and ACK=82 downlink. Only the pieces the sender needs are kept, but the
 * retransmission, cumulative ACK and congestion logic follow the reference implementation.
 */
public final class Kcp {
    public interface Output { void out(byte[] data, int size) throws Exception; }

    static final int RTO_NDL = 30, RTO_MIN = 100, RTO_DEF = 200, RTO_MAX = 60000;
    static final int CMD_PUSH = 81, CMD_ACK = 82, CMD_WASK = 83, CMD_WINS = 84;
    static final int ASK_SEND = 1, ASK_TELL = 2;
    static final int WND_SND = 32, WND_RCV = 128;
    static final int MTU_DEF = 1400, OVERHEAD = 24;
    static final int DEADLINK = 20, THRESH_INIT = 2, THRESH_MIN = 2;
    static final int PROBE_INIT = 7000, PROBE_LIMIT = 120000, FASTACK_LIMIT = 5;

    private static final class Segment {
        long conv, cmd, frg, wnd, ts, sn, una, len;
        long resendts, rto, fastack, xmit;
        byte[] data = new byte[0];
    }

    private final long conv;
    private final Output output;
    private int mtu = MTU_DEF, mss = MTU_DEF - OVERHEAD;
    private int state;
    private long sndUna, sndNxt, rcvNxt;
    private long ssthresh = THRESH_INIT;
    private int rxRttval, rxSrtt;
    private int rxRto = RTO_DEF, rxMinRto = RTO_MIN;
    private long sndWnd = WND_SND, rcvWnd = WND_RCV, rmtWnd = WND_RCV, cwnd, probe;
    private long current, interval = 100, tsFlush = 100;
    private long nodelay, updated;
    private long tsProbe, probeWait;
    private long incr;
    private int fastresend, fastlimit = FASTACK_LIMIT;
    private boolean nocwnd, stream;
    private int deadLink = DEADLINK;

    private final LinkedList<Segment> sndQueue = new LinkedList<>();
    private final LinkedList<Segment> sndBuf = new LinkedList<>();
    private final LinkedList<Segment> rcvQueue = new LinkedList<>();
    private final LinkedList<Segment> rcvBuf = new LinkedList<>();
    private final List<long[]> acklist = new ArrayList<>(); // {sn, ts}
    private byte[] buffer = new byte[(MTU_DEF + OVERHEAD) * 3];

    public Kcp(long conv, Output output) { this.conv = conv; this.output = output; }

    private static int diff(long later, long earlier) { return (int) (later - earlier); }

    private static void encode8u(byte[] p, int off, int v) { p[off] = (byte) v; }
    private static void encode16u(byte[] p, int off, int v) { p[off] = (byte) v; p[off + 1] = (byte) (v >>> 8); }
    private static void encode32u(byte[] p, int off, long v) {
        p[off] = (byte) v; p[off + 1] = (byte) (v >>> 8); p[off + 2] = (byte) (v >>> 16); p[off + 3] = (byte) (v >>> 24);
    }
    private static int decode8u(byte[] p, int off) { return p[off] & 0xff; }
    private static int decode16u(byte[] p, int off) { return (p[off] & 0xff) | ((p[off + 1] & 0xff) << 8); }
    private static long decode32u(byte[] p, int off) {
        return (p[off] & 0xffL) | ((p[off + 1] & 0xffL) << 8) | ((p[off + 2] & 0xffL) << 16) | ((p[off + 3] & 0xffL) << 24);
    }

    /** Queue one application message (our private RTP packet) for reliable delivery. */
    public int send(byte[] data) {
        if (data.length == 0) return -1;
        int offset = 0, size = data.length;
        if (stream && !sndQueue.isEmpty()) {
            Segment last = sndQueue.getLast();
            if (last.len < mss) {
                int capacity = (int) (mss - last.len);
                int extend = Math.min(size, capacity);
                byte[] merged = new byte[(int) last.len + extend];
                System.arraycopy(last.data, 0, merged, 0, (int) last.len);
                System.arraycopy(data, offset, merged, (int) last.len, extend);
                last.data = merged; last.len = merged.length; last.frg = 0;
                offset += extend; size -= extend;
            }
            if (size == 0) return 0;
        }
        int count = size <= mss ? 1 : (int) ((size + mss - 1) / mss);
        if (count >= rcvWnd) return -2;
        if (count == 0) count = 1;
        for (int i = 0; i < count; i++) {
            int chunk = Math.min(size, mss);
            Segment seg = new Segment();
            seg.data = new byte[chunk];
            System.arraycopy(data, offset, seg.data, 0, chunk);
            seg.len = chunk;
            seg.frg = stream ? 0 : (count - i - 1);
            sndQueue.add(seg);
            offset += chunk; size -= chunk;
        }
        return 0;
    }

    private void updateAck(int rtt) {
        if (rxSrtt == 0) { rxSrtt = rtt; rxRttval = rtt / 2; }
        else {
            int delta = Math.abs(rtt - rxSrtt);
            rxRttval = (3 * rxRttval + delta) / 4;
            rxSrtt = (7 * rxSrtt + rtt) / 8;
            if (rxSrtt < 1) rxSrtt = 1;
        }
        int rto = rxSrtt + Math.max((int) interval, 4 * rxRttval);
        rxRto = Math.min(RTO_MAX, Math.max(rxMinRto, rto));
    }

    private void shrinkBuf() {
        sndUna = sndBuf.isEmpty() ? sndNxt : sndBuf.getFirst().sn;
    }

    private void parseAck(long sn) {
        if (diff(sn, sndUna) < 0 || diff(sn, sndNxt) >= 0) return;
        for (int i = 0; i < sndBuf.size(); i++) {
            Segment seg = sndBuf.get(i);
            if (sn == seg.sn) { sndBuf.remove(i); break; }
            if (diff(sn, seg.sn) < 0) break;
        }
    }

    private void parseUna(long una) {
        sndBuf.removeIf(seg -> diff(una, seg.sn) > 0);
    }

    private void parseFastack(long sn, long ts) {
        if (diff(sn, sndUna) < 0 || diff(sn, sndNxt) >= 0) return;
        for (Segment seg : sndBuf) {
            if (diff(sn, seg.sn) < 0) break;
            else if (sn != seg.sn && diff(seg.ts, ts) <= 0) seg.fastack++;
        }
    }

    private void ackPush(long sn, long ts) { acklist.add(new long[]{sn, ts}); }

    private void parseData(Segment newseg) {
        long sn = newseg.sn;
        if (diff(sn, rcvNxt + rcvWnd) >= 0 || diff(sn, rcvNxt) < 0) return;
        boolean repeat = false; int insert = rcvBuf.size();
        for (int i = rcvBuf.size() - 1; i >= 0; i--) {
            Segment seg = rcvBuf.get(i);
            if (seg.sn == sn) { repeat = true; break; }
            if (diff(sn, seg.sn) > 0) { insert = i + 1; break; }
            insert = i;
        }
        if (!repeat) rcvBuf.add(insert, newseg);
        while (!rcvBuf.isEmpty()) {
            Segment seg = rcvBuf.getFirst();
            if (seg.sn == rcvNxt && rcvQueue.size() < rcvWnd) {
                rcvBuf.removeFirst(); rcvQueue.add(seg); rcvNxt++;
            } else break;
        }
    }

    /** Feed a received UDP datagram (meter → phone on 5012), carrying ACK/UNA/window and any data. */
    public int input(byte[] data) {
        long prevUna = sndUna;
        long maxack = 0, latestTs = 0;
        boolean flag = false;
        if (data == null || data.length < OVERHEAD) return -1;
        int offset = 0, size = data.length;
        while (true) {
            if (size < OVERHEAD) break;
            long c = decode32u(data, offset);
            if (c != conv) return -1;
            int cmd = decode8u(data, offset + 4);
            int frg = decode8u(data, offset + 5);
            int wnd = decode16u(data, offset + 6);
            long ts = decode32u(data, offset + 8);
            long sn = decode32u(data, offset + 12);
            long una = decode32u(data, offset + 16);
            long len = decode32u(data, offset + 20);
            offset += OVERHEAD; size -= OVERHEAD;
            if (size < len || len < 0) return -2;
            if (cmd != CMD_PUSH && cmd != CMD_ACK && cmd != CMD_WASK && cmd != CMD_WINS) return -3;
            rmtWnd = wnd;
            parseUna(una);
            shrinkBuf();
            if (cmd == CMD_ACK) {
                if (diff(current, ts) >= 0) updateAck(diff(current, ts));
                parseAck(sn);
                shrinkBuf();
                if (!flag) { flag = true; maxack = sn; latestTs = ts; }
                else if (diff(sn, maxack) > 0) { maxack = sn; latestTs = ts; }
            } else if (cmd == CMD_PUSH) {
                if (diff(sn, rcvNxt + rcvWnd) < 0) {
                    ackPush(sn, ts);
                    if (diff(sn, rcvNxt) >= 0) {
                        Segment seg = new Segment();
                        seg.conv = c; seg.cmd = cmd; seg.frg = frg; seg.wnd = wnd;
                        seg.ts = ts; seg.sn = sn; seg.una = una; seg.len = len;
                        seg.data = new byte[(int) len];
                        System.arraycopy(data, offset, seg.data, 0, (int) len);
                        parseData(seg);
                    }
                }
            } else if (cmd == CMD_WASK) {
                probe |= ASK_TELL;
            }
            offset += len; size -= len;
        }
        if (flag) parseFastack(maxack, latestTs);
        if (diff(sndUna, prevUna) > 0 && cwnd < rmtWnd) {
            long mssl = mss;
            if (cwnd < ssthresh) { cwnd++; incr += mssl; }
            else {
                if (incr < mssl) incr = mssl;
                incr += (mssl * mssl) / incr + (mssl / 16);
                if ((cwnd + 1) * mssl <= incr) cwnd = (incr + mssl - 1) / (mssl > 0 ? mssl : 1);
            }
            if (cwnd > rmtWnd) { cwnd = rmtWnd; incr = rmtWnd * mssl; }
        }
        return 0;
    }

    private int wndUnused() {
        return rcvQueue.size() < rcvWnd ? (int) (rcvWnd - rcvQueue.size()) : 0;
    }

    public void flush() {
        if (updated == 0) return;
        long cur = current;
        int change = 0; boolean lost = false;
        Segment seg = new Segment();
        seg.conv = conv; seg.cmd = CMD_ACK; seg.wnd = wndUnused(); seg.una = rcvNxt;

        int offset = 0;
        // acks
        for (long[] ack : acklist) {
            if (offset + OVERHEAD > mtu) { outputBuffer(offset); offset = 0; }
            seg.sn = ack[0]; seg.ts = ack[1];
            offset = encodeSeg(buffer, offset, seg);
        }
        acklist.clear();

        // window probe
        if (rmtWnd == 0) {
            if (probeWait == 0) { probeWait = PROBE_INIT; tsProbe = current + probeWait; }
            else if (diff(current, tsProbe) >= 0) {
                if (probeWait < PROBE_INIT) probeWait = PROBE_INIT;
                probeWait += probeWait / 2;
                if (probeWait > PROBE_LIMIT) probeWait = PROBE_LIMIT;
                tsProbe = current + probeWait; probe |= ASK_SEND;
            }
        } else { tsProbe = 0; probeWait = 0; }
        if ((probe & ASK_SEND) != 0) {
            seg.cmd = CMD_WASK;
            if (offset + OVERHEAD > mtu) { outputBuffer(offset); offset = 0; }
            offset = encodeSeg(buffer, offset, seg);
        }
        if ((probe & ASK_TELL) != 0) {
            seg.cmd = CMD_WINS;
            if (offset + OVERHEAD > mtu) { outputBuffer(offset); offset = 0; }
            offset = encodeSeg(buffer, offset, seg);
        }
        probe = 0;

        long cwndCap = Math.min(sndWnd, rmtWnd);
        if (!nocwnd) cwndCap = Math.min(cwnd, cwndCap);
        while (diff(sndNxt, sndUna + cwndCap) < 0) {
            if (sndQueue.isEmpty()) break;
            Segment s = sndQueue.removeFirst();
            s.conv = conv; s.cmd = CMD_PUSH; s.wnd = seg.wnd; s.ts = cur;
            s.sn = sndNxt++; s.una = rcvNxt;
            s.resendts = cur; s.rto = rxRto; s.fastack = 0; s.xmit = 0;
            sndBuf.add(s);
        }

        int resent = fastresend > 0 ? fastresend : Integer.MAX_VALUE;
        long rtomin = nodelay == 0 ? (rxRto >> 3) : 0;

        for (Segment s : sndBuf) {
            boolean needsend = false;
            if (s.xmit == 0) {
                needsend = true; s.xmit++; s.rto = rxRto; s.resendts = cur + s.rto + rtomin;
            } else if (diff(cur, s.resendts) >= 0) {
                needsend = true; s.xmit++;
                if (nodelay == 0) s.rto += Math.max(s.rto, rxRto);
                else s.rto += (nodelay < 2 ? s.rto : rxRto) / 2;
                s.resendts = cur + s.rto; lost = true;
            } else if (s.fastack >= resent && (s.xmit <= fastlimit || fastlimit <= 0)) {
                needsend = true; s.xmit++; s.fastack = 0; s.resendts = cur + s.rto; change++;
            }
            if (needsend) {
                s.ts = cur; s.wnd = seg.wnd; s.una = rcvNxt;
                int need = OVERHEAD + (int) s.len;
                if (offset + need > mtu) { outputBuffer(offset); offset = 0; }
                offset = encodeSeg(buffer, offset, s);
                if (s.len > 0) { System.arraycopy(s.data, 0, buffer, offset, (int) s.len); offset += s.len; }
                if (s.xmit >= deadLink) state = -1;
            }
        }
        if (offset > 0) outputBuffer(offset);

        if (change != 0) {
            long inflight = sndNxt - sndUna;
            ssthresh = Math.max(inflight / 2, THRESH_MIN);
            cwnd = ssthresh + resent; incr = cwnd * mss;
        }
        if (lost) {
            ssthresh = Math.max(cwndCap / 2, THRESH_MIN);
            cwnd = 1; incr = mss;
        }
        if (cwnd < 1) { cwnd = 1; incr = mss; }
    }

    private int encodeSeg(byte[] p, int off, Segment s) {
        encode32u(p, off, s.conv);
        encode8u(p, off + 4, (int) s.cmd);
        encode8u(p, off + 5, (int) s.frg);
        encode16u(p, off + 6, (int) s.wnd);
        encode32u(p, off + 8, s.ts);
        encode32u(p, off + 12, s.sn);
        encode32u(p, off + 16, s.una);
        encode32u(p, off + 20, s.len);
        return off + OVERHEAD;
    }

    private void outputBuffer(int size) {
        if (size == 0) return;
        try {
            byte[] packet = new byte[size];
            System.arraycopy(buffer, 0, packet, 0, size);
            output.out(packet, size);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void update(long currentMs) {
        current = currentMs;
        if (updated == 0) { updated = 1; tsFlush = current; }
        int slap = diff(current, tsFlush);
        if (slap >= 10000 || slap < -10000) { tsFlush = current; slap = 0; }
        if (slap >= 0) {
            tsFlush += interval;
            if (diff(current, tsFlush) >= 0) tsFlush = current + interval;
            flush();
        }
    }

    public long check(long currentMs) {
        if (updated == 0) return currentMs;
        long tsFlushLocal = tsFlush;
        if (diff(currentMs, tsFlushLocal) >= 10000 || diff(currentMs, tsFlushLocal) < -10000) tsFlushLocal = currentMs;
        if (diff(currentMs, tsFlushLocal) >= 0) return currentMs;
        long tmFlush = diff(tsFlushLocal, currentMs);
        long tmPacket = 0x7fffffff;
        for (Segment s : sndBuf) {
            int d = diff(s.resendts, currentMs);
            if (d <= 0) return currentMs;
            if (d < tmPacket) tmPacket = d;
        }
        long minimal = Math.min(tmPacket, tmFlush);
        if (minimal >= interval) minimal = interval;
        return currentMs + minimal;
    }

    /** Pull one reassembled application message (unused by the sender path, kept for completeness). */
    public byte[] recv() {
        if (rcvQueue.isEmpty()) return null;
        int peeksize = peekSize();
        if (peeksize < 0) return null;
        byte[] out = new byte[peeksize]; int pos = 0;
        boolean recover = rcvQueue.size() >= rcvWnd;
        while (!rcvQueue.isEmpty()) {
            Segment s = rcvQueue.removeFirst();
            System.arraycopy(s.data, 0, out, pos, (int) s.len); pos += s.len;
            boolean last = s.frg == 0;
            if (last) break;
        }
        while (!rcvBuf.isEmpty()) {
            Segment s = rcvBuf.getFirst();
            if (s.sn == rcvNxt && rcvQueue.size() < rcvWnd) { rcvBuf.removeFirst(); rcvQueue.add(s); rcvNxt++; }
            else break;
        }
        if (rcvQueue.size() < rcvWnd && recover) probe |= ASK_TELL;
        return out;
    }

    private int peekSize() {
        if (rcvQueue.isEmpty()) return -1;
        Segment first = rcvQueue.getFirst();
        if (first.frg == 0) return (int) first.len;
        if (rcvQueue.size() < first.frg + 1) return -1;
        int length = 0;
        for (Segment s : rcvQueue) { length += s.len; if (s.frg == 0) break; }
        return length;
    }

    public void setNodelay(int nodelayFlag, int intervalMs, int resend, boolean nc) {
        nodelay = nodelayFlag;
        rxMinRto = nodelayFlag != 0 ? RTO_NDL : RTO_MIN;
        interval = Math.max(10, Math.min(5000, intervalMs));
        fastresend = resend;
        nocwnd = nc;
    }

    public void setWndSize(int sndWndSize, int rcvWndSize) {
        if (sndWndSize > 0) sndWnd = sndWndSize;
        if (rcvWndSize > 0) rcvWnd = Math.max(rcvWndSize, WND_RCV);
    }

    public void setMtu(int value) {
        if (value < OVERHEAD || value < 50) return;
        mtu = value; mss = value - OVERHEAD;
        buffer = new byte[(value + OVERHEAD) * 3];
    }

    public void setStream(boolean value) { this.stream = value; }
    public int waitSnd() { return sndBuf.size() + sndQueue.size(); }
    public int getState() { return state; }
    public long getConv() { return conv; }
    public long getInterval() { return interval; }
}
