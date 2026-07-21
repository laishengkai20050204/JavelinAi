package com.example.javelinlite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JavelinLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavelinLiteApplication.class, args);
    }
}
