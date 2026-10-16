package com.balance.deadlinereminder.remind

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.balance.deadlinereminder.data.Store

/**
 * 通知按钮的落点：不用打开应用就能登记一段专注时间，或者直接收工。
 *
 * 两个按钮都是"直接写数据 + 关掉这条通知 + 重排后续提醒"，
 * 因为用户点按钮的那一刻，后面所有安排都变了。
 */
class TaskActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        if (taskId.isNullOrBlank()) return
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
        val store = Store.get(context)

        runCatching {
            when (intent.action) {
                ACTION_TASK_PROGRESS -> if (minutes > 0) store.addSpent(taskId, minutes)
                ACTION_TASK_DONE -> store.setTaskDone(taskId, true)
            }
        }

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId) }

        // 进度一改，剩下的排程全变了，立刻重排
        runCatching { ReminderScheduler.reschedule(context) }
    }

    companion object {
        const val ACTION_TASK_PROGRESS = "com.balance.deadlinereminder.TASK_PROGRESS"
        const val ACTION_TASK_DONE = "com.balance.deadlinereminder.TASK_DONE"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val EXTRA_MINUTES = "minutes"
    }
}
