package com.fx.orchestrator;

import java.time.Duration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * The upstream rate feed.
 *
 * It pushes a batch of fresh rates to fx-app-spring, then waits to be called back with
 * ACCEPTED or DECLINED, and uses that answer to decide how soon to push again. The logs
 * narrate every decision it makes.
 */
@SpringBootApplication
public class FxOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(FxOrchestratorApplication.class, args);
    }

    /**
     * Short timeouts on purpose: if the downstream app is down or slow, the feed must not block
     * its own scheduler thread waiting for it.
     */
    @Bean
    RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(2))
                .setReadTimeout(Duration.ofSeconds(3))
                .build();
    }
}
