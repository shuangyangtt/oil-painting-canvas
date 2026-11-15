package app.youtubu.tv

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

const val PARENT_PORT = 8686

/** The projector's address in the home network, for the phone page. */
fun localAddress(context: Context): String? {
    // the official way (newer Android versions restrict NetworkInterface)
    try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props = cm.getLinkProperties(cm.activeNetwork)
        props?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }?.let { return it.hostAddress }
    } catch (_: Exception) {
    }
    return try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}

/**
 * A tiny web server, running only while the parent settings are open, so channels can be
 * managed from a phone or computer in the same WiFi instead of typing with the remote.
 */
class ParentServer(private val context: Context, private val state: AppState) {
    private var socket: ServerSocket? = null

    fun start() {
        if (socket != null) return
        val server = try {
            ServerSocket(PARENT_PORT)
        } catch (e: Exception) {
            return
        }
        socket = server
        thread(name = "parent-server", isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: SocketException) {
                    break
                }
                thread(isDaemon = true) { client.use { handle(it) } }
            }
        }
    }

    fun stop() {
        socket?.close()
        socket = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: 0
            }
        }
        val body = ByteArray(contentLength.coerceAtMost(1_000_000))
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) break
            read += n
        }
        val parts = requestLine.split(" ")
        val method = parts.getOrElse(0) { "GET" }
        val path = parts.getOrElse(1) { "/" }.substringBefore('?')

        val (type, response) = try {
            route(method, path, String(body, 0, read, Charsets.UTF_8))
        } catch (e: Exception) {
            "application/json" to JSONObject().put("error", e.message ?: "出错了").toString()
        }
        val bytes = response.toByteArray(Charsets.UTF_8)
        val out = client.getOutputStream()
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
        }
    }

    private fun route(method: String, path: String, body: String): Pair<String, String> {
        if (method == "GET" && (path == "/" || path == "/index.html")) {
            return "text/html" to context.assets.open("parent.html").bufferedReader().readText()
        }
        val json = if (body.isNotBlank()) JSONObject(body) else JSONObject()
        val result: JSONObject = runBlocking {
            when (path) {
                "/api/state" -> onMain { stateJson() }
                "/api/add" -> add(json.optString("text"))
                "/api/addId" -> {
                    val channel = state.api.channelInfo(json.getString("id"))
                    onMain { state.addChannel(channel) }
                    JSONObject().put("added", JSONArray().put(channel.name))
                }
                "/api/remove" -> onMain { state.removeChannel(json.getString("id")); ok() }
                "/api/move" -> onMain { state.moveChannel(json.getString("id"), json.getInt("delta")); ok() }
                "/api/favRemove" -> onMain { state.removeFavorite(json.getString("id")); ok() }
                "/api/instance" -> onMain { state.setInstance(json.getString("url")); ok() }
                "/api/quality" -> onMain { state.store.maxHeight = json.getInt("height"); ok() }
                "/api/refresh" -> onMain { state.refreshAll(); ok() }
                "/api/reset" -> onMain { state.resetChannels(); ok() }
                "/api/clearCache" -> {
                    withContext(Dispatchers.IO) { Media.clearCache(context) }
                    onMain { state.offlineVideos = emptyList(); ok() }
                }
                "/api/autoPrecache" -> onMain { state.store.autoPrecache = json.getBoolean("on"); ok() }
                else -> JSONObject().put("error", "没有这个功能")
            }
        }
        return "application/json" to result.toString()
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private fun ok() = JSONObject().put("ok", true)

    private fun stateJson(): JSONObject {
        val channels = JSONArray()
        for (ch in state.channels) {
            channels.put(ch.toJson().put("count", state.rows[ch.id]?.videos?.size ?: 0))
        }
        return JSONObject()
            .put("channels", channels)
            .put("favorites", JSONArray(state.favorites.map { it.toJson() }))
            .put("instance", state.store.instance)
            .put("maxHeight", state.store.maxHeight)
            .put("cacheUsed", Media.cacheUsed())
            .put("offlineCount", state.offlineVideos.size)
            .put("autoPrecache", state.store.autoPrecache)
            .put("precacheStatus", state.precacher.status)
            .put("cacheLimit", Media.cacheLimit)
            .put("log", JSONArray(Media.logLines()))
            .put("about", aboutJson(appVersion(context)))
    }

    /** Each line is a channel link, @handle or channel id; a single line of plain words is a search. */
    private suspend fun add(text: String): JSONObject {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val added = JSONArray()
        val failed = JSONArray()
        for (line in lines) {
            val id = state.api.resolveChannel(line)
            if (id == null) {
                if (lines.size == 1) {
                    val results = state.api.searchChannels(line)
                    return JSONObject().put("results", JSONArray(results.map { it.toJson() }))
                }
                failed.put(line)
                continue
            }
            try {
                val channel = state.api.channelInfo(id)
                withContext(Dispatchers.Main) { state.addChannel(channel) }
                added.put(channel.name)
            } catch (e: Exception) {
                failed.put(line)
            }
        }
        return JSONObject().put("added", added).put("failed", failed)
    }
}
