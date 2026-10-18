package com.balance.deadlinereminder

import com.balance.deadlinereminder.data.Task
import com.balance.deadlinereminder.domain.Feasibility
import com.balance.deadlinereminder.domain.TaskPlan
import com.balance.deadlinereminder.ui.TaskDisplayState
import com.balance.deadlinereminder.ui.taskDisplayState
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskPresentationTest {

    private fun plan(task: Task, feasibility: Feasibility, availableMinutes: Int = 0) = TaskPlan(
        task = task,
        deadline = task.deadlineTime,
        remainingMinutes = task.remainingMinutes,
        blocks = emptyList(),
        plannedMinutes = 0,
        availableMinutes = availableMinutes,
        finishAt = null,
        slackMinutes = null,
        feasibility = feasibility,
    )

    @Test
    fun `未开始与被阻塞任务优先显示等待状态而不是紧急`() {
        val task = Task(id = "future", title = "竞赛", estimateMinutes = 600, priority = 2)
        assertEquals(TaskDisplayState.NOT_STARTED, taskDisplayState(task, plan(task, Feasibility.NOT_STARTED)))
        assertEquals(TaskDisplayState.BLOCKED, taskDisplayState(task, plan(task, Feasibility.BLOCKED)))
    }

    @Test
    fun `只有高重要且紧急的任务使用高红状态`() {
        val high = Task(id = "high", title = "高重要", estimateMinutes = 60, priority = 2)
        val medium = high.copy(id = "medium", title = "中重要", priority = 1)
        assertEquals(TaskDisplayState.CRITICAL, taskDisplayState(high, plan(high, Feasibility.IMPOSSIBLE)))
        assertEquals(TaskDisplayState.URGENT, taskDisplayState(medium, plan(medium, Feasibility.IMPOSSIBLE)))
    }

    @Test
    fun `高重要但时间宽裕的任务不使用高红状态`() {
        val task = Task(id = "later", title = "远期竞赛", estimateMinutes = 60, priority = 2)
        assertEquals(TaskDisplayState.RELAXED, taskDisplayState(task, plan(task, Feasibility.COMFORTABLE, 600)))
    }

    @Test
    fun `预计耗时未知显示待估时`() {
        val task = Task(id = "unknown", title = "长期竞赛", priority = 2, estimateUnknown = true)
        assertEquals(TaskDisplayState.NEEDS_ESTIMATE, taskDisplayState(task, plan(task, Feasibility.NEEDS_ESTIMATE)))
    }
}
