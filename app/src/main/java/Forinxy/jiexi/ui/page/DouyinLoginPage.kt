package Forinxy.jiexi.ui.page

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.Toast
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import Forinxy.jiexi.DouyinAuthStore
import Forinxy.jiexi.DouyinCookieWebViewSync
import Forinxy.jiexi.ui.theme.MiuixTextField

/**
 * App 内置抖音登录页：用 WebView 加载 douyin.com，用户在页面内完成扫码/账密登录后，
 * 自动从 CookieManager 捕获 sessionid 系登录 Cookie 并持久化（DouyinAuthStore）。
 * 持久化的登录 Cookie 供「本地解析」与下载请求头使用。
 * 同时支持：复制 / 导出 / 导入当前登录 Cookie。
 */
@SuppressLint("SetJavaScriptEnabled", "WebViewLayout")
@Composable
fun DouyinLoginPage(onClose: () -> Unit) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val clipboardManager = LocalClipboardManager.current
    var nickname by remember { mutableStateOf(DouyinAuthStore.getLoginNickname(appContext)) }
    var statusText by remember {
        mutableStateOf("在下方完成抖音登录，App 会自动捕获登录 Cookie 并保存")
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    fun captureAndSaveCookie(view: WebView?) {
        if (view == null) {
            return
        }
        val cookieHeader = DouyinCookieWebViewSync.readCurrentCookieHeader()
        if (DouyinAuthStore.looksAuthenticated(cookieHeader)) {
            if (DouyinAuthStore.saveAuthCookie(appContext, cookieHeader)) {
                nickname = DouyinAuthStore.getLoginNickname(appContext)
                statusText = "已捕获登录 Cookie，本地解析将使用登录态"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                removeAllViews()
                destroy()
            }
            webViewRef = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "登录抖音账号",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        if (nickname != null) {
            Text(
                text = "已登录：$nickname",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            captureAndSaveCookie(view)
                        }
                    }
                    webViewRef = this
                    DouyinCookieWebViewSync.applyCookieHeader(
                        DouyinAuthStore.getCookie(appContext).orEmpty()
                    )
                    loadUrl("https://www.douyin.com/")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        // Cookie 管理：复制 / 导出 / 导入当前登录 Cookie
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    val cookie = DouyinAuthStore.getCookie(appContext)
                    if (cookie.isNullOrBlank()) {
                        Toast.makeText(context, "尚未保存登录 Cookie", Toast.LENGTH_SHORT).show()
                    } else {
                        clipboardManager.setText(AnnotatedString(cookie))
                        Toast.makeText(context, "登录 Cookie 已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("复制 Cookie")
            }
            OutlinedButton(
                onClick = {
                    val cookie = DouyinAuthStore.getCookie(appContext)
                    if (cookie.isNullOrBlank()) {
                        Toast.makeText(context, "尚未保存登录 Cookie", Toast.LENGTH_SHORT).show()
                    } else {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "dyparse 抖音登录 Cookie")
                            putExtra(Intent.EXTRA_TEXT, cookie)
                        }
                        context.startActivity(
                            Intent.createChooser(sendIntent, "导出登录 Cookie")
                        )
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("导出 Cookie")
            }
            OutlinedButton(
                onClick = { showImportDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileUpload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("导入 Cookie")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    DouyinAuthStore.clearAuthCookie(appContext)
                    nickname = null
                    statusText = "已清除本地登录 Cookie，可重新登录"
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteForever,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("清除登录")
            }
            Button(
                onClick = onClose,
                modifier = Modifier.weight(1f)
            ) {
                Text("完成")
            }
        }
    }

    if (showImportDialog) {
        CookieImportDialog(
            onDismiss = { showImportDialog = false },
            onImport = { rawCookie ->
                val normalized = DouyinAuthStore.normalizeCookieHeader(rawCookie)
                when {
                    normalized.isBlank() -> {
                        Toast.makeText(context, "Cookie 内容为空", Toast.LENGTH_SHORT).show()
                    }
                    !DouyinAuthStore.looksAuthenticated(normalized) -> {
                        Toast.makeText(context, "未识别到有效登录 Cookie（缺少 sessionid 等字段）", Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        DouyinAuthStore.saveAuthCookie(appContext, normalized)
                        DouyinCookieWebViewSync.applyCookieHeader(normalized)
                        nickname = DouyinAuthStore.getLoginNickname(appContext)
                        statusText = "已导入登录 Cookie，本地解析将使用登录态"
                        showImportDialog = false
                    }
                }
            }
        )
    }
}

@Composable
private fun CookieImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var cookieInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入登录 Cookie") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "粘贴抖音网页版登录后的 Cookie（浏览器复制，形如 `sessionid=...; passport_csrf_token=...`）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MiuixTextField(
                    value = cookieInput,
                    onValueChange = { cookieInput = it },
                    label = { Text("Cookie") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 5,
                    minLines = 2,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                clipboardManager.getText()?.text?.let { clip ->
                                    cookieInput = clip
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentPaste,
                                contentDescription = "从剪贴板粘贴"
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(cookieInput) },
                enabled = cookieInput.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("取消")
            }
        }
    )
}