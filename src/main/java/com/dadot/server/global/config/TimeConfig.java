package com.dadot.server.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }
}