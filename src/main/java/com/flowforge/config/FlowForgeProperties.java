package com.flowforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "flowforge")
public class FlowForgeProperties {

    private final Scheduler scheduler = new Scheduler();
    private final Worker worker = new Worker();
    private final Retry retry = new Retry();

    public Scheduler getScheduler() { return scheduler; }
    public Worker getWorker() { return worker; }
    public Retry getRetry() { return retry; }

    public static class Scheduler {
        /** Kill-switch: false stops the @Scheduled sweep (tests, passive instances). */
        private boolean enabled = true;
        private int batchSize = 100;
        private Duration leaseDuration = Duration.ofSeconds(30);
        /** Silence threshold before a worker reads UNHEALTHY. Decoupled from the
         * task lease: heartbeats (seconds) and leases (tens of seconds) fail
         * at different rates and must not share a knob. */
        private Duration workerTimeout = Duration.ofSeconds(90);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public Duration getLeaseDuration() { return leaseDuration; }
        public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
        public Duration getWorkerTimeout() { return workerTimeout; }
        public void setWorkerTimeout(Duration workerTimeout) { this.workerTimeout = workerTimeout; }
    }

    public static class Worker {
        private boolean enabled = false;
        private String id;
        private String hostname;
        private int cpuCapacity;
        private int memoryCapacityMb;
        private Duration heartbeatInterval = Duration.ofSeconds(2);
        private String serverUrl = "http://localhost:8080";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getHostname() { return hostname; }
        public void setHostname(String hostname) { this.hostname = hostname; }
        public int getCpuCapacity() { return cpuCapacity; }
        public void setCpuCapacity(int cpuCapacity) { this.cpuCapacity = cpuCapacity; }
        public int getMemoryCapacityMb() { return memoryCapacityMb; }
        public void setMemoryCapacityMb(int memoryCapacityMb) { this.memoryCapacityMb = memoryCapacityMb; }
        public Duration getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(Duration heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
        public String getServerUrl() { return serverUrl; }
        public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }
    }

    public static class Retry {
        private Duration initialBackoff = Duration.ofSeconds(2);
        private Duration maxBackoff = Duration.ofSeconds(60);
        private int defaultMaxAttempts = 3;

        public Duration getInitialBackoff() { return initialBackoff; }
        public void setInitialBackoff(Duration initialBackoff) { this.initialBackoff = initialBackoff; }
        public Duration getMaxBackoff() { return maxBackoff; }
        public void setMaxBackoff(Duration maxBackoff) { this.maxBackoff = maxBackoff; }
        public int getDefaultMaxAttempts() { return defaultMaxAttempts; }
        public void setDefaultMaxAttempts(int defaultMaxAttempts) { this.defaultMaxAttempts = defaultMaxAttempts; }
    }
}