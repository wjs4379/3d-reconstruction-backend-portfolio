package com.hanyang.backend.domain.model3d.outbox

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

@Configuration
@EnableScheduling
class OutboxConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun outboxClock(): Clock = Clock.systemDefaultZone()
}
