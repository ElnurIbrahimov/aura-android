package com.aura.integrations.google

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.integrations.IntegrationTokenStore
import com.aura.integrations.OAuthFlow
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private val JSON = "application/json; charset=utf-8".toMediaType()

@Singleton
class GoogleGmailTool @Inject constructor(
    private val tokenStore: IntegrationTokenStore,
    private val oauthFlow: OAuthFlow,
    private val userPreferences: com.aura.data.UserPreferences,
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "GoogleGmailTool"
        private const val GMAIL_API = "https://gmail.googleapis.com/gmail/v1"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun definition() = ToolDefinition(
        name = "gmail",
        description = "Read and send Gmail messages. Supports listing recent emails, reading a specific email, searching, and sending. Requires Google account connected in Settings.",
        parameters = ToolParameters(
            properties = mapOf(
                "action" to ToolProperty(type = "string", description = "One of: list, read, search, send"),
                "query" to ToolProperty(type = "string", description = "Search query (for 'search' action). Gmail search syntax: from:, to:, subject:, has:attachment, etc."),
                "email_id" to ToolProperty(type = "string", description = "Email ID (for 'read' action)"),
                "to" to ToolProperty(type = "string", description = "Recipient (for 'send' action)"),
                "subject" to ToolProperty(type = "string", description = "Email subject (for 'send' action)"),
                "body" to ToolProperty(type = "string", description = "Email body (for 'send' action)"),
                "max_results" to ToolProperty(type = "integer", description = "Max results (for 'list'/'search', default 10, max 20)"),
            ),
            required = listOf("action"),
        ),
    )

    val tool = Tool(
        name = "gmail",
        description = definition().description,
        risk = ToolRisk.REMOTE_COST,
        parameters = definition().parameters,
        execute = { call, _ ->
            val action = call.arguments["action"] as? String
                ?: return@Tool ToolResult.Error("missing 'action'", "bad_args")
            val clientId = userPreferences.googleClientIdSync()
            if (clientId.isNullOrBlank()) {
                return@Tool ToolResult.Error("Google account not connected. Go to Settings → Integrations to connect.", "not_connected")
            }
            val token = tokenStore.getValidGoogleAccessToken { refreshToken ->
                oauthFlow.refreshGoogleToken(refreshToken, clientId)
            } ?: return@Tool ToolResult.Error("Google token expired. Reconnect in Settings.", "token_expired")

            when (action) {
                "list" -> listEmails(token, (call.arguments["max_results"] as? Int ?: 10).coerceAtMost(20))
                "read" -> {
                    val emailId = call.arguments["email_id"] as? String
                        ?: return@Tool ToolResult.Error("missing 'email_id'", "bad_args")
                    readEmail(token, emailId)
                }
                "search" -> {
                    val query = call.arguments["query"] as? String
                        ?: return@Tool ToolResult.Error("missing 'query'", "bad_args")
                    searchEmails(token, query, (call.arguments["max_results"] as? Int ?: 10).coerceAtMost(20))
                }
                "send" -> {
                    val to = call.arguments["to"] as? String
                        ?: return@Tool ToolResult.Error("missing 'to'", "bad_args")
                    val subject = call.arguments["subject"] as? String ?: "(no subject)"
                    val body = call.arguments["body"] as? String ?: ""
                    sendEmail(token, to, subject, body)
                }
                else -> ToolResult.Error("unknown action: $action", "bad_args")
            }
        },
    )

    private suspend fun listEmails(token: String, maxResults: Int): ToolResult.Ok = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$GMAIL_API/users/me/messages?maxResults=$maxResults")
            .header("Authorization", "Bearer $token")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("Gmail API error: ${resp.code}")
            val body = resp.body?.string() ?: return@withContext ToolResult.Ok("empty response")
            val parsed = json.parseToJsonElement(body).jsonObject
            val messages = parsed["messages"]?.jsonArray ?: JsonArray(emptyList())
            if (messages.isEmpty()) return@withContext ToolResult.Ok("No recent emails.")
            val result = StringBuilder("Recent emails:\n")
            for (msg in messages) {
                val id = msg.jsonObject["id"]!!.jsonPrimitive.content
                val snippet = getEmailSnippet(token, id)
                result.append("• $id — $snippet\n")
            }
            ToolResult.Ok(result.toString().trim())
        }
    }

    private suspend fun getEmailSnippet(token: String, id: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$GMAIL_API/users/me/messages/$id?format=metadata&metadataHeaders=Subject&metadataHeaders=From")
            .header("Authorization", "Bearer $token")
            .build()
        val response = runCatching { httpClient.newCall(request).execute() }.getOrNull() ?: return@withContext "(unknown)"
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext "(error)"
            val body = resp.body?.string() ?: return@withContext "(empty)"
            val parsed = json.parseToJsonElement(body).jsonObject
            val headers = parsed["payload"]?.jsonObject?.get("headers")?.jsonArray ?: JsonArray(emptyList())
            val subject = headers.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == "Subject" }
                ?.jsonObject?.get("value")?.jsonPrimitive?.content ?: "(no subject)"
            val from = headers.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == "From" }
                ?.jsonObject?.get("value")?.jsonPrimitive?.content ?: "(unknown sender)"
            "$from — $subject"
        }
    }

    private suspend fun readEmail(token: String, emailId: String): ToolResult.Ok = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$GMAIL_API/users/me/messages/$emailId?format=full")
            .header("Authorization", "Bearer $token")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("Gmail API error: ${resp.code}")
            val body = resp.body?.string() ?: return@withContext ToolResult.Ok("empty response")
            val parsed = json.parseToJsonElement(body).jsonObject
            val snippet = parsed["snippet"]?.jsonPrimitive?.content ?: "(no content)"
            val headers = parsed["payload"]?.jsonObject?.get("headers")?.jsonArray ?: JsonArray(emptyList())
            val subject = headers.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == "Subject" }
                ?.jsonObject?.get("value")?.jsonPrimitive?.content ?: "(no subject)"
            val from = headers.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == "From" }
                ?.jsonObject?.get("value")?.jsonPrimitive?.content ?: "(unknown)"
            ToolResult.Ok("From: $from\nSubject: $subject\n\n$snippet")
        }
    }

    private suspend fun searchEmails(token: String, query: String, maxResults: Int): ToolResult.Ok = withContext(Dispatchers.IO) {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url("$GMAIL_API/users/me/messages?q=$encodedQuery&maxResults=$maxResults")
            .header("Authorization", "Bearer $token")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("Gmail search error: ${resp.code}")
            val body = resp.body?.string() ?: return@withContext ToolResult.Ok("empty response")
            val parsed = json.parseToJsonElement(body).jsonObject
            val messages = parsed["messages"]?.jsonArray ?: JsonArray(emptyList())
            if (messages.isEmpty()) return@withContext ToolResult.Ok("No emails found for '$query'.")
            val result = StringBuilder("Search results for '$query':\n")
            for (msg in messages) {
                val id = msg.jsonObject["id"]!!.jsonPrimitive.content
                val snippet = getEmailSnippet(token, id)
                result.append("• $id — $snippet\n")
            }
            ToolResult.Ok(result.toString().trim())
        }
    }

    private suspend fun sendEmail(token: String, to: String, subject: String, body: String): ToolResult.Ok = withContext(Dispatchers.IO) {
        val rawEmail = buildString {
            append("To: $to\r\n")
            append("Subject: $subject\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("\r\n")
            append(body)
        }.toByteArray(Charsets.UTF_8)
        val encoded = android.util.Base64.encodeToString(rawEmail, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        val jsonBody = buildJsonObject { put("raw", encoded) }.toString()
        val request = Request.Builder()
            .url("$GMAIL_API/users/me/messages/send")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody(JSON))
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("Gmail send error: ${resp.code}")
            ToolResult.Ok("Email sent to $to.")
        }
    }
}