package Forinxy.jiexi

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import Forinxy.jiexi.builtin.BuiltInServer
import android.content.Context
import android.util.Log

/**
 * 应用入口：配置 Coil 图片加载器（内存 + 磁盘缓存），并启动内置解析服务器。
 */
class DyparseApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // 服务器配置要先于任何网络调用初始化（解析全程依赖它）
        ServerConfigStore.init(this)
        // 启动内置解析服务器（127.0.0.1 + 随机端口），未配置外部服务器时默认使用
        startBuiltInServer(this)
    }

    private fun startBuiltInServer(context: Context) {
        val appContext = context.applicationContext
        // 内置服务器仅在本进程内服务，用普通线程后台启动即可
        Thread {
            try {
                BuiltInServer.start(appContext)
                if (BuiltInServer.isRunning()) {
                    val port = BuiltInServer.boundPort()
                    val apiBase = "http://127.0.0.1:$port/data.php"
                    val authorApiBase = "http://127.0.0.1:$port/author_list.php"
                    ServerConfigStore.setInternalBase(apiBase, authorApiBase)
                    Log.i("DyparseApp", "Built-in parser ready: $apiBase")
                }
            } catch (e: Exception) {
                Log.w("DyparseApp", "Built-in server start failed", e)
            }
        }.apply {
            isDaemon = true
            name = "builtin-server-start"
        }.start()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cache"))
                    .maxSizeBytes(200L * 1024 * 1024) // 200MB
                    .build()
            }
            .crossfade(false)
            .build()
    }
}