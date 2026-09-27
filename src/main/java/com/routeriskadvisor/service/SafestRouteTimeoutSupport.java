package com.routeriskadvisor.service;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounded-wait timeout helper used exclusively by the Safest Route Finder flow.
 *
 * <p>The Requirements specify hard time budgets for the routing lookup and for classifying each
 * candidate route (both 10 seconds — design "Configuration Strategy",
 * {@code route-risk-advisor.timeouts.safest-classify-ms}). This helper runs a supplied unit of
 * work on a short-lived worker thread and waits at most the configured budget for it to finish,
 * so a slow or hung provider/classifier cannot stall the request beyond the deadline.
 *
 * <p>It is intentionally distinct from any generic timeout utility the classification flow may
 * introduce: it is package-scoped to the safest-route service and named for that flow to avoid
 * colliding with a separately-owned shared helper.
 *
 * <p>Outcomes are normalized so the service applies a single policy:
 * <ul>
 *   <li>the work completes in time &rarr; its value is returned;</li>
 *   <li>the work exceeds the budget &rarr; a {@link BoundedWaitException} with
 *       {@link BoundedWaitException.Cause#TIMEOUT};</li>
 *   <li>the work throws &rarr; a {@link BoundedWaitException} with
 *       {@link BoundedWaitException.Cause#FAILURE} wrapping the original throwable.</li>
 * </ul>
 */
class SafestRouteTimeoutSupport {

    /**
     * Signals that a bounded-wait unit of work either exceeded its budget or threw. The
     * {@link Cause} lets the caller distinguish a timeout from a failure, though the safest-route
     * flow treats both as an abort.
     */
    static final class BoundedWaitException extends Exception {

        enum Cause { TIMEOUT, FAILURE }

        private final Cause cause;

        BoundedWaitException(Cause cause, String message, Throwable original) {
            super(message, original);
            this.cause = cause;
        }

        Cause cause() {
            return cause;
        }
    }

    /**
     * Runs {@code work} and waits at most {@code budgetMillis} for it to complete.
     *
     * @param work        the unit of work to run under the budget; must not be {@code null}
     * @param budgetMillis the maximum time to wait, in milliseconds
     * @param description a short label used in the exception message for diagnostics
     * @param <T>         the work's result type
     * @return the work's result when it completes within the budget
     * @throws BoundedWaitException if the work times out ({@link BoundedWaitException.Cause#TIMEOUT})
     *                              or throws ({@link BoundedWaitException.Cause#FAILURE})
     */
    <T> T runWithin(Callable<T> work, long budgetMillis, String description)
            throws BoundedWaitException {
        // A single-use worker so a hung task never blocks the caller past the deadline. The
        // executor is shut down in a finally block; a task that overran is interrupted.
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "safest-route-timeout");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<T> future = executor.submit(work);
            try {
                return future.get(budgetMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                future.cancel(true);
                throw new BoundedWaitException(
                    BoundedWaitException.Cause.TIMEOUT,
                    description + " exceeded its " + budgetMillis + "ms budget",
                    timeout);
            } catch (ExecutionException failure) {
                throw new BoundedWaitException(
                    BoundedWaitException.Cause.FAILURE,
                    description + " failed: " + failure.getCause(),
                    failure.getCause());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                throw new BoundedWaitException(
                    BoundedWaitException.Cause.FAILURE,
                    description + " was interrupted",
                    interrupted);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
