package io.github.aixtin.nyral

import java.io.File

/**
 * 简化版 BERT WordPiece 分词器 (兼容 bge-small-zh vocab.txt)
 */
class BertTokenizer(vocabFile: File) {

    private val vocab: Map<String, Int>
    private val idToToken: List<String>

    val clsId: Int
    val sepId: Int
    val padId: Int
    val unkId: Int

    init {
        val lines = vocabFile.readLines().map { it.trim() }
        val map = HashMap<String, Int>()
        lines.forEachIndexed { i, t -> map[t] = i }
        vocab = map
        idToToken = lines
        clsId = map["[CLS]"] ?: 101
        sepId = map["[SEP]"] ?: 102
        padId = map["[PAD]"] ?: 0
        unkId = map["[UNK]"] ?: 100
    }

    fun encode(text: String, maxLen: Int = 512): IntArray {
        val tokenIds = mutableListOf(clsId)
        val clean = text.trim()
        for (piece in splitPieces(clean)) {
            if (tokenIds.size >= maxLen - 1) break
            if (piece in vocab) {
                tokenIds.add(vocab[piece]!!)
            } else if (piece.isNotEmpty() && isCjk(piece[0])) {
                tokenIds.add(unkId)
            } else {
                val wp = wordPiece(piece)
                for (t in wp) {
                    if (tokenIds.size >= maxLen - 1) break
                    tokenIds.add(vocab[t] ?: unkId)
                }
            }
        }
        if (tokenIds.size < maxLen) tokenIds.add(sepId) else tokenIds[tokenIds.size - 1] = sepId
        return tokenIds.toIntArray()
    }

    private fun splitPieces(text: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var lastType = -1 // 0=cjk 1=word 2=digit 3=space 4=other
        fun flush() {
            if (sb.isNotEmpty()) {
                val s = sb.toString()
                if (lastType == 3) result.add(s) // 空白保留用于后续忽略
                else if (lastType == 0) s.forEach { result.add(it.toString()) }
                else result.add(s)
                sb.clear()
            }
        }
        for (ch in text) {
            val type = when {
                ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' -> 3
                isCjk(ch) -> 0
                ch.isLetter() -> 1
                ch.isDigit() -> 2
                else -> 4
            }
            if (type != lastType) { flush(); lastType = type }
            sb.append(ch)
        }
        flush()
        return result.filter { it != " " && it != "\t" && it != "\n" && it != "\r" }
    }

    private fun isCjk(ch: Char): Boolean {
        val cp = ch.code
        return (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) ||
            (cp in 0x20000..0x2A6DF) || (cp in 0x3000..0x303F) ||
            (cp in 0xFF00..0xFFEF)
    }

    private fun wordPiece(word: String): List<String> {
        val tokens = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var cur: String? = null
            while (start < end) {
                val sub = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
                if (sub in vocab) { cur = sub; break }
                end--
            }
            if (cur == null) {
                tokens.add("[UNK]")
                break
            }
            tokens.add(cur)
            start = end
        }
        return tokens
    }
}
