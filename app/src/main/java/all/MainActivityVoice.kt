package io.github.aixtin.nyral

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import kotlinx.coroutines.launch

/** 语音模块(MainActivity 扩展): 按住说话录音/语音气泡播放/声波动画 —— 自 MainActivity.kt 拆出 */

    internal fun MainActivity.startWaveAnim() {
        if (waveAnim != null) return
        waveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                waveState.phase = it.animatedValue as Float
                for (wr in audioBubbles) wr.get()?.invalidate()
            }
            start()
        }
    }
    internal fun MainActivity.stopWaveAnim() {
        waveAnim?.cancel(); waveAnim = null
        waveState.phase = 0f
        for (wr in audioBubbles) wr.get()?.invalidate()
    }
    internal fun MainActivity.refreshAudioBubbles() {
        val it = audioBubbles.iterator()
        while (it.hasNext()) {
            val tv = it.next().get() ?: run { it.remove(); null } ?: continue
            val c = tv.tag as? String ?: continue
            tv.text = renderUserContent(c)
        }
    }
    internal fun MainActivity.toggleVoiceMode() {
        if (speaking) return
        if (!voiceMode && !currentModelSupportsVoice()) return
        voiceMode = !voiceMode
        if (voiceMode) {
            // input 用 INVISIBLE 而非 GONE: 仍在 inputArea 中占位, 收起时切回 VISIBLE 不触发重排, 无闪框
            input.visibility = View.INVISIBLE
            speakBar.visibility = View.VISIBLE
            // 仿搜索框展开动画: 回弹极低, 突出展开过程; 锚定右边缘(切换按钮侧), 从右往左展开
            speakBar.scaleX = 0.3f
            speakBar.alpha = 1f
            speakBar.post {
                speakBar.pivotX = speakBar.width.toFloat()
                speakBar.animate().scaleX(1f).setDuration(300)
                    .setInterpolator(OvershootInterpolator(0.1f)).withLayer().start()
            }
            micBtn.background = micIconBg(false, true, resources.displayMetrics.density)
            sendBtn.visibility = View.GONE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
        } else {
            // 收起: 动画期间不动文本/背景(避免动画前同步 resetSpeakBar 触发重绘卡顿),
            // 从右往左缩回+淡出结束后再复位胶囊并恢复输入框
            val onEnd = {
                speakBar.visibility = View.GONE
                speakBar.scaleX = 1f
                speakBar.alpha = 1f
                resetSpeakBar()
                input.visibility = View.VISIBLE
                micBtn.background = micIconBg(false, false, resources.displayMetrics.density)
                updateInputMode()
            }
            speakBar.pivotX = speakBar.width.toFloat()
            speakBar.animate().scaleX(0.3f).alpha(0f).setDuration(220)
                .setInterpolator(OvershootInterpolator(0.1f))
                .withEndAction { onEnd() }.start()
        }
    }

    /** 按住说话手势: 按下开始录音, 上滑进入取消区, 松手发送/取消 */
    internal fun MainActivity.handleSpeakTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (Build.VERSION.SDK_INT >= 23 &&
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.toast_req_mic_perm, Toast.LENGTH_SHORT).show()
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD)
                    return true
                }
                speakCancel = false
                speaking = true
                startRecording()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!speaking) return true
                val loc = IntArray(2)
                speakBar.getLocationOnScreen(loc)
                val cancelZone = ev.rawY < loc[1] - dp(90)
                if (cancelZone != speakCancel) {
                    speakCancel = cancelZone
                    speakBar.text = if (speakCancel) "松开手指，取消发送" else "松开 发送"
                    speakBar.background = rounded(dp(22),
                        if (speakCancel) Color.parseColor("#9AA0A6") else Color.parseColor("#07C160"))
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!speaking) return true
                if (speakCancel) discardRecording() else finishSpeakAndSend()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (speaking) discardRecording()
            }
        }
        return true
    }

    /** 开始录音(语音模式): 计时显示在按住说话条, 超 60 秒自动发送 */
    internal fun MainActivity.startRecording() {
        try {
            // 录音统一 AudioRecord 采 PCM16 单声道 44100Hz, 发送前封装 WAV:
            // 云端多模态 API 仅接受 mp3/flac/m4a/wav/ogg; MediaRecorder 的 MPEG_4+AAC 是 mp4 容器
            // (冒充 m4a 被 400 拒), OGG/VORBIS 又因设备 HAL 不支持 start 失败, 故 AudioRecord 最稳
            val sampleRate = 44100
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf * 2, 8192)
            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                throw IllegalStateException("AudioRecord init failed")
            }
            val pcm = ByteArrayOutputStream()
            ar.startRecording()
            audioRecord = ar
            recPcm = pcm
            recStop = false
            recStartMs = System.currentTimeMillis()
            recThread = Thread {
                val buf = ByteArray(bufSize)
                try {
                    while (!recStop) {
                        val n = ar.read(buf, 0, buf.size)
                        if (n > 0) pcm.write(buf, 0, n)
                        else if (n < 0) break
                    }
                } catch (e: Exception) { /* 停止时 read 抛错: 忽略 */ }
            }.also { it.isDaemon = true; it.start() }
            speakBar.text = getString(R.string.ma_release_send)
            speakBar.background = rounded(dp(22), Color.parseColor("#07C160"))
            // 复用同一成员 Handler 入队: 复位时才能用 removeCallbacks 停表(target 匹配)
            recHandler = Handler(Looper.getMainLooper())
            val handler = recHandler ?: return
            recTimer = object : Runnable {
                override fun run() {
                    val elapsed = System.currentTimeMillis() - recStartMs
                    // 取消态下文字保持"松开手指，取消发送", 不被计时器覆盖
                    if (!speakCancel && elapsed >= 1000) speakBar.text = "松开 发送 ${elapsed / 1000}s"
                    if (elapsed >= MAX_RECORD_MS) {
                        Toast.makeText(this@startRecording, R.string.toast_voice_60s, Toast.LENGTH_SHORT).show()
                        finishSpeakAndSend()
                    } else handler.postDelayed(this, 200)
                }
            }
            handler.postDelayed(recTimer ?: return, 200)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_rec_start_fail, e.message), Toast.LENGTH_SHORT).show()
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
        }
    }

    /** 结束录音并整理结果: 过短(<1秒)/超限(10MB)/失败返回 null, 正常返回(字节,文件名,时长) */
    internal fun MainActivity.finalizeRecord(): Triple<ByteArray, String, Long>? {
        val ar = audioRecord ?: return null
        val startMs = recStartMs
        recStop = true
        recThread?.join(1000)
        recThread = null
        audioRecord = null
        recTimer?.let { recHandler?.removeCallbacks(it) }
        recTimer = null
        recHandler = null
        try { ar.stop() } catch (e: Exception) { /* 过短时 stop 抛错: 静默丢弃 */ }
        try { ar.release() } catch (e: Exception) {}
        val pcm = recPcm?.toByteArray()
        recPcm = null
        val durationMs = System.currentTimeMillis() - startMs
        if (pcm == null || pcm.size == 0) return null
        if (durationMs < 1000) return null
        if (pcm.size > MAX_AUDIO_BYTES - 44) return null
        val bytes = toWav(pcm, 44100)
        return Triple(bytes, "语音_${System.currentTimeMillis()}.wav", durationMs)
    }

    /** PCM16 单声道 → WAV 封装 (已抽离 UiKit.toWav) */

    /** 松手发送: 直接作为语音消息发送, 不进附件预览条 */
    internal fun MainActivity.finishSpeakAndSend() {
        // 先复位说话条再发送: 发送链路偶发异常时也不会残留"松开 发送"录音态
        resetSpeakBar()
        val res = finalizeRecord()
        if (res == null) {
            Toast.makeText(this, R.string.toast_voice_too_short, Toast.LENGTH_SHORT).show()
            return
        }
        val (bytes, name, durationMs) = res
        if (aiBusy) {
            Toast.makeText(this, R.string.toast_ai_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val att = LocalEngine.Attachment(
            mime = "audio/wav",
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            name = name,
            text = getString(R.string.ma_voice_msg),
            isVoice = true
        )
        try {
            doSend(listOf(att))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_voice_send_fail, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    /** 取消发送: 停止并删除录音, 重置说话条 */
    internal fun MainActivity.discardRecording() {
        val ar = audioRecord ?: run { resetSpeakBar(); return }
        recStop = true
        recThread?.join(1000)
        recThread = null
        audioRecord = null
        recPcm = null
        recTimer?.let { recHandler?.removeCallbacks(it) }
        recTimer = null
        recHandler = null
        try { ar.stop() } catch (e: Exception) {}
        try { ar.release() } catch (e: Exception) {}
        resetSpeakBar()
    }

    /** 说话条复位到待命态 */
    internal fun MainActivity.resetSpeakBar() {
        // 必须停表: recTimer 每 200ms 会把文本改回"松开 发送 Ns", 不清掉松手后会继续残留计时
        recTimer?.let { recHandler?.removeCallbacks(it) }
        recTimer = null
        recHandler = null
        speaking = false
        speakCancel = false
        speakBar.text = getString(R.string.ma_hold_to_speak)
        speakBar.background = rounded(dp(22), Color.parseColor("#9AA0A6"))
    }

    /** 音频附件点击: 播放/停止当前 m4a 文件 */
    internal fun MainActivity.togglePlayAudio(fileName: String) {
        if (playingFileName == fileName && audioPlayer?.isPlaying == true) {
            audioPlayer?.stop()
            playingFileName = null
            stopWaveAnim()
            animateVoiceBubble(fileName, false)
            refreshAudioBubbles()
            return
        }
        audioPlayer?.release()
        audioPlayer = null
        playingFileName = null
        val f = AttachmentStore.fileOf(this, fileName) ?: return
        try {
            val p = MediaPlayer()
            p.setAudioStreamType(AudioManager.STREAM_MUSIC)
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener {
                it.release()
                if (audioPlayer === it) { audioPlayer = null; playingFileName = null }
                uiScope.launch { stopWaveAnim(); animateVoiceBubble(fileName, false); refreshAudioBubbles() }
            }
            p.prepare()
            p.start()
            audioPlayer = p
            playingFileName = fileName
            startWaveAnim()
            animateVoiceBubble(fileName, true)
            refreshAudioBubbles()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_play_fail, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    /** 语音气泡播放动画: 播放时整体轻微缩小(0.96)后回弹循环, 停止/播完恢复原尺寸 */
    internal fun MainActivity.animateVoiceBubble(fileName: String, playing: Boolean) {
        for (wr in audioBubbles) {
            val tv = wr.get() ?: continue
            val c = tv.tag as? String ?: continue
            val fname = Regex("""\(att://([^)]+)\)""").find(c)?.groupValues?.get(1) ?: continue
            if (fname != fileName) continue
            tv.clearAnimation()
            tv.scaleX = 1f
            tv.scaleY = 1f
            if (playing) {
                tv.startAnimation(android.view.animation.ScaleAnimation(
                    1f, 0.96f, 1f, 0.96f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f).apply {
                    duration = (220L * TypewriterCenter.slowMul()).toLong()
                    repeatCount = 2
                    repeatMode = android.view.animation.Animation.REVERSE
                    interpolator = android.view.animation.DecelerateInterpolator()
                })
            }
        }
    }

