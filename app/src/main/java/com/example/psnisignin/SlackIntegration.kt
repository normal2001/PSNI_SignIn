package com.example.psnisignin

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/** Slack Web API functions used for best-effort visitor notifications. */
object SlackIntegration {
    private const val API_BASE = "https://slack.com/api/"
    private val executor = Executors.newSingleThreadExecutor()
    private val cacheExecutor = Executors.newSingleThreadExecutor()
    val cacheListeners = CopyOnWriteArraySet<() -> Unit>()

    fun cacheScope(token: String = AppConfig.SLACK_BOT_TOKEN, group: String = AppConfig.SLACK_USER_GROUP): String =
        MessageDigest.getInstance("SHA-256").digest("$token\n$group".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Refresh atomically, retaining the previous cache if any lookup fails. */
    fun refreshUsersAsync(database: DatabaseManager, logger: AppLogger) {
        if (!AppConfig.SLACK_NOTIFICATIONS_ENABLED) return
        val token = AppConfig.SLACK_BOT_TOKEN
        val group = AppConfig.SLACK_USER_GROUP
        if (token.isBlank() || group.isBlank()) return
        val scope = cacheScope(token, group)
        cacheExecutor.execute {
            try {
                val groupId = if (group.matches(Regex("S[A-Z0-9]+"))) group else {
                    val groups = get("usergroups.list", emptyMap(), token).getJSONArray("usergroups")
                    (0 until groups.length()).map { groups.getJSONObject(it) }.firstOrNull {
                        it.getString("handle").equals(group.removePrefix("@"), ignoreCase = true)
                    }?.getString("id") ?: throw IOException("Configured Slack user group was not found")
                }
                val ids = get("usergroups.users.list", mapOf("usergroup" to groupId), token).getJSONArray("users")
                val users = (0 until ids.length()).map { ids.getString(it) }.distinct().mapNotNull { id ->
                    val user = get("users.info", mapOf("user" to id), token).getJSONObject("user")
                    if (user.optBoolean("deleted") || user.optBoolean("is_bot")) null else {
                        val profile = user.optJSONObject("profile")
                        val name = profile?.optString("display_name")?.trim().orEmpty()
                            .ifBlank { profile?.optString("real_name")?.trim().orEmpty() }
                            .ifBlank { user.optString("name").trim() }.ifBlank { id }
                        SlackRecipient(id, name)
                    }
                }
                database.replaceSlackUsers(scope, users)
                logger.audit("slack-cache-refreshed", "users=${users.size}")
                cacheListeners.forEach { it() }
            } catch (e: Exception) {
                logger.error("SLACK_CACHE_REFRESH_FAILED", "Could not refresh Slack users; previous cache retained", e)
            }
        }
    }

    data class DeliveryReceipt(
        val destinationId: String,
        val messageTimestamp: String
    )

    /** Queue notification using settings captured at sign-in; failure never rolls back the record. */
    fun notifySuccessfulSignInAsync(
        firstName: String,
        lastName: String,
        signInDateTime: String,
        reason: String?,
        recipient: SlackRecipient,
        logger: AppLogger
    ) {
        if (!AppConfig.SLACK_NOTIFICATIONS_ENABLED) {
            logger.audit("slack-notification-skipped", "reason=disabled")
            return
        }
        val channel = AppConfig.SLACK_CHANNEL
        val token = AppConfig.SLACK_BOT_TOKEN
        val target = AppConfig.SLACK_NOTIFICATION_TARGET
        if (target == "Individuals" && recipient.userId == null) {
            logger.audit("slack-notification-skipped", "reason=other; target=Individuals")
            return
        }
        executor.execute {
            try {
                val message = buildString {
                    append("Visitor $firstName $lastName signed in at $signInDateTime.")
                    if (!reason.isNullOrBlank()) append(" Reason: $reason.")
                    append(" Visiting: ${recipient.displayName}.")
                }
                logger.audit("slack-notification-attempt", "target=$target")
                val destination = if (target == "Individuals") {
                    post("conversations.open", JSONObject().put("users", requireNotNull(recipient.userId)), token)
                        .getJSONObject("channel").getString("id")
                } else channel
                val receipt = sendNotificationToChannel(destination, message, token)
                logger.audit(
                    "slack-notification-success",
                    "target=$target; destination=${receipt.destinationId}; " +
                        "message_ts=${receipt.messageTimestamp}"
                )
            } catch (e: Exception) {
                logger.error("SLACK_NOTIFICATION_FAILED", "Could not deliver Slack notification (target=$target)", e)
            }
        }
    }

    /** Send one notification to the configured channel name or channel ID. */
    @Throws(IOException::class)
    fun sendNotificationToChannel(channelNameOrId: String, message: String, token: String = AppConfig.SLACK_BOT_TOKEN): DeliveryReceipt {
        require(token.isNotBlank()) { "SLACK_BOT_TOKEN is not configured" }
        val channel = channelNameOrId.trim().removePrefix("#")
        if (channel.isBlank()) throw IllegalStateException("SLACK_CHANNEL is not configured")
        val response = post("chat.postMessage", JSONObject().put("channel", channel).put("text", message), token)
        return DeliveryReceipt(
            response.optString("channel", channel),
            response.optString("ts", "not_returned")
        )
    }

    private fun post(apiMethod: String, body: JSONObject, token: String): JSONObject =
        execute(apiMethod, "$API_BASE$apiMethod", "POST", body.toString(), token)

    private fun get(apiMethod: String, params: Map<String, String>, token: String): JSONObject {
        val query = params.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        return execute(apiMethod, "$API_BASE$apiMethod?$query", "GET", null, token)
    }

    private fun execute(apiMethod: String, url: String, httpMethod: String, body: String?, token: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = httpMethod
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                throw IOException("Slack API $apiMethod ($httpMethod) returned HTTP $responseCode; response=$responseText")
            }

            val response = try {
                JSONObject(responseText)
            } catch (e: Exception) {
                throw IOException("Slack API $apiMethod ($httpMethod) returned invalid JSON; response=$responseText", e)
            }
            if (!response.optBoolean("ok")) {
                val details = listOfNotNull(
                    "error=${response.optString("error", "unknown_error")}",
                    response.optString("needed").takeIf { it.isNotBlank() }?.let { "needed=$it" },
                    response.optString("provided").takeIf { it.isNotBlank() }?.let { "provided=$it" },
                    connection.getHeaderField("x-oauth-scopes")?.takeIf { it.isNotBlank() }?.let { "token_scopes=$it" },
                    connection.getHeaderField("x-accepted-oauth-scopes")?.takeIf { it.isNotBlank() }?.let { "accepted_scopes=$it" }
                ).joinToString("; ")
                throw IOException("Slack API $apiMethod ($httpMethod) failed: $details; response=$responseText")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

}
