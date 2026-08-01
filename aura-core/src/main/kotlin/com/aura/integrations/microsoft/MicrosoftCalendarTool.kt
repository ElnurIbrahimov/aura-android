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
class MicrosoftCalendarTool @Inject constructor(
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
        name = "outlook_calendar",
        description = "Read and manage Outlook calendar events via Microsoft Graph. List upcoming events and create new events. Requires Microsoft account connected in Settings.",
        parameters = ToolParameters(
            properties = mapOf(
                "action" to ToolProperty(type = "string", description = "One of: list, create"),
                "max_results" to ToolProperty(type = "integer", description = "Max events to list (default 10, max 25)"),
                "summary" to ToolProperty(type = "string", description = "Event title (for 'create')"),
                "start_time" to ToolProperty(type = "string", description = "Start time ISO 8601 (for 'create')"),
                "end_time" to ToolProperty(type = "string", description = "End time ISO 8601 (for 'create')"),
                "description" to ToolProperty(type = "string", description = "Event description (for 'create')"),
            ),
            required = listOf("action"),
        ),
    )

    val tool = Tool(
        name = "outlook_calendar",
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
                "list" -> listEvents(token, (call.arguments["max_results"] as? Int ?: 10).coerceAtMost(25))
                "create" -> {
                    val summary = call.arguments["summary"] as? String
                        ?: return@Tool ToolResult.Error("missing 'summary'", "bad_args")
                    val start = call.arguments["start_time"] as? String
                        ?: return@Tool ToolResult.Error("missing 'start_time'", "bad_args")
                    val end = call.arguments["end_time"] as? String
                        ?: return@Tool ToolResult.Error("missing 'end_time'", "bad_args")
                    val desc = call.arguments["description"] as? String ?: ""
                    createEvent(token, summary, start, end, desc)
                }
                else -> ToolResult.Error("unknown action: $action", "bad_args")
            }
        },
    )

    private suspend fun listEvents(token: String, maxResults: Int): ToolResult.Ok = withContext(Dispatchers.IO) {
        val now = java.time.Instant.now().toString()
        val request = Request.Builder()
            .url("$GRAPH_API/me/calendarview?startDateTime=${java.net.URLEncoder.encode(now, "UTF-8")}&\$top=$maxResults&\$select=id,subject,start,end,organizer")
            .header("Authorization", "Bearer $token")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("Graph calendar error: ${resp.code}")
            val body = resp.body?.string() ?: return@withContext ToolResult.Ok("empty")
            val parsed = json.parseToJsonElement(body).jsonObject
            val events = parsed["value"]?.jsonArray ?: JsonArray(emptyList())
            if (events.isEmpty()) return@withContext ToolResult.Ok("No upcoming Outlook events.")
            val result = events.joinToString("\n") { e ->
                val event = e.jsonObject
                val subject = event["subject"]?.jsonPrimitive?.content ?: "(no title)"
                val start = event["start"]?.jsonObject?.get("dateTime")?.jsonPrimitive?.content ?: "?"
                "• $subject @ $start"
            }
            ToolResult.Ok("Upcoming Outlook events:\n$result")
        }
    }

    private suspend fun createEvent(token: String, summary: String, start: String, end: String, description: String): ToolResult.Ok = withContext(Dispatchers.IO) {
        val tz = java.time.ZoneId.systemDefault().id
        val eventJson = buildJsonObject {
            put("subject", summary)
            put("body", buildJsonObject {
                put("contentType", "Text")
                put("content", description)
            })
            put("start", buildJsonObject {
                put("dateTime", start)
                put("timeZone", tz)
            })
            put("end", buildJsonObject {
                put("dateTime", end)
                put("timeZone", tz)
            })
        }.toString()
        val request = Request.Builder()
            .url("$GRAPH_API/me/events")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(eventJson.toRequestBody(JSON))
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("Graph create error: ${resp.code}")
            ToolResult.Ok("Outlook event '$summary' created.")
        }
    }
}