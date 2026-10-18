package com.syeyoung.assistant.common

import io.r2dbc.spi.Readable
import org.springframework.r2dbc.core.DatabaseClient
import java.util.UUID

/** Binds [value], or a typed NULL when it is null (R2DBC needs the type for NULLs). */
fun <T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: T?,
    type: Class<T>,
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)

inline fun <reified T : Any> Readable.req(name: String): T =
    get(name, T::class.java) ?: error("column $name is null")

inline fun <reified T : Any> Readable.opt(name: String): T? = get(name, T::class.java)

fun Readable.uuids(name: String): List<UUID> = get(name, Array<UUID>::class.java)?.toList() ?: emptyList()
