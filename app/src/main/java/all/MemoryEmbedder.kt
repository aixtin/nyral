package io.github.aixtin.nyral

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File

/**
 * bge-small-zh-v1.5 ONNX 语义嵌入器(模型热更新版)。
 * 加载优先级: filesDir 热更模型 -> assets 内置模型(旧包兜底) -> 未就绪(ModelUpdater 下载完成后 reloadAll)。
 */
class MemoryEmbedder(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
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
            var loaded = modelFile.exists() && vocabFile.exists()
            if (!loaded) {
                // assets 兜底: 旧包仍内置模型
                try {
                    modelFile.parentFile?.mkdirs()
                    ctx.assets.open("mem_model/model_quantized.onnx").use { input ->
                        modelFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    ctx.assets.open("mem_model/vocab.txt").use { input ->
                        vocabFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    loaded = true
                } catch (e: Exception) {
                    loaded = false
                }
            }
            if (!loaded) {
                closeSession()
                return false
            }
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(2)
            val s = env.createSession(modelFile.absolutePath, opts)
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
        val s = session ?: throw IllegalStateException("语义模型未就绪")
        val tk = tokenizer!!
        val on = outputName!!
        val ids = tk.encode(text, 512)
        val seqLen = ids.size
        val inputIds = LongArray(seqLen) { ids[it].toLong() }
        val attn = LongArray(seqLen) { 1L }
        val types = LongArray(seqLen) { 0L }

        val inputs = LinkedHashMap<String, OnnxTensor>()
        inputs["input_ids"] = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(inputIds), longArrayOf(1L, seqLen.toLong()))
        inputs["attention_mask"] = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(attn), longArrayOf(1L, seqLen.toLong()))
        inputs["token_type_ids"] = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(types), longArrayOf(1L, seqLen.toLong()))

        s.run(inputs).use { results ->
            val out = results[on]?.get() as ai.onnxruntime.OnnxTensor
            val buf = out.floatBuffer
            val data = FloatArray(buf.remaining())
            buf.get(data)
            val shape = out.info.shape ?: longArrayOf(1L, seqLen.toLong(), 512L)
            if (on.contains("sentence", true)) {
                val arr = FloatArray(MemoryDb.DIM)
                for (i in 0 until MemoryDb.DIM) arr[i] = data[i]
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
