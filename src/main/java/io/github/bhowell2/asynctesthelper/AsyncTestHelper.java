package io.github.bhowell2.asynctesthelper;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Provides async functionality for tests.
 * @author Blake Howell
 */
public class AsyncTestHelper {

	@FunctionalInterface
	public interface ThrowableRunnable {
		void run() throws Throwable;
	}

	public static volatile long DEFAULT_AWAIT_TIME = 1;
	public static volatile TimeUnit DEFAULT_AWAIT_TIME_UNIT = TimeUnit.MINUTES;

	/**
	 * Useful for some tests that can be flaky due to the timing of interleaving of threads.
	 * @param maxRetryCount how many times to retry on failure the {@code runnable} before failing the test
	 * @param runnable code to run
	 * @throws Throwable if {@code maxRetryCount} has been exceeded and an error has been thrown every
	 *                   time it will be thrown
	 */
	public static void retryOnFailure(int maxRetryCount, ThrowableRunnable runnable) throws Throwable {
		Throwable err = null;
		int count = maxRetryCount;
		do {
			// reset at beginning
			err = null;
			try {
				runnable.run();
			} catch (Throwable t) {
				err = t;
			}
		} while (--count > 0 && err != null);
		if (err != null) {
			throw err;
		}
	}

	ScheduledExecutorService scheduler;
	volatile CountDownLatch[] latches = new CountDownLatch[0];
	volatile Throwable throwable;
	volatile boolean completeImmediately = false;

	public AsyncTestHelper() {
		this(1);
	}

	public AsyncTestHelper(int executorThreadPoolSize) {
		this.scheduler = Executors.newScheduledThreadPool(executorThreadPoolSize);
	}

	/**
	 * Submits a task to run asynchronously on another thread. This already
	 * wraps the {@link ThrowableRunnable} so that errors are propagated to
	 * the test thread.
	 *
	 * @param runnable to be wrapped and run on another thread
	 */
	public void submitToExecutor(ThrowableRunnable runnable) {
		scheduler.submit(getWrappedRunnable(runnable));
	}

	/**
	 * Submits a task to run asynchronously on another thread. This already
	 * wraps the {@link ThrowableRunnable} so that errors are propagated to
	 * the test thread.
	 *
	 * @param delay amount of time (in {@code timeUnit}) to wait before running
	 * @param timeUnit time unit for {@code delay}
	 * @param runnable to be wrapped and run on another thread
	 */
	public void submitToExecutor(long delay, TimeUnit timeUnit, ThrowableRunnable runnable) {
		scheduler.schedule(getWrappedRunnable(runnable), delay, timeUnit);
	}

	/**
	 * Allows for scheduling some type of async event that will run periodically.
	 * A use case for this is that the user may want to assert some condition
	 * that may happen within some interval and then complete
	 *
	 * @param initialDelay how long to wait before first run
	 * @param period how often to (attempt to) rerun
	 * @param timeUnit time unit of {@code period} and {@code initialDelay}
	 * @param runnable to run periodically
	 * @return the scheduled future that will run periodically so that it may be cancelled if desired
	 */
	public ScheduledFuture<?> schedulePeriodic(long initialDelay, long period, TimeUnit timeUnit, ThrowableRunnable runnable) {
		return scheduler.scheduleAtFixedRate(getWrappedRunnable(runnable), initialDelay, period, timeUnit);
	}

	/**
	 * See {@link ExecutorService#shutdownNow()}.
	 *
	 * @return non-executed tasks with the executor service
	 */
	public List<Runnable> shutdownNow() {
		return this.scheduler.shutdownNow();
	}

	/**
	 * Shuts down the executor service, completing all currently submitted tasks,
	 * but disallowing future tasks from being submitted. This does not block, if
	 * you need to wait for task completion use {@link #shutdown(long, TimeUnit)}.
	 */
	public void shutdown() {
		this.scheduler.shutdown();
	}

	/**
	 * Shuts down the executor service and waits for it to complete all tasks.
	 *
	 * @param timeout how long to wait in the {@code timeUnit} provided
	 * @param timeUnit the time unit of {@code timeout}
	 * @return whether or not the executor service shutdown within the timeout period
	 * @throws InterruptedException if the shutdown was interrupted
	 */
	public boolean shutdown(long timeout, TimeUnit timeUnit) throws InterruptedException {
		this.scheduler.shutdown();
		return this.scheduler.awaitTermination(timeout, timeUnit);
	}

	/**
	 * Whether or not the executor service has been shutdown.
	 * See {@link ExecutorService#isShutdown()}.
	 *
	 * @return whether or not the executor service has been shutdown.
	 */
	public boolean isShutdown() {
		return this.scheduler.isShutdown();
	}

	/**
	 * Whether or not the executor service has been terminated.
	 * See {@link ExecutorService#isTerminated()}.
	 *
	 * @return whether or not the executor service has been terminated.
	 */
	public boolean isTerminated() {
		return this.scheduler.isTerminated();
	}

	/**
	 * Creates a new latch that {@link #await()} will wait upon for completion.
	 * Any amount of these can be created (on or off the test thread) and all
	 * must complete before the helper will continue after the {@link #await()}
	 * call.
	 *
	 * It should be noted that it is likely the user needs to create one of these
	 * on the test thread so that it is registered - if it is created in an async
	 * manner it may be created after the {@link #await()} call, which will cause
	 * effectively make await finish immediately.
	 *
	 * @param count how many countdowns the latch needs to close
	 * @return the created countdown latch
	 */
	public synchronized CountDownLatch getNewCountdownLatch(int count) {
		CountDownLatch latch = new CountDownLatch(count);
		CountDownLatch[] copy = Arrays.copyOf(this.latches, this.latches.length + 1);
		copy[copy.length - 1] = latch;
		this.latches = copy;
		return latch;
	}

	public boolean helperFailed() {
		return this.throwable != null;
	}

	/**
	 * This is not synchronized, because it would cause issues with calls
	 * to {@link #getNewCountdownLatch(int)} that occur in separate threads - due to
	 * {@link #await(long, TimeUnit)} querying this many times to check
	 * if the latches have been cleared and thus can exit.
	 *
	 * @return whether or not all latches created by this instance have closed
	 */
	private boolean allLatchesCleared() {
		for (int i = 0; i < this.latches.length; i++) {
			if (latches[i].getCount() > 0) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Fails the helper so that the async error will be propagated to the test thread.
	 *
	 * @param message failure message to be returned to fail the test.
	 */
	public void fail(String message) {
		fail(new Exception(message));
	}

	/**
	 * Fails the helper so that the async error will be propagated to the test thread.
	 *
	 * @param throwable throwable to be returned to fail the test.
	 */
	public void fail(Throwable throwable) {
		if (this.throwable == null) {
			this.throwable = throwable;
		}
	}

	/**
	 * Used to wrap code that runs in a different thread so that errors will
	 * be caught and propagated to the test thread so that it fails.
	 *
	 * @param runnable async code to run
	 */
	public void wrapAsyncThrowable(ThrowableRunnable runnable) {
		try {
			runnable.run();
		} catch (Throwable t) {
			fail(t);
		}
	}

	/**
	 * Used to wrap code that runs in a different thread so that errors will
	 * be caught and propagated to the test thread so that it fails.
	 *
	 * @param runnable async code to run
	 * @return a runnable that is wrapped with {@link #wrapAsyncThrowable(ThrowableRunnable)}
	 */
	public Runnable getWrappedRunnable(ThrowableRunnable runnable) {
		return () -> {
			wrapAsyncThrowable(runnable);
		};
	}

	/**
	 * Wraps a callable so that if it throws an exception it will fail the
	 * test and rethrow the exception to the caller.
	 *
	 * @param callable the callable to wrap to catch async exceptions
	 * @param <T> the return type
	 * @return the wrapped callable
	 */
	public <T> Callable<T> getWrappedCallable(Callable<T> callable) {
		return () -> {
			try {
				return callable.call();
			} catch (Throwable t) {
				// call failure to be picked up by test thread, then throw to fail on async thread
				fail(t);
				throw t;
			}
		};
	}

	/**
	 * Completes this async helper immediately, causing await() to finish, regardless
	 * of whether or not there are any latches open.
	 */
	public void completeImmediately() {
		this.completeImmediately = true;
	}

	/**
	 * Will block calling thread (almost always the test thread) until latches
	 * (created on this instance) complete, an async throwable is caught (was
	 * wrapped by {@link #wrapAsyncThrowable(ThrowableRunnable)}), or until
	 * timeout - which will throw an exception.
	 *
	 * Awaits for the default amount of time {@link AsyncTestHelper#DEFAULT_AWAIT_TIME}
	 * {@link AsyncTestHelper#DEFAULT_AWAIT_TIME_UNIT}. These can be overridden by the
	 * user for their tests.
	 *
	 * @throws Throwable if an error occurred in an async operation (so long as it was wrapped)
	 */
	public void await() throws Throwable {
		await(DEFAULT_AWAIT_TIME, DEFAULT_AWAIT_TIME_UNIT);
	}

	/**
	 * Will block calling thread (almost always the test thread) until latches
	 * (created on this instance) complete, an async throwable is caught (was
	 * wrapped by {@link #wrapAsyncThrowable(ThrowableRunnable)}), or until
	 * timeout - which will throw an exception.
	 *
	 * Awaits for the default amount of time {@link AsyncTestHelper#DEFAULT_AWAIT_TIME}
	 * {@link AsyncTestHelper#DEFAULT_AWAIT_TIME_UNIT}. These can be overridden by the
	 * user for their tests.
	 *
	 * @param message optional message to append to timeout message
	 * @throws Throwable if an error occurred in an async operation (so long as it was wrapped)
	 */
	public void await(String message) throws Throwable {
		await(DEFAULT_AWAIT_TIME, DEFAULT_AWAIT_TIME_UNIT, message);
	}

	/**
	 * Will block calling thread (almost always the test thread) until latches
	 * (created on this instance) complete, an async throwable is caught (was
	 * wrapped by {@link #wrapAsyncThrowable(ThrowableRunnable)}), or until
	 * timeout - which will throw an exception.
	 *
	 * @param timeout the amount of time to wait for latches to close
	 * @param timeUnit time unit for {@code timeout}
	 * @throws Throwable if an error occurred in an async operation (so long as it was wrapped)
	 */
	public void await(long timeout, TimeUnit timeUnit) throws Throwable {
		await(timeout, timeUnit, null);
	}

	private Callable<Boolean> makeTimeExpiredFromNowCallable(long timeout, TimeUnit timeUnit) {
		long startTime = System.nanoTime();
		return () -> timeUnit.convert((System.nanoTime() - startTime), TimeUnit.NANOSECONDS) >= timeout;
	}

	/**
	 * Will block calling thread (almost always the test thread) until latches
	 * (created on this instance) complete, an async throwable is caught (was
	 * wrapped by {@link #wrapAsyncThrowable(ThrowableRunnable)}), or until
	 * timeout - which will throw an exception.
	 *
	 * @param timeout the amount of time to wait for latches to close
	 * @param timeUnit time unit for {@code timeout}
	 * @param message optional message to append to timeout message
	 * @throws Throwable if an error occurred in an async operation (so long as it was wrapped)
	 */
	public void await(long timeout, TimeUnit timeUnit, String message) throws Throwable {
		message = "Await timed out after " + timeout + " " + timeUnit.name() + ". " +
			(message != null ? message : "");
		/*
		 * Need to use >=, because when converting from a finer-grained interval
		 * to a less-fine-grained interval, it shaves off the remainder.
		 * E.g., when using a timeout in minutes, the granularity is a minute, so
		 * if it was desired to timeout after a minute, >= must be used or else
		 * 1 > 1 is false and would have to wait until 2 minutes elapsed.
		 * */
		Callable<Boolean> awaitTimeExpired = makeTimeExpiredFromNowCallable(timeout, timeUnit);
		while (true) {
			// check for error first, then check if latches have been cleared then check expiration
			if (this.throwable != null) {
				throw this.throwable;
			} else if (allLatchesCleared()) {
				break;
			} else if (completeImmediately) {
				break;
			} else if (awaitTimeExpired.call()) {
				throw new TimeoutException(message);
			}
		}
	}

	/**
	 * Sleeps the thread and checks if an exception was thrown asynchronously.
	 * This is useful in cases where it is difficult to use latches (e.g., waiting
	 * to make sure an event or exception DOES NOT occur) and allows for waiting
	 * and then checking if an event occurred. This does not actually call sleep,
	 * but spins until the timeout expires.
	 *
	 * This is more succinct than creating a latch, executing on another thread,
	 * sleeping the thread for X amount of time, and then counting down the latch
	 * after the time expires.
	 *
	 * @param sleepTime how long to sleep the current thread
	 * @param timeUnit {@code sleepTime}'s time unit
	 */
	public void sleepAndThrowIfFailureOccurs(long sleepTime, TimeUnit timeUnit) throws Throwable {
		Callable<Boolean> waitTimeExpired = makeTimeExpiredFromNowCallable(sleepTime, timeUnit);
		while (!waitTimeExpired.call()) {
			// check during sleep time if an error occurred.
			if (this.helperFailed()) {
				throw this.throwable;
			}
		}
		// check 1 more time for good measure.
		if (this.helperFailed()) {
			throw this.throwable;
		}
	}

}
