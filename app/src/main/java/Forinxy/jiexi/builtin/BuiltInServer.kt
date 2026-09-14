package Forinxy.jiexi.builtin

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 内置解析服务器：在 App 进程内监听 127.0.0.1 的随机端口，
 * 提供与服务器版 data.php 兼容的 /data.php 端点（含 /author_list.php 占位）。
 *
 * 默认（用户未配置外部服务器）时，ServerConfigStore 会把 apiBase 指向这里，
 * 使 App 安装即用、无需自建服务器。外部服务器仍是可选项。
 *
 * 安全模型：只绑定 loopback，拒绝非本机连接；请求来自本 App 自己的 OkHttp，
 * 不校验 HMAC（签名密钥本就内置在 App 里，校验无意义）。
 */
internal object BuiltInServer {
    private const val TAG = "BuiltInServer"

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val executor = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "builtin-http").apply { isDaemon = true }
    }

    @Volatile
    private var parser: BuiltInParser? = null

    @Volatile
    private var boundPort: Int = 0

    /** 运行状态（Compose 可收集，驱动设置页状态显示实时刷新） */
    private val _runningState = MutableStateFlow(false)

    val runningState: StateFlow<Boolean> = _runningState.asStateFlow()

    /** 启动失败原因（用于设置页展示诊断信息） */
    @Volatile
    private var lastError: String? = null

    fun lastError(): String? = lastError

    /** 启动内置服务器（幂等）。线程安全，可在 Application.onCreate 调用。 */
    fun start(context: Context) {
        if (running.get()) return
        synchronized(this) {
            if (running.get()) return
            try {
                if (parser == null) {
                    parser = BuiltInParser(context)
                }
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                serverSocket = socket
                boundPort = socket.localPort
                running.set(true)
                lastError = null
                _runningState.value = true
                // 注册为内置解析地址（任何入口启动都自动生效，无需调用方重复注册）
                Forinxy.jiexi.ServerConfigStore.setInternalBase(
                    "http://127.0.0.1:$boundPort/data.php",
                    "http://127.0.0.1:$boundPort/author_list.php"
                )
                acceptThread = Thread({ acceptLoop(socket) }, "builtin-accept").apply {
                    isDaemon = true
                    start()
                }
                Log.i(TAG, "Built-in server listening on 127.0.0.1:$boundPort")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start built-in server", e)
                running.set(false)
                lastError = e.message ?: e.javaClass.simpleName
                _runningState.value = false
                runCatching { serverSocket?.close() }
                serverSocket = null
                boundPort = 0
            }
        }
    }

    fun isRunning(): Boolean = running.get()

    fun boundPort(): Int = boundPort

    fun stop() {
        synchronized(this) {
            if (!running.get()) return
            running.set(false)
            _runningState.value = false
            runCatching { serverSocket?.close() }
            serverSocket = null
            acceptThread?.interrupt()
            acceptThread = null
            boundPort = 0
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Accept failed", e)
                break
            }
            try {
                executor.execute { handleConnection(client) }
            } catch (e: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = runCatching { reader.readLine() }.getOrNull() ?: return
            // 读取请求头直到空行（不读取 body，本服务只处理 GET）
            runCatching {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
            }
            val segments = requestLine.split(" ")
            if (segments.size < 2) return
            val target = segments[1]
            val response = route(target)
            writeResponse(s.getOutputStream(), response)
        }
    }

    private fun route(target: String): String {
        val (path, queryString) = splitTarget(target)
        return when {
            path.endsWith("/data.php") || path == "/data.php" || path.endsWith("data.php") -> {
                handleData(queryString)
            }
            path.endsWith("/author_list.php") || path.endsWith("author_list.php") -> {
                handleAuthorList(queryString)
            }
            else -> {
                val err = com.google.gson.JsonObject()
                err.addProperty("success", false)
                err.addProperty("error", "not found")
                err.addProperty("code", 404)
                com.google.gson.Gson().toJson(err)
            }
        }
    }

    private fun splitTarget(target: String): Pair<String, String> {
        val idx = target.indexOf('?')
        return if (idx >= 0) {
            target.substring(0, idx) to target.substring(idx + 1)
        } else {
            target to ""
        }
    }

    private fun handleData(queryString: String): String {
        val params = parseQuery(queryString)
        if (params["diag"] == "1") {
            return parser?.diag() ?: "{\"success\":false,\"error\":\"builtin parser not ready\"}"
        }
        val rawUrl = params["url"] ?: return "{\"success\":false,\"error\":\"缺少 url 参数\"}"
        val useCookie = params["mode"] == "cookie"
        val original = params["original"] == "1"
        val highest = params["highest"] == "1"
        return parser?.parse(rawUrl, useCookie, original, highest)
            ?: "{\"success\":false,\"error\":\"builtin parser not ready\"}"
    }

    private fun handleAuthorList(queryString: String): String {
        // 内置服务器不支持作者列表：返回错误促使客户端回落到 App 内置 WebView 链路
        val err = com.google.gson.JsonObject()
        err.addProperty("success", false)
        err.addProperty("error", "内置服务器不支持作者列表，请使用 App 内置解析")
        err.addProperty("code", 501)
        return com.google.gson.Gson().toJson(err)
    }

    private fun parseQuery(queryString: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (queryString.isBlank()) return result
        queryString.split("&").forEach { pair ->
            if (pair.isBlank()) return@forEach
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            val value = if (idx >= 0) pair.substring(idx + 1) else ""
            val decodedKey = runCatching { URLDecoder.decode(key, "UTF-8") }.getOrElse { key }
            val decodedValue = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrElse { value }
            result[decodedKey] = decodedValue
        }
        return result
    }

    private fun writeResponse(out: OutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }
}
