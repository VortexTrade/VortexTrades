package com.vortextrade.blackjackoverlay

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Sends a screenshot of the blackjack table to Claude's vision model and returns a short,
 * overlay-sized play recommendation. One [analyze] call == one API request, fired only when
 * the user taps "Calculate Now".
 */
class BlackjackAdvisor(private val appContext: Context) {

    // Built lazily from the key stored on-device, so the client picks up a key
    // entered in the app without needing a rebuild.
    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(ApiKeyStore.get(appContext))
            .build()
    }

    suspend fun analyze(frame: Bitmap): String = withContext(Dispatchers.IO) {
        if (!ApiKeyStore.hasKey(appContext)) {
            return@withContext "No API key set. Open the app and paste your Anthropic API key."
        }

        val base64Image = frame.toJpegBase64(quality = 70)

        val imageBlock = ImageBlockParam.builder()
            .source(
                Base64ImageSource.builder()
                    .data(base64Image)
                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                    .build()
            )
            .build()

        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(400L)
            .system(SYSTEM_PROMPT)
            .addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(imageBlock),
                    ContentBlockParam.ofText(
                        TextBlockParam.builder().text(USER_PROMPT).build()
                    )
                )
            )
            .build()

        try {
            val message = client.messages().create(params)
            val text = message.content()
                .mapNotNull { block -> block.text().orElse(null)?.text() }
                .joinToString("\n")
                .trim()
            text.ifBlank { "No readable cards found. Reposition the overlay and try again." }
        } catch (t: Throwable) {
            "Error contacting Claude: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun Bitmap.toJpegBase64(quality: Int): String {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        /**
         * Model used for every request. You asked for Sonnet 3.5 to keep credit spend low, but
         * `claude-3-5-sonnet` was retired on 2025-10-28 and now returns a 404. Haiku 4.5 is the
         * cheapest *current* model with vision ($1 / $5 per 1M tokens) and reads a blackjack table
         * easily — swap this one string for `claude-sonnet-5` if you want stronger reasoning.
         */
        const val MODEL = "claude-haiku-4-5"

        private const val SYSTEM_PROMPT =
            "You are a blackjack basic-strategy coach reading a screenshot of a blackjack table. " +
                "Identify the player's hand and the dealer's up-card from the image. Assume standard " +
                "rules (dealer stands on soft 17, double after split allowed) unless the screen shows " +
                "otherwise. Respond with the mathematically optimal basic-strategy action."

        private const val USER_PROMPT =
            "Look at this blackjack table. Reply in this exact compact format, nothing else:\n" +
                "ACTION: <HIT | STAND | DOUBLE | SPLIT | SURRENDER>\n" +
                "HAND: <your read of the player's cards> vs dealer <up-card>\n" +
                "WHY: <one short sentence>\n" +
                "If you cannot clearly see the cards, reply exactly: ACTION: UNCLEAR."
    }
}
