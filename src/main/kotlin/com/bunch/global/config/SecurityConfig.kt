package com.bunch.global.config

import jakarta.servlet.DispatcherType
import com.bunch.global.security.CustomAccessDeniedHandler
import com.bunch.global.security.CustomAuthenticationEntryPoint
import com.bunch.global.security.JwtAuthenticationFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
class SecurityConfig(
    private val jwtFilter: JwtAuthenticationFilter,
    private val loggingFilter: MDCLoggingFilter,
    private val entryPoint: CustomAuthenticationEntryPoint,
    private val deniedHandler: CustomAccessDeniedHandler,
    @Value("\${springdoc.api-docs.enabled:true}") private val docsEnabled: Boolean
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.csrf { it.disable() }.formLogin { it.disable() }.httpBasic { it.disable() }
            .cors { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling { it.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler) }
            .authorizeHttpRequests {
                it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/v1/auth/apple/login", "/api/v1/auth/google/login",
                    "/api/v1/auth/kakao/login", "/api/v1/auth/reissue").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/invitations/*").permitAll()
                if (docsEnabled) it.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                it.anyRequest().authenticated()
            }
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterAfter(loggingFilter, JwtAuthenticationFilter::class.java)
        return http.build()
    }
    @Bean
    fun jwtRegistration() = FilterRegistrationBean(jwtFilter).apply { isEnabled = false }
    @Bean
    fun loggingRegistration() = FilterRegistrationBean(loggingFilter).apply { isEnabled = false }
}
