package com.bunch.global.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SwaggerConfig {

    @Bean
    fun openApi(): OpenAPI {
        return OpenAPI()
            .info(apiInfo())
            .servers(listOf(Server().url("/")))
            .components(components())
            .addSecurityItem(SecurityRequirement().addList("access-token"))

    }

    private fun apiInfo(): Info {
        return Info()
            .title("BUNCH API")
            .description("BUNCH API Documentation")
            .version("v1")
    }

    private fun components(): Components {
        val securityScheme = SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .`in`(SecurityScheme.In.HEADER)
            .name("Authorization")

        return Components().addSecuritySchemes("access-token", securityScheme)

    }
}
