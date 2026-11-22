package com.hyperdrive.agent.read;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Cancels one in-flight provider read: the pending exchange and/or the open body stream. */
final class CancelToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<?>> exchange = new AtomicReference<>();
    private final AtomicReference<Closeable> body = new AtomicReference<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Runs {@code r} once when cancelled (immediately if already cancelled). */
    void onCancel(Runnable r) {
        listeners.add(r);
        if (cancelled.get() && listeners.remove(r)) {
            r.run();
        }
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    void attach(CompletableFuture<?> future) {
        exchange.set(future);
        if (cancelled.get()) {
            future.cancel(true);
        }
    }

    void attach(Closeable stream) {
        body.set(stream);
        if (cancelled.get()) {
            closeQuietly(stream);
        }
    }

    void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            CompletableFuture<?> f = exchange.get();
            if (f != null) {
                f.cancel(true);
            }
            Closeable c = body.get();
            if (c != null) {
                closeQuietly(c);
            }
            for (Runnable r : listeners) {
                if (listeners.remove(r)) {
                    r.run();
                }
            }
        }
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
