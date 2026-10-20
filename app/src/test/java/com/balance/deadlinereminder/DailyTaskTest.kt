package com.balance.deadlinereminder

import com.balance.deadlinereminder.data.Task
import com.balance.deadlinereminder.data.TaskState
import com.balance.deadlinereminder.data.refreshDailyTaskStates
import com.balance.deadlinereminder.data.taskDayAt
import com.balance.deadlinereminder.data.nextTaskRefreshAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DailyTaskTest {

    private val today = LocalDate.parse("2026-10-17")

    @Test
    fun `昨天完成的每日任务会在今天重置`() {
        val task = Task(
            id = "daily",
            title = "背单词",
            estimateMinutes = 30,
            spentMinutes = 30,
            done = true,
            daily = true,
            completedOn = "2026-10-16",
        )
        val refreshed = refreshDailyTaskStates(listOf(task), today).single()
        assertFalse(refreshed.done)
        assertEquals(0, refreshed.spentMinutes)
        assertEquals("", refreshed.completedOn)
    }

    @Test
    fun `跨日刷新后的任务状态会立即发布给界面`() {
        val task = Task(
            id = "daily",
            title = "背单词",
            estimateMinutes = 30,
            spentMinutes = 30,
            done = true,
            daily = true,
            completedOn = "2026-10-16",
        )
        val state = TaskState(listOf(task))

        state.refreshDaily(today)

        assertFalse(state.flow.value.single().done)
        assertEquals("", state.flow.value.single().completedOn)
    }

    @Test
    fun `今天完成的每日任务不会重复重置`() {
        val task = Task(id = "daily", title = "背单词", done = true, daily = true, completedOn = today.toString())
        assertTrue(refreshDailyTaskStates(listOf(task), today).single().done)
    }

    @Test
    fun `普通已完成任务会在维护时清除`() {
        val task = Task(id = "normal", title = "报告", done = true, spentMinutes = 60)
        assertTrue(refreshDailyTaskStates(listOf(task), today).isEmpty())
    }

    @Test
    fun `清除普通已完成任务时会解除其他任务的依赖`() {
        val completed = Task(id = "completed", title = "资料收集", done = true)
        val pending = Task(id = "pending", title = "写报告", dependencyIds = listOf("completed"))

        val refreshed = refreshDailyTaskStates(listOf(completed, pending), today)

        assertEquals(listOf(pending.copy(dependencyIds = emptyList())), refreshed)
    }

    @Test
    fun `凌晨四点前仍属于前一个任务日`() {
        assertEquals(
            LocalDate.parse("2026-10-16"),
            taskDayAt(LocalDateTime.parse("2026-10-17T03:59")),
        )
        assertEquals(
            LocalDate.parse("2026-10-17"),
            taskDayAt(LocalDateTime.parse("2026-10-17T04:00")),
        )
    }

    @Test
    fun `下一次任务列表刷新固定在凌晨四点`() {
        assertEquals(
            LocalDateTime.parse("2026-10-17T04:00"),
            nextTaskRefreshAt(LocalDateTime.parse("2026-10-17T03:59")),
        )
        assertEquals(
            LocalDateTime.parse("2026-10-18T04:00"),
            nextTaskRefreshAt(LocalDateTime.parse("2026-10-17T04:00")),
        )
    }
}
