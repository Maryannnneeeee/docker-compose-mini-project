package com.fx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The currency exchange service: REST endpoints over JDBC against fxdb, serving rates and
 * conversions, accepting rate batches from the upstream feed and ACKing them back.
 *
 * One annotation does three things here: @SpringBootApplication is @Configuration +
 * @EnableAutoConfiguration + @ComponentScan. The scan starts in THIS class's package
 * (com.fx), which is why everything you add under com.fx.* is found automatically —
 * and why this class must stay at the root of the package tree.
 */
@SpringBootApplication
public class FxAppSpringApplication {

    public static void main(String[] args) {
        SpringApplication.run(FxAppSpringApplication.class, args);
    }
}
