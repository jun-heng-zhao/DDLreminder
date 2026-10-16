package com.balance.deadlinereminder.remind

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.balance.deadlinereminder.data.Store
import com.balance.deadlinereminder.domain.PlanEngine
import com.balance.deadlinereminder.domain.WeekCalc
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 把四类提醒挂成 AlarmManager 精确闹钟，全部落在 ReminderReceiver 上：
 *
 * 1. **上课提醒** —— 跟课表走，课前 N 分钟（原来的行为）。
 * 2. **任务时段提醒** —— 排出"什么时候做什么"之后，每个工作块的开始时刻提醒。
 * 3. **DDL 预警** —— 截止前的预警点、紧急点、以及截止当刻。
 * 4. **每日播报** —— 每天早上把今天要推进的事播一次。
 *
 * 排窗是未来 [HORIZON_DAYS] 天，每次响铃、开机、改数据都全部重排一次。
 * 不常驻后台，也不会因为手机重启漏提醒。
 */
object ReminderScheduler {

    const val ACTION_CLASS = "com.balance.deadlinereminder.REMIND_CLASS"
    const val ACTION_SESSION = "com.balance.deadlinereminder.REMIND_SESSION"
    const val ACTION_DEADLINE = "com.balance.deadlinereminder.REMIND_DEADLINE"
    const val ACTION_DIGEST = "com.balance.deadlinereminder.REMIND_DIGEST"

    private const val PREFS = "reminder_pending"
    private const val KEY_CODES = "codes"
    private const val HORIZON_DAYS = 8
    /** 闹钟总量上限：留出余量，但不想把 AlarmManager 塞爆。 */
    private const val MAX_ALARMS = 200
    private const val MIN_LEAD_MILLIS = 5_000L

    private data class Alarm(val code: Int, val atMillis: Long, val intent: Intent)

    fun reschedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        cancelAll(context)

        val store = Store.get(context)
        val now = LocalDateTime.now()
        val nowMillis = System.currentTimeMillis()
        val settings = store.settings
        val alarms = mutableListOf<Alarm>()
        val used = mutableSetOf<Int>()

        fun add(group: Int, key: String, at: LocalDateTime, intent: Intent) {
            val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            // 已经过点的提醒不补发：重排时连环弹一堆通知比漏提醒更糟
            if (millis <= nowMillis + MIN_LEAD_MILLIS) return
            var code = group * 7_919_999 xor (31 * key.hashCode())
            while (!used.add(code)) code++
            alarms += Alarm(code, millis, intent)
        }

        // 1. 上课提醒
        WeekCalc.upcoming(store.courses, settings, now, HORIZON_DAYS).forEach { occ ->
            val lead = WeekCalc.reminderMinutes(occ.course, settings)
            add(
                group = 1,
                key = occ.course.id + "@" + occ.start.toLocalDate(),
                at = occ.start.minusMinutes(lead.toLong()),
                intent = classIntent(context, occ.course.id, occ.course.name, occ.course.location, occ.start, lead, settings.strongReminder),
            )
        }

        // 2 + 3. 任务排程与 DDL 预警（都用同一份计划，算一次就够）
        val unfinished = store.tasks.filter { !it.done }
        val hasTaskWork = unfinished.any { it.remainingMinutes > 0 }
        if (hasTaskWork) {
            val plan = PlanEngine.build(settings, store.courses, store.tasks, now, HORIZON_DAYS)
            if (settings.sessionReminderEnabled) {
                plan.blocks.forEach { block ->
                    add(
                        group = 2,
                        key = block.taskId + "@" + block.start,
                        at = block.start,
                        intent = sessionIntent(context, block.taskId, block.start, block.minutes),
                    )
                }
            }
        }

        unfinished.filter { it.deadlineTime != null }.forEach { task ->
            val deadline = task.deadlineTime ?: return@forEach
            if (!deadline.isAfter(now)) return@forEach
            val warnAt = deadline.minusHours(settings.ddlWarnHours.toLong())
            val urgentAt = deadline.minusHours(settings.ddlUrgentHours.toLong())
            add(4, task.id + "@warn", warnAt, deadlineIntent(context, task.id, LEVEL_WARN))
            add(5, task.id + "@urgent", urgentAt, deadlineIntent(context, task.id, LEVEL_URGENT))
            add(6, task.id + "@final", deadline, deadlineIntent(context, task.id, LEVEL_FINAL))
        }

        // 4. 每日播报
        if (settings.digestEnabled) {
            val horizon = now.plusDays(HORIZON_DAYS.toLong())
            var date = now.toLocalDate()
            while (date.isBefore(horizon.toLocalDate()) || date == horizon.toLocalDate()) {
                val at = date.atTime(settings.digestMinute / 60, settings.digestMinute % 60)
                add(7, date.toString(), at, digestIntent(context, date))
                date = date.plusDays(1)
            }
        }

        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        val codes = mutableListOf<Int>()
        alarms.sortedBy { it.atMillis }.take(MAX_ALARMS).forEach { alarm ->
            runCatching {
                if (exactAllowed) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.atMillis, pending(context, alarm, PendingIntent.FLAG_UPDATE_CURRENT))
                } else {
                    // 没拿到精确闹钟权限就退化成普通闹钟，宁可晚几分钟也不能不响
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.atMillis, pending(context, alarm, PendingIntent.FLAG_UPDATE_CURRENT))
                }
                codes += alarm.code
            }
        }
        saveCodes(context, codes)
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        loadCodes(context).forEach { code ->
            // 取消困难：PendingIntent 的身份取决于 action+component，这里逐个 action 试一遍
            ALL_ACTIONS.forEach { action ->
                val template = Intent(context, ReminderReceiver::class.java).setAction(action)
                val pi = PendingIntent.getBroadcast(context, code, template, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                if (pi != null) {
                    am.cancel(pi)
                    pi.cancel()
                }
            }
        }
        saveCodes(context, emptyList())
    }

    private val ALL_ACTIONS = listOf(ACTION_CLASS, ACTION_SESSION, ACTION_DEADLINE, ACTION_DIGEST)

    private fun pending(context: Context, alarm: Alarm, flags: Int): PendingIntent =
        PendingIntent.getBroadcast(context, alarm.code, alarm.intent, flags or PendingIntent.FLAG_IMMUTABLE)

    private fun classIntent(
        context: Context,
        courseId: String,
        name: String,
        location: String,
        start: LocalDateTime,
        minutes: Int,
        strong: Boolean,
    ) = Intent(context, ReminderReceiver::class.java).apply {
        action = ACTION_CLASS
        putExtra(ReminderReceiver.EXTRA_COURSE_ID, courseId)
        putExtra(ReminderReceiver.EXTRA_NAME, name)
        putExtra(ReminderReceiver.EXTRA_LOCATION, location)
        putExtra(ReminderReceiver.EXTRA_START, "%02d:%02d".format(start.hour, start.minute))
        putExtra(ReminderReceiver.EXTRA_MINUTES, minutes)
        putExtra(ReminderReceiver.EXTRA_STRONG, strong)
    }

    private fun sessionIntent(context: Context, taskId: String, start: LocalDateTime, minutes: Int) =
        Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_SESSION
            putExtra(TaskActionReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskActionReceiver.EXTRA_MINUTES, minutes)
            putExtra(ReminderReceiver.EXTRA_KEY, "$taskId@$start")
        }

    private fun deadlineIntent(context: Context, taskId: String, level: String) =
        Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_DEADLINE
            putExtra(TaskActionReceiver.EXTRA_TASK_ID, taskId)
            putExtra(ReminderReceiver.EXTRA_LEVEL, level)
        }

    private fun digestIntent(context: Context, date: java.time.LocalDate) =
        Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_DIGEST
            putExtra(ReminderReceiver.EXTRA_KEY, date.toString())
        }

    private fun loadCodes(context: Context): List<Int> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CODES, "")
            .orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }

    private fun saveCodes(context: Context, codes: List<Int>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CODES, codes.joinToString(","))
            .apply()
    }

    /** 通知 id：用稳定 key 算，同一个工作块重复提醒会覆盖而不是堆叠。 */
    fun notificationId(key: String): Int = key.hashCode() and 0x7fffffff

    const val LEVEL_WARN = "warn"
    const val LEVEL_URGENT = "urgent"
    const val LEVEL_FINAL = "final"
}
