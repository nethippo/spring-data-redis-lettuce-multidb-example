package com.example.multidb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MultiDbExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(MultiDbExampleApplication.class, args);
    }
}
