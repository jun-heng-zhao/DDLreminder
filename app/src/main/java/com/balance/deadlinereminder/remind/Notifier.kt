package com.balance.deadlinereminder.remind

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.balance.deadlinereminder.MainActivity
import com.balance.deadlinereminder.R

/** 通知渠道 + 发通知。四类提醒各有自己的渠道，用户可以在系统里单独关掉某一类。 */
object Notifier {

    const val CHANNEL_CLASS = "class_reminder"
    const val CHANNEL_CLASS_STRONG = "class_reminder_strong"
    const val CHANNEL_SESSION = "ddl_session"
    const val CHANNEL_DDL = "ddl_deadline"
    const val CHANNEL_DIGEST = "ddl_digest"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CLASS, "上课提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "课前 N 分钟提醒上课地点"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CLASS_STRONG, "上课强提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "响铃 + 震动，不容易错过"
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SESSION, "该做这件事了", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "排好的任务时段开始时提醒"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DDL, "DDL 预警", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "临近截止时间时提醒，并给出剩余工作量"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DIGEST, "今日计划播报", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "每天早上播报今天要推进什么、最近的 DDL 还有多久"
            }
        )
    }

    /** 上课提醒（沿用原课表应用的行为）。 */
    fun showClass(
        context: Context,
        id: Int,
        courseName: String,
        location: String,
        startLabel: String,
        minutes: Int,
        strong: Boolean,
    ) {
        val text = buildString {
            append(startLabel)
            if (location.isNotBlank()) append(" 在 ").append(location)
            append(" 上课")
            if (minutes > 0) append("，还有 ").append(minutes).append(" 分钟")
        }
        post(context, id, if (strong) CHANNEL_CLASS_STRONG else CHANNEL_CLASS, courseName, text, strong, emptyList())
        // 强提醒直接自己放铃声：MIUI 上渠道声音经常被系统按"通知"处理，靠渠道响不起来
        if (strong) playAlarm(context)
    }

    /**
     * 任务时段提醒：带两个按钮，不用打开应用就能登记进度或收工。
     * [minutes] 是这一段计划的长度，也是"登记这段"按钮的分钟数。
     */
    fun showSession(context: Context, id: Int, taskId: String, title: String, text: String, minutes: Int) {
        val actions = listOf(
            NotificationCompat.Action(
                android.R.drawable.checkbox_on_background, "登记 $minutes 分钟",
                actionIntent(context, TaskActionReceiver.ACTION_TASK_PROGRESS, taskId, id, minutes),
            ),
            NotificationCompat.Action(
                android.R.drawable.ic_menu_close_clear_cancel, "整件完成",
                actionIntent(context, TaskActionReceiver.ACTION_TASK_DONE, taskId, id, 0),
            ),
        )
        post(context, id, CHANNEL_SESSION, title, text, false, actions)
    }

    /** DDL 预警：剩余工作量和"按计划能不能赶上"都在正文里。 */
    fun showDeadline(context: Context, id: Int, taskId: String, title: String, text: String, urgent: Boolean) {
        val actions = listOf(
            NotificationCompat.Action(
                android.R.drawable.checkbox_on_background, "完成",
                actionIntent(context, TaskActionReceiver.ACTION_TASK_DONE, taskId, id, 0),
            ),
        )
        post(context, id, CHANNEL_DDL, title, text, urgent, actions)
        if (urgent) playAlarm(context)
    }

    /** 每日播报。 */
    fun showDigest(context: Context, id: Int, title: String, text: String) {
        post(context, id, CHANNEL_DIGEST, title, text, false, emptyList())
    }

    /** 设置页的"发一条测试通知"：只验证权限和渠道，不打扰任何人。 */
    fun testNotification(context: Context) {
        post(
            context,
            TEST_ID,
            CHANNEL_DIGEST,
            "测试通知",
            "能看到这条，说明通知权限没问题。真正的工作提醒会按你的课程表和 DDL 自己排。",
            false,
            emptyList(),
        )
    }

    private const val TEST_ID = 990_001

    private fun post(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        text: String,
        strong: Boolean,
        actions: List<NotificationCompat.Action>,
    ) {
        ensureChannels(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notify_clock)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(if (strong) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (strong) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
        if (strong) builder.setVibrate(longArrayOf(0, 600, 300, 600, 300, 600))
        actions.forEach { builder.addAction(it) }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                manager.notify(id, builder.build())
            }
        }
    }

    private fun actionIntent(
        context: Context,
        actionName: String,
        taskId: String,
        notificationId: Int,
        minutes: Int,
    ): PendingIntent {
        val intent = Intent(context, TaskActionReceiver::class.java).apply {
            action = actionName
            putExtra(TaskActionReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(TaskActionReceiver.EXTRA_MINUTES, minutes)
        }
        // extras 不参与 PendingIntent 的身份判定，所以 requestCode 必须一条通知一个
        val code = taskId.hashCode() * 31 + actionName.hashCode() + notificationId
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 用 MediaPlayer 走闹钟音频流放一段铃声。
     * 渠道声音在 HyperOS 上不一定响（通知被折叠/静音策略影响），自己放最稳。
     */
    private fun playAlarm(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    if (player.isPlaying) player.stop()
                    player.release()
                }
            }, 20_000)
        }
    }
}
