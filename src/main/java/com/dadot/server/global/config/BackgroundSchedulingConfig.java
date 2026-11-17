package com.dadot.server.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class BackgroundSchedulingConfig {

    @Bean
    ThreadPoolTaskScheduler interviewGenerationScheduler() {
        return scheduler("dadot-interview-generation-");
    }

    @Bean
    ThreadPoolTaskScheduler interviewEvaluationScheduler() {
        return scheduler("dadot-interview-evaluation-");
    }

    @Bean
    ThreadPoolTaskScheduler quizGenerationScheduler() {
        return scheduler("dadot-quiz-generation-");
    }

    @Bean
    ThreadPoolTaskScheduler disclosureScheduler() {
        return scheduler("dadot-disclosure-");
    }

    @Bean
    ThreadPoolTaskScheduler accountDeletionScheduler() {
        return scheduler("dadot-account-deletion-");
    }

    @Bean(name = {"verificationMaintenanceScheduler", "taskScheduler"})
    ThreadPoolTaskScheduler verificationMaintenanceScheduler() {
        return scheduler("dadot-auth-maintenance-");
    }

    @Bean
    ThreadPoolTaskScheduler quizCleanupScheduler() {
        return scheduler("dadot-quiz-cleanup-");
    }

    @Bean
    ThreadPoolTaskScheduler disclosureCleanupScheduler() {
        return scheduler("dadot-disclosure-cleanup-");
    }

    private static ThreadPoolTaskScheduler scheduler(String threadNamePrefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        // 작업 종료 후 fixed delay를 기다리므로 같은 작업의 실행이 쌓이지 않는다.
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }
}
