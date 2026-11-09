package io.github.aixtin.nyral

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * persisted 周期 Job(15min, 2026-11-10): 唯一不依赖 BOOT_COMPLETED 广播投递的重排兜底。
 *
 * 时序推演(一加重启场景): 广播被 ColorOS BootPressureHolder 压住 -> TimerBootReceiver 不跑;
 * 用户不点开 App -> NyralApp.onCreate 也不跑; 于是 L1/L2 双双失效, 只剩这一层。
 * JobInfo.setPersisted(true) 的 job 由 system_server 的 JobScheduler 在重启后自行恢复,
 * 厂商拦的是广播投递, 拦不住 job 恢复。
 */
class TimerBootJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            try {
                TimerRecovery.ensure(this, "job_tick")
            } catch (e: Exception) {
                Log.e("TimerBoot", "job_tick: 重排异常 ${e.message}")
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val TAG = "TimerBoot"
        private const val JOB_ID = 0x4E59 // "NY"

        /** 注册/确认兜底 job(已存在则跳过, 幂等) */
        fun ensureScheduled(context: Context) {
            val js = context.applicationContext
                .getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            try {
                if (js.getPendingJob(JOB_ID) != null) return
                val info = JobInfo.Builder(JOB_ID, ComponentName(context, TimerBootJobService::class.java))
                    .setPersisted(true)              // 重启后由 system_server 自动恢复
                    .setPeriodic(15 * 60 * 1000L, 5 * 60 * 1000L) // 系统最小周期, flex 取最小 5min(单参版 flex=interval 会被拖到 30min 才跑)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build()
                js.schedule(info)
                Log.i(TAG, "persisted 兜底 job 已注册(15min)")
            } catch (e: Exception) {
                Log.e(TAG, "注册兜底 job 失败 ${e.message}")
            }
        }
    }
}
