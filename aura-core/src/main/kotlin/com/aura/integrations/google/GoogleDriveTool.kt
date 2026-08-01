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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

private val JSON = "application/json; charset=utf-8".toMediaType()

@Singleton
class GoogleDriveTool @Inject constructor(
    private val tokenStore: IntegrationTokenStore,
    private val oauthFlow: OAuthFlow,
    private val userPreferences: UserPreferences,
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val DRIVE_API = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun definition() = ToolDefinition(
        name = "google_drive",
        description = "List, search, and upload files to Google Drive. Requires Google account connected in Settings.",
        parameters = ToolParameters(
            properties = mapOf(
                "action" to ToolProperty(type = "string", description = "One of: list, search"),
                "query" to ToolProperty(type = "string", description = "Search query (for 'search' action)"),
                "max_results" to ToolProperty(type = "integer", description = "Max results (default 10, max 20)"),
            ),
            required = listOf("action"),
        ),
    )

    val tool = Tool(
        name = "google_drive",
        description = definition().description,
        risk = ToolRisk.REMOTE_COST,
        parameters = definition().parameters,
        execute = { call, _ ->
            val action = call.arguments["action"] as? String
                ?: return@Tool ToolResult.Error("missing 'action'", "bad_args")
            val clientId = userPreferences.googleClientIdSync()
            if (clientId.isNullOrBlank()) return@Tool ToolResult.Error("Google not connected", "not_connected")
            val token = tokenStore.getValidGoogleAccessToken { refreshToken ->
                oauthFlow.refreshGoogleToken(refreshToken, clientId)
            } ?: return@Tool ToolResult.Error("Google token expired", "token_expired")

            when (action) {
                "list" -> listFiles(token, (call.arguments["max_results"] as? Int ?: 10).coerceAtMost(20))
                "search" -> {
                    val query = call.arguments["query"] as? String
                        ?: return@Tool ToolResult.Error("missing 'query'", "bad_args")
                    searchFiles(token, query, (call.arguments["max_results"] as? Int ?: 10).coerceAtMost(20))
                }
                else -> ToolResult.Error("unknown action: $action", "bad_args")
            }
        },
    )

    private suspend fun listFiles(token: String, maxResults: Int): ToolResult.Ok = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$DRIVE_API/files?pageSize=$maxResults&fields=files(id,name,mimeType,modifiedTime)")
            .header("Authorization", "Bearer $token")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("Drive API error: ${resp.code}")
            val body = resp.body?.string() ?: return@withContext ToolResult.Ok("empty response")
            val parsed = json.parseToJsonElement(body).jsonObject
            val files = parsed["files"]?.jsonArray ?: JsonArray(emptyList())
            if (files.isEmpty()) return@withContext ToolResult.Ok("No files found.")
            val result = files.joinToString("\n") { f ->
                val file = f.jsonObject
                val name = file["name"]?.jsonPrimitive?.content ?: "?"
                val type = file["mimeType"]?.jsonPrimitive?.content ?: "?"
                "• $name ($type)"
            }
            ToolResult.Ok("Google Drive files:\n$result")
        }
    }

    private suspend fun searchFiles(token: String, query: String, maxResults: Int): ToolResult.Ok = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url("$DRIVE_API/files?q=name+contains+'$encoded'&pageSize=$maxResults&fields=files(id,name,mimeType)")
            .header("Authorization", "Bearer $token")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("Drive search error: ${resp.code}")
            val body = resp.body?.string() ?: return@withContext ToolResult.Ok("empty response")
            val parsed = json.parseToJsonElement(body).jsonObject
            val files = parsed["files"]?.jsonArray ?: JsonArray(emptyList())
            if (files.isEmpty()) return@withContext ToolResult.Ok("No files matching '$query'.")
            val result = files.joinToString("\n") { f ->
                val file = f.jsonObject
                "• ${file["name"]?.jsonPrimitive?.content ?: "?"}"
            }
            ToolResult.Ok("Search results:\n$result")
        }
    }
}