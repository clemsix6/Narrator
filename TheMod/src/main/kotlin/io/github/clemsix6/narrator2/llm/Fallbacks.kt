package io.github.clemsix6.narrator2.llm

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam
import com.fasterxml.jackson.databind.JsonNode

/**
 * A switch point of a server-side fallback: a safety classifier declined the request on [from] and
 * [to] answered instead. The API marks it with a `fallback` content block, which the stable SDK does
 * not know (2.65.0 to 2.75.0): it keeps it as raw JSON, and `ContentBlock.toParam()` throws on it.
 *
 * @property trigger why, e.g. `refusal`; [category] the refusal's category, e.g. `cyber`.
 */
data class Fallback(val from: String?, val to: String?, val trigger: String?, val category: String?) {
    /** `claude-opus-5-5 → claude-opus-4-8 (refusal, cyber)`, for logs and transcripts. */
    fun describe(): String = "${from ?: "?"} → ${to ?: "?"} (${listOfNotNull(trigger, category).joinToString().ifEmpty { "no trigger" }})"
}

/** The `type` of [block] as the API sent it, known to the SDK or not; null when it has none. */
internal fun rawTypeOf(block: ContentBlock): String? =
    block._json().map { json -> jsonMapper().valueToTree<JsonNode>(json).path("type").asText(null) }.orElse(null)
        ?: when {
            block.isText() -> "text"
            block.isThinking() -> "thinking"
            block.isRedactedThinking() -> "redacted_thinking"
            block.isToolUse() -> "tool_use"
            else -> null
        }

private fun isFallback(block: ContentBlock): Boolean =
    !block.isText() && !block.isThinking() && !block.isToolUse() && rawTypeOf(block) == FALLBACK_TYPE

/** The fallbacks of [message], in order; empty when the requested model answered. */
fun fallbacksOf(message: Message): List<Fallback> = message.content().filter(::isFallback).map { block ->
    val json = block._json().map { jsonMapper().valueToTree<JsonNode>(it) }.orElse(null)
    Fallback(
        from = json?.path("from")?.path("model")?.asText(null),
        to = json?.path("to")?.path("model")?.asText(null),
        trigger = json?.path("trigger")?.path("type")?.asText(null),
        category = json?.path("trigger")?.path("category")?.asText(null),
    )
}

/**
 * The blocks that make [message]'s answer. Without a fallback, all of them. With one, the `fallback`
 * blocks are left out (an audit marker the API ignores in a request), and before the last of them only
 * text is kept: the thinking, tool calls and other blocks of a declined model are not part of the
 * answer, and the API wants them omitted when the turn is sent back; its text is, since the fallback
 * model continued from it.
 */
fun answerOf(message: Message): List<ContentBlock> {
    val content = message.content()
    val boundary = content.indexOfLast(::isFallback)
    return content.filterIndexed { index, block ->
        when {
            isFallback(block) -> false
            index < boundary -> block.isText()
            else -> true
        }
    }
}

/**
 * The reply as it joins the history: its answer ([answerOf]), minus blank text blocks, which the API
 * rejects in a request (a streamed text block can end up empty). A block the SDK does not know is sent
 * back as the API returned it.
 */
internal fun replayOf(message: Message): MessageParam =
    MessageParam.builder()
        .role(MessageParam.Role.ASSISTANT)
        .contentOfBlockParams(answerOf(message).filterNot { it.isText() && it.asText().text().isBlank() }.map(::paramOf))
        .build()

private fun paramOf(block: ContentBlock): ContentBlockParam {
    val raw = block._json().orElse(null)
    if (raw == null || block.isValid()) return block.toParam()
    return requireNotNull(raw.convert(ContentBlockParam::class.java)) { "a ${rawTypeOf(block)} block cannot be sent back: $raw" }
}

/**
 * True when [message] has nothing to commit: no block of its answer ([answerOf]) but blank text. The
 * API rejects an empty assistant turn, so such a reply is never committed (it happens after tool
 * results, when the model considers the turn over). A reply with only thinking is not empty.
 */
fun isEmptyReply(message: Message): Boolean = answerOf(message).all { it.isText() && it.asText().text().isBlank() }

/** True when [message] answers with text or a tool call: what a fallback must bring to count as an answer. */
internal fun hasAnswer(message: Message): Boolean =
    answerOf(message).any { it.isToolUse() || (it.isText() && it.asText().text().isNotBlank()) }

private const val FALLBACK_TYPE = "fallback"
