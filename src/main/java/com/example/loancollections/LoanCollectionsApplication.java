package com.example.loancollections;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LoanCollectionsApplication {
    public static void main(String[] args) {
        SpringApplication.run(LoanCollectionsApplication.class, args);
    }
}
