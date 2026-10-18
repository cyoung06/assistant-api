package com.syeyoung.assistant

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class AssistantApplication

fun main(args: Array<String>) {
    runApplication<AssistantApplication>(*args)
}
