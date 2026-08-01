package com.aura.integrations.microsoft

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.integrations.IntegrationTokenStore
import com.aura.integrations.OAuthFlow
import com.aura.data.UserPreferences
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

private val JSON = "application/json; charset=utf-8".toMediaType()

@Singleton
class MicrosoftMailTool @Inject constructor(
    private val tokenStore: IntegrationTokenStore,
    private val oauthFlow: OAuthFlow,
    private val userPreferences: UserPreferences,
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val GRAPH_API = "https://graph.microsoft.com/v1.0"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun definition() = ToolDefinition(
        name = "outlook_mail",
        description = "Read and send Outlook.com/Hotmail email via Microsoft Graph. List recent emails, read a specific email, search, and send. Requires Microsoft account connected in Settings.",
        parameters = ToolParameters(
            properties = mapOf(
                "action" to ToolProperty(type = "string", description = "One of: list, read, search, send"),
                "query" to ToolProperty(type = "string", description = "Search query (for 'search' action)"),
                "email_id" to ToolProperty(type = "string", description = "Email ID (for 'read' action)"),
                "to" to ToolProperty(type = "string", description = "Recipient (for 'send' action)"),
                "subject" to ToolProperty(type = "string", description = "Email subject (for 'send' action)"),
                "body" to ToolProperty(type = "string", description = "Email body (for 'send' action)"),
                "max_results" to ToolProperty(type = "integer", description = "Max results (default 10, max 20)"),
            ),
            required = listOf("action"),
        ),
    )

    val tool = Tool(
        name = "outlook_mail",
        description = definition().description,
        risk = ToolRisk.REMOTE_COST,
        parameters = definition().parameters,
        execute = { call, _ ->
            val action = call.arguments["action"] as? String
                ?: return@Tool ToolResult.Error("missing 'action'", "bad_args")
            val clientId = userPreferences.microsoftClientIdSync()
            if (clientId.isNullOrBlank()) return@Tool ToolResult.Error("Microsoft not connected", "not_connected")
            val token = tokenStore.getValidMicrosoftAccessToken { refreshToken ->
                oauthFlow.refreshMicrosoftToken(refreshToken, clientId)
            } ?: return@Tool ToolResult.Error("Microsoft token expired", "token_expired")

            when (action) {
                "list" -> listEmails(token, (call.arguments["max_results"] as? Int ?: 10).coerceAtMost(20))
                "read" -> {
                    val id = call.arguments["email_id"] as? String
                        ?: return@Tool ToolResult.Error("missing 'email_id'", "bad_args")
                    readEmail(token, id)
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
            .url("$GRAPH_API/me/messages?\$top=$maxResults&\$select=id,subject,from,bodyPreview,receivedDateTime&\$orderby=receivedDateTime desc")
            .header("Authorization", "Bearer $token")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("Graph API error: ${resp.code}")
            val body = resp.body?.string() ?: return@withContext ToolResult.Ok("empty")
            val parsed = json.parseToJsonElement(body).jsonObject
            val messages = parsed["value"]?.jsonArray ?: JsonArray(emptyList())
            if (messages.isEmpty()) return@withContext ToolResult.Ok("No recent emails.")
            val result = messages.joinToString("\n") { msg ->
                val m = msg.jsonObject
                val id = m["id"]?.jsonPrimitive?.content ?: "?"
                val subject = m["subject"]?.jsonPrimitive?.content ?: "(no subject)"
                val from = m["from"]?.jsonObject?.get("emailAddress")?.jsonObject?.get("address")?.jsonPrimitive?.content ?: "?"
                val preview = m["bodyPreview"]?.jsonPrimitive?.content?.take(80) ?: ""
                "• $id — $from — $subject — $preview"
            }
            ToolResult.Ok("Recent Outlook emails:\n$result")
        }
    }

    private suspend fun readEmail(token: String, id: String): ToolResult.Ok = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$GRAPH_API/me/messages/$id?\$select=subject,from,body,receivedDateTime")
            .header("Authorization", "Bearer $token")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("Graph API error: ${resp.code}")
            val body = resp.body?.string() ?: return@withContext ToolResult.Ok("empty")
            val parsed = json.parseToJsonElement(body).jsonObject
            val subject = parsed["subject"]?.jsonPrimitive?.content ?: "(no subject)"
            val from = parsed["from"]?.jsonObject?.get("emailAddress")?.jsonObject?.get("address")?.jsonPrimitive?.content ?: "?"
            val content = parsed["body"]?.jsonObject?.get("content")?.jsonPrimitive?.content?.take(2000) ?: "(empty)"
            // Strip HTML tags for plain text
            val plainText = content.replace(Regex("<[^>]+>"), "").trim().take(1000)
            ToolResult.Ok("From: $from\nSubject: $subject\n\n$plainText")
        }
    }

    private suspend fun searchEmails(token: String, query: String, maxResults: Int): ToolResult.Ok = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url("$GRAPH_API/me/messages?\$search=\"$encoded\"&\$top=$maxResults&\$select=id,subject,from,bodyPreview")
            .header("Authorization", "Bearer $token")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("Graph search error: ${resp.code}")
            val body = resp.body?.string() ?: return@withContext ToolResult.Ok("empty")
            val parsed = json.parseToJsonElement(body).jsonObject
            val messages = parsed["value"]?.jsonArray ?: JsonArray(emptyList())
            if (messages.isEmpty()) return@withContext ToolResult.Ok("No emails found for '$query'.")
            val result = messages.joinToString("\n") { msg ->
                val m = msg.jsonObject
                "• ${m["id"]?.jsonPrimitive?.content ?: "?"} — ${m["subject"]?.jsonPrimitive?.content ?: "(no subject)"}"
            }
            ToolResult.Ok("Outlook search results:\n$result")
        }
    }

    private suspend fun sendEmail(token: String, to: String, subject: String, body: String): ToolResult.Ok = withContext(Dispatchers.IO) {
        val emailJson = buildJsonObject {
            put("subject", subject)
            put("body", buildJsonObject {
                put("contentType", "Text")
                put("content", body)
            })
            put("toRecipients", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("emailAddress", buildJsonObject {
                        put("address", to)
                    })
                })
            })
        }.toString()
        val request = Request.Builder()
            .url("$GRAPH_API/me/sendMail")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(emailJson.toRequestBody(JSON))
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("Graph send error: ${resp.code}")
            ToolResult.Ok("Email sent to $to via Outlook.")
        }
    }
}