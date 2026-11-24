package com.dadot.server.global.config;

import com.dadot.server.domain.interview.support.InterviewGenerationCapacity;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class InterviewGenerationExecutionConfig {
    @Bean
    ThreadPoolTaskExecutor interviewGenerationExecutor(InterviewGenerationCapacity capacity) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(capacity.maxConcurrent());
        executor.setMaxPoolSize(capacity.maxConcurrent());
        // 대기는 DB ai_tasks가 맡는다. JVM 메모리에 작업 큐를 추가하지 않는다.
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("dadot-interview-generation-exec-");
        executor.setStrictEarlyShutdown(true);
        // 예약 작업을 먼저 멈춘 뒤 진행 중 실행을 Spring 종료 절차로 정리한다.
        executor.setPhase(ThreadPoolTaskExecutor.DEFAULT_PHASE - 1);
        executor.setAwaitTerminationSeconds(5);
        LoggerFactory.getLogger(InterviewGenerationExecutionConfig.class).info(
                "Interview generation capacity maxConcurrent={} maxOutstanding={}",
                capacity.maxConcurrent(), capacity.maxOutstanding());
        return executor;
    }
}
