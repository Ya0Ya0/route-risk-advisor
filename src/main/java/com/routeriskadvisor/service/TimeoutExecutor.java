package com.routeriskadvisor.service;

import com.routeriskadvisor.provider.ProviderException;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs a unit of work with a bounded wait so the service layer can enforce the per-step timeout
 * budgets the requirements specify (10s geocoding/routing, 5s classification, 2s recommendation,
 * 2s service-area). The design's "Timeout enforcement" note requires timeouts to be applied at
 * the service layer so placeholder and future real providers share identical timeout semantics —
 * this helper is that single enforcement point.
 *
 * <p>The distinction between a timeout and an ordinary failure is preserved so callers can pick
 * the correct degrade-vs-abort policy: work that exceeds its budget surfaces as a
 * {@link ProviderException} of {@link ProviderException.Kind#TIMEOUT}; work that throws surfaces
 * as {@link ProviderException.Kind#FAILURE} (an existing {@link ProviderException} is passed
 * through with its original kind).
 *
 * <p>This helper is intentionally self-contained: it holds its own daemon thread pool and does
 * not depend on any other service-layer type, so it can be shared by both the classification and
 * safest-route flows without coupling them.
 */
@Component
public class TimeoutExecutor {

    private final ExecutorService executor;

    public TimeoutExecutor() {
        this(Executors.newCachedThreadPool(daemonThreadFactory()));
    }

    /**
     * Package-visible constructor for tests that want to inject a controlled executor.
     */
    TimeoutExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    /**
     * Executes {@code work} and waits at most {@code budget} for it to complete.
     *
     * @param work   the work to run; may throw a {@link ProviderException} or any other exception
     * @param budget the maximum time to wait; must be positive
     * @param label  a short description of the step used in error messages (e.g. "geocoding")
     * @param <T>    the work's result type
     * @return the work's result if it completes within the budget
     * @throws ProviderException with {@link ProviderException.Kind#TIMEOUT} if the budget elapses,
     *                           or {@link ProviderException.Kind#FAILURE} if the work throws
     */
    public <T> T callWithin(Callable<T> work, Duration budget, String label) throws ProviderException {
        if (work == null) {
            throw new IllegalArgumentException("work must not be null");
        }
        if (budget == null || budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("budget must be a positive duration");
        }
        String step = label == null ? "operation" : label;

        Future<T> future = executor.submit(work);
        try {
            return future.get(budget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ProviderException(
                step + " did not complete within " + budget.toMillis() + " ms", ProviderException.Kind.TIMEOUT, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ProviderException pe) {
                // Preserve the provider's own timeout-vs-failure discriminator.
                throw pe;
            }
            throw new ProviderException(
                step + " failed: " + cause.getMessage(), ProviderException.Kind.FAILURE, cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProviderException(
                step + " was interrupted", ProviderException.Kind.FAILURE, e);
        }
    }

    /**
     * Convenience overload accepting a millisecond budget.
     */
    public <T> T callWithin(Callable<T> work, long budgetMillis, String label) throws ProviderException {
        return callWithin(work, Duration.ofMillis(budgetMillis), label);
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, "timeout-executor-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
