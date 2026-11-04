package com.dadot.server.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@Profile("monitoring-local & !prod")
public class LocalMonitoringSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain localMonitoringSecurityFilterChain(
            HttpSecurity http,
            @Value("${management.server.port}") int managementPort
    ) throws Exception {

        http
                .securityMatcher(request ->
                        request.getLocalPort() == managementPort
                                && HttpMethod.GET.matches(request.getMethod())
                                && "/actuator/prometheus".equals(
                                request.getRequestURI()
                        )
                )
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .authorizeHttpRequests(authorize ->
                        authorize.anyRequest().permitAll()
                );

        return http.build();
    }
}