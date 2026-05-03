package com.ylcloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.ylcloud")
public class YlCloudApplication {

    public static void main(String[] args) {
        SpringApplication.run(YlCloudApplication.class, args);
    }
}
