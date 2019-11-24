package io.github.bhowell2;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Provides async functionality for tests.
 * @author Blake Howell
 */
public class AsyncTestHelper {

	public static volatile long DEFAULT_AWAIT_TIME = 1;
	public static volatile TimeUnit DEFAULT_AWAIT_TIME_UNIT = TimeUnit.MINUTES;

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
	 * @param runnable to be wrapped and run on another thread
	 */
	public void submitToExecutor(ThrowableRunnable runnable) {
		scheduler.submit(getWrappedRunnable(runnable));
	}

	/**
	 * Submits a task to run asynchronously on another thread. This already
	 * wraps the {@link ThrowableRunnable} so that errors are propagated to
	 * the test thread.
	 * @param delay amount of time (in {@code timeUnit}) to wait before running
	 * @param timeUnit time unit for {@code delay}
	 * @param runnable the runnable to wrap and run in the future
	 */
	public void submitToExecutor(long delay, TimeUnit timeUnit, ThrowableRunnable runnable) {
		scheduler.schedule(getWrappedRunnable(runnable), delay, timeUnit);
	}

	/**
	 * See {@link ExecutorService#shutdownNow()}.
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
	 *
	 * @param timeout how long to wait in the {@code timeUnit} provided
	 * @param timeUnit the time unit of {@code timeout}
	 * @return whether or not the executor service shutdown within the timeout period
	 * @throws InterruptedException
	 */
	public boolean shutdown(long timeout, TimeUnit timeUnit) throws InterruptedException {
		this.scheduler.shutdown();
		return this.scheduler.awaitTermination(timeout, timeUnit);
	}

	/**
	 *
	 * @return
	 */
	public boolean isShutdown() {
		return this.scheduler.isShutdown();
	}

	/**
	 *
	 * @return
	 */
	public boolean isTerminated() {
		return this.scheduler.isTerminated();
	}

	/**
	 *
	 * @param count
	 * @return
	 */
	public synchronized CountDownLatch getNewLatch(int count) {
		CountDownLatch latch = new CountDownLatch(count);
		CountDownLatch[] copy = Arrays.copyOf(this.latches, this.latches.length + 1);
		copy[copy.length - 1] = latch;
		this.latches = copy;
		return latch;
	}

	/**
	 * This is not synchronized, because it would cause issues with calls
	 * to {@link #getNewLatch(int)} that occur in separate threads - due to
	 * {@link #await(long, TimeUnit)} querying this many times to check
	 * if the latches have been cleared and thus can exit.
	 * @return
	 */
	private boolean allLatchesCleared() {
		for (int i = 0; i < this.latches.length; i++) {
			if (latches[i].getCount() > 0) {
				return false;
			}
		}
		return true;
	}

	public void fail(String message) {
		fail(new Exception(message));
	}

	public void fail(Throwable throwable) {
		if (this.throwable == null) {
			this.throwable = throwable;
		}
	}

	/**
	 * Used to wrap code that runs in a different thread.
	 * @param runnable
	 */
	public void wrapAsyncThrowable(ThrowableRunnable runnable) {
		try {
			runnable.run();
		} catch (Throwable t) {
			fail(t);
		}
	}

	public Runnable getWrappedRunnable(ThrowableRunnable runnable) {
		return () -> {
			wrapAsyncThrowable(runnable);
		};
	}

	/**
	 *
	 * @param callable
	 * @param <T>
	 * @return
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

	public void completeImmediately() {
		this.completeImmediately = true;
	}

	/**
	 * Awaits for the default amount of time {@link AsyncTestHelper#DEFAULT_AWAIT_TIME}
	 * {@link AsyncTestHelper#DEFAULT_AWAIT_TIME_UNIT}. These can be overridden by the
	 * user for their tests.
	 */
	public void await() throws Throwable {
		await(DEFAULT_AWAIT_TIME, DEFAULT_AWAIT_TIME_UNIT);
	}

	/**
	 * Should be called on main test thread to block until latches complete
	 * or until timeout - which will throw an exception.
	 * @param timeout
	 * @param timeUnit
	 */
	public void await(long timeout, TimeUnit timeUnit) throws Throwable {
		long startTime = System.nanoTime();
		Callable<Boolean> awaitTimeExpired =
			() -> timeUnit.convert((System.nanoTime() - startTime), TimeUnit.NANOSECONDS) > timeout;
		while (true) {
			// check for error first, then check if latches have been cleared then check expiration
			if (this.throwable != null) {
				throw this.throwable;
			} else if (allLatchesCleared()) {
				break;
			} else if (completeImmediately) {
				break;
			} else if (awaitTimeExpired.call()) {
				throw new TimeoutException("Await timed out.");
			}
		}
	}

}
