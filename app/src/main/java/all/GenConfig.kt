package io.github.aixtin.nyral

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 生成能力配置(2026-11-04 v2 契约版): 多厂商 AI 生图配置列表。
 * - 每个厂商一条配置: 契约(GenContract)描述协议(端点/鉴权/请求模式/取图路径), Key 独立加密存本机
 * - 月度限额: 按自然月滚动计数(默认 100 张/月), 防 AI 失控花钱
 * - Key 红线: 任何 Key 不进入 describeForAI() 输出(主 AI 只见模型身份/成本/额度)
 */
object GenConfig {
    private const val PREFS = "nyral_gen"
    private const val K_CONFIGS = "gen_configs_json"
    private const val K_MONTH = "gen_month"             // "yyyy-MM"
    private const val K_COUNT = "gen_month_count"       // 当月已用张数
    private const val K_QUOTA = "gen_month_quota"       // 月度上限(张)

    /** 平台协议契约: 描述一个生图 API 的完整调用方式 */
    data class GenContract(
        val provider: String = "",                // 厂商标识(如 dashscope/hunyuan/openai)
        val displayName: String = "",             // 显示名(如 通义万相)
        val endpoint: String = "",                // 同步请求端点(完整 URL)
        val authType: String = "bearer",          // bearer | header | none
        val authHeaderName: String = "Authorization",
        val authPrefix: String = "Bearer ",
        val mode: String = "sync",                // sync | async
        val submitEndpoint: String = "",          // async: 提交端点
        val pollEndpoint: String = "",            // async: 轮询端点(含 {task_id} 占位)
        val taskIdPath: String = "output.task_id",
        val statusPath: String = "output.task_status",
        val successValue: String = "SUCCEEDED",
        val template: String = "prompt",          // prompt | chat
        val modelField: String = "model",
        val promptField: String = "prompt",
        val sizeField: String = "size",
        val sizeSeparator: String = "*",          // 尺寸分隔符: 万相 "*" / 混元 "x"
        val extraHeaders: Map<String, String> = emptyMap(),
        val imageUrlPath: String = "data[0].url", // 响应取图路径: data[0].url / choices[0].delta.image.url / output.results[0].url
        val modelId: String = "",                 // 模型名(如 wanx2.1-t2i-turbo)
        val pricePerImage: Double = 0.0           // 单张成本(元), 仅作主 AI 元信息
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("provider", provider)
            .put("displayName", displayName)
            .put("endpoint", endpoint)
            .put("authType", authType)
            .put("authHeaderName", authHeaderName)
            .put("authPrefix", authPrefix)
            .put("mode", mode)
            .put("submitEndpoint", submitEndpoint)
            .put("pollEndpoint", pollEndpoint)
            .put("taskIdPath", taskIdPath)
            .put("statusPath", statusPath)
            .put("successValue", successValue)
            .put("template", template)
            .put("modelField", modelField)
            .put("promptField", promptField)
            .put("sizeField", sizeField)
            .put("sizeSeparator", sizeSeparator)
            .put("extraHeaders", JSONObject(extraHeaders))
            .put("imageUrlPath", imageUrlPath)
            .put("modelId", modelId)
            .put("pricePerImage", pricePerImage)

        companion object {
            fun fromJson(o: JSONObject): GenContract {
                val eh = mutableMapOf<String, String>()
                o.optJSONObject("extraHeaders")?.keys()?.forEach { k ->
                    eh[k] = o.optJSONObject("extraHeaders").optString(k)
                }
                return GenContract(
                    provider = o.optString("provider"),
                    displayName = o.optString("displayName"),
                    endpoint = o.optString("endpoint"),
                    authType = o.optString("authType", "bearer"),
                    authHeaderName = o.optString("authHeaderName", "Authorization"),
                    authPrefix = o.optString("authPrefix", "Bearer "),
                    mode = o.optString("mode", "sync"),
                    submitEndpoint = o.optString("submitEndpoint"),
                    pollEndpoint = o.optString("pollEndpoint"),
                    taskIdPath = o.optString("taskIdPath", "output.task_id"),
                    statusPath = o.optString("statusPath", "output.task_status"),
                    successValue = o.optString("successValue", "SUCCEEDED"),
                    template = o.optString("template", "prompt"),
                    modelField = o.optString("modelField", "model"),
                    promptField = o.optString("promptField", "prompt"),
                    sizeField = o.optString("sizeField", "size"),
                    sizeSeparator = o.optString("sizeSeparator", "*"),
                    extraHeaders = eh,
                    imageUrlPath = o.optString("imageUrlPath", "data[0].url"),
                    modelId = o.optString("modelId"),
                    pricePerImage = o.optDouble("pricePerImage", 0.0)
                )
            }
        }
    }

    /** 单个厂商配置条目 */
    data class Entry(
        val id: String,
        val displayName: String,
        val enabled: Boolean = true,
        val isDefault: Boolean = false,
        val contract: GenContract,
        val apiKeyEnc: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("displayName", displayName)
            .put("enabled", enabled)
            .put("isDefault", isDefault)
            .put("contract", contract.toJson())
            .put("apiKeyEnc", apiKeyEnc)

        companion object {
            fun fromJson(o: JSONObject): Entry = Entry(
                id = o.optString("id", UUID.randomUUID().toString()),
                displayName = o.optString("displayName"),
                enabled = o.optBoolean("enabled", true),
                isDefault = o.optBoolean("isDefault", false),
                contract = GenContract.fromJson(o.optJSONObject("contract") ?: JSONObject()),
                apiKeyEnc = o.optString("apiKeyEnc")
            )
        }
    }

    // ---------- 配置列表 ----------
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun configs(c: Context): List<Entry> {
        val raw = prefs(c).getString(K_CONFIGS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Entry.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveConfigs(c: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString(K_CONFIGS, arr.toString()).apply()
    }

    fun addOrUpdate(c: Context, e: Entry) {
        val list = configs(c).toMutableList()
        val idx = list.indexOfFirst { it.id == e.id }
        if (idx >= 0) list[idx] = e else list.add(e)
        if (e.isDefault) {
            for (i in list.indices) {
                if (list[i].id != e.id && list[i].isDefault) list[i] = list[i].copy(isDefault = false)
            }
        }
        saveConfigs(c, list)
    }

    fun delete(c: Context, id: String) {
        saveConfigs(c, configs(c).filter { it.id != id })
    }

    fun entryById(c: Context, id: String): Entry? = configs(c).firstOrNull { it.id == id }

    /** 默认厂商: 优先 isDefault, 否则第一个 enabled */
    fun defaultEntry(c: Context): Entry? {
        val list = configs(c)
        return list.firstOrNull { it.isDefault && it.enabled } ?: list.firstOrNull { it.enabled }
    }

    /** enabled 厂商列表 */
    fun enabledEntries(c: Context): List<Entry> = configs(c).filter { it.enabled }

    // ---------- Key(加密存本机, 不出设备) ----------
    fun keyOf(c: Context, e: Entry): String {
        if (e.apiKeyEnc.isBlank()) return ""
        return Secrets.decrypt(c, e.apiKeyEnc) ?: ""
    }

    fun entryWithKey(c: Context, e: Entry, key: String): Entry {
        val enc = if (key.trim().isEmpty()) "" else Secrets.encrypt(c, key.trim())
        return e.copy(apiKeyEnc = enc)
    }

    // ---------- 月度限额(全局) ----------
    fun quotaMax(c: Context): Int = prefs(c).getInt(K_QUOTA, 100)

    fun setQuotaMax(c: Context, max: Int) {
        prefs(c).edit().putInt(K_QUOTA, max.coerceAtLeast(1)).apply()
    }

    private fun currentMonth(): String =
        SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    fun monthUsed(c: Context): Int {
        val p = prefs(c)
        return if (p.getString(K_MONTH, "") == currentMonth()) p.getInt(K_COUNT, 0) else 0
    }

    fun canConsume(c: Context): Boolean = monthUsed(c) < quotaMax(c)

    fun consume(c: Context) {
        val p = prefs(c)
        val m = currentMonth()
        val used = if (p.getString(K_MONTH, "") == m) p.getInt(K_COUNT, 0) else 0
        p.edit().putString(K_MONTH, m).putInt(K_COUNT, used + 1).apply()
    }

    // ---------- 平台指纹表(域名 → 默认契约) ----------
    /** 从 URL 识别平台, 返回默认契约(不含 Key), 识别不出返回 null */
    fun inferContractFromUrl(url: String): GenContract? {
        val u = url.lowercase()
        return when {
            u.contains("tokenhub.tencentmaas.com") -> GenContract(
                provider = "hunyuan", displayName = "腾讯混元",
                endpoint = "https://tokenhub.tencentmaas.com/v1/wand/hunyuan-image/v3-generation",
                authType = "bearer", authHeaderName = "Authorization", authPrefix = "Bearer ",
                mode = "sync", template = "prompt", sizeSeparator = "x",
                imageUrlPath = "data[0].url",
                pricePerImage = 0.14
            )
            u.contains("dashscope.aliyuncs.com") -> GenContract(
                provider = "dashscope", displayName = "通义万相",
                endpoint = "https://dashscope.aliyuncs.com/api/v1",
                authType = "bearer", authHeaderName = "Authorization", authPrefix = "Bearer ",
                mode = "async",
                submitEndpoint = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis",
                pollEndpoint = "https://dashscope.aliyuncs.com/api/v1/tasks/{task_id}",
                taskIdPath = "output.task_id", statusPath = "output.task_status", successValue = "SUCCEEDED",
                template = "prompt", sizeSeparator = "*",
                extraHeaders = mapOf("X-DashScope-Async" to "enable"),
                imageUrlPath = "output.results[0].url",
                pricePerImage = 0.14
            )
            u.contains("/v1/images/generations") || (u.contains("/v1/") && u.contains("images")) -> GenContract(
                provider = "openai", displayName = "OpenAI 兼容",
                endpoint = url,
                authType = "bearer", authHeaderName = "Authorization", authPrefix = "Bearer ",
                mode = "sync", template = "prompt", sizeSeparator = "x",
                imageUrlPath = "data[0].url",
                pricePerImage = 0.0
            )
            else -> null
        }
    }

    /** 从粘贴的官方示例提取硬特征: Key / model / URL */
    fun parseExample(example: String): Triple<String, String, String> {
        val key = Regex("""sk-[A-Za-z0-9]{6,}""").find(example)?.value ?: ""
        val model = Regex("""["']?model["']?\s*[:=]\s*["']([A-Za-z0-9._-]+)["']""").find(example)?.groupValues?.get(1) ?: ""
        val url = Regex("""https?://[^\s"'\)\]]+""").find(example)?.value ?: ""
        return Triple(key, model, url)
    }

    /** 按模型名修正契约: 模型含 v3.5/v35(混元新协议) 时切换为 v3.5 契约(Chat/Messages + choices[0].delta.image.url) */
    fun refineContractByModel(contract: GenContract, model: String): GenContract {
        val m = model.lowercase()
        if (m.contains("v3.5") || m.contains("v35")) {
            return GenContract(
                provider = "hunyuan", displayName = "腾讯混元",
                endpoint = "https://tokenhub.tencentmaas.com/v1/wand/hunyuan-image/v35-generation",
                authType = "bearer", authHeaderName = "Authorization", authPrefix = "Bearer ",
                mode = "sync", template = "chat", sizeSeparator = "x",
                imageUrlPath = "choices[0].delta.image.url",
                modelId = model,
                pricePerImage = if (contract.pricePerImage > 0) contract.pricePerImage else 0.14
            )
        }
        return contract
    }

    /** 主 AI 可见的元信息描述(绝不包含 Key), 供 LocalEngine 动态拼 gen_image ToolSpec */
    fun describeForAI(c: Context): String {
        val list = enabledEntries(c)
        if (list.isEmpty()) return "未配置任何生图厂商, 请先在 设置 → AI 生成 添加并测试。"
        val sb = StringBuilder()
        list.forEach { e ->
            val keyState = if (keyOf(c, e).isBlank()) "未填Key" else "Key已填"
            val used = monthUsed(c); val max = quotaMax(c)
            val price = if (e.contract.pricePerImage > 0) "约 ${e.contract.pricePerImage} 元/张" else "价格未知"
            val flag = if (e.isDefault) " [默认]" else ""
            sb.append("· ${e.displayName}$flag: ${e.contract.modelId.ifBlank { "模型未填" }}, $price, 本月 $used/$max 张, $keyState\n")
        }
        sb.append("用户不指定厂商时默认用 [默认] 厂商; 调用前需用户确认, 且受月度限额保护。")
        return sb.toString()
    }
}
