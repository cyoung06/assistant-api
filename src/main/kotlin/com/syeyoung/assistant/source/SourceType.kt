package com.syeyoung.assistant.source

import com.fasterxml.jackson.annotation.JsonValue

/** Where a synced task or event comes from; the prefix of its X-ASSISTANT-SOURCE reference. */
enum class SourceType(@get:JsonValue val wire: String) {
    MANUAL("manual"), PLMS("plms"), EMAIL("email"), UPLOAD("upload"), ICAL("ical"), CONTESTS("contests");
}
