package net.fairping.handler;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import net.fairping.FairPingState;

public class PacketDelayHandler extends ChannelDuplexHandler {

    public static final String NAME = "fairping_delay";

    private static final long SPIN_NS     = TimeUnit.MILLISECONDS.toNanos(2);
    private static final long MIN_WAIT_NS = TimeUnit.MILLISECONDS.toNanos(1);

    private record Slot<T>(T payload, long releaseNs) {}

    private final class DelayQueue<T> {
        private final ConcurrentLinkedQueue<Slot<T>> queue = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final BiConsumer<ChannelHandlerContext, T> dispatch;
        private final Consumer<ChannelHandlerContext> afterBatch;

        DelayQueue(BiConsumer<ChannelHandlerContext, T> dispatch,
                   Consumer<ChannelHandlerContext> afterBatch) {
            this.dispatch   = dispatch;
            this.afterBatch = afterBatch;
        }

        void enqueue(ChannelHandlerContext ctx, T item, long delayMs) {
            queue.offer(new Slot<>(item, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs)));
            kick(ctx);
        }

        void kick(ChannelHandlerContext ctx) {
            if (!running.compareAndSet(false, true)) return;
            if (ctx.executor().inEventLoop()) drain(ctx);
            else ctx.executor().execute(() -> drain(ctx));
        }

        void drain(ChannelHandlerContext ctx) {
            boolean needsFlush = false;
            try {
                while (true) {
                    Slot<T> head = queue.peek();
                    if (head == null) {
                        if (needsFlush && afterBatch != null) afterBatch.accept(ctx);
                        running.set(false);
                        return;
                    }
                    long remaining = head.releaseNs() - System.nanoTime();
                    if (remaining <= 0) {
                        queue.poll();
                        if (ctx.channel().isOpen()) { dispatch.accept(ctx, head.payload()); needsFlush = true; }
                        continue;
                    }
                    if (needsFlush && afterBatch != null) { afterBatch.accept(ctx); needsFlush = false; }
                    if (remaining <= SPIN_NS) { spin(remaining); continue; }
                    long wait = Math.max(remaining - SPIN_NS, MIN_WAIT_NS);
                    ctx.executor().schedule(() -> kick(ctx), wait, TimeUnit.NANOSECONDS);
                    running.set(false);
                    return;
                }
            } catch (Exception e) {
                running.set(false);
                throw e;
            }
        }

        void flushNow(ChannelHandlerContext ctx) {
            Slot<T> s;
            boolean wrote = false;
            while ((s = queue.poll()) != null) {
                if (ctx.channel().isOpen()) { dispatch.accept(ctx, s.payload()); wrote = true; }
            }
            if (wrote && afterBatch != null) afterBatch.accept(ctx);
            running.set(false);
        }

        void clear() { queue.clear(); running.set(false); }
        boolean isEmpty() { return queue.isEmpty(); }
    }

    private record OutMsg(Object msg, ChannelPromise promise) {}

    private final DelayQueue<OutMsg> outQueue;
    private final DelayQueue<Object> inQueue;

    private volatile boolean enabled = true;
    private volatile ChannelHandlerContext ctx;

    public PacketDelayHandler() {
        outQueue = new DelayQueue<>(
            (c, item) -> {
                if (item.msg() instanceof QueryPingC2SPacket qp)
                    FairPingState.getInstance().onPingDispatched(qp.getStartTime());
                c.write(item.msg(), item.promise());
            },
            c -> { if (c.channel().isOpen()) c.flush(); }
        );
        inQueue = new DelayQueue<>(
            (c, msg) -> {
                if (msg instanceof PingResultS2CPacket pr) {
                    FairPingState.getInstance().onPingReceived(pr.startTime());
                    FairPingState.getInstance().onPingResult(pr);
                }
                c.fireChannelRead(msg);
            },
            null
        );
    }

    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) flush();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) { this.ctx = ctx; }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) { flush(); this.ctx = null; }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!enabled || !(msg instanceof Packet<?> pkt)) { super.write(ctx, msg, promise); return; }

        if (pkt.transitionsNetworkState()) {
            outQueue.enqueue(ctx, new OutMsg(msg, promise), 0);
            return;
        }

        if (pkt instanceof QueryPingC2SPacket qp)
            FairPingState.getInstance().onPingQueued(qp.getStartTime());

        FairPingState state = FairPingState.getInstance();
        long delay = state.outboundHalf();

        if (state.isOff() && outQueue.isEmpty()) {
            if (pkt instanceof QueryPingC2SPacket qp) state.onPingDispatched(qp.getStartTime());
            super.write(ctx, msg, promise);
            return;
        }
        if (delay <= 0 && outQueue.isEmpty()) {
            if (pkt instanceof QueryPingC2SPacket qp) state.onPingDispatched(qp.getStartTime());
            super.write(ctx, msg, promise);
            return;
        }

        if (pkt instanceof QueryPingC2SPacket qp) state.recordOutDelay(qp.getStartTime(), delay);
        outQueue.enqueue(ctx, new OutMsg(msg, promise), delay);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!enabled || !(msg instanceof Packet<?> pkt)) { super.channelRead(ctx, msg); return; }

        if (pkt.transitionsNetworkState()) { inQueue.enqueue(ctx, msg, 0); return; }

        FairPingState state = FairPingState.getInstance();
        long delay = state.inboundHalf();

        if (state.isOff() && inQueue.isEmpty()) {
            if (pkt instanceof PingResultS2CPacket pr) { state.onPingReceived(pr.startTime()); state.onPingResult(pr); }
            super.channelRead(ctx, msg);
            return;
        }
        if (delay <= 0 && inQueue.isEmpty()) {
            if (pkt instanceof PingResultS2CPacket pr) { state.onPingReceived(pr.startTime()); state.onPingResult(pr); }
            super.channelRead(ctx, msg);
            return;
        }

        if (pkt instanceof PingResultS2CPacket pr) state.recordInDelay(pr.startTime(), delay);
        inQueue.enqueue(ctx, msg, delay);
    }

    public void flush() {
        ChannelHandlerContext c = ctx;
        if (c == null || !c.channel().isOpen()) { outQueue.clear(); inQueue.clear(); return; }
        Runnable go = () -> { outQueue.flushNow(c); inQueue.flushNow(c); };
        if (c.executor().inEventLoop()) go.run();
        else c.executor().execute(go);
    }

    private static void spin(long ns) {
        long end = System.nanoTime() + ns;
        while (System.nanoTime() < end) Thread.onSpinWait();
    }
}
