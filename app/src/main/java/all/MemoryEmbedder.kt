package io.github.aixtin.droidagent

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File

/**
 * bge-small-zh-v1.5 ONNX 语义嵌入器
 * 输入: input_ids / attention_mask / token_type_ids
 * 输出: sentence_embedding 直出; 或 last_hidden_state 做 mean pooling
 */
class MemoryEmbedder(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer: BertTokenizer
    private val outputName: String

    init {
        val modelFile = File(context.filesDir, "bge_model.onnx")
        if (!modelFile.exists()) {
            modelFile.parentFile?.mkdirs()
            context.assets.open("mem_model/model_quantized.onnx").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val vocabFile = File(context.filesDir, "bge_vocab.txt")
        if (!vocabFile.exists()) {
            context.assets.open("mem_model/vocab.txt").use { input ->
                vocabFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(2)
        session = env.createSession(modelFile.absolutePath, opts)
        tokenizer = BertTokenizer(vocabFile)

        // 运行时探测输出名: sentence_embedding 优先, 否则取第一个输出做池化
        val info = session.outputInfo
        outputName = if (info.keys.any { it.contains("sentence", true) }) {
            info.keys.first { it.contains("sentence", true) }
        } else {
            info.keys.first()
        }
    }

    fun embed(text: String): FloatArray {
        val ids = tokenizer.encode(text, 512)
        val seqLen = ids.size
        val inputIds = LongArray(seqLen) { ids[it].toLong() }
        val attn = LongArray(seqLen) { 1L }
        val types = LongArray(seqLen) { 0L }

        val inputs = LinkedHashMap<String, OnnxTensor>()
        inputs["input_ids"] = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(inputIds), longArrayOf(1L, seqLen.toLong()))
        inputs["attention_mask"] = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(attn), longArrayOf(1L, seqLen.toLong()))
        inputs["token_type_ids"] = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(types), longArrayOf(1L, seqLen.toLong()))

        session.run(inputs).use { results ->
            val out = results[outputName]?.get() as ai.onnxruntime.OnnxTensor
            val buf = out.floatBuffer
            val data = FloatArray(buf.remaining())
            buf.get(data)
            val shape = out.info.shape ?: longArrayOf(1L, seqLen.toLong(), 512L)
            if (outputName.contains("sentence", true)) {
                val arr = FloatArray(MemoryDb.DIM)
                for (i in 0 until MemoryDb.DIM) arr[i] = data[i]
                return arr
            } else {
                // last_hidden_state [1, seq, dim] -> mean pooling (忽略 padding)
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
}
