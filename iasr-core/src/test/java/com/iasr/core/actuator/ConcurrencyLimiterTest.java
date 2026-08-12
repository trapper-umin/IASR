package com.iasr.core.actuator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ConcurrencyLimiterTest {

    private static boolean acquire(ConcurrencyLimiter limiter) throws InterruptedException {
        return limiter.tryAcquire(50, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("clamps the initial limit into [min, max]")
    void clampsInitialLimit() {
        assertThat(new ConcurrencyLimiter(1000, 10, 500).currentValue()).isEqualTo(500);
        assertThat(new ConcurrencyLimiter(1, 10, 500).currentValue()).isEqualTo(10);
        assertThat(new ConcurrencyLimiter(100, 10, 500).currentValue()).isEqualTo(100);
    }

    @Test
    @DisplayName("rejects nonsensical bounds")
    void validatesBounds() {
        assertThatThrownBy(() -> new ConcurrencyLimiter(5, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConcurrencyLimiter(5, 10, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("hands out exactly `limit` permits and no more")
    void enforcesLimit() throws Exception {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(3, 1, 10);

        assertThat(acquire(limiter)).isTrue();
        assertThat(acquire(limiter)).isTrue();
        assertThat(acquire(limiter)).isTrue();
        assertThat(acquire(limiter)).isFalse();

        assertThat(limiter.getInflight()).isEqualTo(3);
    }

    @Test
    @DisplayName("clamps applied values to the configured bounds")
    void applyClampsToBounds() {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(100, 10, 500);

        limiter.apply(9999);
        assertThat(limiter.currentValue()).isEqualTo(500);

        limiter.apply(0);
        assertThat(limiter.currentValue()).isEqualTo(10);
    }

    @Test
    @DisplayName("an increase makes new permits available at once")
    void increaseReleasesPermits() throws Exception {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(2, 1, 10);

        assertThat(acquire(limiter)).isTrue();
        assertThat(acquire(limiter)).isTrue();
        assertThat(acquire(limiter)).isFalse();

        limiter.apply(4);

        assertThat(acquire(limiter)).isTrue();
        assertThat(acquire(limiter)).isTrue();
        assertThat(acquire(limiter)).isFalse();
    }

    @Test
    @DisplayName("an increase wakes a thread already blocked on acquire")
    void increaseWakesBlockedWaiter() throws Exception {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(1, 1, 10);
        assertThat(acquire(limiter)).isTrue();

        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger(-1);
        Thread waiter = new Thread(() -> {
            started.countDown();
            try {
                acquired.set(limiter.tryAcquire(5, TimeUnit.SECONDS) ? 1 : 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        // Give the waiter a moment to actually park on the semaphore.
        Thread.sleep(100);

        limiter.apply(2);

        waiter.join(5_000);
        assertThat(acquired.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a decrease below the in-flight count creates permit debt repaid by releases")
    void decreaseCreatesPermitDebt() throws Exception {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(4, 1, 10);

        for (int i = 0; i < 4; i++) {
            assertThat(acquire(limiter)).isTrue();
        }
        assertThat(limiter.getInflight()).isEqualTo(4);

        // Four permits are held; the new limit is 2, so the semaphore now owes 2.
        limiter.apply(2);
        assertThat(limiter.currentValue()).isEqualTo(2);

        // The first two releases repay the debt rather than freeing capacity.
        limiter.release();
        limiter.release();
        assertThat(acquire(limiter)).isFalse();

        // Only once the debt is settled does capacity come back.
        limiter.release();
        assertThat(acquire(limiter)).isTrue();
    }

    @Test
    @DisplayName("in-flight count returns to zero after balanced acquire/release")
    void inflightIsBalanced() throws Exception {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(5, 1, 10);

        for (int i = 0; i < 5; i++) {
            assertThat(acquire(limiter)).isTrue();
        }
        for (int i = 0; i < 5; i++) {
            limiter.release();
        }

        assertThat(limiter.getInflight()).isZero();
        assertThat(acquire(limiter)).isTrue();
    }

    @Test
    @DisplayName("an unmatched release is ignored instead of inflating capacity")
    void unmatchedReleaseIsIgnored() throws Exception {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(1, 1, 10);

        limiter.release();
        limiter.release();

        assertThat(limiter.getInflight()).isZero();
        // Capacity is still 1, not 3.
        assertThat(acquire(limiter)).isTrue();
        assertThat(acquire(limiter)).isFalse();
    }

    @Test
    @DisplayName("stays consistent under concurrent traffic and live resizing")
    void survivesConcurrentResize() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(30), () -> {
            ConcurrencyLimiter limiter = new ConcurrencyLimiter(20, 5, 50);
            int threads = 12;
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger maxObservedInflight = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                new Thread(() -> {
                    try {
                        for (int i = 0; i < 200; i++) {
                            if (limiter.tryAcquire(20, TimeUnit.MILLISECONDS)) {
                                try {
                                    maxObservedInflight.accumulateAndGet(
                                            limiter.getInflight(), Math::max);
                                } finally {
                                    limiter.release();
                                }
                            }
                        }
                    } catch (Throwable e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                }).start();
            }

            Thread resizer = new Thread(() -> {
                int[] sizes = {5, 40, 10, 50, 8, 25};
                for (int i = 0; i < 60; i++) {
                    limiter.apply(sizes[i % sizes.length]);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            resizer.start();

            assertThat(done.await(25, TimeUnit.SECONDS)).isTrue();
            resizer.join(5_000);

            assertThat(failures.get()).isZero();
            assertThat(limiter.getInflight()).isZero();
            // Never more concurrent holders than the largest limit ever set.
            assertThat(maxObservedInflight.get()).isLessThanOrEqualTo(50);
        });
    }
}
