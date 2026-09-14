package Forinxy.jiexi.builtin

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 一次 HTTP 请求结果 */
data class HttpResult(
    val body: String,
    val finalUrl: String,
    val statusCode: Int
)

/**
 * 平台解析器共用的 HTTP 通道。统一超时/重定向策略；各平台通过 headers
 * 传递私有 UA/Referer/Cookie。OkHttp 默认跟随 302，finalUrl 为最终地址。
 */
internal class PlatformHttp(private val client: OkHttpClient) {

    fun get(url: String, headers: Map<String, String>): HttpResult? {
        return execute("GET", url, headers, body = null, contentType = null)
    }

    fun post(url: String, headers: Map<String, String>, body: String): HttpResult? {
        return execute("POST", url, headers, body, FORM_ENCODED)
    }

    fun postJson(url: String, headers: Map<String, String>, jsonBody: String): HttpResult? {
        return execute("POST", url, headers, jsonBody, JSON)
    }

    private fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        contentType: okhttp3.MediaType?
    ): HttpResult? {
        return try {
            val builder = Request.Builder().url(url)
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value)
            }
            val request = when (method) {
                "POST" -> builder.post(body.orEmpty().toRequestBody(contentType)).build()
                else -> builder.build()
            }
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string().orEmpty()
                HttpResult(respBody, response.request.url.toString(), response.code)
            }
        } catch (e: IOException) {
            Log.w(TAG, "$method $url failed", e)
            null
        } catch (e: Throwable) {
            Log.w(TAG, "$method $url failed: ${e.javaClass.simpleName}", e)
            null
        }
    }

    private fun String.toRequestBody(contentType: okhttp3.MediaType?): okhttp3.RequestBody {
        return okhttp3.RequestBody.create(
            contentType ?: FORM_ENCODED,
            this
        )
    }

    companion object {
        private const val TAG = "PlatformHttp"
        private val FORM_ENCODED = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
        private val JSON = "application/json; charset=UTF-8".toMediaType()

        /** 创建共享客户端（短超时，跟随重定向） */
        fun create(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        val PC_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        val MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    }
}