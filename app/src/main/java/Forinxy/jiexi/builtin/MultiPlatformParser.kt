package Forinxy.jiexi.builtin

import android.content.Context
import android.util.Log

/**
 * 内置解析门面：按 [Platform.detect] 把输入分发给对应平台解析器。
 *
 * 抖音由既有 BuiltInParser 处理（逻辑不动，避免回归）；其余平台由各平台
 * 解析器实现 [PlatformParser]。输出均为 data.php 兼容 JSON。
 */
internal class MultiPlatformParser(context: Context) : MediaParser {
    private val parsers: List<PlatformParser> = listOf(
        BuiltInParser(context),
        BilibiliParser(http),
        KuaishouParser(http),
        XiaoHongShuParser(http),
        WeiboParser(http),
        ToutiaoParser(http),
        PiPiXiaParser(http),
        PiPiGaoXiaoParser(http),
        NeteaseMusicParser(http),
        QiShuiMusicParser(http),
        XiguaParser(http),
        HaokanParser(http),
        ZhihuParser(http),
        HuyaParser(http),
        LvzhouParser(http),
        MeipaiParser(http),
        QuanminkgeParser(http),
        XinpianchangParser(http),
        ZuiyouParser(http),
        QQMusicParser(http),
        KugouMusicParser(http),
        AcfunParser(http),
        WeishiParser(http),
        LishipinParser(http),
        KlingParser(http),
        KwaiyingParser(http),
        PeiyinxiuParser(http),
        PineconeMomentParser(http),
        DewuParser(http),
        SoulParser(http),
        XianyuParser(http),
        LofterParser(http),
        HailuoParser(http),
        XiaoyunqueParser(http),
        WechatChannelsParser(http),
        WechatMpParser(http),
        ButterflyaiParser(http),
        CctvParser(http),
        YangshipinParser(http),
        FanqieParser(http),
        QianwenParser(http),
        QuarkAIParser(http),
        JianyingParser(http),
        TencentChannelParser(http),
        YuanbaoParser(http),
        JimengParser(http),
        PinduoduoParser(http),
        DoubaoParser(http)
    )

    private val byPlatform: Map<Platform, PlatformParser> = indexByPlatform(parsers)

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        val platform = Platform.detect(input)
            ?: return failResponse(unsupportedMessage())
        val parser = byPlatform[platform]
            ?: return failResponse(unsupportedMessage())
        return try {
            parser.parse(input, useCookie, original, highest)
        } catch (e: Throwable) {
            Log.w(TAG, "parse ${platform.label} failed", e)
            failResponse("解析失败（${platform.label}）：${e.message}")
        }
    }

    override fun diag(): String {
        val obj = com.google.gson.JsonObject()
        obj.addProperty("version", "dyparse-builtin-4.9")
        obj.addProperty("platform", "android-inner")
        obj.addProperty("success", true)
        val supported = com.google.gson.JsonArray()
        byPlatform.keys.sortedBy { it.ordinal }.forEach { supported.add(it.label) }
        obj.add("supported", supported)
        return com.google.gson.Gson().toJson(obj)
    }

    private fun unsupportedMessage(): String =
        "无法识别链接所属平台，支持：${Platform.entries.joinToString("、") { it.label }}"

    companion object {
        private const val TAG = "MultiPlatformParser"
        private val http = PlatformHttp(PlatformHttp.create())
    }
}