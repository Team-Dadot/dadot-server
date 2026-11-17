package com.dadot.server.global.config;

import com.dadot.server.domain.auth.service.VerificationMaintenance;
import com.dadot.server.domain.disclosure.service.DisclosureWorker;
import com.dadot.server.domain.interview.service.InterviewEvaluationWorker;
import com.dadot.server.domain.interview.service.InterviewGenerationWorker;
import com.dadot.server.domain.member.service.AccountDeletionWorker;
import com.dadot.server.domain.quiz.service.QuizGenerationWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class BackgroundSchedulingIsolationTest {
    private static final List<Schedule> SCHEDULES = List.of(
            new Schedule(InterviewGenerationWorker.class, "poll", "interviewGenerationScheduler", "dadot-interview-generation-"),
            new Schedule(InterviewEvaluationWorker.class, "poll", "interviewEvaluationScheduler", "dadot-interview-evaluation-"),
            new Schedule(QuizGenerationWorker.class, "poll", "quizGenerationScheduler", "dadot-quiz-generation-"),
            new Schedule(DisclosureWorker.class, "poll", "disclosureScheduler", "dadot-disclosure-"),
            new Schedule(AccountDeletionWorker.class, "tick", "accountDeletionScheduler", "dadot-account-deletion-"),
            new Schedule(VerificationMaintenance.class, "clean", "verificationMaintenanceScheduler", "dadot-auth-maintenance-"),
            new Schedule(QuizGenerationWorker.class, "cleanup", "quizCleanupScheduler", "dadot-quiz-cleanup-"),
            new Schedule(DisclosureWorker.class, "cleanup", "disclosureCleanupScheduler", "dadot-disclosure-cleanup-")
    );

    static Stream<Schedule> schedules() {
        return SCHEDULES.stream();
    }

    @ParameterizedTest(name = "{0} 지연 중에도 다른 예약 작업은 계속 실행된다")
    @MethodSource("schedules")
    void slowWorkDoesNotBlockOtherSchedulesOrAccumulateAnotherRun(Schedule blocked) throws Exception {
        try (var fixture = new Fixture(blocked, false)) {
            Probe waiting = fixture.probes.get(blocked);
            assertTrue(waiting.entered.await(5, TimeUnit.SECONDS), "느린 작업 시작");
            var progressed = new CountDownLatch(SCHEDULES.size() - 1);
            fixture.probes.forEach((schedule, probe) -> {
                if (!schedule.equals(blocked)) probe.expectNextRun(progressed);
            });
            assertTrue(progressed.await(3, TimeUnit.SECONDS), "느린 작업 중 나머지 7개 작업 실행");
            assertFalse(waiting.finished.await(100, TimeUnit.MILLISECONDS));
            assertEquals(1, waiting.calls.get(), "완료 전 같은 작업 재실행 금지");
            assertEquals(1, waiting.maximumActive.get());
            assertTrue(fixture.scheduler(blocked).getScheduledThreadPoolExecutor().getQueue().isEmpty(),
                    "긴 작업의 다음 실행이 큐에 누적되지 않아야 한다");
            fixture.probes.forEach((schedule, probe) -> {
                assertEquals(1, fixture.scheduler(schedule).getPoolSize());
                assertTrue(probe.threadName.startsWith(schedule.threadPrefix()),
                        schedule + " 실제 예약 실행의 전용 스레드");
            });
        }
    }

    @Test
    void anUnexpectedFailureDoesNotStopSubsequentRunsOrOtherSchedules() throws Exception {
        Schedule failing = SCHEDULES.getFirst();
        try (var fixture = new Fixture(null, true)) {
            Probe probe = fixture.probes.get(failing);
            assertTrue(probe.failed.await(5, TimeUnit.SECONDS));
            var nextRun = new CountDownLatch(1);
            probe.expectNextRun(nextRun);
            assertTrue(nextRun.await(3, TimeUnit.SECONDS));
            for (Probe other : fixture.probes.values()) {
                assertTrue(other.entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, other.maximumActive.get());
            }
        }
    }

    @Test
    void closingTheContextStopsSchedulesAndInterruptsWorkAfterTheShutdownDeadline() throws Exception {
        Schedule blocked = SCHEDULES.getFirst();
        var fixture = new Fixture(blocked, false);
        List<ThreadPoolTaskScheduler> schedulers = SCHEDULES.stream().map(fixture::scheduler).toList();
        try {
            assertTrue(fixture.probes.get(blocked).entered.await(5, TimeUnit.SECONDS));
            // 운영의 종료 유예시간 대신 이 컨텍스트만 100ms로 줄여 종료 경로를 검증한다.
            fixture.context.getBean("lifecycleProcessor", DefaultLifecycleProcessor.class)
                    .setTimeoutPerShutdownPhase(100);
            fixture.context.close();
            for (var scheduler : schedulers) {
                assertTrue(scheduler.getScheduledThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(scheduler.getScheduledThreadPoolExecutor().getQueue().isEmpty());
            }
            assertTrue(fixture.probes.get(blocked).interrupted.get());
        } finally {
            fixture.close();
        }
    }

    record Schedule(Class<?> worker, String method, String scheduler, String threadPrefix) {
        @Override
        public String toString() {
            return worker.getSimpleName() + "." + method;
        }
    }

    private static class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final Map<Schedule, Probe> probes = new LinkedHashMap<>();

        Fixture(Schedule blocked, boolean failFirst) throws Exception {
            Map<String, Object> properties = new LinkedHashMap<>();
            for (String key : List.of("app.interview-generation.worker-delay-ms",
                    "app.interview-evaluation.worker-delay-ms", "app.quiz-generation.worker-delay-ms",
                    "app.dart.worker-delay-ms", "app.account-deletion.delay-ms",
                    "app.email-verification.maintenance-delay-ms", "app.quiz-generation.cleanup-delay-ms",
                    "app.dart.cleanup-delay-ms")) {
                properties.put(key, "20");
            }
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("scheduling-test", properties));
            context.register(BackgroundSchedulingConfig.class);
            Map<Class<?>, Object> workers = new LinkedHashMap<>();
            for (Schedule schedule : SCHEDULES) {
                Probe probe = new Probe(schedule.equals(blocked), failFirst && schedule.equals(SCHEDULES.getFirst()));
                probes.put(schedule, probe);
                Object worker = workers.computeIfAbsent(schedule.worker(), type -> mock(type));
                Object stub = doAnswer(invocation -> {
                    probe.run();
                    return null;
                }).when(worker);
                schedule.worker().getMethod(schedule.method()).invoke(stub);
            }
            // 본문은 가짜 작업으로 대체하지만 실제 운영 클래스의 @Scheduled가 라우팅한다.
            // DB·AI·SMTP·DART 의존성은 만들거나 호출하지 않는다.
            workers.forEach(this::registerWorker);
            try {
                context.refresh();
            } catch (RuntimeException | Error ex) {
                close();
                throw ex;
            }
        }

        private <T> void registerWorker(Class<T> type, Object worker) {
            context.registerBean(type, () -> type.cast(worker));
        }

        ThreadPoolTaskScheduler scheduler(Schedule schedule) {
            return context.getBean(schedule.scheduler(), ThreadPoolTaskScheduler.class);
        }

        @Override
        public void close() {
            probes.values().forEach(probe -> probe.release.countDown());
            context.close();
        }
    }

    private static class Probe {
        final boolean blocked;
        final AtomicBoolean failFirst;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maximumActive = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch failed = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        volatile String threadName = "";
        volatile Runnable onNextRun = () -> {};

        Probe(boolean blocked, boolean failFirst) {
            this.blocked = blocked;
            this.failFirst = new AtomicBoolean(failFirst);
        }

        void expectNextRun(CountDownLatch progress) {
            var once = new AtomicBoolean();
            onNextRun = () -> {
                if (once.compareAndSet(false, true)) progress.countDown();
            };
        }

        void run() {
            calls.incrementAndGet();
            maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            threadName = Thread.currentThread().getName();
            entered.countDown();
            try {
                if (blocked && !release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test work timed out");
                }
                if (failFirst.compareAndSet(true, false)) {
                    failed.countDown();
                    throw new IllegalStateException("test work failure");
                }
                onNextRun.run();
            } catch (InterruptedException ex) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
                finished.countDown();
            }
        }
    }
}
