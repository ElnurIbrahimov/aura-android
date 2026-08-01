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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicrosoftFilesTool @Inject constructor(
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
        name = "onedrive",
        description = "List and search files on OneDrive via Microsoft Graph. Requires Microsoft account connected in Settings.",
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
        name = "onedrive",
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
            .url("$GRAPH_API/me/drive/root/children?\$top=$maxResults&\$select=name,file,folder,size,lastModifiedDateTime")
            .header("Authorization", "Bearer $token")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("OneDrive API error: ${resp.code}")
            val body = resp.body?.string() ?: return@withContext ToolResult.Ok("empty")
            val parsed = json.parseToJsonElement(body).jsonObject
            val items = parsed["value"]?.jsonArray ?: JsonArray(emptyList())
            if (items.isEmpty()) return@withContext ToolResult.Ok("No files in OneDrive root.")
            val result = items.joinToString("\n") { item ->
                val f = item.jsonObject
                val name = f["name"]?.jsonPrimitive?.content ?: "?"
                val isFolder = f["folder"] != null
                "• $name ${if (isFolder) "(folder)" else "(file)"}"
            }
            ToolResult.Ok("OneDrive files:\n$result")
        }
    }

    private suspend fun searchFiles(token: String, query: String, maxResults: Int): ToolResult.Ok = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url("$GRAPH_API/me/drive/root/search(q='$encoded')?\$top=$maxResults&\$select=name")
            .header("Authorization", "Bearer $token")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext ToolResult.Ok("OneDrive search error: ${resp.code}")
            val body = resp.body?.string() ?: return@withContext ToolResult.Ok("empty")
            val parsed = json.parseToJsonElement(body).jsonObject
            val items = parsed["value"]?.jsonArray ?: JsonArray(emptyList())
            if (items.isEmpty()) return@withContext ToolResult.Ok("No files matching '$query' on OneDrive.")
            val result = items.joinToString("\n") { f ->
                "• ${f.jsonObject["name"]?.jsonPrimitive?.content ?: "?"}"
            }
            ToolResult.Ok("OneDrive search results:\n$result")
        }
    }
}