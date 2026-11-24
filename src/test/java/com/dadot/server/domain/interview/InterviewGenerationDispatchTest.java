package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.repository.InterviewGenerationRepository;
import com.dadot.server.domain.interview.service.InterviewGenerationWorker;
import com.dadot.server.domain.interview.support.InterviewGenerationCapacity;
import com.dadot.server.domain.interview.support.InterviewGenerationProperties;
import com.dadot.server.global.config.InterviewGenerationExecutionConfig;
import com.dadot.server.infrastructure.openai.OpenAiInterviewClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InterviewGenerationDispatchTest {
    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void configuredCallsOverlapWhileRepeatedTicksDoNotQueueOrRunOnSchedulerThread(int concurrent) throws Exception {
        try (var f = new Fixture(concurrent)) {
            var entered = new CountDownLatch(concurrent);
            var release = new CountDownLatch(1);
            var finished = new CountDownLatch(concurrent);
            var active = new AtomicInteger();
            var maximum = new AtomicInteger();
            var wrongThread = new AtomicReference<String>();
            doAnswer(invocation -> {
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                if (!Thread.currentThread().getName().startsWith("dadot-interview-generation-exec-")) {
                    wrongThread.set(Thread.currentThread().getName());
                }
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                finally { active.decrementAndGet(); finished.countDown(); }
                return null;
            }).when(f.worker).runOnce();
            try {
                for (int i = 0; i < concurrent; i++) f.worker.poll();
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                for (int i = 0; i < 100; i++) f.worker.poll();
                verify(f.worker, times(concurrent)).runOnce();
                assertEquals(concurrent, maximum.get());
                assertEquals(0, f.executor.getQueueSize());
                assertNull(wrongThread.get());
            } finally {
                release.countDown();
                assertTrue(finished.await(3, TimeUnit.SECONDS));
            }
            // 앞의 작업이 완전히 반환한 뒤 다음 tick에서도 슬롯을 다시 사용할 수 있다.
            doNothing().when(f.worker).runOnce();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (mockingDetails(f.worker).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("runOnce")).count() < concurrent + 1
                    && System.nanoTime() < deadline) {
                f.worker.poll();
                Thread.sleep(10);
            }
            verify(f.worker, atLeast(concurrent + 1)).runOnce();
        }
    }

    @Test
    void oneConfiguredSlotPreservesSerialExecution() throws Exception {
        try (var f = new Fixture(1)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            doAnswer(i -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return null; })
                    .when(f.worker).runOnce();
            try {
                f.worker.poll();
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                for (int i = 0; i < 20; i++) f.worker.poll();
                verify(f.worker, times(1)).runOnce();
                assertEquals(1, f.executor.getMaxPoolSize());
                assertEquals(0, f.executor.getQueueSize());
            } finally { release.countDown(); }
        }
    }

    @Test
    void unexpectedStorageFailureReleasesLocalSlot() {
        var calls = new AtomicInteger();
        var worker = worker(new InterviewGenerationCapacity(1, 20), Runnable::run);
        doAnswer(i -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("test storage failure");
            return null;
        }).when(worker).runOnce();
        assertDoesNotThrow(worker::poll);
        worker.poll();
        assertEquals(2, calls.get());
    }

    @Test
    void rejectionDoesNotClaimTaskOrConsumeSlotAndNextTickCanRun() {
        var offers = new AtomicInteger();
        TaskExecutor executor = task -> {
            if (offers.incrementAndGet() == 1) throw new RejectedExecutionException("test saturation");
            task.run();
        };
        var worker = worker(new InterviewGenerationCapacity(1, 20), executor);
        doNothing().when(worker).runOnce();
        assertDoesNotThrow(worker::poll);
        verify(worker, never()).runOnce();
        worker.poll();
        verify(worker, times(1)).runOnce();
    }

    @Test
    void disabledOrUnreadyWorkerDoesNotDispatch() {
        TaskExecutor executor = mock(TaskExecutor.class);
        var props = mock(InterviewGenerationProperties.class);
        var client = mock(OpenAiInterviewClient.class);
        var worker = new InterviewGenerationWorker(mock(InterviewGenerationRepository.class), client,
                props, Clock.systemUTC(), mock(PlatformTransactionManager.class),
                new InterviewGenerationCapacity(2, 20), executor);
        worker.poll();
        when(props.workerEnabled()).thenReturn(true);
        worker.poll();
        verifyNoInteractions(executor);
    }

    @Test
    void closingContextRejectsFurtherOffersAndStopsExecutor() throws Exception {
        var f = new Fixture(2);
        f.close();
        doNothing().when(f.worker).runOnce();
        assertDoesNotThrow(f.worker::poll);
        assertDoesNotThrow(f.worker::poll);
        verify(f.worker, never()).runOnce();
        assertTrue(f.executor.getThreadPoolExecutor().awaitTermination(3, TimeUnit.SECONDS));
    }

    @Test
    void closingContextLetsRunningCallFinishWithinGracePeriod() throws Exception {
        var f = new Fixture(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        doAnswer(i -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            finally { done.countDown(); }
            return null;
        }).when(f.worker).runOnce();
        try {
            f.worker.poll();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var closer = new Thread(f::close);
            closer.start();
            release.countDown();
            closer.join(5000);
            assertFalse(closer.isAlive());
            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertTrue(f.executor.getThreadPoolExecutor().isTerminated());
        } finally { release.countDown(); f.close(); }
    }

    @ParameterizedTest
    @CsvSource({"0,20", "-1,20", "17,20", "2,1", "1,0", "2,1001"})
    void invalidLimitsFailBeforeStartingWorkers(int concurrent, int outstanding) {
        assertThrows(IllegalStateException.class,
                () -> new InterviewGenerationCapacity(concurrent, outstanding));
    }

    @ParameterizedTest
    @CsvSource({"1,1", "1,20", "2,20", "3,20", "16,1000"})
    void validLimitsAreIndependent(int concurrent, int outstanding) {
        var capacity = new InterviewGenerationCapacity(concurrent, outstanding);
        assertEquals(concurrent, capacity.maxConcurrent());
        assertEquals(outstanding, capacity.maxOutstanding());
    }

    @ParameterizedTest
    @CsvSource({"1,3", "3,20"})
    void operatorEnvironmentVariablesBindToCapacityAndActualExecutor(int concurrent, int outstanding) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.SystemEnvironmentPropertySource("operator-env", java.util.Map.of(
                            "APP_INTERVIEW_GENERATION_MAX_CONCURRENT", Integer.toString(concurrent),
                            "APP_INTERVIEW_GENERATION_MAX_OUTSTANDING", Integer.toString(outstanding))));
            context.register(InterviewGenerationCapacity.class, InterviewGenerationExecutionConfig.class);
            context.refresh();
            var capacity = context.getBean(InterviewGenerationCapacity.class);
            assertEquals(concurrent, capacity.maxConcurrent());
            assertEquals(outstanding, capacity.maxOutstanding());
            assertEquals(concurrent, context.getBean(ThreadPoolTaskExecutor.class).getMaxPoolSize());
        }
    }

    @Test
    void absentOverridesUseThreeConcurrentAndTwentyOutstanding() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(InterviewGenerationCapacity.class, InterviewGenerationExecutionConfig.class);
            context.refresh();
            var capacity = context.getBean(InterviewGenerationCapacity.class);
            assertEquals(3, capacity.maxConcurrent());
            assertEquals(20, capacity.maxOutstanding());
            var executor = context.getBean(ThreadPoolTaskExecutor.class);
            assertEquals(3, executor.getCorePoolSize());
            assertEquals(3, executor.getMaxPoolSize());
            assertEquals(0, executor.getQueueCapacity());
        }
    }

    private static InterviewGenerationWorker worker(InterviewGenerationCapacity capacity, TaskExecutor executor) {
        var props = mock(InterviewGenerationProperties.class);
        when(props.workerEnabled()).thenReturn(true);
        var client = mock(OpenAiInterviewClient.class);
        when(client.ready()).thenReturn(true);
        return spy(new InterviewGenerationWorker(mock(InterviewGenerationRepository.class), client,
                props, Clock.systemUTC(), mock(PlatformTransactionManager.class), capacity, executor));
    }

    private static class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final ThreadPoolTaskExecutor executor;
        final InterviewGenerationWorker worker;
        Fixture(int concurrent) {
            var capacity = new InterviewGenerationCapacity(concurrent, 20);
            context.registerBean(InterviewGenerationCapacity.class, () -> capacity);
            context.register(InterviewGenerationExecutionConfig.class);
            context.refresh();
            executor = context.getBean("interviewGenerationExecutor", ThreadPoolTaskExecutor.class);
            worker = worker(capacity, executor);
        }
        @Override public void close() { context.close(); }
    }
}
