package com.flowforge.config;

import com.flowforge.engine.RetryPolicy;
import com.flowforge.engine.TaskSimulator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public RetryPolicy retryPolicy(FlowForgeProperties props) {
        return new RetryPolicy(props.getRetry());
    }

    @Bean
    public TaskSimulator taskSimulator() {
        return new TaskSimulator();
    }
}