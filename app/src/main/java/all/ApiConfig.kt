package io.github.aixtin.nyral

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * API 公共配置（动态，多 Provider 独立存储）。
 * - 每个供应商（预设 3 个 + 用户自定义 N 个）单独保存自己的 base/key/model
 * - 当前使用的供应商单独标记 current_provider
 * - 列表持久化为 JSON（providers_json），兼容旧版单配置字段（首次读取兜底）
 * - 注意：所有读取入口都必须先调用 init(context)（每个 Activity onCreate 调用）
 */
object ApiConfig {
    private const val PREF = "api_config"
    private const val K_PROVIDERS = "providers_json"
    private const val K_CURRENT = "current_provider"
    private const val K_SELECTED = "selected_providers"

    // ============ 思考强度(档位抽象, 与厂商实际参数映射见 thinkingEffortParams) ============
    const val THINK_AUTO = "auto"      // 不传参, 交给模型默认
    const val THINK_OFF = "off"        // 关闭思考(仅支持关闭的模型生效)
    const val THINK_ON = "on"          // 开启思考(仅支持开关的模型展示)
    const val THINK_LOW = "low"
    const val THINK_MEDIUM = "medium"
    const val THINK_HIGH = "high"
    const val THINK_ULTRA = "ultra"
    /** 全量档位 key 列表, 顺序即展示顺序; 各厂商可见子集由 thinkingLevelsOf 决定 */
    val THINK_LEVEL_KEYS = listOf(THINK_AUTO, THINK_OFF, THINK_ON, THINK_LOW, THINK_MEDIUM, THINK_HIGH)

    /** 档位 key -> 当前 locale 显示文案 */
    fun thinkLabel(ctx: Context, key: String): String = when (key) {
        THINK_AUTO -> ctx.getString(R.string.think_auto)
        THINK_OFF -> ctx.getString(R.string.think_off)
        THINK_ON -> ctx.getString(R.string.think_on)
        THINK_LOW -> ctx.getString(R.string.think_low)
        THINK_MEDIUM -> ctx.getString(R.string.think_medium)
        THINK_HIGH -> ctx.getString(R.string.think_high)
        else -> key
    }

    // ============ 模型能力（输入模态 + 工具调用） ============
    const val CAP_TEXT = "text"     // 文本（所有模型必备）
    const val CAP_IMAGE = "image"   // 图片输入
    const val CAP_VIDEO = "video"   // 视频输入
    const val CAP_AUDIO = "audio"   // 音频输入
    const val CAP_TOOL = "tool"     // 工具调用

    /** 能力 key -> 当前 locale 显示文案 */
    fun capLabel(ctx: Context, cap: String): String = when (cap) {
        CAP_TEXT -> ctx.getString(R.string.cap_text)
        CAP_IMAGE -> ctx.getString(R.string.cap_image)
        CAP_VIDEO -> ctx.getString(R.string.cap_video)
        CAP_AUDIO -> ctx.getString(R.string.cap_audio)
        CAP_TOOL -> ctx.getString(R.string.cap_tool)
        else -> cap
    }

    /** 某供应商/模型支持的能力集合（文件上传选项/工具开关按此渲染）。
     *  优先级: 手动模型级勾选 modelCaps[model] → 预设内置表/启发式 → 供应商级勾选兜底 → 手动默认仅文本 */
    fun modelCapabilities(id: String, model: String): Set<String> {
        val p = providerById(id)
        if (p == null) return defaultCaps()
        val caps = p.modelCaps[model]
        if (caps != null && caps.isNotEmpty()) return caps
        if (!p.isPreset) {
            return if (p.capabilities.isNotEmpty()) p.capabilities else setOf(CAP_TEXT)
        }
        return presetCapabilities(p, model)
    }

    private fun defaultCaps(): Set<String> = setOf(CAP_TEXT, CAP_TOOL)

    /** 预置模型能力表：按 id/model 查表，未知模型名启发式兜底 */
    private fun presetCapabilities(p: Provider, model: String): Set<String> {
        val m = model.lowercase()
        return when {
            p.id == "mimo" || m.contains("mimo") -> setOf(CAP_TEXT, CAP_IMAGE, CAP_VIDEO, CAP_AUDIO, CAP_TOOL)
            p.id == "deepseek" && m.contains("vision") -> setOf(CAP_TEXT, CAP_IMAGE, CAP_VIDEO, CAP_TOOL)
            p.id == "deepseek" -> setOf(CAP_TEXT, CAP_TOOL)
            p.id == "zhipu" || m.contains("glm") -> setOf(CAP_TEXT, CAP_TOOL)
            m.contains("vl") || m.contains("vision") || m.contains("omni") -> setOf(CAP_TEXT, CAP_IMAGE, CAP_TOOL)
            m.contains("audio") -> setOf(CAP_TEXT, CAP_AUDIO, CAP_TOOL)
            else -> defaultCaps()
        }
    }

    /** 模型是否支持某能力 */
    fun modelHasCap(id: String, model: String, cap: String): Boolean =
        cap in modelCapabilities(id, model)

    /** 按模型名启发式猜测能力（拉取模型弹窗预填标注用；不依赖供应商 id） */
    fun guessModelCaps(model: String): Set<String> {
        val m = model.lowercase()
        return when {
            m.contains("mimo") || m.contains("omni") ->
                setOf(CAP_TEXT, CAP_IMAGE, CAP_VIDEO, CAP_AUDIO, CAP_TOOL)
            m.contains("vision") || m.contains("-vl") || m.contains("_vl") ->
                setOf(CAP_TEXT, CAP_IMAGE, CAP_VIDEO, CAP_TOOL)
            m.contains("audio") || m.contains("voice") || m.contains("speech") ->
                setOf(CAP_TEXT, CAP_AUDIO, CAP_TOOL)
            m.contains("deepseek") || m.contains("gpt") || m.contains("qwen") || m.contains("glm") ||
                m.contains("llama") || m.contains("gemini") || m.contains("claude") || m.contains("doubao") ||
                m.contains("kimi") || m.contains("moonshot") || m.contains("spark") || m.contains("ernie") ->
                setOf(CAP_TEXT, CAP_TOOL)
            else -> setOf(CAP_TEXT)
        }
    }

    data class Provider(
        val id: String,
        val label: String,
        val defaultBase: String,
        val defaultModel: String,
        val key: String = "",
        val isPreset: Boolean = true,
        /** api=联网 API；local=本地 GGUF 模型 */
        val type: String = "api",
        /** 本地模型文件 URI（SAF 持久化授权） */
        val uri: String = "",
        /** 该供应商可选子模型列表（空 = 仅 defaultModel 单模型） */
        val models: List<String> = emptyList(),
        /** 当前选中的子模型（空 = 用 defaultModel 兜底） */
        val selectedModel: String = "",
        /** 思考强度档位（THINK_*），按供应商级存储、按当前模型动态映射 */
        val thinkingEffort: String = THINK_AUTO,
        /** 模型能力集合（CAP_*）；预设=内置表，自定义=用户勾选（供应商级兜底） */
        val capabilities: Set<String> = emptySet(),
        /** 模型级能力：模型名 → CAP_* 集合；手动模型每个子模型独立勾选，预设不存（动态查表） */
        val modelCaps: Map<String, Set<String>> = emptyMap(),
        /** 鉴权方式：bearer（默认）/ x-api-key / header（自定义请求头名） */
        val authType: String = "bearer",
        /** 自定义鉴权请求头名（authType=header 时生效，默认 Authorization） */
        val authHeader: String = "Authorization"
    )

    // 默认兜底(仅 base/model; 不再硬编码 API key, key 未配置时明确报错而非静默用旧 key)
    private const val DEFAULT_BASE = "https://api.deepseek.com/v1"
    private const val DEFAULT_MODEL = "deepseek-chat"

    @Volatile private var app: Context? = null

    /** 由每个 Activity.onCreate 调用一次注入 context（重复调用无害） */
    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun prefs(): SharedPreferences? = app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // 预设仅提供 base/model 骨架，不预置子模型列表：子模型必须由用户显式"拉取"或手动添加后才会出现，
    // 这样清除数据后模型列表随持久化数据一起消失，不会由兜底预设"复活"
    private val PRESETS = listOf(
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        Provider("zhipu", "Zhipu GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
        Provider("mimo", "Xiaomi MiMo", "https://api.xiaomimimo.com/v1", "mimo-v2.5"),
    )

    // ============ Provider 列表持久化 ============

    /** 全部 Provider（预设 + 自定义），带用户已保存的 base/key/model */
    fun providers(): List<Provider> {
        val raw = prefs()?.getString(K_PROVIDERS, null)
        if (!raw.isNullOrEmpty()) {
            try {
                // 安全加固: Key 落盘加密; 兼容旧明文自动迁移
                var plain: String? = null
                var migrated = false
                val appCtx = app
                if (appCtx != null) {
                    plain = Secrets.decrypt(appCtx, raw)
                    if (plain == null) {
                        JSONArray(raw) // 旧明文仅校验格式
                        plain = raw
                        migrated = true
                    }
                } else plain = raw
                val arr = JSONArray(plain)
                val out = mutableListOf<Provider>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val models = if (o.has("models")) {
                        val ja = o.getJSONArray("models")
                        (0 until ja.length()).map { ja.getString(it) }
                    } else emptyList()
                    out.add(Provider(
                        o.getString("id"),
                        o.getString("label"),
                        o.optString("base", ""),
                        o.optString("model", ""),
                        o.optString("key", ""),
                        o.optBoolean("isPreset", true),
                        o.optString("type", "api"),
                        o.optString("uri", ""),
                        models,
                        o.optString("selectedModel", ""),
                        o.optString("thinkingEffort", THINK_AUTO),
                        capabilitiesOf(o),
                        modelCapsOf(o),
                        o.optString("authType", "bearer"),
                        o.optString("authHeader", "Authorization")
                    ))
                }
                if (out.isNotEmpty()) {
                    if (migrated) saveProviders(out) // 旧明文迁移为加密存储
                    return out
                }
            } catch (_: Exception) {}
        }
        // 兼容旧版单配置字段：旧版保存过 deepseek 配置则合并进预设
        val old = prefs()
        val oldId = old?.getString("provider", null)
        return if (old != null && oldId != null) {
            val o = old
            PRESETS.map {
                if (it.id == oldId) it.copy(
                    defaultBase = o.getString("base", it.defaultBase) ?: it.defaultBase,
                    defaultModel = o.getString("model", it.defaultModel) ?: it.defaultModel,
                    key = o.getString("key", it.key) ?: it.key
                ) else it
            }
        } else PRESETS
    }

    private fun saveProviders(list: List<Provider>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("label", p.label)
                put("base", p.defaultBase)
                put("model", p.defaultModel)
                put("key", p.key)
                put("isPreset", p.isPreset)
                put("type", p.type)
                put("uri", p.uri)
                put("models", JSONArray(p.models))
                put("selectedModel", p.selectedModel)
                put("thinkingEffort", p.thinkingEffort)
                put("capabilities", JSONArray(p.capabilities.toList()))
                put("modelCaps", JSONObject().apply {
                    p.modelCaps.forEach { (m, caps) ->
                        put(m, JSONArray(caps.toList()))
                    }
                })
                put("authType", p.authType)
                put("authHeader", p.authHeader)
            })
        }
        prefs()?.edit()?.putString(K_PROVIDERS, app?.let { Secrets.encrypt(it, arr.toString()) } ?: arr.toString())?.apply()
    }

    /** 解析 Provider 配置中的 capabilities JSONArray */
    private fun capabilitiesOf(o: JSONObject): Set<String> {
        val ja = o.optJSONArray("capabilities") ?: return emptySet()
        val out = mutableSetOf<String>()
        for (i in 0 until ja.length()) {
            val c = ja.optString(i, "")
            if (c.isNotEmpty()) out.add(c)
        }
        return out
    }

    /** 解析 Provider 配置中的 modelCaps JSONObject：模型名 → CAP_* 集合 */
    private fun modelCapsOf(o: JSONObject): Map<String, Set<String>> {
        val jo = o.optJSONObject("modelCaps") ?: return emptyMap()
        val out = mutableMapOf<String, Set<String>>()
        jo.keys().forEach { m ->
            val ja = jo.optJSONArray(m) ?: return@forEach
            val caps = mutableSetOf<String>()
            for (i in 0 until ja.length()) {
                val c = ja.optString(i, "")
                if (c.isNotEmpty()) caps.add(c)
            }
            if (caps.isNotEmpty()) out[m] = caps
        }
        return out
    }

    /** 新增自定义 Provider，返回新 id；models 为拉取多选的子模型集合（可为空） */
    fun addCustomProvider(label: String, base: String, key: String, model: String, models: List<String>? = null, thinkingEffort: String = THINK_AUTO, capabilities: Set<String> = emptySet(), modelCaps: Map<String, Set<String>> = emptyMap(), authType: String = "bearer", authHeader: String = "Authorization"): String {
        val list = providers().toMutableList()
        var n = 1
        while (list.any { it.id == "custom_$n" }) n++
        val id = "custom_$n"
        val picked = models?.map { m -> m.trim() }?.filter { m -> m.isNotEmpty() }?.distinct()
        val newModels = if (picked != null) {
            if (picked.isEmpty()) picked else (picked + model.trim()).distinct()
        } else emptyList()
        list.add(Provider(id, label.ifBlank { "自定义 $n" }, base, model, key, isPreset = false,
            models = newModels, selectedModel = if (newModels.isNotEmpty()) model.trim() else "",
            thinkingEffort = thinkingEffort, capabilities = capabilities, modelCaps = modelCaps,
            authType = authType, authHeader = authHeader))
        saveProviders(list)
        return id
    }

    fun deleteProvider(id: String) {
        val list = providers().filterNot { it.id == id }
        saveProviders(list)
        if (providerId() == id) setCurrent(list.firstOrNull()?.id ?: "deepseek")
    }

    /** 新增本地 GGUF 模型，返回新 id；uri 为 SAF 持久化授权后的文件 URI */
    fun addLocalProvider(label: String, uri: String, setAsCurrent: Boolean = true): String {
        val list = providers().toMutableList()
        var n = 1
        while (list.any { it.id == "local_$n" }) n++
        val id = "local_$n"
        list.add(Provider(id, label.ifBlank { "本地模型 $n" }, "", label, "", isPreset = false, type = "local", uri = uri))
        saveProviders(list)
        if (setAsCurrent) setCurrent(id)
        return id
    }

    /** 是否本地模型 */
    fun isLocal(id: String): Boolean =
        providers().find { it.id == id }?.type == "local"

    /** 当前是否使用本地模型 */
    fun isCurrentLocal(): Boolean = isLocal(providerId())

    /** 当前本地模型的 SAF 文件 URI（非本地时返回空） */
    fun localUri(): String {
        val p = providerById(providerId()) ?: return ""
        return if (p.type == "local") p.uri else ""
    }

    fun providerById(id: String): Provider? = providers().find { it.id == id }

    // ============ 当前 Provider ============

    fun providerId(): String = prefs()?.getString(K_CURRENT, "deepseek") ?: "deepseek"

    fun setCurrent(id: String) {
        prefs()?.edit()?.putString(K_CURRENT, id)?.apply()
    }

    fun providerLabel(id: String): String =
        providers().find { it.id == id }?.label ?: id

    // ============ 当前配置读取 ============

    fun baseUrl(): String {
        val p = providerById(providerId()) ?: return DEFAULT_BASE
        return p.defaultBase.ifBlank { DEFAULT_BASE }
    }

    fun apiKey(): String {
        val p = providerById(providerId())
        return p?.key ?: ""
    }

    fun model(): String {
        val p = providerById(providerId()) ?: return DEFAULT_MODEL
        if (p.models.isNotEmpty()) {
            val sm = p.selectedModel
            if (sm.isNotBlank() && p.models.contains(sm)) return sm
            return p.defaultModel.ifBlank { p.models.first() }
        }
        return p.defaultModel.ifBlank { DEFAULT_MODEL }
    }

    /** 某供应商当前生效的子模型（models 非空时优先 selectedModel/models.first()） */
    fun currentModelOf(id: String): String {
        val p = providerById(id) ?: return ""
        if (p.models.isNotEmpty()) {
            val sm = p.selectedModel
            if (sm.isNotBlank() && p.models.contains(sm)) return sm
            return p.models.first()
        }
        return p.defaultModel
    }

    /** 设置某供应商当前选中的子模型 */
    fun setSelectedModel(id: String, model: String) {
        val list = providers().map {
            if (it.id == id) it.copy(selectedModel = model) else it
        }
        saveProviders(list)
    }

    /** 拼出 /chat/completions 完整地址 */
    fun chatUrl(): String = baseUrl().trimEnd('/') + "/chat/completions"

    /** 保存某个 Provider 的配置，可选设为当前。
     *  models 非 null 时表示用户从"拉取模型"多选得到的子模型集合，直接写入；为 null 时沿用编辑前逻辑
     *  thinkingEffort 非 null 时覆盖思考强度档位；为 null 时保留原值
     *  capabilities 非 null 时覆盖供应商级能力；modelCaps 非 null 时覆盖模型级能力（手动模型逐模型勾选） */
    fun saveProvider(id: String, base: String, key: String, model: String, setAsCurrent: Boolean, models: List<String>? = null, thinkingEffort: String? = null, capabilities: Set<String>? = null, modelCaps: Map<String, Set<String>>? = null, authType: String? = null, authHeader: String? = null) {
        val list = providers().map {
            if (it.id == id) {
                val picked = models?.map { m -> m.trim() }?.filter { m -> m.isNotEmpty() }?.distinct()
                val newModels = if (picked != null) {
                    // 拉取多选结果：主模型输入框的值也并入，确保二级列表可见
                    if (picked.isEmpty()) picked else (picked + model.trim()).distinct()
                } else {
                    // 编辑页改了模型名时同步子模型列表：已存在则选中该项，否则收敛为单模型
                    if (it.models.isNotEmpty()) {
                        if (it.models.contains(model.trim())) it.models else listOf(model.trim())
                    } else it.models
                }
                it.copy(
                    defaultBase = base.trim(),
                    defaultModel = model.trim(),
                    key = key.trim(),
                    models = newModels,
                    selectedModel = if (newModels.isNotEmpty()) model.trim() else it.selectedModel,
                    thinkingEffort = thinkingEffort ?: it.thinkingEffort,
                    capabilities = capabilities ?: it.capabilities,
                    modelCaps = modelCaps ?: it.modelCaps,
                    authType = authType ?: it.authType,
                    authHeader = authHeader ?: it.authHeader
                )
            } else it
        }
        saveProviders(list)
        if (setAsCurrent) setCurrent(id)
    }

    // ============ 鉴权方式读取 ============

    /** 某供应商的鉴权方式（bearer / x-api-key / header） */
    fun authTypeOf(id: String): String = providerById(id)?.authType ?: "bearer"

    /** 某供应商的自定义鉴权请求头名（authType=header 时使用） */
    fun authHeaderOf(id: String): String = providerById(id)?.authHeader ?: "Authorization"

    // ============ 思考强度读取/设置/映射 ============

    /** 某供应商的思考强度档位（默认 auto） */
    fun thinkingEffortOf(id: String): String = providerById(id)?.thinkingEffort ?: THINK_AUTO

    /** 设置某供应商的思考强度档位 */
    fun setThinkingEffort(id: String, effort: String) {
        val list = providers().map {
            if (it.id == id) it.copy(thinkingEffort = effort) else it
        }
        saveProviders(list)
    }

    /** 某供应商/模型可见的思考强度档位（始终含"自动"；不支持的档位不展示）。
     *  规则：DeepSeek/Qwen 支持开关 -> 自动/关闭/开启；OpenAI o 系支持强度 -> 自动/低/中/高；
     *  其它未知厂商 -> 仅自动（不传参）。 */
    fun thinkingLevelsOf(id: String, model: String): List<String> {
        val p = providerById(id)
        val label = (p?.label ?: "").lowercase()
        val m = model.lowercase()
        val levels = mutableListOf(THINK_AUTO)
        when {
            label.contains("deepseek") -> {
                levels.add(THINK_OFF); levels.add(THINK_ON)
            }
            label.contains("openai") || m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") -> {
                levels.add(THINK_LOW); levels.add(THINK_MEDIUM); levels.add(THINK_HIGH)
            }
            m.contains("qwen3") || label.contains("qwen") -> {
                levels.add(THINK_OFF); levels.add(THINK_ON)
            }
        }
        return levels
    }

    /** 六档 -> 厂商实际请求参数。
     *  返回 null 表示该模型不支持思考开关/档位，调用方不传任何 thinking 参数（交模型默认）。
     *  支持关系按"供应商 label + 当前模型名"动态判断，不支持的厂商档位一律忽略兜底。 */
    fun thinkingEffortParams(id: String, model: String, effort: String): JSONObject? {
        if (effort == THINK_AUTO) return null
        val p = providerById(id)
        val label = (p?.label ?: "").lowercase()
        val m = model.lowercase()
        return when {
            // OpenAI o 系：reasoning_effort low/medium/high；off/超高 不支持则忽略
            label.contains("openai") || m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") -> {
                if (effort == THINK_OFF || effort == THINK_ON) return null
                val v = when (effort) {
                    THINK_LOW -> "low"; THINK_MEDIUM -> "medium"; THINK_HIGH -> "high"; THINK_ULTRA -> "high"
                    else -> null
                } ?: return null
                JSONObject().put("reasoning_effort", v)
            }
            // DeepSeek：thinking 为对象格式 {"type":"enabled"|"disabled"}（V3.1 起支持；低/中/高/超高统一视为开启）
            label.contains("deepseek") ->
                JSONObject().put("thinking", JSONObject().put("type", if (effort == THINK_OFF) "disabled" else "enabled"))
            // Qwen（Qwen3 等）：enable_thinking 布尔
            m.contains("qwen3") || label.contains("qwen") ->
                JSONObject().put("enable_thinking", effort != THINK_OFF)
            // 其它厂商：暂无思考参数映射，忽略
            else -> null
        }
    }

    // ============ 多选池（启用的供应商集合） ============

    /** 勾选的多模型池：同时启用的供应商 id 集合（与当前单值 providerId 互不影响） */
    fun selectedIds(): Set<String> {
        val raw = prefs()?.getString(K_SELECTED, null)
        if (!raw.isNullOrEmpty()) {
            try {
                val arr = JSONArray(raw)
                val out = mutableSetOf<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i))
                return out
            } catch (_: Exception) {}
        }
        return setOf(providerId())
    }

    fun setSelectedIds(ids: Set<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        prefs()?.edit()?.putString(K_SELECTED, arr.toString())?.apply()
    }
}
