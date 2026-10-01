package Forinxy.jiexi

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import java.util.regex.Pattern

/** Captures visible work links from the logged-in Douyin collection/like page. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CollectionCaptureDialog(
    mode: CollectionMode,
    viewModel: ParserViewModel,
    onClose: () -> Unit
) {
    val pageUrl = if (mode == CollectionMode.LIKES) {
        "https://www.douyin.com/user/self?showTab=like"
    } else {
        "https://www.douyin.com/user/self?showTab=collection"
    }
    var ids by remember { mutableStateOf(linkedSetOf<String>()) }
    var status by remember { mutableStateOf("正在打开抖音页面...") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val idPattern = remember { Pattern.compile("/(?:video|note)/(\\d{15,21})") }

    fun scan(view: WebView?) {
        view?.evaluateJavascript(
            """
            (function(){return JSON.stringify(Array.from(document.querySelectorAll('a[href]')).map(a=>a.href));})()
            """.trimIndent()
        ) { raw ->
            runCatching {
                val text = raw.trim().removePrefix("\"").removeSuffix("\"").replace("\\\"", "\"")
                val array = JSONArray(text)
                val found = linkedSetOf<String>()
                for (i in 0 until array.length()) {
                    val matcher = idPattern.matcher(array.optString(i))
                    if (matcher.find()) found += matcher.group(1)
                }
                if (found.isNotEmpty()) {
                    ids = (ids + found).toCollection(linkedSetOf())
                    status = "已发现 ${ids.size} 个作品，可继续滚动加载"
                }
            }
        }
    }

    LaunchedEffect(webView) {
        while (webView != null) {
            scan(webView)
            webView?.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null)
            kotlinx.coroutines.delay(1600)
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(if (mode == CollectionMode.LIKES) "点赞作品" else "收藏作品")
            Text(status, modifier = Modifier.padding(vertical = 8.dp))
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(520.dp),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                status = "正在扫描已加载作品..."
                                scan(view)
                            }
                        }
                        webView = this
                        loadUrl(pageUrl)
                    }
                },
                update = { webView = it }
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("关闭") }
                Button(
                    enabled = ids.isNotEmpty(),
                    onClick = {
                        status = "已加入解析队列：${ids.size} 个作品"
                        viewModel.parseAuthorBatchFromCapturedIds(
                            text = pageUrl,
                            countInput = ids.size.toString(),
                            awemeIds = ids.toList(),
                            isComplete = false
                        )
                        onClose()
                    }
                ) { Text("解析已发现作品") }
            }
        }
    }
}

enum class CollectionMode { LIKES, COLLECTION }
