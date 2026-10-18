package com.syeyoung.assistant.quickadd

import ai.koog.http.client.spring.SpringWebClientKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLMProvider
import com.syeyoung.assistant.config.AppProperties
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

/** What the model returns for one piece of text. Dates and times are local to the user's zone, as ISO strings. */
@Serializable
data class LlmDraft(
    val title: String,
    val notes: String = "",
    val by: LlmBy? = null,
    val at: LlmAt? = null,
    val tags: List<String> = emptyList(),
)

/** Deadline. `date` is YYYY-MM-DD; `time` is HH:MM (24h) or null for "by end of day". */
@Serializable
data class LlmBy(val date: String, val time: String? = null)

/** Scheduled time. Timed: `start`/`end` as YYYY-MM-DDTHH:MM. All-day: `startDate`/`endDate` (inclusive). */
@Serializable
data class LlmAt(
    val allDay: Boolean,
    val start: String? = null,
    val end: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
)

/** Turns free text into an [LlmDraft]. An interface so tests can replace the LLM. */
interface ItemExtractor {
    val configured: Boolean
    suspend fun extract(instructions: String, text: String): LlmDraft
}

@Component
class KoogItemExtractor(webClient: WebClient.Builder, private val props: AppProperties) : ItemExtractor {

    // The model ID comes from config; capabilities are taken from Koog's closest known model.
    private val model = OpenAIModels.Chat.GPT5_6Luna.copy(id = props.llm.model)

    private val executor by lazy {
        val client = OpenAILLMClient(
            apiKey = props.llm.apiKey,
            settings = OpenAIClientSettings(baseUrl = props.llm.baseUrl),
            httpClientFactory = SpringWebClientKoogHttpClient.Factory(webClient.clone()),
        )
        MultiLLMPromptExecutor(mapOf(LLMProvider.OpenAI to client))
    }

    override val configured get() = props.llm.apiKey.isNotBlank()

    override suspend fun extract(instructions: String, text: String): LlmDraft = withTimeout(props.llm.timeout.toMillis()) {
        val request = prompt("quick-add") {
            system(instructions)
            user(text)
        }
        executor.executeStructured<LlmDraft>(request, model).getOrThrow().data
    }
}
