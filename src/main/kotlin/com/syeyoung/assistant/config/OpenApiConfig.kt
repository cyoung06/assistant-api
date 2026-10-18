package com.syeyoung.assistant.config

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.media.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.reflect.full.primaryConstructor

@Configuration
class OpenApiConfig {
    @Bean
    fun kotlinRequiredProperties(): ModelConverter = KotlinRequiredProperties()
}

/**
 * Marks a data class property as required in the OpenAPI spec when it is non-null and has no
 * default value, so generated clients (TypeScript, Kotlin, Swift) get accurate optionality.
 */
class KotlinRequiredProperties : ModelConverter {
    override fun resolve(type: AnnotatedType, context: ModelConverterContext, chain: Iterator<ModelConverter>): Schema<*>? {
        val schema = if (chain.hasNext()) chain.next().resolve(type, context, chain) else null ?: return null
        val cls = runCatching { Json.mapper().constructType(type.type).rawClass }.getOrNull() ?: return schema
        val kotlinClass = cls.kotlin
        if (!kotlinClass.isData) return schema
        val required = kotlinClass.primaryConstructor?.parameters
            ?.filter { !it.isOptional && !it.type.isMarkedNullable }
            ?.mapNotNull { it.name }
            ?: return schema
        // Object schemas usually come back as a $ref; the definition lives in the context.
        val model = schema?.`$ref`?.substringAfterLast('/')?.let { context.definedModels[it] } ?: schema
        model?.required = required.filter { model?.properties?.containsKey(it) == true }.ifEmpty { null }
        return schema
    }
}
