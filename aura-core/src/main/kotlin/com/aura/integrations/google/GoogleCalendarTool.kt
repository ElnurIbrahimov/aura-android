package com.aura.integrations.google

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
import kotlinx.coroutines.flow.first
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
class GoogleCalendarTool @Inject constructor(
    private val tokenStore: IntegrationTokenStore,
    private val oauthFlow: OAuthFlow,
    private val userPreferences: UserPreferences,
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val CAL_API = "https://www.googleapis.com/calendar/v3"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun definition() = ToolDefinition(
        name = "google_calendar",
        description = "Read and manage Google Calendar events. List upcoming events, create new events, and delete events. Requires Google account connected in Settings.",
        parameters = ToolParameters(
            properties = mapOf(
                "action" to ToolProperty(type = "string", description = "One of: list, create, delete"),
                "max_results" to ToolProperty(type = "integer", description = "Max events to list (default 10, max 25)"),
                "summary" to ToolProperty(type = "string", description = "Event title (for 'create')"),
                "start_time" to ToolProperty(type = "string", description = "Start time ISO 8601 (for 'create', e.g. 2026-08-02T10:00:00)"),
                "end_time" to ToolProperty(type = "string", description = "End time ISO 8601 (for 'create')"),
                "description" to ToolProperty(type = "string", description = "Event description (for 'create')"),
                "event_id" to ToolProperty(type = "string", description = "Event ID (for 'delete')"),
            ),
            required = listOf("action"),
        ),
    )

    val tool = Tool(
        name = "google_calendar",
        description = definition().description,
        risk = ToolRisk.REMOTE_COST,
        parameters = definition().parameters,
        execute = { call, _ ->
            val action = call.arguments["action"] as? String
                ?: return@Tool ToolResult.Error("missing 'action'", "bad_args")
            val clientId = userPreferences.googleClientId.first()
            if (clientId.isNullOrBlank()) return@Tool ToolResult.Error("Google not connected", "not_connected")
            val token = tokenStore.getValidGoogleAccessToken { refreshToken ->
                oauthFlow.refreshGoogleToken(refreshToken, clientId)
            } ?: return@Tool ToolResult.Error("Google token expired", "token_expired")

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
                "delete" -> {
                    val eventId = call.arguments["event_id"] as? String
                        ?: return@Tool ToolResult.Error("missing 'event_id'", "bad_args")
                    deleteEvent(token, eventId)
                }
                else -> ToolResult.Error("unknown action: $action", "bad_args")
            }
        },
    )

    private suspend fun listEvents(token: String, maxResults: Int): ToolResult.Ok = withContext(Dispatchers.IO) {
        val now = java.time.Instant.now().toString()
        val request = Request.Builder()
            .url("$CAL_API/calendars/primary/events?maxResults=$maxResults&timeMin=${java.net.URLEncoder.encode(now, "UTF-8")}&singleEvents=true&orderBy=startTime")
            .header("Authorization", "Bearer $token")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("Calendar API error: ${resp.code}")
            val body = resp.body?.string() ?: return@withContext ToolResult.Ok("empty response")
            val parsed = json.parseToJsonElement(body).jsonObject
            val items = parsed["items"]?.jsonArray ?: JsonArray(emptyList())
            if (items.isEmpty()) return@withContext ToolResult.Ok("No upcoming events.")
            val result = items.joinToString("\n") { item ->
                val event = item.jsonObject
                val id = event["id"]?.jsonPrimitive?.content ?: "?"
                val summary = event["summary"]?.jsonPrimitive?.content ?: "(no title)"
                val start = event["start"]?.jsonObject?.get("dateTime")?.jsonPrimitive?.content
                    ?: event["start"]?.jsonObject?.get("date")?.jsonPrimitive?.content ?: "?"
                "• $id — $summary @ $start"
            }
            ToolResult.Ok("Upcoming events:\n$result")
        }
    }

    private suspend fun createEvent(token: String, summary: String, start: String, end: String, description: String): ToolResult.Ok = withContext(Dispatchers.IO) {
        val eventJson = buildJsonObject {
            put("summary", summary)
            put("description", description)
            put("start", buildJsonObject {
                put("dateTime", start)
                put("timeZone", java.time.ZoneId.systemDefault().id)
            })
            put("end", buildJsonObject {
                put("dateTime", end)
                put("timeZone", java.time.ZoneId.systemDefault().id)
            })
        }.toString()
        val request = Request.Builder()
            .url("$CAL_API/calendars/primary/events")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(eventJson.toRequestBody(JSON))
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("Calendar create error: ${resp.code}")
            ToolResult.Ok("Event '$summary' created.")
        }
    }

    private suspend fun deleteEvent(token: String, eventId: String): ToolResult.Ok = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$CAL_API/calendars/primary/events/$eventId")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("Calendar delete error: ${resp.code}")
            ToolResult.Ok("Event deleted.")
        }
    }
}