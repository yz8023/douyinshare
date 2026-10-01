package Forinxy.jiexi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Root capability used only for the opt-in clipboard worker. No cookies or credentials are read. */
object RootAccess {
    data class Status(val available: Boolean, val detail: String)

    suspend fun check(): Status = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val ok = process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0 &&
                output.contains("uid=0")
            Status(ok, if (ok) "Root 可用" else "Root 未授权")
        }.getOrElse { Status(false, "设备未提供可用的 su") }
    }

    /** Reads only the current clipboard text through Android's shell command. */
    suspend fun readClipboard(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("su", "-c", "cmd clipboard get-clip")
                .redirectErrorStream(true)
                .start()
            val value = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(3, TimeUnit.SECONDS)
            value.trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences("dyparse_root", Context.MODE_PRIVATE)
        .getBoolean("clipboard_enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences("dyparse_root", Context.MODE_PRIVATE)
            .edit().putBoolean("clipboard_enabled", enabled).apply()
    }
}
