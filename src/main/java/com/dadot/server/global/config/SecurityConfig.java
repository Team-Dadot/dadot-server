package com.dadot.server.global.config;

import com.dadot.server.global.security.SecurityExceptionHandler;
import com.dadot.server.global.security.SessionJwtAuthenticationConverter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            SecurityExceptionHandler exceptionHandler,
            SessionJwtAuthenticationConverter jwtAuthentication,
            @Value("${springdoc.api-docs.enabled:false}")
            boolean apiDocsEnabled
    ) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)

                .cors(cors ->
                        cors.configurationSource(
                                corsConfigurationSource
                        )
                )

                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(exceptionHandler)
                        .accessDeniedHandler(exceptionHandler)
                )

                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt ->
                                jwt.jwtAuthenticationConverter(
                                        jwtAuthentication
                                )
                        )
                        .authenticationEntryPoint(exceptionHandler)
                        .accessDeniedHandler(exceptionHandler)
                )

                .authorizeHttpRequests(authorize -> {
                    authorize
                            .dispatcherTypeMatchers(
                                    DispatcherType.ERROR
                            )
                            .permitAll();

                    authorize
                            .requestMatchers(
                                    HttpMethod.GET,
                                    "/actuator/health",
                                    "/actuator/health/**",
                                    "/v1/app-config"
                            )
                            .permitAll();

                    authorize
                            .requestMatchers(
                                    HttpMethod.POST,
                                    "/v1/auth/email-verifications",
                                    "/v1/auth/email-verifications/*/verify",
                                    "/v1/auth/signup",
                                    "/v1/auth/login",
                                    "/v1/auth/social/*",
                                    "/v1/auth/social/apple/challenges",
                                    "/v1/auth/password/reset"
                            )
                            .permitAll();

                    if (apiDocsEnabled) {
                        authorize
                                .requestMatchers(
                                        HttpMethod.GET,
                                        "/swagger-ui.html",
                                        "/swagger-ui/**",
                                        "/v3/api-docs",
                                        "/v3/api-docs/**",
                                        "/v3/api-docs.yaml"
                                )
                                .permitAll();
                    }

                    authorize
                            .requestMatchers("/v1/admin/**")
                            .hasRole("ADMIN");

                    authorize
                            .requestMatchers("/v1/**")
                            .authenticated();

                    authorize
                            .anyRequest()
                            .denyAll();
                });

        return http.build();
    }
}