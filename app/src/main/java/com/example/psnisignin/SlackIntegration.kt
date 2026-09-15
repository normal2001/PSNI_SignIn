package com.example.psnisignin

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Slack Web API functions used for best-effort visitor notifications. */
object SlackIntegration {
    private const val API_BASE = "https://slack.com/api/"
    private val executor = Executors.newSingleThreadExecutor()

    data class DeliveryReceipt(
        val destinationId: String,
        val messageTimestamp: String
    )

    /** Refresh the configured Slack user group without blocking application startup. */
    fun refreshConfiguredUserGroupAsync(database: DatabaseManager, logger: AppLogger) {
        if (AppConfig.SLACK_BOT_TOKEN.isBlank() || AppConfig.SLACK_USER_GROUP.isBlank()) return
        executor.execute {
            try {
                database.replaceSlackUsers(getConfiguredGroupUsers())
            } catch (e: Exception) {
                logger.error(
                    "SLACK_CACHE_REFRESH_FAILED",
                    "Could not refresh Slack user group '${AppConfig.SLACK_USER_GROUP}'; the previous cache was retained",
                    e
                )
            }
        }
    }

    /**
     * Queue a channel notification or a direct message to the selected Slack user.
     * Slack failures are logged on the worker thread and never affect the completed sign-in.
     */
    fun notifySuccessfulSignInAsync(
        firstName: String,
        lastName: String,
        signInDateTime: String,
        visiting: String?,
        reason: String?,
        selectedSlackUserId: String?,
        logger: AppLogger
    ) {
        if (!AppConfig.SLACK_NOTIFICATIONS_ENABLED) {
            logger.audit(
                "slack-notification-skipped",
                "reason=disabled; visiting=${visiting.orEmpty()}"
            )
            return
        }
        executor.execute notification@{
            try {
                val target = AppConfig.SLACK_NOTIFICATION_TARGET
                val message = buildString {
                    append("Visitor $firstName $lastName signed in at $signInDateTime.")
                    if (!visiting.isNullOrBlank()) append(" Visiting: $visiting.")
                    if (!reason.isNullOrBlank()) append(" Reason: $reason.")
                }
                if (target == AppConfig.SLACK_TARGET_INDIVIDUALS) {
                    if (selectedSlackUserId.isNullOrBlank()) {
                        logger.error(
                            "SLACK_NOTIFICATION_SKIPPED",
                            "Individual notification was skipped because visiting='${visiting.orEmpty()}' has no cached Slack user ID; " +
                                "target=$target. Select a cached Slack user rather than Other."
                        )
                        return@notification
                    }
                    logger.audit(
                        "slack-notification-attempt",
                        "target=individual; visiting=${visiting.orEmpty()}; user_id=$selectedSlackUserId"
                    )
                    val receipt = sendNotificationToUsers(listOf(selectedSlackUserId), message).single()
                    logger.audit(
                        "slack-notification-success",
                        "target=individual; visiting=${visiting.orEmpty()}; user_id=$selectedSlackUserId; " +
                            "dm_channel=${receipt.destinationId}; message_ts=${receipt.messageTimestamp}"
                    )
                } else {
                    val configuredChannel = AppConfig.SLACK_CHANNEL
                    logger.audit(
                        "slack-notification-attempt",
                        "target=channel; channel=$configuredChannel"
                    )
                    val receipt = sendNotificationToChannel(configuredChannel, message)
                    logger.audit(
                        "slack-notification-success",
                        "target=channel; channel=$configuredChannel; destination=${receipt.destinationId}; " +
                            "message_ts=${receipt.messageTimestamp}"
                    )
                }
            } catch (e: Exception) {
                logger.error(
                    "SLACK_NOTIFICATION_FAILED",
                    if (AppConfig.SLACK_NOTIFICATION_TARGET == AppConfig.SLACK_TARGET_INDIVIDUALS) {
                        "Could not notify selected Slack user id=${selectedSlackUserId.orEmpty()} for visiting=${visiting.orEmpty()}"
                    } else {
                        "Could not notify Slack channel '${AppConfig.SLACK_CHANNEL}'"
                    },
                    e
                )
            }
        }
    }

    /**
     * Return the Slack user ids in AppConfig.SLACK_USER_GROUP.
     * This performs network I/O and must be called from a background thread.
     */
    @Throws(IOException::class)
    fun getUsersInConfiguredGroup(): List<String> {
        requireConfiguredToken()
        val groups = get("usergroups.list", mapOf("include_disabled" to "false"))
            .getJSONArray("usergroups")
        var groupId: String? = null
        for (index in 0 until groups.length()) {
            val group = groups.getJSONObject(index)
            if (group.optString("handle").equals(AppConfig.SLACK_USER_GROUP, ignoreCase = true) ||
                group.optString("name").equals(AppConfig.SLACK_USER_GROUP, ignoreCase = true)
            ) {
                groupId = group.getString("id")
                break
            }
        }
        if (groupId == null) throw IOException("Slack user group '${AppConfig.SLACK_USER_GROUP}' was not found")

        val users = get("usergroups.users.list", mapOf("usergroup" to groupId)).getJSONArray("users")
        return List(users.length()) { index -> users.getString(index) }
    }

    /** Resolve cached user IDs to human-readable Slack names. Requires users:read. */
    @Throws(IOException::class)
    fun getConfiguredGroupUsers(): List<SlackUser> = getUsersInConfiguredGroup().map { userId ->
        val user = get("users.info", mapOf("user" to userId)).getJSONObject("user")
        val profile = user.optJSONObject("profile") ?: JSONObject()
        val displayName = profile.optString("display_name").ifBlank {
            profile.optString("real_name")
        }.ifBlank {
            user.optString("real_name")
        }.ifBlank {
            user.optString("name")
        }.ifBlank {
            userId
        }
        SlackUser(userId, displayName)
    }

    /**
     * Send the same direct-message notification to each supplied Slack user id.
     * This performs network I/O and must be called from a background thread.
     */
    @Throws(IOException::class)
    fun sendNotificationToUsers(userIds: List<String>, message: String): List<DeliveryReceipt> {
        requireConfiguredToken()
        return userIds.map { userId ->
            val conversation = post("conversations.open", JSONObject().put("users", userId))
            val channelId = conversation.getJSONObject("channel").getString("id")
            val response = post("chat.postMessage", JSONObject().put("channel", channelId).put("text", message))
            DeliveryReceipt(channelId, response.optString("ts", "not_returned"))
        }
    }

    /** Send one notification to the configured channel name or channel ID. */
    @Throws(IOException::class)
    fun sendNotificationToChannel(channelNameOrId: String, message: String): DeliveryReceipt {
        requireConfiguredToken()
        val channel = channelNameOrId.trim().removePrefix("#")
        if (channel.isBlank()) throw IllegalStateException("SLACK_CHANNEL is not configured")
        val response = post("chat.postMessage", JSONObject().put("channel", channel).put("text", message))
        return DeliveryReceipt(
            response.optString("channel", channel),
            response.optString("ts", "not_returned")
        )
    }

    private fun get(apiMethod: String, parameters: Map<String, String>): JSONObject {
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val url = if (query.isBlank()) "$API_BASE$apiMethod" else "$API_BASE$apiMethod?$query"
        return execute(apiMethod, url, "GET", null)
    }

    private fun post(apiMethod: String, body: JSONObject): JSONObject =
        execute(apiMethod, "$API_BASE$apiMethod", "POST", body.toString())

    private fun execute(apiMethod: String, url: String, httpMethod: String, body: String?): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = httpMethod
            connection.setRequestProperty("Authorization", "Bearer ${AppConfig.SLACK_BOT_TOKEN}")
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

    private fun requireConfiguredToken() {
        if (AppConfig.SLACK_BOT_TOKEN.isBlank()) {
            throw IllegalStateException("SLACK_BOT_TOKEN is not configured")
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
