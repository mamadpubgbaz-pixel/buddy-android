package com.sepand.ai.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/** Minimal Streamable HTTP MCP client. Every tool invocation is separately approved in the UI. */
object McpClient {
    private val ids = AtomicInteger(1)
    @Volatile private var activeEndpoint: String = ""
    @Volatile private var sessionId: String? = null
    @Volatile private var protocolVersion: String = "2025-03-26"

    suspend fun initialize(endpoint: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            validateEndpoint(endpoint)
            val request = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", ids.getAndIncrement())
                put("method", "initialize")
                put("params", JSONObject().apply {
                    put("protocolVersion", "2025-03-26")
                    put("capabilities", JSONObject())
                    put("clientInfo", JSONObject().put("name", "Sepand AI").put("version", "0.2.0-beta1"))
                })
            }
            val response = post(endpoint, request, includeSession = false)
            val json = parseJson(response.body)
            val error = json.optJSONObject("error")
            check(error == null) { error?.optString("message", "MCP initialize failed") ?: "MCP initialize failed" }
            protocolVersion = json.optJSONObject("result")?.optString("protocolVersion", "2025-03-26") ?: "2025-03-26"
            activeEndpoint = endpoint
            sessionId = response.sessionId
            post(endpoint, JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), includeSession = true, allowEmpty = true)
            val server = json.optJSONObject("result")?.optJSONObject("serverInfo")
            "Connected: ${server?.optString("name", "MCP server") ?: "MCP server"}"
        }
    }

    suspend fun listTools(endpoint: String): Result<List<JSONObject>> = withContext(Dispatchers.IO) {
        try {
            ensureEndpoint(endpoint)
            runCatching {
                val request = JSONObject().put("jsonrpc", "2.0")
                    .put("id", ids.getAndIncrement())
                    .put("method", "tools/list")
                    .put("params", JSONObject())
                val json = parseJson(post(endpoint, request).body)
                val error = json.optJSONObject("error")
                check(error == null) { error?.optString("message", "tools/list failed") ?: "tools/list failed" }
                val array = json.optJSONObject("result")?.optJSONArray("tools")
                buildList { if (array != null) for (i in 0 until array.length()) add(array.getJSONObject(i)) }
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun callTool(endpoint: String, name: String, arguments: JSONObject): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureEndpoint(endpoint)
            runCatching {
                val request = JSONObject().put("jsonrpc", "2.0")
                    .put("id", ids.getAndIncrement())
                    .put("method", "tools/call")
                    .put("params", JSONObject().put("name", name).put("arguments", arguments))
                val json = parseJson(post(endpoint, request).body)
                val error = json.optJSONObject("error")
                check(error == null) { error?.optString("message", "Tool call failed") ?: "Tool call failed" }
                val result = json.optJSONObject("result") ?: JSONObject()
                val content = result.optJSONArray("content")
                val text = buildString {
                    if (content != null) for (i in 0 until content.length()) {
                        val item = content.optJSONObject(i) ?: continue
                        if (item.optString("type") == "text") append(item.optString("text")).append('\n')
                        else append("[${item.optString("type", "non-text content")}]\n")
                    }
                }.trim()
                if (result.optBoolean("isError")) "MCP tool returned an error: $text" else text.ifBlank { result.toString() }.take(8000)
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    private suspend fun ensureEndpoint(endpoint: String) {
        validateEndpoint(endpoint)
        if (activeEndpoint != endpoint) {
            val init = initialize(endpoint)
            init.getOrThrow()
        }
    }

    private fun validateEndpoint(endpoint: String) {
        val uri = URI(endpoint)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        val secure = scheme == "https"
        val loopbackHttp = scheme == "http" && host in setOf("localhost", "127.0.0.1")
        require(secure || loopbackHttp) {
            "MCP endpoints must use HTTPS. HTTP is allowed only for localhost/127.0.0.1 on this device."
        }
        require(uri.userInfo == null) { "Do not embed credentials in the MCP URL." }
    }

    private data class HttpResponse(val body: String, val sessionId: String?)

    private fun post(endpoint: String, request: JSONObject, includeSession: Boolean = true, allowEmpty: Boolean = false): HttpResponse {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("MCP-Protocol-Version", protocolVersion)
            if (includeSession) sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        try {
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "HTTP $code: ${body.take(500)}" }
            if (!allowEmpty) check(body.isNotBlank()) { "MCP server returned an empty response" }
            return HttpResponse(body, connection.getHeaderField("Mcp-Session-Id") ?: sessionId)
        } finally { connection.disconnect() }
    }

    private fun parseJson(body: String): JSONObject {
        val line = body.lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim()
        return JSONObject(line ?: body)
    }
}
