package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.notBuiltYet

/**
 * `/conversations/<id>` (M6 §8.4): a transcript rendered from its `.jsonl`, found by the path recorded in
 * `conversations.transcript` and checked to lie under `world/narrator2/logs/conversations/` (M6 §8.3). Not built yet:
 * M6-11 writes it.
 */
object TranscriptPage {
    /** The transcript of [conversationId]; null when there is no such conversation (404). */
    fun render(conversationId: String, request: WebRequest, context: PageContext): Page? = notBuiltYet("Conversation $conversationId")
}
