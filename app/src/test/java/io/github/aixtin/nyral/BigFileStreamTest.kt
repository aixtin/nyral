package io.github.aixtin.nyral


import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超大附件流式解析回归（2026-11-19 闪退修复配套）。
 * 真机根因: attach_read 对 1.58GB 刷机包 f.readBytes() 整读 → OutOfMemoryError 崩进程。
 * 修复要求不只是"不崩", 而是超限文件仍能给出可读结果, 因此这里验证:
 * 超过 32MB 整读上限的压缩包/大文本, 走 extractFromFile 仍能列出结构与内部文本。
 */
class BigFileStreamTest {

    private val threshold = 32L * 1024 * 1024

    @Test
    fun largeZipIsListedWithoutFullRead() {
        val f = File.createTempFile("bigstream", ".zip")
        try {
            val bigLen = 48L * 1024 * 1024
            ZipOutputStream(f.outputStream().buffered()).use { zos ->
                zos.putNextEntry(ZipEntry("README.txt"))
                zos.write("这是刷机包说明: payload.bin 为 A/B OTA 负载".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("images/boot.img"))
                zos.write(ByteArray(4096))
                zos.closeEntry()
                val chunk = ByteArray(1024 * 1024)
                val crc = java.util.zip.CRC32()
                var written = 0L
                while (written < bigLen) {
                    crc.update(chunk)
                    written += chunk.size
                }
                val big = ZipEntry("payload.bin")
                big.method = ZipEntry.STORED
                big.size = bigLen
                big.compressedSize = bigLen
                big.crc = crc.value
                zos.putNextEntry(big)
                written = 0L
                while (written < bigLen) {
                    zos.write(chunk)
                    written += chunk.size
                }
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                zos.write("Manifest-Version: 1.0\n".toByteArray())
                zos.closeEntry()
                // 无扩展名的清单文件: 内容为 key=value 文本, 应被探测读入
                zos.putNextEntry(ZipEntry("META-INF/com/android/metadata"))
                zos.write("ota-type=AB\npost-build=LineageOS/mustang/mustang:17/BP2A/eng.root:userdebug/test-keys\n".toByteArray())
                zos.closeEntry()
                // 二进制 protobuf: 含 NUL, 应被探测挡掉
                zos.putNextEntry(ZipEntry("metadata.pb"))
                zos.write(byteArrayOf(0x0a, 0x04, 0x00, 0x00, 0x00, 0x00, 0x12, 0x02, 0x08, 0x01, 0x00, 0x00))
                zos.closeEntry()
            }
            assertTrue("测试文件应超过整读上限", f.length() > threshold)

            val out = DocTextExtractor.extractFromFile(f.name, f)
            println("=== zip output ===\n$out")
            assertTrue("应返回结果而不是 null", out != null)
            assertTrue("应列出条目", out!!.contains("payload.bin"))
            assertTrue("应给出大小", out.contains("GB") || out.contains("MB"))
            assertTrue("应读到内部说明文本", out.contains("A/B OTA") || out.contains("README"))
            assertTrue("无扩展名 metadata 应被文本探测读入", out.contains("ota-type=AB"))
            assertTrue("二进制 pb 不应被当成文本", !out.contains("\u0000"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun largeTarIsListedWithoutFullRead() {
        val f = File.createTempFile("bigstream", ".tar")
        try {
            f.outputStream().buffered().use { os ->
                fun writeEntry(name: String, data: ByteArray) {
                    val h = ByteArray(512)
                    val nb = name.toByteArray()
                    System.arraycopy(nb, 0, h, 0, nb.size)
                    val oct = String.format("%011o", data.size)
                    System.arraycopy(oct.toByteArray(), 0, h, 124, oct.length)
                    h[156] = '0'.code.toByte()
                    os.write(h)
                    os.write(data)
                    val pad = (512 - data.size % 512) % 512
                    if (pad > 0) os.write(ByteArray(pad))
                }
                writeEntry("notes/install.md", "# 安装说明\nfastboot flash boot boot.img".toByteArray())
                writeEntry("system.img", ByteArray(48 * 1024 * 1024))
                os.write(ByteArray(1024))
            }
            assertTrue("测试文件应超过整读上限", f.length() > threshold)

            val out = DocTextExtractor.extractFromFile(f.name, f)
            println("=== tar output ===\n$out")
            assertTrue("应返回结果而不是 null", out != null)
            assertTrue("应列出 system.img", out!!.contains("system.img"))
            assertTrue("应读到内部 markdown", out.contains("fastboot flash boot"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun largeTextReadsHeadOnly() {
        val f = File.createTempFile("bigstream", ".log")
        try {
            RandomAccessFile(f, "rw").use { raf ->
                raf.write("第一行日志: 刷机失败 at 2026-11-19\n".toByteArray())
                raf.setLength(40L * 1024 * 1024)
            }
            assertTrue("测试文件应超过整读上限", f.length() > threshold)

            val out = DocTextExtractor.extractFromFile(f.name, f)
            println("=== text output head ===\n${out?.take(200)}")
            assertTrue("应返回结果而不是 null", out != null)
            assertTrue("应读到开头内容", out!!.contains("刷机失败"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun unsupportedLargeTypeReturnsNull() {
        val f = File.createTempFile("bigstream", ".bin")
        try {
            RandomAccessFile(f, "rw").use { it.setLength(40L * 1024 * 1024) }
            assertTrue("二进制大文件应返回 null 走替代方案", DocTextExtractor.extractFromFile(f.name, f) == null)
        } finally {
            f.delete()
        }
    }
}
