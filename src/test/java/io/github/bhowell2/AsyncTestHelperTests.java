package io.github.bhowell2;

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Blake Howell
 */
public class AsyncTestHelperTests {

	@Test
	public void shouldTimeout() throws Throwable {
		assertThrows(TimeoutException.class, () -> {
			AsyncTestHelper async = new AsyncTestHelper();
			CountDownLatch latch = async.getNewLatch(1);
			async.await(5, TimeUnit.MILLISECONDS);
		});
	}

	@Test
	public void shouldNotTimeout() throws Throwable {
		AsyncTestHelper async = new AsyncTestHelper();
		CountDownLatch latch = async.getNewLatch(1);
		latch.countDown();
		async.await(1, TimeUnit.MILLISECONDS);

		AsyncTestHelper async2 = new AsyncTestHelper();
		CountDownLatch latch2 = async2.getNewLatch(2);
		// will complete almost immediately, since is run on separate thread
		async2.submitToExecutor(() -> {
			latch2.countDown();
		});
		async2.submitToExecutor(() -> {
			latch2.countDown();
		});
		async2.await(10, TimeUnit.MILLISECONDS);
	}

	@Test
	public void shouldFailFromTimeoutFromMultipleLatches() throws Throwable {
		assertThrows(TimeoutException.class, () -> {
			AsyncTestHelper async = new AsyncTestHelper();
			CountDownLatch latch = async.getNewLatch(1);
			CountDownLatch latch2 = async.getNewLatch(1);
			latch.countDown();
			async.await(5, TimeUnit.MILLISECONDS);
		});
	}

	@Test
	public void shouldCompleteSuccessfullyWithMultipleLatches() throws Throwable {
		AsyncTestHelper async = new AsyncTestHelper();
		CountDownLatch latch = async.getNewLatch(2);
		// will complete almost immediately, since is run on separate thread
		async.submitToExecutor(() -> {
			latch.countDown();
		});
		async.submitToExecutor(() -> {
			latch.countDown();
		});
		CountDownLatch latch2 = async.getNewLatch(1);
		async.submitToExecutor(1, TimeUnit.MICROSECONDS, () -> {
			latch2.countDown();
		});
		async.await(20, TimeUnit.MILLISECONDS);
	}

	@Test
	public void shouldCompleteSuccessfullyWithLatchCreatedOnSeparateThread() throws Throwable {
		AsyncTestHelper async = new AsyncTestHelper();
		CountDownLatch latch = async.getNewLatch(2);
		// will complete almost immediately, since is run on separate thread
		async.submitToExecutor(() -> {
			latch.countDown();
		});
		async.submitToExecutor(() -> {
			latch.countDown();
		});
		async.submitToExecutor(1, TimeUnit.MICROSECONDS, () -> {
			CountDownLatch latch2 = async.getNewLatch(1);
			Thread.sleep(1);
			latch2.countDown();
		});
		async.await(20, TimeUnit.MILLISECONDS);
	}

	@Test
	public void shouldShutdownAndNotBlock() throws Throwable {
		AtomicBoolean didRun = new AtomicBoolean(false);
		AsyncTestHelper async = new AsyncTestHelper(1);
		async.submitToExecutor(() -> {
			Thread.sleep(5);
			didRun.set(true);
		});
		async.shutdown();
		assertTrue(async.isShutdown());
		assertFalse(async.isTerminated());
		Thread.sleep(10);
		assertTrue(async.isTerminated());
		assertTrue(didRun.get());
	}

	@Test
	public void shouldShutdownAfterExecutingTasksFinish() throws Throwable {
		AsyncTestHelper async = new AsyncTestHelper(1);
		long startTime = System.nanoTime();
		async.submitToExecutor(() -> {
			Thread.sleep(5);
		});
		assertTrue(async.shutdown(10, TimeUnit.MILLISECONDS));
		long stopTime = System.nanoTime();
		assertTrue(TimeUnit.MILLISECONDS.convert(stopTime - startTime, TimeUnit.NANOSECONDS) >= 5,
		           "Should not have shutdown before 5ms, because executing thread sleeps for 5ms.");
	}


	@Test
	public void shouldShutdownImmediately() throws Throwable {
		AtomicReference<Throwable> interruptedException = new AtomicReference<>(null);
		AsyncTestHelper async = new AsyncTestHelper(1);
		async.submitToExecutor(() -> {
			try {
				Thread.sleep(10);
			} catch (Exception e) {
				interruptedException.set(e);
			}
		});
		async.submitToExecutor(() -> {
			try {
				Thread.sleep(10);
			} catch (Exception e) {
				interruptedException.set(e);
			}
		});
		Thread.sleep(1);
		assertEquals(1, async.shutdownNow().size(),
		             "One task should be running (thread pool size = 1) and the other should be " +
			             "waiting to run. When cancelled ");
		Thread.sleep(3);
		assertNotNull(interruptedException.get());
		assertTrue(interruptedException.get() instanceof InterruptedException);
	}

	@Test
	public void shouldFailFromAsyncThrowable() throws Throwable {
		assertThrows(IllegalArgumentException.class, () -> {
			AsyncTestHelper async = new AsyncTestHelper();
			async.getNewLatch(1);
			new Thread(() -> {
				async.wrapAsyncThrowable(() -> {
					throw new IllegalArgumentException("Failed!");
				});
			}).start();
			async.await(1, TimeUnit.SECONDS);
		});
	}

	@Test
	public void shouldFailWhenFailureIsCalledOnAsyncTestHelper() throws Throwable {
		assertThrows(Exception.class, () -> {
			AsyncTestHelper async = new AsyncTestHelper();
			async.getNewLatch(1);
			async.submitToExecutor(() -> {
				async.fail("Some failure!");
			});
			async.await(1, TimeUnit.SECONDS);
		});
	}

	@Test
	public void shouldPassWrappedCallable() throws Throwable {
		AsyncTestHelper async = new AsyncTestHelper();
		CountDownLatch latch = async.getNewLatch(1);
		new Thread(() -> {
			// callable itself can throw, so must catch it..
			try {
				async.getWrappedCallable(() -> {
					latch.countDown();
					return 1;
				}).call();
			} catch (Exception e) {
				async.fail(e);
			}
		}).start();
		async.await();
	}

	@Test
	public void shouldFailWrappedCallable() throws Throwable {
		AsyncTestHelper asyncCallableCatch = new AsyncTestHelper();
		CountDownLatch latchCallableCatch = asyncCallableCatch.getNewLatch(1);
		try {
			AsyncTestHelper async = new AsyncTestHelper();
			// latch not used, because of thrown exception but needed for await to not exit immediately
			CountDownLatch latch = async.getNewLatch(1);
			new Thread(() -> {
				try {
					async.getWrappedCallable(() -> {
						throw new IllegalArgumentException("Fail");
					}).call();
				} catch (Exception e) {
					// the thrown exception will be caught in the wrapper to fail the main thread, then
					latchCallableCatch.countDown();
				}
			}).start();
			async.await();
		} catch (Exception e) {
			assertEquals(IllegalArgumentException.class, e.getClass());
			asyncCallableCatch.await();
		}
	}

	@Test
	public void shouldCompleteImmediately() throws Throwable {
		AsyncTestHelper async = new AsyncTestHelper();
		CountDownLatch latch1 = async.getNewLatch(1);
		CountDownLatch latch2 = async.getNewLatch(2);
		async.submitToExecutor(() -> {
			async.completeImmediately();
		});
		// latches are never called, but should not timeout because of completeImmediately call.
		async.await(20, TimeUnit.MILLISECONDS);
	}

	@Test
	public void shouldSuccessfullyAssertAsynchronously() throws Throwable {
		AsyncTestHelper async = new AsyncTestHelper();
		CountDownLatch latch = async.getNewLatch(1);
		async.submitToExecutor(() -> {
			assertEquals(1, 1);
			latch.countDown();
		});
		async.await();
	}

	@Test
	public void shouldFailOnAsyncAssertions() throws Throwable {
		assertThrows(AssertionFailedError.class, () -> {
			AsyncTestHelper async = new AsyncTestHelper();
			async.getNewLatch(1);
			async.submitToExecutor(() -> {
				assertEquals(1, 2);
			});
			async.await();
		});
	}

	@Test
	public void shouldSucceedWithRetry() throws Throwable {
		AtomicInteger counter = new AtomicInteger(0);
		AsyncTestHelper.retryOnFailure(5, () -> {
			AsyncTestHelper async = new AsyncTestHelper();
			CountDownLatch latch = async.getNewLatch(1);
			async.submitToExecutor(() -> {
				if (counter.getAndIncrement() < 3) {
					throw new RuntimeException("Failed.");
				}
				latch.countDown();
			});
			async.await();
		});
		assertEquals(4, counter.get());
	}

	@Test
	public void shouldFailWithRetry() throws Throwable {
		AtomicInteger counter = new AtomicInteger(0);
		assertThrows(RuntimeException.class, () -> {
			AsyncTestHelper.retryOnFailure(5, () -> {
				AsyncTestHelper async = new AsyncTestHelper();
				CountDownLatch latch = async.getNewLatch(1);
				async.submitToExecutor(() -> {
					counter.getAndIncrement();
					throw new RuntimeException("Failed.");
				});
				async.await();
			});
		});
		assertEquals(5, counter.get());
	}

}
