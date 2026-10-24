package com.bunch.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

@Configuration
class ManagementSecurityConfig(
    private val environment: Environment
) {
    @Bean
    @Order(0)
    fun managementSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.securityMatcher { request ->
            val managementPort = environment.getProperty("local.management.port", Int::class.java)
            managementPort != null && request.localPort == managementPort
        }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/prometheus").permitAll()
                it.anyRequest().denyAll()
            }

        return http.build()
    }
}
