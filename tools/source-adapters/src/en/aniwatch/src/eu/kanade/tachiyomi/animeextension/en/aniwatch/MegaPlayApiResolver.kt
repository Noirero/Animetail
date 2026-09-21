package eu.kanade.tachiyomi.animeextension.en.aniwatch

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MegaPlayApiResolver(
    private val client: OkHttpClient,
    private val globalHeaders: Headers,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolve(embedUrl: String, referer: String): Result? = runCatching {
        val pageHeaders = globalHeaders.newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", referer)
            .build()

        val pageBody = client.newCall(GET(embedUrl, pageHeaders)).awaitSuccess().body.string()
        val mediaId = DATA_ID_REGEX.find(pageBody)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf(String::isNotBlank)
            ?: FILE_ID_REGEX.find(pageBody)?.groupValues?.getOrNull(1)
            ?: return null

        val original = embedUrl.toHttpUrl()
        val sourcesUrl = "${original.scheme}://${original.host}/stream/getSources".toHttpUrl()
            .newBuilder()
            .addQueryParameter("id", mediaId)
            .apply {
                original.queryParameter("s")?.let { addQueryParameter("s", it) }
            }
            .build()

        val apiHeaders = globalHeaders.newBuilder()
            .set("Accept", "application/json,*/*")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", embedUrl)
            .build()

        val raw = client.newCall(GET(sourcesUrl, apiHeaders)).awaitSuccess().body.string()
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
        val enc = (root["enc"] as? JsonPrimitive)?.contentOrNull
        val source = extractSource(root["sources"])
        val mediaUrl = processSource(enc, source) ?: return null

        val origin = "${original.scheme}://${original.host}"
        val mediaHeaders = globalHeaders.newBuilder()
            .set("Referer", "$origin/")
            .set("Origin", origin)
            .build()

        Result(mediaUrl, mediaHeaders)
    }.getOrNull()

    private fun extractSource(element: JsonElement?): String? = when (element) {
        is JsonObject -> (element["file"] as? JsonPrimitive)?.contentOrNull
        is JsonArray -> element.firstOrNull()?.let(::extractSource)
        is JsonPrimitive -> element.contentOrNull
        else -> null
    }

    private fun processSource(enc: String?, source: String?): String? {
        var mediaUrl: String? = null
        var decrypted = false

        if (!enc.isNullOrBlank()) {
            runCatching {
                val keyBytes = ByteArray(32)
                val keySource = AES_KEY.toByteArray(StandardCharsets.UTF_8)
                System.arraycopy(keySource, 0, keyBytes, 0, keySource.size.coerceAtMost(keyBytes.size))
                val iv = AES_IV.toByteArray(StandardCharsets.UTF_8)
                val encrypted = Base64.decode(
                    enc.replace('-', '+').replace('_', '/'),
                    Base64.DEFAULT,
                )

                if (encrypted.isNotEmpty() && encrypted.size % 16 == 0) {
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(keyBytes, "AES"),
                        IvParameterSpec(iv),
                    )
                    val decoded = String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
                    mediaUrl = FILE_JSON_REGEX.find(decoded)?.groupValues?.getOrNull(1)
                    decrypted = !mediaUrl.isNullOrBlank()
                }
            }
        }

        if (mediaUrl.isNullOrBlank()) mediaUrl = source
        val current = mediaUrl?.takeIf(String::isNotBlank) ?: return null

        if (!decrypted || TOKEN_PARAM_REGEX.containsMatchIn(current)) return current

        val pathMatch = PATH_KEY_REGEX.find(current) ?: return current
        val pathKey = "${pathMatch.groupValues[1].lowercase()}/${pathMatch.groupValues[2].lowercase()}"
        val expiry = (System.currentTimeMillis() / 1000) + 90
        val payload = "$expiry|$pathKey"

        return runCatching {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(TOKEN_SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            val signature = Base64.encodeToString(
                mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)),
                Base64.URL_SAFE or Base64.NO_WRAP,
            ).trimEnd('=')
            val encodedPayload = Base64.encodeToString(
                payload.toByteArray(StandardCharsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP,
            ).trimEnd('=')
            current.toHttpUrl().newBuilder()
                .setQueryParameter("token", "$encodedPayload.$signature")
                .build()
                .toString()
        }.getOrDefault(current)
    }

    data class Result(
        val url: String,
        val headers: Headers,
    )

    companion object {
        private const val AES_KEY = "i?LMTAx0Q6,:}50U"
        private const val AES_IV = "W0;27ToaUpl_P%'c"
        private const val TOKEN_SECRET = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"

        private val DATA_ID_REGEX = Regex("""data-id=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val FILE_ID_REGEX = Regex("""File\s+(\d+)""", RegexOption.IGNORE_CASE)
        private val FILE_JSON_REGEX = Regex(""""file"\s*:\s*"([^"]+)"""")
        private val TOKEN_PARAM_REGEX = Regex("""[?&]token=""", RegexOption.IGNORE_CASE)
        private val PATH_KEY_REGEX = Regex("""/([a-f0-9]{32})/([a-f0-9]{32})/""", RegexOption.IGNORE_CASE)
    }
}
