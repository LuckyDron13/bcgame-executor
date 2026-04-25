package com.dron.bcgame;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BcGameExecutorApplication {
    public static void main(String[] args) {
        SpringApplication.run(BcGameExecutorApplication.class, args);
    }
}
