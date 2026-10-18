package com.balance.deadlinereminder.ui

import com.balance.deadlinereminder.data.Task
import com.balance.deadlinereminder.data.Urgency
import com.balance.deadlinereminder.data.urgencyFor
import com.balance.deadlinereminder.domain.Feasibility
import com.balance.deadlinereminder.domain.TaskPlan

/** 任务卡摘要状态：先表达能否开始，再表达时间紧迫程度。 */
enum class TaskDisplayState {
    DONE,
    NEEDS_ESTIMATE,
    NOT_STARTED,
    BLOCKED,
    RELAXED,
    ATTENTION,
    URGENT,
    /** 只有“高重要且紧急”才进入高红状态。 */
    CRITICAL,
}

fun taskDisplayState(task: Task, plan: TaskPlan?): TaskDisplayState {
    if (task.done) return TaskDisplayState.DONE
    if (task.estimateUnknown || plan?.feasibility == Feasibility.NEEDS_ESTIMATE) return TaskDisplayState.NEEDS_ESTIMATE
    if (plan?.feasibility == Feasibility.NOT_STARTED) return TaskDisplayState.NOT_STARTED
    if (plan?.feasibility == Feasibility.BLOCKED) return TaskDisplayState.BLOCKED
    val urgency = plan?.let { urgencyFor(it.availableMinutes, task) } ?: Urgency.ATTENTION
    return when {
        urgency == Urgency.URGENT && task.priority == 2 -> TaskDisplayState.CRITICAL
        urgency == Urgency.URGENT -> TaskDisplayState.URGENT
        urgency == Urgency.ATTENTION -> TaskDisplayState.ATTENTION
        else -> TaskDisplayState.RELAXED
    }
}
