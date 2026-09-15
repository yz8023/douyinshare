package Forinxy.jiexi.builtin

/**
 * 夸克 AI Studio 外部分享作品解析器。夸克 AI 与通义千问分享页使用相同的
 * __INITIAL_PROPS__ 数据结构，复用 QianwenParser 已验证的解析逻辑，
 * 保留独立平台标识。按 Python 参考实现移植。
 */
internal class QuarkAIParser(http: PlatformHttp) : QianwenParser(http) {

    override val platform: Platform get() = Platform.QUARK_AI
}