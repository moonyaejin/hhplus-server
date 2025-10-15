package kr.hhplus.be.server.concurrency.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 동시성 테스트를 위한 TaskExecutor 설정
 * - Spring이 관리하는 스레드 풀 제공
 * - 트랜잭션 컨텍스트 자동 관리
 */

@TestConfiguration
public class TestTaskExecutorConfig {
    /**
     * 일반적인 동시성 테스트용 Executor
     */

    @Bean(name = "concurrencyTestExecutor")
    public TaskExecutor concurrencyTestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("test-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler((r, e) -> {
            // 큐가 가득 찬 경우 호출 스레드에서 직접 실행
            if (!e.isShutdown()) {
                r.run();
            }
        });
        executor.initialize();
        return executor;
    }

    /**
     * 대규모 동시성 테스트용 Executor
     */
    @Bean(name = "extremeTestExecutor")
    public TaskExecutor extremeTestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(50);
        executor.setMaxPoolSize(100);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("extreme-test-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler((r, e) -> {
            // 큐가 가득 찬 경우 호출 스레드에서 직접 실행
            if (!e.isShutdown()) {
                r.run();
            }
        });
        executor.initialize();
        return executor;
    }

    /**
     * 단일 스레드 테스트용 Executor
     */

    @Bean(name = "singleThreadTestExecutor")
    public TaskExecutor singleThreadTestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("single-test-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
