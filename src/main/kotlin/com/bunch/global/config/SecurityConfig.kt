package com.bunch.global.config

import com.bunch.global.common.ApiResponse
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter

@Configuration
class SecurityConfig(
    private val loggingFilter: MDCLoggingFilter,
    private val objectMapper: ObjectMapper,
    @Value("\${springdoc.api-docs.enabled:true}") private val docsEnabled: Boolean
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.csrf { it.disable() }.formLogin { it.disable() }.httpBasic { it.disable() }
            .cors { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    response.status = 401
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    objectMapper.writeValue(response.outputStream,
                        ApiResponse.fail("AUTH_001", "error.unauthorized"))
                }
            }
            .authorizeHttpRequests {
                if (docsEnabled) it.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                it.requestMatchers("/error").permitAll().anyRequest().denyAll()
            }
            .addFilterBefore(loggingFilter, AuthorizationFilter::class.java)
        return http.build()
    }

    @Bean
    fun loggingRegistration() = FilterRegistrationBean(loggingFilter).apply { isEnabled = false }
}
