package com.example.loancollections.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Development HTTP Basic security. Roles map to the business actors: ANALYST (operations analyst: queue,
 * contacts, promises), COMPLIANCE (risk/compliance reviewer: exceptions) and ADMIN (system administrator).
 */
@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/health", "/css/**", "/error").permitAll()
                        .requestMatchers("/api/v1/admin/**", "/admin/**", "/evaluation").hasRole("ADMIN")
                        .requestMatchers("/api/v1/compliance/**", "/exceptions").hasAnyRole("COMPLIANCE", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/**").hasAnyRole("ANALYST", "ADMIN")
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    UserDetailsService users(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("analyst").password(encoder.encode("analyst123")).roles("ANALYST").build(),
                User.withUsername("compliance").password(encoder.encode("compliance123")).roles("COMPLIANCE").build(),
                User.withUsername("admin").password(encoder.encode("admin123")).roles("ADMIN").build()
        );
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
}
