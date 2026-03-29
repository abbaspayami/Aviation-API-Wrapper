package com.sporty.aviation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Entry point for the Aviation microservice.
 *
 * {@code @EnableFeignClients} — scans for @FeignClient interfaces and generates
 *   HTTP client implementations automatically at startup.
 *
 * {@code @EnableCaching} — activates Spring's cache abstraction so that
 *   @Cacheable annotations are processed. Without this annotation, @Cacheable
 *   is silently ignored and every request hits the upstream API.
 */
@SpringBootApplication
@EnableFeignClients
@EnableCaching
public class AviationApplication {

    public static void main(String[] args) {
        SpringApplication.run(AviationApplication.class, args);
    }
}
