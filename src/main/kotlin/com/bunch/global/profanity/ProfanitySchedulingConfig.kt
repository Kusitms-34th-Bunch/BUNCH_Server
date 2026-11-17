package com.bunch.global.profanity

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableScheduling
@ConditionalOnExpression("\${profanity.reload-enabled:true} or \${profanity.recheck-enabled:true}")
class ProfanitySchedulingConfig
