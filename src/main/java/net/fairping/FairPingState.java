package net.fairping;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import net.minecraft.util.Util;

public class FairPingState {

    public enum Mode { OFF, ADD, TOTAL }

    private static final FairPingState INSTANCE = new FairPingState();

    private static final long BASE_MAX_AGE_MS       = 1500;
    private static final long PING_COOLDOWN_MS      = 1000;
    private static final long DELAY_MIN_INTERVAL_MS = 150;
    private static final long DELAY_HYSTERESIS_MS   = 2;
    private static final int  DELAY_STEP_MS         = 2;
    private static final double SMOOTH_WEIGHT       = Math.exp(-Math.log(2.0) / 10.0);
    private static final double SMOOTH_GATE_MS      = 25.0;
    private static final int  SAMPLE_WINDOW         = 7;
    private static final int  MAX_DELAY_MS          = 400;

    private Mode mode = Mode.OFF;
    private int addTarget   = 0;
    private int totalTarget = 0;

    private long delayMs      = 0;
    private double smoothDelay = 0;
    private long lastDelayUpdate = 0;

    private int    lastBase        = 0;
    private double smoothedBase    = 0;
    private long   lastBaseSample  = 0;
    private long   lastPingSent    = 0;
    private boolean waitingForBase = false;
    private long lastRtt = -1;

    private final int[] sampleRing   = new int[SAMPLE_WINDOW];
    private final int[] sampleSorted = new int[SAMPLE_WINDOW];
    private int sampleCount = 0;
    private int sampleHead  = 0;

    private static final class PingRecord {
        long appliedDelay;
        long actualSendTime = -1;
        long arrivalTime    = -1;
        long outDelay       = 0;
        long inDelay        = 0;
    }

    private final Map<Long, PingRecord> pendingPings = new ConcurrentHashMap<>();

    private FairPingState() {}

    public static FairPingState getInstance() {
        return INSTANCE;
    }

    public void setOff() {
        mode = Mode.OFF;
        delayMs = 0;
        smoothDelay = 0;
        clearMeasurements();
    }

    public void setAdd(int ms) {
        mode = Mode.ADD;
        addTarget = clamp(ms);
        smoothDelay = addTarget;
        delayMs = quantize(smoothDelay);
        lastDelayUpdate = Util.getMeasuringTimeMs();
    }

    public void setTotal(int ms) {
        int target = Math.max(0, ms);
        boolean same = mode == Mode.TOTAL && target == totalTarget;

        mode = Mode.TOTAL;
        totalTarget = target;

        long now = Util.getMeasuringTimeMs();
        if (!same) clearMeasurements();
        lastDelayUpdate = now;

        MinecraftClient client = MinecraftClient.getInstance();
        int seed = seedFromPlayerList(client);
        if (seed > 0) {
            applyBaseSeed(seed);
            double want = clamp((int) Math.max(0, totalTarget - seed));
            if (!same || want > smoothDelay) smoothDelay = want;
            delayMs = quantize(smoothDelay);
        } else if (!same) {
            smoothDelay = clamp(totalTarget);
            delayMs = quantize(smoothDelay);
        }

        ClientPlayNetworkHandler net = client == null ? null : client.getNetworkHandler();
        if (net != null) sendPing(net, true);
    }

    public void onNewPlaySession() {
        clearMeasurements();
        if (mode == Mode.ADD) {
            smoothDelay = addTarget;
            delayMs = quantize(smoothDelay);
            lastDelayUpdate = Util.getMeasuringTimeMs();
        }
    }

    public void onProtocolChange() {
        clearMeasurements();
    }

    public void onPingQueued(long startTime) {
        if (mode == Mode.OFF) return;
        PingRecord r = new PingRecord();
        r.appliedDelay = delayMs;
        r.outDelay = outboundHalf();
        r.inDelay  = inboundHalf();
        pendingPings.put(startTime, r);
        lastPingSent = Util.getMeasuringTimeMs();
        waitingForBase = true;
    }

    public void onPingDispatched(long startTime) {
        PingRecord r = pendingPings.get(startTime);
        if (r != null) r.actualSendTime = Util.getMeasuringTimeMs();
    }

    public void onPingReceived(long startTime) {
        PingRecord r = pendingPings.get(startTime);
        if (r != null) r.arrivalTime = Util.getMeasuringTimeMs();
    }

    public void onPingResult(PingResultS2CPacket pkt) {
        PingRecord r = pendingPings.remove(pkt.startTime());
        if (r == null) return;

        long now = Util.getMeasuringTimeMs();
        long arrive = r.arrivalTime > 0 ? r.arrivalTime : now;
        long rtt = Math.max(0, arrive - pkt.startTime());
        lastRtt = rtt;

        long totalDeducted = r.outDelay + r.inDelay > 0 ? r.outDelay + r.inDelay : r.appliedDelay;
        long baseEstimate  = Math.max(0, rtt - totalDeducted);
        int  median        = pushMedian((int) baseEstimate);

        if (median <= 0) { waitingForBase = false; return; }

        lastBase = median;
        double gated = smoothedBase > 0
            ? Math.max(smoothedBase - SMOOTH_GATE_MS, Math.min(smoothedBase + SMOOTH_GATE_MS, median))
            : median;
        smoothedBase = smoothedBase == 0
            ? gated
            : gated + (smoothedBase - gated) * SMOOTH_WEIGHT;
        lastBaseSample = now;
        waitingForBase = false;
    }

    public void tick(MinecraftClient client) {
        recalcDelay(client);
    }

    private void recalcDelay(MinecraftClient client) {
        if (mode == Mode.OFF) { delayMs = 0; return; }

        if (mode == Mode.ADD) {
            smoothDelay = clamp(addTarget);
            delayMs = quantize(smoothDelay);
            return;
        }

        if (client == null) client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        ClientPlayNetworkHandler net = client.getNetworkHandler();
        if (net == null) return;

        long now = Util.getMeasuringTimeMs();
        sendPing(net, false);

        if (!hasFreshBase(now)) return;

        int base   = calibratedBase();
        int target = totalTarget > 0 ? totalTarget : base;
        long want  = clamp((int) Math.max(0, target - base));
        moveToward(want);
        applyQuantized(now, quantize(smoothDelay));
    }

    private void sendPing(ClientPlayNetworkHandler net, boolean force) {
        long now = Util.getMeasuringTimeMs();
        if (!force) {
            if (hasFreshBase(now)) return;
            if (waitingForBase && now - lastPingSent < PING_COOLDOWN_MS) return;
        }
        net.sendPacket(new QueryPingC2SPacket(now));
    }

    private void applyQuantized(long now, long next) {
        long diff = Math.abs(next - delayMs);
        if (diff == 0) return;
        boolean big   = diff >= (DELAY_STEP_MS * 3L);
        boolean hyst  = diff >= DELAY_HYSTERESIS_MS;
        boolean timed = now - lastDelayUpdate >= DELAY_MIN_INTERVAL_MS;
        if (big || (hyst && timed)) {
            delayMs = next;
            lastDelayUpdate = now;
        }
    }

    private void moveToward(long target) {
        double gap = target - smoothDelay;
        if (Math.abs(gap) < 0.5) { smoothDelay = target; return; }
        double step = Math.min(Math.max(Math.abs(gap) * 0.15, 0.5), 60.0);
        smoothDelay += gap > 0 ? step : -step;
        if (Math.abs(target - smoothDelay) < 0.5) smoothDelay = target;
    }

    private boolean hasFreshBase(long now) {
        return lastBase > 0 && now - lastBaseSample <= BASE_MAX_AGE_MS;
    }

    private int calibratedBase() {
        return (int) Math.round(smoothedBase > 0 ? smoothedBase : lastBase);
    }

    private int seedFromPlayerList(MinecraftClient client) {
        int best = calibratedBase();
        if (client == null || client.player == null) return best;
        ClientPlayNetworkHandler net = client.getNetworkHandler();
        if (net == null) return best;
        PlayerListEntry self = net.getPlayerListEntry(client.player.getUuid());
        if (self != null && self.getLatency() > 0) best = Math.max(best, self.getLatency());
        return best;
    }

    private void applyBaseSeed(int ms) {
        if (ms <= 0) return;
        lastBase = Math.max(lastBase, ms);
        smoothedBase = smoothedBase <= 0 ? ms : Math.max(smoothedBase, ms);
        lastBaseSample = Util.getMeasuringTimeMs();
    }

    private int pushMedian(int value) {
        if (value <= 0) return -1;
        if (sampleCount == SAMPLE_WINDOW) {
            int evict = sampleRing[sampleHead];
            int pos = 0;
            // Must check all sampleCount positions — stopping at sampleCount-1 misses the last
            while (pos < sampleCount && sampleSorted[pos] != evict) pos++;
            if (pos < sampleCount)
                System.arraycopy(sampleSorted, pos + 1, sampleSorted, pos, sampleCount - pos - 1);
            sampleCount--;
        }
        sampleRing[sampleHead] = value;
        sampleHead = (sampleHead + 1) % SAMPLE_WINDOW;
        int ins = sampleCount;
        while (ins > 0 && sampleSorted[ins - 1] > value) {
            sampleSorted[ins] = sampleSorted[ins - 1];
            ins--;
        }
        sampleSorted[ins] = value;
        sampleCount++;
        return sampleSorted[sampleCount / 2];
    }

    private static long quantize(double ms) {
        if (ms <= 0) return 0;
        long r = Math.round(ms);
        long half = DELAY_STEP_MS / 2L;
        return Math.max(0, ((r + half) / DELAY_STEP_MS) * DELAY_STEP_MS);
    }

    private static int clamp(int ms) {
        return Math.min(MAX_DELAY_MS, Math.max(0, ms));
    }

    private void clearMeasurements() {
        pendingPings.clear();
        waitingForBase = false;
        lastPingSent   = 0;
        lastBase       = 0;
        smoothedBase   = 0;
        lastBaseSample = 0;
        lastRtt        = -1;
        sampleCount    = 0;
        sampleHead     = 0;
    }

    public void recordOutDelay(long startTime, long ms) {
        PingRecord r = pendingPings.get(startTime);
        if (r != null) r.outDelay = ms;
    }

    public void recordInDelay(long startTime, long ms) {
        PingRecord r = pendingPings.get(startTime);
        if (r != null) r.inDelay = ms;
    }

    public long outboundHalf() { return delayMs / 2; }
    public long inboundHalf()  { return delayMs - outboundHalf(); }

    public Mode  getMode()        { return mode; }
    public int   getDelayMs()     { return (int) delayMs; }
    public int   getBasePing()    { return lastBase; }
    public int   getTotalPing()   { return lastBase + (int) delayMs; }
    public boolean isOff()        { return mode == Mode.OFF; }
    public boolean isAdd(int ms)  { return mode == Mode.ADD   && addTarget   == Math.max(0, ms); }
    public boolean isTotal(int ms){ return mode == Mode.TOTAL && totalTarget == Math.max(0, ms); }

    public String statusMessage() {
        if (mode == Mode.OFF) return "FairPing: OFF";
        long now = Util.getMeasuringTimeMs();
        int added = (int) delayMs;

        int base = 0;
        if (hasFreshBase(now)) {
            base = (int) Math.round(smoothedBase);
        } else {
            MinecraftClient client = MinecraftClient.getInstance();
            base = seedFromPlayerList(client);
        }

        if (base <= 0)
            return String.format("FairPing: Added +%dms | Measuring base ping...", added);

        return String.format("FairPing: Ping %dms | +%dms added | %dms total", base, added, base + added);
    }
}
