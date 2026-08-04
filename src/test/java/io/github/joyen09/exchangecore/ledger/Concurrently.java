package io.github.joyen09.exchangecore.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Starts N threads on the same starting gun, so contention is real rather than incidental. */
final class Concurrently {

    private Concurrently() {}

    record Outcome(int successes, List<Throwable> failures, long durationMillis) {}

    static Outcome attempt(int threads, Runnable action) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());

        long start;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        action.run();
                        successes.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failures.add(e);
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                });
            }
            ready.await(30, TimeUnit.SECONDS);
            start = System.nanoTime();
            go.countDown();
        }
        long durationMillis = (System.nanoTime() - start) / 1_000_000;

        return new Outcome(successes.get(), List.copyOf(failures), durationMillis);
    }

    /** As {@link #attempt}, but any failure is a test failure. */
    static void run(int threads, Runnable action) throws InterruptedException {
        Outcome outcome = attempt(threads, action);
        if (!outcome.failures().isEmpty()) {
            AssertionError error = new AssertionError(
                    "%d of %d concurrent actions failed".formatted(outcome.failures().size(), threads));
            outcome.failures().forEach(error::addSuppressed);
            throw error;
        }
    }
}
