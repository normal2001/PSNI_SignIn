package com.example.psnisignin

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLStreamHandler
import java.util.concurrent.CopyOnWriteArrayList

/** Dependency-free emulator checks. All HTTPS requests are intercepted; no Slack messages leave the device. */
class SlackFeatureInstrumentation : Instrumentation() {
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    @Volatile private var failLookup = false
    @Volatile private var emptyGroup = false

    // Avoid loading the installed app's settings or starting its integrations during these tests.
    override fun callApplicationOnCreate(app: Application) = Unit
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        try {
            URL.setURLStreamHandlerFactory { protocol ->
                if (protocol != "https") null else object : URLStreamHandler() {
                    override fun openConnection(url: URL) = object : HttpURLConnection(url) {
                        private val body = ByteArrayOutputStream()
                        override fun connect() = Unit
                        override fun disconnect() = Unit
                        override fun usingProxy() = false
                        override fun getOutputStream() = body
                        override fun getResponseCode() = 200
                        override fun getInputStream(): ByteArrayInputStream {
                            val method = url.path.substringAfterLast('/')
                            requests += method to body.toString("UTF-8")
                            val response = when (method) {
                                "usergroups.list" -> """{"ok":true,"usergroups":[{"id":"S123","handle":"hosts"}]}"""
                                "usergroups.users.list" -> if (emptyGroup) """{"ok":true,"users":[]}""" else """{"ok":true,"users":["U1"]}"""
                                "users.info" -> if (failLookup) """{"ok":false,"error":"ratelimited"}""" else """{"ok":true,"user":{"id":"U1","profile":{"display_name":"Alex"}}}"""
                                "conversations.open" -> """{"ok":true,"channel":{"id":"D123"}}"""
                                "chat.postMessage" -> """{"ok":true,"channel":"D123","ts":"1"}"""
                                else -> error("Unexpected API request: $method")
                            }
                            return ByteArrayInputStream(response.toByteArray())
                        }
                    }
                }
            }
            // Isolate settings and storage from the installed application's real configuration.
            val testContext = object : ContextWrapper(targetContext) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("slack_feature_checks_$name", mode)
            }
            testContext.getSharedPreferences("app_config", 0).edit().clear().commit()
            AppConfig.initialize(testContext)
            val directory = File(targetContext.cacheDir, "slack-check-${System.nanoTime()}").apply { mkdirs() }
            AppConfig.saveApplication(File(directory, "records.db").path, File(directory, "audit.txt").path,
                File(directory, "error.txt").path, "yyyy-MM-dd HH:mm:ss")
            val logger = AppLogger(testContext)
            val db = DatabaseManager(testContext, logger)
            fun configure(enabled: Boolean, target: String = "Channel", group: String = "@hosts") =
                AppConfig.saveAdmin(enabled, "C123", "test-token", group, target)
            configure(true)
            val scope = SlackIntegration.cacheScope()
            val old = listOf(SlackRecipient("U0", "Previous"))
            db.replaceSlackUsers(scope, old)
            try { db.replaceSlackUsers(scope, old + old); error("Duplicate IDs must fail") } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            check(db.cachedSlackUsers(scope) == old) { "Failed replacement lost the cache" }
            check(db.cachedSlackUsers(SlackIntegration.cacheScope("different-token", "@hosts")).isEmpty())
            check(db.cachedSlackUsers(SlackIntegration.cacheScope("test-token", "@different")).isEmpty())

            failLookup = true
            SlackIntegration.refreshUsersAsync(db, logger)
            await { File(directory, "error.txt").takeIf { it.exists() }?.readText()?.contains("SLACK_CACHE_REFRESH_FAILED") == true }
            check(db.cachedSlackUsers(scope) == old)
            failLookup = false
            SlackIntegration.refreshUsersAsync(db, logger)
            await { db.cachedSlackUsers(scope) == listOf(SlackRecipient("U1", "Alex")) }
            emptyGroup = true
            SlackIntegration.refreshUsersAsync(db, logger)
            await { db.cachedSlackUsers(scope).isEmpty() }

            configure(false)
            val count = requests.size
            SlackIntegration.notifySuccessfulSignInAsync("Test", "Visitor", "2026-09-22 12:00:00", "Test", SlackRecipient("U1", "Alex"), logger)
            SlackIntegration.refreshUsersAsync(db, logger)
            check(requests.size == count)
            configure(true, "Individuals")
            SlackIntegration.notifySuccessfulSignInAsync("Test", "Visitor", "2026-09-22 12:00:00", "Test", SlackRecipient(null, "Other"), logger)
            check(requests.size == count)
            val audit = File(directory, "audit.txt")
            SlackIntegration.notifySuccessfulSignInAsync("Test", "Visitor", "2026-09-22 12:00:00", "Test", SlackRecipient("U1", "Alex"), logger)
            await { audit.readText().contains("slack-notification-success") }
            val direct = requests.drop(count)
            check(direct.map { it.first } == listOf("conversations.open", "chat.postMessage"))
            check(JSONObject(direct[0].second).getString("users") == "U1")
            check(JSONObject(direct[1].second).getString("channel") == "D123")
            configure(true)
            val beforeChannel = requests.size
            SlackIntegration.notifySuccessfulSignInAsync("Test", "Visitor", "2026-09-22 12:00:00", "Test", SlackRecipient(null, "Other"), logger)
            await { audit.readText().contains("target=Channel; destination=") }
            val channel = requests.drop(beforeChannel)
            check(channel.size == 1 && channel[0].first == "chat.postMessage")
            check(JSONObject(channel[0].second).getString("channel") == "C123")
            AppConfig.initialize(testContext)
            check(AppConfig.SLACK_USER_GROUP == "@hosts" && AppConfig.SLACK_NOTIFICATION_TARGET == "Channel")
            CsvFeatureChecks.run(testContext, directory)
            result.putString("stream", "PASS: cache rollback and isolation, refresh recovery, notification routing, saved settings, visiting migration and persistence, yearly/export CSV columns, escaping, empty exports, and host preservation after edits/sign-out.\n")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for background work" }
            Thread.sleep(20)
        }
    }
}
