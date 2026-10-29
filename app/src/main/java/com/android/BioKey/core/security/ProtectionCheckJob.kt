package com.android.BioKey.core.security

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * While app locking is off, looks every 15 minutes or so (Android's shortest period) and brings
 * the "App lock is off" alert back if it was swiped away. The reminder at each unlock
 * ([ProtectionAlerts.remindIfOff]) needs BioKey's process alive; with no engine running, Android
 * may end it, and this keeps the reminder going. Scheduled when an engine stops, cancelled when
 * one runs again; kept across restarts of the phone.
 */
class ProtectionCheckJob : JobService() {

    companion object {
        private const val JOB_ID = 4201
        private const val PERIOD_MS = 15 * 60_000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            runCatching {
                scheduler.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(context, ProtectionCheckJob::class.java))
                        .setPeriodic(PERIOD_MS)
                        .setPersisted(true)
                        .build()
                )
            }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        // Off the main thread: at a cold start this reads BioKey's storage.
        Thread {
            try {
                when {
                    // Storage cleared and not set up again: said until it is, engine or not.
                    ProtectionGaps.awaitingSetupAfterClear(this) ->
                        ProtectionAlerts.remindIfStorageCleared(this)
                    ProtectionEngine.isProtecting(this) -> cancel(this)
                    else -> ProtectionAlerts.remindIfOff(this)
                }
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
