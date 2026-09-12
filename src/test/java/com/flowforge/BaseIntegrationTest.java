package com.flowforge;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared integration-test base: one Postgres container for the whole suite
 * (JVM singleton — a static @Container field would be restarted per subclass
 * container), Flyway migrations applied, background @Scheduled sweep DISABLED
 * (tests call {@code scheduler.runPass()} explicitly for determinism),
 * embedded worker disabled (tests drive the claim/ack protocol directly).
 *
 * Every test method starts from an empty database (TRUNCATE in @BeforeEach),
 * so test classes and methods are fully order-independent despite sharing the
 * container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Kill the background @Scheduled sweep: tests drive runPass()
        // explicitly for determinism. (spring.task.scheduling.enabled does
        // NOT stop @EnableScheduling processing; the flag below does.)
        "flowforge.scheduler.enabled=false",
        "flowforge.worker.enabled=false",
        "flowforge.scheduler.scan-interval-ms=50",
        "flowforge.scheduler.lease-duration=500ms",
        "flowforge.scheduler.batch-size=10",
        "flowforge.retry.initial-backoff=100ms",
        "flowforge.retry.max-backoff=500ms",
        "flowforge.retry.default-max-attempts=3"
})
public abstract class BaseIntegrationTest {

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        // Ordered DELETEs (children first) take only ROW EXCLUSIVE locks, so
        // cleanup can never deadlock with concurrent readers — unlike
        // TRUNCATE, which needs ACCESS EXCLUSIVE on every table at once.
        jdbc.execute("DELETE FROM task_events");
        jdbc.execute("DELETE FROM task_outputs");
        jdbc.execute("DELETE FROM task_executions");
        jdbc.execute("DELETE FROM workflow_executions");
        jdbc.execute("DELETE FROM workflows");
        jdbc.execute("DELETE FROM workers");
    }
}
