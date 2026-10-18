package com.balance.deadlinereminder

import com.balance.deadlinereminder.data.AppSettings
import com.balance.deadlinereminder.data.Course
import com.balance.deadlinereminder.data.PeriodTime
import com.balance.deadlinereminder.data.Task
import com.balance.deadlinereminder.data.Urgency
import com.balance.deadlinereminder.data.urgencyFor
import com.balance.deadlinereminder.domain.Feasibility
import com.balance.deadlinereminder.domain.PlanEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 排程引擎的纯 JVM 测试：不碰 Android，时间全部写死。
 *
 * 关键约定：2026-03-02 是周一，测试里把它当作第一周的周一。
 */
class PlanEngineTest {

    private val monday: LocalDate = LocalDate.parse("2026-03-02")

    private fun settings(
        dailyCapMinutes: Int = 180,
        focusChunkMinutes: Int = 60,
        breakMinutes: Int = 0,
        minSlotMinutes: Int = 20,
    ) = AppSettings(
        termStartDate = monday.toString(),
        totalWeeks = 20,
        periods = listOf(PeriodTime(8 * 60, 9 * 60), PeriodTime(10 * 60, 11 * 60)),
        wakeMinute = 8 * 60,
        sleepMinute = 23 * 60,
        dailyCapMinutes = dailyCapMinutes,
        focusChunkMinutes = focusChunkMinutes,
        breakMinutes = breakMinutes,
        minSlotMinutes = minSlotMinutes,
    )

    private fun course(startPeriod: Int = 1, endPeriod: Int = 1) = Course(
        id = "c1",
        name = "高等数学",
        dayOfWeek = 1,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        startWeek = 1,
        endWeek = 20,
    )

    private fun task(
        id: String,
        estimate: Int,
        deadline: String,
        priority: Int = 1,
        earliestStart: String = "",
        dependencyIds: List<String> = emptyList(),
        daily: Boolean = false,
        estimateUnknown: Boolean = false,
    ) = Task(
        id = id,
        title = "任务$id",
        estimateMinutes = estimate,
        deadline = deadline,
        priority = priority,
        earliestStart = earliestStart,
        dependencyIds = dependencyIds,
        daily = daily,
        estimateUnknown = estimateUnknown,
    )

    @Test
    fun `测试基准日是周一`() {
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
    }

    @Test
    fun `空闲时间会扣掉上课并受每日上限约束`() {
        val slots = PlanEngine.freeSlots(
            settings(dailyCapMinutes = 180),
            listOf(course()),
            monday.atTime(8, 0),
            monday.atTime(23, 0),
        )
        // 8:00-9:00 有课，所以第一段从 9:00 开始
        assertEquals(monday.atTime(9, 0), slots.first().start)
        // 每日上限 180 分钟，再多空档也不给
        assertEquals(180, slots.sumOf { it.minutes })
    }

    @Test
    fun `每日上限按天分别计算`() {
        val slots = PlanEngine.freeSlots(
            settings(dailyCapMinutes = 120),
            emptyList(),
            monday.atTime(8, 0),
            monday.plusDays(2).atTime(23, 0),
        )
        assertEquals(360, slots.sumOf { it.minutes }) // 三天各 120
    }

    @Test
    fun `每天自动扣除通勤饮食与九小时睡眠`() {
        val allDay = settings(dailyCapMinutes = 1440).copy(
            wakeMinute = 0,
            sleepMinute = 1440,
            commuteMinutes = 60,
            mealMinutes = 120,
            sleepMinutes = 540,
        )
        val slots = PlanEngine.freeSlots(allDay, emptyList(), monday.atStartOfDay(), monday.plusDays(1).atStartOfDay())
        assertEquals(720, slots.sumOf { it.minutes })
    }

    @Test
    fun `紧急程度同时考虑时间余量与重要程度`() {
        val low = task("low", 60, monday.atTime(23, 0).toString(), priority = 0)
        val medium = task("medium", 60, monday.atTime(23, 0).toString(), priority = 1)
        val high = task("high", 60, monday.atTime(23, 0).toString(), priority = 2)
        assertEquals(Urgency.RELAXED, urgencyFor(240, low))
        assertEquals(Urgency.ATTENTION, urgencyFor(240, medium))
        assertEquals(Urgency.ATTENTION, urgencyFor(240, high))
        assertEquals(Urgency.URGENT, urgencyFor(120, high))
        assertEquals(Urgency.ATTENTION, urgencyFor(120, low))
    }

    @Test
    fun `一次专注时长会切成多段并留出休息`() {
        val plan = PlanEngine.build(
            settings(dailyCapMinutes = 180, focusChunkMinutes = 60, breakMinutes = 10),
            emptyList(),
            listOf(task("a", 180, monday.atTime(23, 0).toString())),
            monday.atTime(8, 0),
        )
        val planFor = plan.planFor("a")!!
        val blocks = planFor.blocks
        // 8:00 开始，满一小时歇 10 分钟；180 分钟的日窗口里第三段只剩 40 分钟
        assertEquals(3, blocks.size)
        assertEquals(listOf(60, 60, 40), blocks.map { it.minutes })
        assertEquals(monday.atTime(8, 0), blocks[0].start)
        assertEquals(monday.atTime(9, 10), blocks[1].start) // 中间留了 10 分钟休息
        assertEquals(monday.atTime(10, 20), blocks[2].start)
        // 日上限按"可支配的整段时间"算，休息也算在里面，所以还差 20 分钟
        assertEquals(160, planFor.plannedMinutes)
        assertEquals(20, planFor.shortfallMinutes)
        assertEquals(Feasibility.IMPOSSIBLE, planFor.feasibility)
    }

    @Test
    fun `先排最先截止的任务`() {
        val plan = PlanEngine.build(
            settings(dailyCapMinutes = 60), // 当天只有 9:00-10:00 一个小时
            listOf(course()),
            listOf(
                task("later", 60, monday.atTime(21, 0).toString()),
                task("sooner", 60, monday.atTime(18, 0).toString()),
            ),
            monday.atTime(8, 0),
        )
        assertTrue(plan.planFor("later")!!.blocks.isEmpty())
        assertEquals(1, plan.planFor("sooner")!!.blocks.size)
        assertEquals(monday.atTime(9, 0), plan.planFor("sooner")!!.blocks.first().start)
        assertEquals(Feasibility.IMPOSSIBLE, plan.planFor("later")!!.feasibility)
    }

    @Test
    fun `同截止时间时优先级高的先排`() {
        val plan = PlanEngine.build(
            settings(dailyCapMinutes = 60),
            listOf(course()),
            listOf(
                task("low", 60, monday.atTime(20, 0).toString(), priority = 0),
                task("high", 60, monday.atTime(20, 0).toString(), priority = 2),
            ),
            monday.atTime(8, 0),
        )
        assertEquals(1, plan.planFor("high")!!.blocks.size)
        assertTrue(plan.planFor("low")!!.blocks.isEmpty())
    }

    @Test
    fun `空档不够时只排能排的部分并报缺多少`() {
        val plan = PlanEngine.build(
            settings(dailyCapMinutes = 180),
            listOf(course()),
            listOf(task("big", 120, monday.atTime(10, 30).toString())),
            monday.atTime(8, 0),
        )
        val planFor = plan.planFor("big")!!
        assertEquals(90, planFor.plannedMinutes)  // 9:00-10:00 与 10:00-10:30
        assertEquals(30, planFor.shortfallMinutes)
        assertEquals(Feasibility.IMPOSSIBLE, planFor.feasibility)
        assertTrue(plan.riskyPlans.isNotEmpty())
    }

    @Test
    fun `余量很小时标记为偏紧`() {
        val plan = PlanEngine.build(
            settings(),
            listOf(course()),
            listOf(task("tight", 60, monday.atTime(10, 20).toString())),
            monday.atTime(8, 0),
        )
        val planFor = plan.planFor("tight")!!
        assertEquals(monday.atTime(10, 0), planFor.finishAt)
        assertEquals(20, planFor.slackMinutes)
        assertEquals(Feasibility.TIGHT, planFor.feasibility)
    }

    @Test
    fun `没有截止时间的任务也会在滚动窗口内排程`() {
        val plan = PlanEngine.build(settings(), emptyList(), listOf(task("noDdl", 60, "")), monday.atTime(8, 0))
        val planFor = plan.planFor("noDdl")!!
        assertEquals(1, planFor.blocks.size)
        assertEquals(monday.atTime(8, 0), planFor.blocks.first().start)
        assertEquals(Feasibility.NO_DEADLINE, planFor.feasibility)
        assertEquals(monday.atTime(9, 0), planFor.finishAt)
    }

    @Test
    fun `无截止的每日任务也参与排程`() {
        val plan = PlanEngine.build(
            settings(),
            emptyList(),
            listOf(task("daily", 30, "", daily = true)),
            monday.atTime(8, 0),
        )
        assertEquals(30, plan.planFor("daily")!!.plannedMinutes)
    }

    @Test
    fun `预计耗时未知的长周期任务等待估时且不占排程`() {
        val plan = PlanEngine.build(
            settings(dailyCapMinutes = 60),
            emptyList(),
            listOf(
                task("contest", 60, monday.plusDays(7).atTime(20, 0).toString(), priority = 2, estimateUnknown = true),
                task("known", 60, monday.atTime(20, 0).toString()),
            ),
            monday.atTime(8, 0),
        )
        val contest = plan.planFor("contest")!!
        assertTrue(contest.blocks.isEmpty())
        assertEquals(0, contest.plannedMinutes)
        assertEquals(Feasibility.NEEDS_ESTIMATE, contest.feasibility)
        assertEquals(60, plan.requiredMinutes)
        assertEquals("known", plan.blocks.single().taskId)
    }

    @Test
    fun `未到最早开始时间时不排程并在到点后加入排程`() {
        val start = monday.atTime(10, 0)
        val before = PlanEngine.build(
            settings(dailyCapMinutes = 180),
            emptyList(),
            listOf(task("future", 60, monday.atTime(20, 0).toString(), earliestStart = start.toString())),
            monday.atTime(8, 0),
        )
        assertTrue(before.planFor("future")!!.blocks.isEmpty())
        assertEquals(Feasibility.NOT_STARTED, before.planFor("future")!!.feasibility)

        val released = PlanEngine.build(
            settings(dailyCapMinutes = 180),
            emptyList(),
            listOf(task("future", 60, monday.atTime(20, 0).toString(), earliestStart = start.toString())),
            start,
        )
        assertEquals(start, released.planFor("future")!!.firstBlock?.start)
    }

    @Test
    fun `依赖实际完成前阻塞完成后解锁`() {
        val prerequisite = task("first", 30, monday.atTime(20, 0).toString())
        val dependent = task(
            "second",
            30,
            monday.atTime(21, 0).toString(),
            dependencyIds = listOf(prerequisite.id),
        )
        val blocked = PlanEngine.build(settings(), emptyList(), listOf(prerequisite, dependent), monday.atTime(8, 0))
        assertTrue(blocked.planFor("second")!!.blocks.isEmpty())
        assertEquals(Feasibility.BLOCKED, blocked.planFor("second")!!.feasibility)

        val unlocked = PlanEngine.build(
            settings(),
            emptyList(),
            listOf(prerequisite.copy(done = true), dependent),
            monday.atTime(8, 0),
        )
        assertEquals(30, unlocked.planFor("second")!!.plannedMinutes)
    }

    @Test
    fun `临近截止的低重要任务不会被远期高重要任务压后`() {
        val plan = PlanEngine.build(
            settings(dailyCapMinutes = 60),
            emptyList(),
            listOf(
                task("important", 60, monday.plusDays(2).atTime(20, 0).toString(), priority = 2),
                task("urgent", 60, monday.atTime(9, 0).toString(), priority = 0),
            ),
            monday.atTime(8, 0),
        )
        assertEquals("urgent", plan.blocks.first().taskId)
    }

    @Test
    fun `已经过期的任务只报过期不再排时间`() {
        val plan = PlanEngine.build(
            settings(),
            emptyList(),
            listOf(task("late", 60, monday.minusDays(1).atTime(10, 0).toString())),
            monday.atTime(8, 0),
        )
        val planFor = plan.planFor("late")!!
        assertTrue(planFor.blocks.isEmpty())
        assertEquals(Feasibility.OVERDUE, planFor.feasibility)
    }

    @Test
    fun `已完成的任务不占排程`() {
        val plan = PlanEngine.build(
            settings(dailyCapMinutes = 60),
            emptyList(),
            listOf(task("done", 600, monday.atTime(23, 0).toString()).copy(done = true)),
            monday.atTime(8, 0),
        )
        assertTrue(plan.blocks.isEmpty())
        assertEquals(0, plan.requiredMinutes)
    }

    @Test
    fun `登记过进度之后剩余工作量会减少`() {
        val plan = PlanEngine.build(
            settings(),
            emptyList(),
            listOf(task("half", 120, monday.atTime(23, 0).toString()).copy(spentMinutes = 60)),
            monday.atTime(8, 0),
        )
        val planFor = plan.planFor("half")!!
        assertEquals(60, planFor.remainingMinutes)
        assertEquals(60, planFor.plannedMinutes)
        assertEquals(Feasibility.COMFORTABLE, planFor.feasibility)
    }

    @Test
    fun `没有可用空档时任务排不下`() {
        // 只剩 23:20-23:30 十分钟，短于最小可用时段：今天排不进任何一段
        val s = settings().copy(wakeMinute = 23 * 60, sleepMinute = 23 * 60 + 30)
        val plan = PlanEngine.build(
            s,
            emptyList(),
            listOf(task("nofit", 60, monday.atTime(23, 29).toString())),
            monday.atTime(23, 20),
        )
        val planFor = plan.planFor("nofit")!!
        assertTrue(planFor.blocks.isEmpty())
        assertEquals(Feasibility.IMPOSSIBLE, planFor.feasibility)
    }

    @Test
    fun `排程结果按时间排序且下一块指向未来`() {
        val plan = PlanEngine.build(
            settings(focusChunkMinutes = 60, breakMinutes = 0),
            emptyList(),
            listOf(
                task("a", 60, monday.atTime(15, 0).toString()),
                task("b", 60, monday.atTime(16, 0).toString()),
            ),
            monday.atTime(8, 0),
        )
        val starts = plan.blocks.map { it.start }
        assertEquals(starts.sorted(), starts)
        assertEquals("a", plan.nextBlock()!!.taskId)
        // 两个小时的空档足够放下两个一小时的任务，不该报警
        assertTrue(plan.riskyPlans.isEmpty())
    }
}
