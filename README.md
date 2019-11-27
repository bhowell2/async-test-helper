![](https://github.com/bhowell2/async-test-helper/workflows/build/badge.svg)
[![codecov](https://codecov.io/gh/bhowell2/async-test-helper/branch/master/graph/badge.svg)](https://codecov.io/gh/bhowell2/async-test-helper)
![](https://img.shields.io/maven-central/v/io.github.bhowell2/async-test-helper)
# Asynchronous Test Helper
Provides some basic and streamlined functionality for writing asynchronous tests. There are two problems with async 
testing: 
1. Waiting on async completion.
2. Exceptions thrown on threads other than the test thread.

To help reduce the overhead of dealing with these issues this has been created. 

## Installation
This may be obtained from the Maven Central repository.

#### Maven
```xml 
<dependency>
    <groupId>io.github.bhowell2</groupId>
    <artifactId>async-test-helper</artifactId>
    <version>1.2.0</version>
</dependency>
```

#### Gradle
```groovy
dependencies {
    compile "io.github.bhowell2:async-test-helper:1.2.0"
}
```

## Usage
This library is extremely simple. Sometime before an asynchronous call the user needs to create an `AsyncTestHelper` 
object, call `AsyncTestHelper.getNewLatch(count)` and after the async code call `AsyncTestHelper.await(...)`, which 
will wait for the specified amount of time before failing the tests. `AsyncTestHelper` has `DEFAULT_AWAIT_TIME` and 
`DEFAULT_AWAIT_TIME_UNIT` that can be overridden by the user so they do not need to call 
`AsyncTestHelper.await(1, TimeUnit.Seconds)` every time, but can instead just call `AsyncTestHelper.await()`.
Every call to `AsyncTestHelper.getNewLatch(count)` will create a new latch and `AsyncTestHelper.await()` will wait 
for all latches to complete (be fully counted down), an error to be thrown (which will be propagated to the test thread), 
`AsyncTestHelper.completeImmediately()` to be called, or for the await time to expire and a `TimeoutException` will be 
thrown to fail the test. See [exceptions](#exceptions) below for more information on how these are handled in async code.

### Exceptions 
When an exception occurs on another thread it does not fail the test thread, because the test thread does not know about 
the exception. To rectify this, async code that may fail should be wrapped with `wrapAsyncThrowable` or `getWrappedRunnable`. 
or `getWrappedCallable`. This will retrieve the exception on the test thread and throw it as if it occurred on the test 
thread. It should also be noted that so long as the async code is wrapped, `Assertions.*` can be used on other threads 
(they throw exceptions) and they will fail the test as if they were called on the test thread.

```java
@Test
public void shouldFailFromAsyncThrowable() throws Throwable {
    assertThrows(IllegalArgumentException.class, () -> {
        AsyncTestHelper async = new AsyncTestHelper();
        async.getNewLatch(1);
        new Thread(() -> {
            async.wrapAsyncThrowable(() -> {
                // if wrapping was not done (or even if it was), could 
                // call async.fail() to achieve the same result.
                throw new IllegalArgumentException("Failed!");
            });
        }).start();
        async.await(1, TimeUnit.SECONDS);
    });
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
```

### Retry on Failure
Sometimes async tests are just flaky due to the timing of the interleaving of threads - this problem becomes magnified 
when running on fewer cores. Though it is advised the user try to avoid using this if possible, this is provided for the 
most flaky of tests.

```java
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
```

See unit tests for more [examples](.//src/test/java/io/github/bhowell2/AsyncTestHelperTests.java).
