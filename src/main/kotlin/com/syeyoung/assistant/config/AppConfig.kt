package com.syeyoung.assistant.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class AppConfig {
    /** All "today" and "overdue" logic uses this clock, in the user's zone. */
    @Bean
    fun clock(props: AppProperties): Clock = Clock.system(props.zone)
}
