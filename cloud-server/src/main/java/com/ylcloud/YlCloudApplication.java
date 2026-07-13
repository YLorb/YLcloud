package com.ylcloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.ylcloud")
@EnableScheduling
public class YlCloudApplication {

    public static void main(String[] args) {
        SpringApplication.run(YlCloudApplication.class, args);
    }
}
