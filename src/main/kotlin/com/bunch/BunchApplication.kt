package com.bunch
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
class BunchApplication
fun main(args: Array<String>) { runApplication<BunchApplication>(*args) }
