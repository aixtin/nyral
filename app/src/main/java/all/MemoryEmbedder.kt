package io.github.aixtin.nyral

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File

/**
 * bge-small-zh-v1.5 ONNX 语义嵌入器(模型纯热更版)。
 * 加载源: filesDir 热更模型, 未就绪时由 ModelUpdater 冷启动下载, 完成后 reloadAll() 接管。
 * v2.9.7 起不再内置 assets 模型(APK 减 24MB), 单一来源避免两份模型维护不同步。
 */
class MemoryEmbedder(context: Context) {

    // U7(2026-11-16): OrtEnvironment.getEnvironment() 会触发 native 库(onnnxruntime)加载。
    // 原实现放在字段初始化里 = 类被首次触摸(含 MainActivity.onCreate 构造 MemoryEmbedder)
    // 就加载; Robolectric/JVM 单测没有 .so, 直接 UnsatisfiedLinkError 打断整个类初始化。
    // 改成 by lazy + runCatching: 拿不到就保持 null, 上层按"模型未就绪"降级 ——
    // 语义检索不可用, 不影响其它功能(与 bge_model.onnx 缺失时的既有降级路径一致)。
    private val env: OrtEnvironment? by lazy {
        runCatching { OrtEnvironment.getEnvironment() }.getOrNull()
    }
    private val ctx: Context = context.applicationContext

    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: BertTokenizer? = null
    @Volatile private var outputName: String? = null
    @Volatile private var ready = false

    init {
        instances.add(java.lang.ref.WeakReference(this))
        reload()
    }

    @Synchronized
    fun reload(): Boolean {
        return try {
            val modelFile = File(ctx.filesDir, "bge_model.onnx")
            val vocabFile = File(ctx.filesDir, "bge_vocab.txt")
            // 仅认 filesDir 热更模型; 缺失即视为未就绪, 交由 ModelUpdater 冷启动下载
            if (!modelFile.exists() || !vocabFile.exists()) {
                closeSession()
                return false
            }
            val opts = OrtSession.SessionOptions()
            // 单线程推理: 双线程虽快但会占满 2 个核导致发热(2026-10-20 真机诊断), 归档场景优先控温
            opts.setIntraOpNumThreads(1)
            // native 库不可用(单测/Robolectric 或 so 缺失): 保持未就绪, 语义检索降级
            val e = env ?: run { closeSession(); return false }
            val s = e.createSession(modelFile.absolutePath, opts)
            val info = s.outputInfo
            val out = if (info.keys.any { it.contains("sentence", true) }) {
                info.keys.first { it.contains("sentence", true) }
            } else {
                info.keys.first()
            }
            session = s
            tokenizer = BertTokenizer(vocabFile)
            outputName = out
            ready = true
            true
        } catch (e: Exception) {
            closeSession()
            false
        }
    }

    private fun closeSession() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        tokenizer = null
        outputName = null
        ready = false
    }

    fun isReady(): Boolean = ready

    fun embed(text: String): FloatArray {
        val e = env ?: throw IllegalStateException("onnxruntime 不可用")
        val s = session ?: throw IllegalStateException("语义模型未就绪")
        val tk = tokenizer!!
        val on = outputName!!
        val ids = tk.encode(text, 512)
        val seqLen = ids.size
        val inputIds = LongArray(seqLen) { ids[it].toLong() }
        val attn = LongArray(seqLen) { 1L }
        val types = LongArray(seqLen) { 0L }

        val inputs = LinkedHashMap<String, OnnxTensor>()
        inputs["input_ids"] = OnnxTensor.createTensor(e, java.nio.LongBuffer.wrap(inputIds), longArrayOf(1L, seqLen.toLong()))
        inputs["attention_mask"] = OnnxTensor.createTensor(e, java.nio.LongBuffer.wrap(attn), longArrayOf(1L, seqLen.toLong()))
        inputs["token_type_ids"] = OnnxTensor.createTensor(e, java.nio.LongBuffer.wrap(types), longArrayOf(1L, seqLen.toLong()))

        s.run(inputs).use { results ->
            val out = results[on]?.get() as ai.onnxruntime.OnnxTensor
            val buf = out.floatBuffer
            val data = FloatArray(buf.remaining())
            buf.get(data)
            val shape = out.info.shape ?: longArrayOf(1L, seqLen.toLong(), 512L)
            if (on.contains("sentence", true)) {
                // B4 修复(2026-10-20): 热更模型输出维度与 DIM 不一致时防御, 避免越界
                val dim = minOf(data.size, MemoryDb.DIM)
                val arr = FloatArray(MemoryDb.DIM)
                for (i in 0 until dim) arr[i] = data[i]
                return arr
            } else {
                val dim = shape.last().toInt()
                val seq = shape[1].toInt()
                val sum = FloatArray(dim)
                var count = 0
                for (t in 0 until seq) {
                    if (attn[t] == 0L) continue
                    val base = t * dim
                    for (d in 0 until dim) sum[d] += data[base + d]
                    count++
                }
                if (count > 0) for (d in 0 until dim) sum[d] = sum[d] / count
                return sum
            }
        }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (denom == 0f) 0f else dot / denom
    }

    companion object {
        private val instances = java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<MemoryEmbedder>>()

        /** ModelUpdater 下载完成后调用: 所有存活实例重新加载 */
        fun reloadAll() {
            val it = instances.iterator()
            while (it.hasNext()) {
                val ref = it.next()
                val inst = ref.get()
                if (inst == null) it.remove() else inst.reload()
            }
        }
    }
}
