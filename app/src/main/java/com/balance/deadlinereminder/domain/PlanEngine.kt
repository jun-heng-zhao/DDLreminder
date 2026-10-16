package com.balance.deadlinereminder.domain

import com.balance.deadlinereminder.data.AppSettings
import com.balance.deadlinereminder.data.Course
import com.balance.deadlinereminder.data.Task
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** 一段连续时间。 */
data class TimeRange(val start: LocalDateTime, val end: LocalDateTime) {
    val minutes: Int get() = ChronoUnit.MINUTES.between(start, end).toInt().coerceAtLeast(0)
    fun overlaps(other: TimeRange): Boolean = start.isBefore(other.end) && other.start.isBefore(end)
}

/** 一件任务在当前空档下的处境。 */
enum class Feasibility {
    /** 已经做完了。 */
    DONE,

    /** 排得下，而且余量充足。 */
    COMFORTABLE,

    /** 排得下，但几乎踩点，一次意外就会拖过截止。 */
    TIGHT,

    /** 空档不够，按现在的时间表做不完。 */
    IMPOSSIBLE,

    /** 截止时间已经过了还没做完。 */
    OVERDUE,

    /** 没填截止时间，没法排程。 */
    NO_DEADLINE,
}

/** 一段被安排给某件任务的工作时间。 */
data class WorkBlock(
    val taskId: String,
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
) {
    val minutes: Int get() = ChronoUnit.MINUTES.between(start, end).toInt().coerceAtLeast(0)
}

/** 一件任务的排程结果。 */
data class TaskPlan(
    val task: Task,
    val deadline: LocalDateTime?,
    val remainingMinutes: Int,
    val blocks: List<WorkBlock>,
    /** 空档里已经排进去的分钟数；排不下时会小于 [remainingMinutes]。 */
    val plannedMinutes: Int,
    /** 全部做完的时刻；排不下时为 null。 */
    val finishAt: LocalDateTime?,
    /** 截止时间减去 [finishAt]，正数=富余，负数=来不及。 */
    val slackMinutes: Int?,
    val feasibility: Feasibility,
) {
    val firstBlock: WorkBlock? get() = blocks.firstOrNull()
    val shortfallMinutes: Int get() = (remainingMinutes - plannedMinutes).coerceAtLeast(0)
}

/** 一份完整的排程：把任务摊到课程之间的空档上。 */
data class Plan(
    val generatedAt: LocalDateTime,
    val horizonEnd: LocalDateTime,
    /** 从现在到最后一件事截止，真正能拿来干活的总分钟数。 */
    val freeMinutes: Int,
    /** 所有未完成任务还需要的总分钟数。 */
    val requiredMinutes: Int,
    /** 按时间排好序的全部工作块。 */
    val blocks: List<WorkBlock>,
    /** 每件任务的处境，按紧迫程度排序。 */
    val plans: List<TaskPlan>,
) {
    fun planFor(taskId: String): TaskPlan? = plans.firstOrNull { it.task.id == taskId }

    fun blocksOn(date: LocalDate): List<WorkBlock> = blocks.filter { it.start.toLocalDate() == date }

    /** 还没结束的下一块。 */
    fun nextBlock(): WorkBlock? = blocks.firstOrNull { it.end.isAfter(generatedAt) }

    val riskyPlans: List<TaskPlan>
        get() = plans.filter { it.feasibility == Feasibility.IMPOSSIBLE || it.feasibility == Feasibility.OVERDUE }

    val tightPlans: List<TaskPlan> get() = plans.filter { it.feasibility == Feasibility.TIGHT }
}

/**
 * 排程引擎：只做纯计算，不碰 Android API，方便 JVM 单测。
 *
 * 输入 = 课程表（什么时候有课）+ 设置（几点起、几点睡、每天最多干多久）+ 任务（要多久、什么时候截止）
 * 输出 = 把任务填进空档后的时间表，以及每件任务"来不来得及"的结论。
 *
 * 规则：
 * 1. 只用 [AppSettings.wakeMinute, AppSettings.sleepMinute] 之间的时间，每天再封顶 [AppSettings.dailyCapMinutes]。
 * 2. 上课时间一律不排。
 * 3. 永远先排"最先截止"的那件（并列时优先级高的先排）。
 * 4. 单块不超过任务的 [Task.chunkMinutes]（默认跟随 [AppSettings.focusChunkMinutes]），
 *    排满一块后留 [AppSettings.breakMinutes] 休息。
 * 5. 一件任务在截止前排不下时，能排多少排多少，剩下的记在 [TaskPlan.shortfallMinutes] 里。
 */
object PlanEngine {

    const val HORIZON_DAYS = 14

    fun build(
        settings: AppSettings,
        courses: List<Course>,
        tasks: List<Task>,
        now: LocalDateTime,
        horizonDays: Int = HORIZON_DAYS,
    ): Plan {
        val unfinished = tasks.filter { !it.done }
        val withDeadline = unfinished.filter { it.deadlineTime != null }
        val latestDeadline = withDeadline.mapNotNull { it.deadlineTime }.maxOrNull()
        val horizon = now.plusDays(horizonDays.toLong())
        val planUntil = when {
            latestDeadline == null -> horizon
            latestDeadline.isAfter(horizon) -> horizon
            else -> latestDeadline
        }

        // 只有"还没过期、还有活要干"的任务参与排程
        val active = unfinished
            .filter { it.remainingMinutes > 0 }
            .filter { it.deadlineTime?.isAfter(now) == true }
            .sortedWith(compareBy({ it.deadlineTime }, { -it.priority }, { it.title }))

        val slots = freeSlots(settings, courses, now, planUntil)
        val freeMinutes = slots.sumOf { it.minutes }

        val remaining = active.associate { it.id to it.remainingMinutes }.toMutableMap()
        val blocks = mutableListOf<WorkBlock>()

        slots.forEach { slot ->
            var cursor = slot.start
            while (cursor.isBefore(slot.end)) {
                val candidate = active.firstOrNull { task ->
                    (remaining[task.id] ?: 0) > 0 && task.deadlineTime!!.isAfter(cursor)
                } ?: break
                val deadline = candidate.deadlineTime!!
                val left = remaining[candidate.id] ?: 0
                val untilDeadline = ChronoUnit.MINUTES.between(cursor, deadline).toInt()
                val avail = ChronoUnit.MINUTES.between(cursor, slot.end).toInt()
                // 长度不够一次最小专注、或已经贴着截止时间，就不再往下切了
                if (minOf(avail, untilDeadline) < minOf(settings.minSlotMinutes, left)) break
                val give = minOf(avail, untilDeadline, left, candidate.chunk(settings)).coerceAtLeast(1)
                val end = cursor.plusMinutes(give.toLong())
                blocks += WorkBlock(candidate.id, candidate.title, cursor, end)
                remaining[candidate.id] = left - give
                cursor = if (give >= candidate.chunk(settings)) {
                    end.plusMinutes(settings.breakMinutes.toLong()) // 排满一块，留个休息
                } else end
            }
        }

        val plans = unfinished.sortedWith(
            compareBy(
                { it.deadlineTime == null },                 // 有截止时间的排前面
                { it.deadlineTime ?: LocalDateTime.MAX },
                { -it.priority },
                { it.title },
            )
        ).map { task ->
            val taskBlocks = blocks.filter { it.taskId == task.id }
            val planned = taskBlocks.sumOf { it.minutes }
            val doneAll = remaining[task.id] == 0 && task.remainingMinutes > 0 || task.remainingMinutes == 0
            val deadline = task.deadlineTime
            val finishAt = if (doneAll && task.remainingMinutes > 0) taskBlocks.lastOrNull()?.end else null
            val slack = if (finishAt != null && deadline != null) {
                ChronoUnit.MINUTES.between(finishAt, deadline).toInt()
            } else null
            val feasibility = when {
                task.remainingMinutes == 0 -> Feasibility.DONE
                deadline == null -> Feasibility.NO_DEADLINE
                deadline.isBefore(now) -> Feasibility.OVERDUE
                finishAt == null -> Feasibility.IMPOSSIBLE
                slack != null && slack < tightThreshold(task) -> Feasibility.TIGHT
                else -> Feasibility.COMFORTABLE
            }
            TaskPlan(task, deadline, task.remainingMinutes, taskBlocks, planned, finishAt, slack, feasibility)
        }

        return Plan(
            generatedAt = now,
            horizonEnd = planUntil,
            freeMinutes = freeMinutes,
            requiredMinutes = unfinished.sumOf { it.remainingMinutes },
            blocks = blocks,
            plans = plans,
        )
    }

    /** 富余时间少于这个数就算"紧"：预估耗时的四分之一，且至少半小时。 */
    fun tightThreshold(task: Task): Int = maxOf(30, task.estimateMinutes / 4)

    /** 某天的空闲时间段（已扣掉课程、已按每日上限截断、已从"现在"开始算）。 */
    fun freeMinutesOn(settings: AppSettings, courses: List<Course>, date: LocalDate, now: LocalDateTime): Int =
        freeSlots(settings, courses, maxOf(now, date.atStartOfDay()), date.atTime(23, 59)).sumOf { it.minutes }

    /**
     * 从 [from] 到 [to] 之间所有能干活的时间段：先扣掉上课，再按每天的
     * 起止时间与每日上限切好。返回结果按时间升序。
     */
    fun freeSlots(
        settings: AppSettings,
        courses: List<Course>,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<TimeRange> {
        if (!from.isBefore(to)) return emptyList()
        val out = mutableListOf<TimeRange>()
        var date = from.toLocalDate()
        val lastDate = to.toLocalDate()
        while (!date.isAfter(lastDate)) {
            out += daySlots(settings, courses, date, from, to)
            date = date.plusDays(1)
        }
        return out
    }

    private fun daySlots(
        settings: AppSettings,
        courses: List<Course>,
        date: LocalDate,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<TimeRange> {
        val windowStart = date.atTime(minutesToTime(settings.workWindowStart))
        val windowEnd = date.atTime(minutesToTime(settings.workWindowEnd))
        var cursor = maxOf(windowStart, from)
        val hardEnd = minOf(windowEnd, to)
        if (!cursor.isBefore(hardEnd)) return emptyList()

        val busy = classRanges(settings, courses, date).sortedBy { it.start }
        val gaps = mutableListOf<TimeRange>()
        busy.forEach { range ->
            if (range.start.isAfter(cursor) && range.start.isBefore(hardEnd)) {
                gaps += TimeRange(cursor, minOf(range.start, hardEnd))
            }
            if (range.end.isAfter(cursor)) cursor = maxOf(cursor, minOf(range.end, hardEnd))
        }
        if (cursor.isBefore(hardEnd)) gaps += TimeRange(cursor, hardEnd)

        // 每日上限：从当天最早的空档开始累计，超出部分直接砍掉
        val capped = mutableListOf<TimeRange>()
        var budget = settings.dailyCapMinutes
        for (gap in gaps) {
            if (budget <= 0) break
            val take = minOf(gap.minutes, budget)
            if (take > 0) {
                capped += TimeRange(gap.start, gap.start.plusMinutes(take.toLong()))
                budget -= take
            }
        }
        return capped
    }

    /** 某天的课，换算成时间段。 */
    fun classRanges(settings: AppSettings, courses: List<Course>, date: LocalDate): List<TimeRange> =
        WeekCalc.coursesOn(settings, courses, date)
            .filter { it.end.isAfter(it.start) }
            .map { TimeRange(it.start, it.end) }

    private fun minutesToTime(minute: Int): LocalTime {
        val m = ((minute % 1440) + 1440) % 1440
        return LocalTime.of(m / 60, m % 60)
    }
}
