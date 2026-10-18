package com.balance.deadlinereminder.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 一条 DDL 任务：要做什么（[title]）、大概要多久（[estimateMinutes]）、什么时候必须交（[deadline]）。
 *
 * 应用的全部排程都围着这三个字段转：拿"课程空档"去填"需要的时间"，
 * 算出"按现在的空档，这件事大概什么时候能做完"，再和 [deadline] 比对。
 */
data class Task(
    val id: String,
    val title: String,
    /** 关联的课程/科目，可空；留空就是纯私人事务。 */
    val courseName: String = "",
    /** 预估总耗时（分钟）。 */
    val estimateMinutes: Int = 60,
    /** 已经投入的时间（分钟），用户登记进度时累加。 */
    val spentMinutes: Int = 0,
    /** 截止时间，格式 yyyy-MM-ddTHH:mm；空串表示还没填。 */
    val deadline: String = "",
    /** 0=低 1=中 2=高。 */
    val priority: Int = 1,
    val notes: String = "",
    val done: Boolean = false,
    val createdAt: String = "",
    /** 单个任务一次最多安排多久；-1 表示跟随全局"单次专注时长"。 */
    val chunkMinutes: Int = -1,
    /** 每日任务每天重新出现，完成状态只对当天有效。 */
    val daily: Boolean = false,
    /** 每日任务最近一次完成日期，格式 yyyy-MM-dd。 */
    val completedOn: String = "",
    /** 最早开始时间；未到该时刻前不参与排程。 */
    val earliestStart: String = "",
    /** 必须先实际完成的任务 ID；不存在的 ID 按已移除处理。 */
    val dependencyIds: List<String> = emptyList(),
    /** 预计耗时尚不明确；保留任务约束，但暂不生成工作块。 */
    val estimateUnknown: Boolean = false,
) {
    /** 还没投入的时间；已投入超过预估时为 0。 */
    val remainingMinutes: Int get() = (estimateMinutes - spentMinutes).coerceAtLeast(0)

    val deadlineTime: LocalDateTime? get() = parseDateTime(deadline)
    val earliestStartTime: LocalDateTime? get() = parseDateTime(earliestStart)

    /** 0.0～1.0，用于进度条。 */
    val progress: Float
        get() = if (estimateMinutes <= 0) 0f else (spentMinutes.toFloat() / estimateMinutes).coerceIn(0f, 1f)

    fun isOverdue(now: LocalDateTime): Boolean =
        !done && deadlineTime?.isBefore(now) == true

    fun chunk(settings: AppSettings): Int =
        if (chunkMinutes > 0) chunkMinutes else settings.focusChunkMinutes
}

const val DATE_TIME_PATTERN = "yyyy-MM-dd'T'HH:mm"

private val DEADLINE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN)

fun formatDateTime(time: LocalDateTime): String = time.format(DEADLINE_FORMAT)

/** 解析 yyyy-MM-ddTHH:mm；也容忍只有日期（补 23:59）或只有 "yyyy-MM-dd HH:mm"。 */
fun parseDateTime(text: String): LocalDateTime? {
    val t = text.trim().replace(' ', 'T')
    if (t.isEmpty()) return null
    runCatching { return LocalDateTime.parse(t, DEADLINE_FORMAT) }
    runCatching { return LocalDateTime.parse(t) }
    runCatching { return LocalDate.parse(t).atTime(23, 59) }
    return null
}

/** 中文优先级名。 */
val PRIORITY_NAMES = listOf("低", "中", "高")

/** 按可用空闲时间、剩余工作量与重要程度判断任务紧急程度。 */
enum class Urgency { RELAXED, ATTENTION, URGENT }

fun urgencyFor(freeMinutes: Int, task: Task): Urgency {
    if (task.remainingMinutes <= 0) return Urgency.RELAXED
    val ratio = freeMinutes.toFloat() / task.remainingMinutes.toFloat()
    val relaxedThreshold = 4f + task.priority.coerceIn(0, 2)
    val attentionThreshold = 2f + task.priority.coerceIn(0, 2)
    return when {
        ratio >= relaxedThreshold -> Urgency.RELAXED
        ratio >= attentionThreshold -> Urgency.ATTENTION
        else -> Urgency.URGENT
    }
}

fun priorityName(priority: Int): String = PRIORITY_NAMES.getOrElse(priority) { "中" }

/**
 * 距离截止还剩多久的人话描述。已过期返回"已过期 X"。
 * [now] 显式传入，方便单测固定时间。
 */
fun countdownLabel(deadline: LocalDateTime?, now: LocalDateTime): String {
    if (deadline == null) return "未设截止时间"
    val minutes = ChronoUnit.MINUTES.between(now, deadline)
    if (minutes <= 0) return "已过期 " + humanDuration(-minutes)
    return "还剩 " + humanDuration(minutes)
}

/** 把分钟数写成"3 天 2 小时"这类描述；不足 1 分钟显示"0 分钟"。 */
fun humanDuration(minutes: Long): String {
    val m = minutes.coerceAtLeast(0)
    val days = m / 1440
    val hours = (m % 1440) / 60
    val mins = m % 60
    return when {
        days > 0 && hours > 0 -> "$days 天 $hours 小时"
        days > 0 -> "$days 天"
        hours > 0 && mins > 0 && hours < 6 -> "$hours 小时 $mins 分钟"
        hours > 0 -> "$hours 小时"
        mins > 0 -> "$mins 分钟"
        else -> "不到 1 分钟"
    }
}

/** "10月15日 周三 23:59" 这种好扫读的截止时间写法。 */
fun deadlineLabel(deadline: LocalDateTime?): String =
    deadline?.let { "%d月%d日 %s %02d:%02d".format(it.monthValue, it.dayOfMonth, dayName(it.dayOfWeek.value), it.hour, it.minute) }
        ?: "未设截止时间"

/** 今天的 23:59，用于"今晚"这类快捷按钮。 */
fun endOfDay(date: LocalDate): LocalDateTime = date.atTime(23, 59)
