package io.github.aixtin.nyral

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 数据备份/还原(2026-11-02): 一键备份 = 对话库 + 产物附件 + 设置 打包。
 * - 包格式: ZIP(含 backup.json 清单) / 加密时外层为 [MAGIC][salt16][iv12][AES-GCM密文]
 * - 范围: dataDir/databases + dataDir/shared_prefs + filesDir
 *   (filesDir 根下大于 MAX_ROOT_FILE_BYTES 的文件视为可重下模型, 不进包)
 * - 还原 = 覆盖写回 + 重启进程
 */
object BackupManager {
    private const val MAGIC = "NYRBK1"
    private const val MANIFEST = "backup.json"
    private const val GCM_TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 60000
    private const val MAX_ROOT_FILE_BYTES = 2L * 1024 * 1024

    data class Report(val ok: Boolean, val message: String)

    // ---------- 备份 ----------

    /** 打包并写出到 out; password 为空则不加密 */
    fun backup(context: Context, password: String?, out: OutputStream): Report {
        var tmp: File? = null
        try {
            checkpointDb(File(File(context.applicationInfo.dataDir, "databases"), "memory.db"))
            tmp = File(context.cacheDir, "nyral_backup_tmp.zip")
            tmp.delete()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { z ->
                val manifest = JSONObject()
                    .put("type", "nyral_backup")
                    .put("version", 1)
                    .put("ts", System.currentTimeMillis())
                z.putNextEntry(ZipEntry(MANIFEST))
                z.write(manifest.toString().toByteArray(Charsets.UTF_8))
                z.closeEntry()
                val dataDir = context.applicationInfo.dataDir
                addTree(z, File(dataDir, "databases"), "databases", Long.MAX_VALUE)
                addTree(z, File(dataDir, "shared_prefs"), "shared_prefs", Long.MAX_VALUE)
                addTree(z, context.filesDir, "files", MAX_ROOT_FILE_BYTES)
            }
            if (password.isNullOrBlank()) {
                tmp.inputStream().use { it.copyTo(out) }
                out.flush()
            } else {
                val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val key = deriveKey(password, salt)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                out.write(salt)
                out.write(iv)
                CipherOutputStream(out, cipher).use { cos ->
                    tmp.inputStream().use { it.copyTo(cos) }
                }
            }
            return Report(true, "备份完成")
        } catch (e: Exception) {
            return Report(false, "备份失败: ${e.message}")
        } finally {
            tmp?.delete()
        }
    }

    // ---------- 还原 ----------

    /** 0=普通zip包, 1=加密包, -1=不认识的文件 */
    fun archiveKind(f: File): Int = try {
        val head = ByteArray(6)
        RandomAccessFile(f, "r").use { raf -> raf.readFully(head) }
        when {
            String(head, Charsets.US_ASCII) == MAGIC -> 1
            head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> 0
            else -> -1
        }
    } catch (_: Exception) {
        -1
    }

    /** 校验是本应用的备份包, 返回清单; 否则 null */
    fun manifestOf(zipFile: File): JSONObject? {
        return try {
            ZipFile(zipFile).use { zf ->
                val e = zf.getEntry(MANIFEST) ?: return null
                JSONObject(zf.getInputStream(e).bufferedReader().readText())
                    .takeIf { it.optString("type") == "nyral_backup" }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 解密到 out(明文zip); 密码错误返回 false */
    fun decryptToFile(raw: File, password: String, out: File): Boolean {
        return try {
        val data = raw.readBytes()
        if (data.size <= 6 + 16 + 12) return false
        val salt = data.copyOfRange(6, 22)
        val iv = data.copyOfRange(22, 34)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        out.writeBytes(cipher.doFinal(data, 34, data.size - 34))
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 版本保护(2026-11-02): 检查备份包里各数据库的结构版本(user_version),
     * 高于本机现有同名库 = 备份来自更新版本的应用, 返回该库名; 否则返回 null。
     * 本机不存在的库跳过比较(旧包由 onUpgrade 自动升格, 天然兼容)。
     */
    fun findTooNewDb(context: Context, zipFile: File): String? {
        val liveDir = File(context.applicationInfo.dataDir, "databases")
        return try {
            ZipFile(zipFile).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val n = e.name
                    if (!n.startsWith("databases/") || !n.endsWith(".db")) continue
                    val base = n.removePrefix("databases/")
                    val live = File(liveDir, base)
                    if (!live.exists()) continue
                    val head = ByteArray(64)
                    zf.getInputStream(e).use { ins ->
                        var off = 0
                        while (off < 64) {
                            val r = ins.read(head, off, 64 - off)
                            if (r < 0) break
                            off += r
                        }
                        if (off < 64) return@use
                    }
                    val bkVer = ((head[60].toInt() and 0xFF) shl 24) or ((head[61].toInt() and 0xFF) shl 16) or
                        ((head[62].toInt() and 0xFF) shl 8) or (head[63].toInt() and 0xFF)
                    val liveHead = ByteArray(64)
                    RandomAccessFile(live, "r").use { raf -> raf.readFully(liveHead) }
                    val liveVer = ((liveHead[60].toInt() and 0xFF) shl 24) or ((liveHead[61].toInt() and 0xFF) shl 16) or
                        ((liveHead[62].toInt() and 0xFF) shl 8) or (liveHead[63].toInt() and 0xFF)
                    if (bkVer > liveVer) return base
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 覆盖写回全部数据(调用方负责完成后重启进程) */
    fun restore(context: Context, zipFile: File): Report {
        try {
            val dataDir = context.applicationInfo.dataDir
            ZipFile(zipFile).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val name = e.name
                    if (name == MANIFEST || name.endsWith("/")) continue
                    val target = when {
                        name.startsWith("databases/") -> File(File(dataDir, "databases"), name.removePrefix("databases/"))
                        name.startsWith("shared_prefs/") -> File(File(dataDir, "shared_prefs"), name.removePrefix("shared_prefs/"))
                        name.startsWith("files/") -> File(context.filesDir, name.removePrefix("files/"))
                        else -> continue
                    }
                    target.parentFile?.mkdirs()
                    zf.getInputStream(e).use { ins -> FileOutputStream(target).use { ins.copyTo(it) } }
                }
            }
            return Report(true, "还原完成")
        } catch (e: Exception) {
            return Report(false, "还原失败: ${e.message}")
        }
    }

    /** 还原后重启应用进程(重新走全部初始化) */
    fun restartApp(context: Context) {
        val i = context.packageManager.getLaunchIntentForPackage(context.packageName)
        i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (i != null) context.startActivity(i)
        Runtime.getRuntime().exit(0)
    }

    // ---------- 内部 ----------

    /** 备份前把数据库 WAL 日志并入主文件, 保证拷出去的 db 完整 */
    private fun checkpointDb(db: File) {
        if (!db.exists()) return
        try {
            val s = SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            s.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            s.close()
        } catch (_: Exception) { /* 检查点失败不阻断备份, 主文件仍是有效快照 */ }
    }

    /** 递归打包目录; rootFileCap 只限制根下散文件(大文件跳过), 子目录不受限 */
    private fun addTree(z: ZipOutputStream, dir: File, zipDir: String, rootFileCap: Long) {
        if (!dir.isDirectory) return
        dir.walk().forEach { f ->
            if (!f.isFile) return@forEach
            val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
            if (!rel.contains('/') && f.length() > rootFileCap) return@forEach
            z.putNextEntry(ZipEntry("$zipDir/$rel"))
            f.inputStream().use { it.copyTo(z) }
            z.closeEntry()
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(f.generateSecret(spec).encoded, "AES")
    }
}
