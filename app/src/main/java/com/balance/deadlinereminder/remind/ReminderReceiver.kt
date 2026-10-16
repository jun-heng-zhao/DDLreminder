package com.balance.deadlinereminder.remind

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.balance.deadlinereminder.data.Store
import com.balance.deadlinereminder.data.Task
import com.balance.deadlinereminder.data.countdownLabel
import com.balance.deadlinereminder.data.deadlineLabel
import com.balance.deadlinereminder.data.humanDuration
import com.balance.deadlinereminder.domain.Plan
import com.balance.deadlinereminder.domain.PlanEngine
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 所有闹钟的落点。通知文字在**响的那一刻**才现算：
 * 用户中途登记了进度、加了任务、改了截止时间，提醒说的就是最新情况，不会念旧数据。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val now = LocalDateTime.now()
        runCatching {
            when (intent.action) {
                ReminderScheduler.ACTION_CLASS -> onClass(context, intent)
                ReminderScheduler.ACTION_SESSION -> onSession(context, intent, now)
                ReminderScheduler.ACTION_DEADLINE -> onDeadline(context, intent, now)
                ReminderScheduler.ACTION_DIGEST -> onDigest(context, intent, now)
            }
        }
        // 响过一次之后余下的安排都要重算（进度、剩余空档都可能变了）
        runCatching { ReminderScheduler.reschedule(context) }
    }

    private fun onClass(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_NAME) ?: return
        val location = intent.getStringExtra(EXTRA_LOCATION).orEmpty()
        val startLabel = intent.getStringExtra(EXTRA_START).orEmpty()
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
        val strong = intent.getBooleanExtra(EXTRA_STRONG, false)
        val courseId = intent.getStringExtra(EXTRA_COURSE_ID).orEmpty()
        val id = ("class:$courseId@$startLabel").let { ReminderScheduler.notificationId(it) }
        Notifier.showClass(context, id, name, location, startLabel, minutes, strong)
    }

    private fun onSession(context: Context, intent: Intent, now: LocalDateTime) {
        val taskId = intent.getStringExtra(TaskActionReceiver.EXTRA_TASK_ID) ?: return
        val minutes = intent.getIntExtra(TaskActionReceiver.EXTRA_MINUTES, 0)
        val store = Store.get(context)
        val task = store.tasks.firstOrNull { it.id == taskId } ?: return
        if (task.done || task.remainingMinutes <= 0) return

        val plan = PlanEngine.build(store.settings, store.courses, store.tasks, now)
        val planFor = plan.planFor(taskId)
        val key = intent.getStringExtra(EXTRA_KEY) ?: "$taskId@${now.toLocalDate()}"
        val id = ReminderScheduler.notificationId(key)

        val text = buildString {
            append("还剩 ").append(humanDuration(task.remainingMinutes.toLong())).append("工作量")
            if (task.deadlineTime != null) {
                append(" · ").append(deadlineLabel(task.deadlineTime))
                append(" · ").append(countdownLabel(task.deadlineTime, now))
            }
            planFor?.finishAt?.let { finish ->
                append("\n照现在的空档，做完大约在 ").append(deadlineLabel(finish))
            } ?: append("\n按现在的课表排不下这件事，建议手动插一段时间")
        }
        Notifier.showSession(context, id, taskId, "该做：${task.title}", text, maxOf(minutes, 15))
    }

    private fun onDeadline(context: Context, intent: Intent, now: LocalDateTime) {
        val taskId = intent.getStringExtra(TaskActionReceiver.EXTRA_TASK_ID) ?: return
        val level = intent.getStringExtra(EXTRA_LEVEL) ?: ReminderScheduler.LEVEL_WARN
        val store = Store.get(context)
        val task = store.tasks.firstOrNull { it.id == taskId } ?: return
        if (task.done || task.remainingMinutes <= 0) return

        val plan = PlanEngine.build(store.settings, store.courses, store.tasks, now)
        val planFor = plan.planFor(taskId)
        val remaining = humanDuration(task.remainingMinutes.toLong())
        val left = task.deadlineTime?.let { deadlineLabel(it) }.orEmpty()

        val title = when (level) {
            ReminderScheduler.LEVEL_URGENT -> "马上到期：${task.title}"
            ReminderScheduler.LEVEL_FINAL -> "到点了：${task.title}"
            else -> "DDL 预警：${task.title}"
        }
        val text = buildString {
            append(left)
            append(" · 还剩 ").append(remaining).append("没做")
            when {
                planFor?.finishAt != null ->
                    append("\n按当前课表，预计 ").append(deadlineLabel(planFor.finishAt)).append(" 能做完")

                planFor != null -> append("\n⚠ 当前空档不够，做完会超过截止时间（缺 ").append(humanDuration(planFor.shortfallMinutes.toLong())).append("）")
                else -> Unit
            }
        }
        val urgent = level != ReminderScheduler.LEVEL_WARN
        Notifier.showDeadline(context, ReminderScheduler.notificationId("$taskId@$level"), taskId, title, text, urgent)
    }

    private fun onDigest(context: Context, intent: Intent, now: LocalDateTime) {
        val store = Store.get(context)
        val date = intent.getStringExtra(EXTRA_KEY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: now.toLocalDate()
        val plan = PlanEngine.build(store.settings, store.courses, store.tasks, now)
        val text = digestText(plan, date, now)
        Notifier.showDigest(context, ReminderScheduler.notificationId("digest:$date"), "今天要推进的事", text)
    }

    /** 每日播报正文：今天有多少活、最近的一个 DDL 是谁。 */
    fun digestText(plan: Plan, date: LocalDate, now: LocalDateTime): String {
        val blocks = plan.blocksOn(date)
        val minutes = blocks.sumOf { it.minutes }
        val tasks = blocks.map { it.taskId }.distinct().mapNotNull { id -> plan.plans.firstOrNull { it.task.id == id }?.task }
        // deadline 可空，这里用"最晚"兜底，保证能比较
        val nearest = plan.plans
            .filter { it.task.remainingMinutes > 0 && it.deadline != null }
            .minByOrNull { it.deadline ?: LocalDateTime.MAX }

        return buildString {
            if (blocks.isEmpty()) {
                append("今天没有排定的任务时段。")
            } else {
                append("今天 ").append(tasks.size).append(" 件事、约 ").append(humanDuration(minutes.toLong())).append("：\n")
                blocks.take(4).forEach { block ->
                    append("· %02d:%02d %s\n".format(block.start.hour, block.start.minute, block.title))
                }
                if (blocks.size > 4) append("· 还有 ").append(blocks.size - 4).append(" 个时段\n")
            }
            nearest?.let { plan ->
                append("最近的是「").append(plan.task.title).append("」，")
                append(countdownLabel(plan.deadline, now))
                if (plan.feasibility == com.balance.deadlinereminder.domain.Feasibility.IMPOSSIBLE) {
                    append("（当前空档排不完，建议现在动手或调整时间）")
                }
            }
        }.trim()
    }

    companion object {
        const val EXTRA_COURSE_ID = "courseId"
        const val EXTRA_NAME = "name"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_START = "start"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_STRONG = "strong"
        const val EXTRA_KEY = "key"
        const val EXTRA_LEVEL = "level"
    }
}
