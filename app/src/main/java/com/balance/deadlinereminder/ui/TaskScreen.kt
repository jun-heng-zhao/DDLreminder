package com.balance.deadlinereminder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.balance.deadlinereminder.data.AppSettings
import com.balance.deadlinereminder.data.Course
import com.balance.deadlinereminder.data.Task
import com.balance.deadlinereminder.data.countdownLabel
import com.balance.deadlinereminder.data.deadlineLabel
import com.balance.deadlinereminder.data.formatDateTime
import com.balance.deadlinereminder.data.humanDuration
import com.balance.deadlinereminder.data.parseDateTime
import com.balance.deadlinereminder.data.priorityName
import com.balance.deadlinereminder.domain.Feasibility
import com.balance.deadlinereminder.domain.Plan
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 任务页：这是用户唯一需要主动输入的地方。
 * 写三件事就够——做什么、大概要多久、什么时候必须交——剩下的交给排程引擎。
 */
@Composable
fun TaskScreen(
    tasks: List<Task>,
    courses: List<Course>,
    settings: AppSettings,
    plan: Plan?,
    onSave: (Task) -> Unit,
    onDelete: (String) -> Unit,
    onProgress: (String, Int) -> Unit,
    onDone: (String, Boolean) -> Unit,
    compact: Boolean = false,
) {
    val now = LocalDateTime.now()
    var editing by remember { mutableStateOf<Task?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }

    val visible = tasks
        .filter { if (showDone) it.done else !it.done }
        .sortedWith(compareBy({ it.deadlineTime == null }, { it.deadlineTime ?: LocalDateTime.MAX }, { -it.priority }))

    val screenModifier = if (compact) {
        Modifier.fillMaxWidth().padding(horizontal = 6.dp)
    } else {
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)
    }
    Column(screenModifier) {
        if (!compact) NextUpCard(plan, tasks, now)

        val risky = plan?.riskyPlans.orEmpty()
        if (!compact && risky.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("按现在的课表，这些事来不及", fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                    risky.forEach { plan ->
                        val why = when (plan.feasibility) {
                            Feasibility.OVERDUE -> "截止时间已经过了，还差 ${humanDuration(plan.remainingMinutes.toLong())}"
                            else -> "直到截止只有空档 ${humanDuration(plan.plannedMinutes.toLong())}，还缺 ${humanDuration(plan.shortfallMinutes.toLong())}"
                        }
                        Text("· ${plan.task.title}：$why", fontSize = 13.sp)
                    }
                    Text("可以考虑：拆小一点、降低要求、或者把截止时间往后挪。", fontSize = 12.sp, color = Color(0xFF546E7A))
                }
            }
        }

        FlowRow(Modifier.padding(top = 6.dp)) {
            FilterChip(
                selected = !showDone,
                onClick = { showDone = false },
                label = { Text("进行中 ${tasks.count { !it.done }}") },
                modifier = Modifier.padding(end = 6.dp),
            )
            FilterChip(
                selected = showDone,
                onClick = { showDone = true },
                label = { Text("已完成 ${tasks.count { it.done }}") },
            )
        }

        if (visible.isEmpty()) {
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (showDone) "还没有完成的任务。" else "还没有要推进的事。", fontWeight = FontWeight.Medium)
                    Text("点下面的「＋ 新增任务」，填上大概要多久和什么时候必须交。", fontSize = 13.sp)
                }
            }
        }

        visible.forEach { task ->
            TaskCard(
                task = task,
                planFor = plan?.planFor(task.id),
                now = now,
                onProgress = onProgress,
                onDone = onDone,
                onEdit = { editing = task },
            )
        }

        TextButton(onClick = { creating = true }) { Text("＋ 新增任务") }
        if (!compact) Spacer(Modifier.height(60.dp))
    }

    if (creating) {
        TaskEditDialog(
            task = null,
            courses = courses,
            settings = settings,
            onDismiss = { creating = false },
            onSave = { onSave(it); creating = false },
            onDelete = null,
        )
    }
    editing?.let { task ->
        TaskEditDialog(
            task = task,
            courses = courses,
            settings = settings,
            onDismiss = { editing = null },
            onSave = { onSave(it); editing = null },
            onDelete = { onDelete(task.id); editing = null },
        )
    }
}

/** 顶部"下一件事"：给一个立刻可做的动作，而不是一整页清单。 */
@Composable
private fun NextUpCard(plan: Plan?, tasks: List<Task>, now: LocalDateTime) {
    val clock = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }
    val next = plan?.nextBlock()
    val nextTask = next?.let { block -> tasks.firstOrNull { it.id == block.taskId } }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("下一件事", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (next == null || nextTask == null) {
                Text(
                    if (tasks.none { !it.done && it.remainingMinutes > 0 }) "还没有待推进的任务。写一件事，我来排时间。"
                    else "眼下没有排定的时段：可能今天已经排满，或者剩余空档太碎。",
                    fontSize = 13.sp,
                )
            } else {
                Text("${next.start.format(clock)} 开始 · ${next.title}", fontWeight = FontWeight.Medium)
                Text(
                    "计划这一段 ${humanDuration(next.minutes.toLong())}；任务剩余 ${humanDuration(nextTask.remainingMinutes.toLong())}",
                    fontSize = 12.sp,
                )
                Text(
                    "截止 ${deadlineLabel(nextTask.deadlineTime)} · ${countdownLabel(nextTask.deadlineTime, now)}",
                    fontSize = 12.sp,
                    color = if (nextTask.isOverdue(now)) Color(0xFFC62828) else Color(0xFF37474F),
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: Task,
    planFor: com.balance.deadlinereminder.domain.TaskPlan?,
    now: LocalDateTime,
    onProgress: (String, Int) -> Unit,
    onDone: (String, Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val stamp = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }
    val overdue = task.isOverdue(now)
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                PriorityTag(task.priority)
            }
            Text(
                "截止 ${deadlineLabel(task.deadlineTime)} · ${countdownLabel(task.deadlineTime, now)}",
                fontSize = 12.sp,
                color = if (overdue) Color(0xFFC62828) else Color(0xFF37474F),
            )
            if (task.courseName.isNotBlank()) Text("科目：${task.courseName}", fontSize = 12.sp)

            LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp),
            )
            Text(
                "已投入 ${humanDuration(task.spentMinutes.toLong())} / 预估 ${humanDuration(task.estimateMinutes.toLong())} · 还剩 ${humanDuration(task.remainingMinutes.toLong())}",
                fontSize = 12.sp,
            )

            val suggestion = when {
                task.done -> "已完成"
                task.remainingMinutes == 0 -> "工作量已登记满，可以标完成了"
                planFor?.firstBlock != null ->
                    "建议 ${planFor.firstBlock!!.start.format(stamp)} 开始 · 共 ${planFor.blocks.size} 段 / ${humanDuration(planFor.plannedMinutes.toLong())}"

                task.deadlineTime == null -> "没填截止时间，先补一个才能排进时间表"
                else -> "当前空档排不下，缺 ${humanDuration((planFor?.shortfallMinutes ?: task.remainingMinutes).toLong())}"
            }
            Text(suggestion, fontSize = 12.sp, color = Color(0xFF00695C))

            planFor?.let { p ->
                when (p.feasibility) {
                    Feasibility.IMPOSSIBLE, Feasibility.OVERDUE ->
                        Text("⚠ 来不及：${if (p.feasibility == Feasibility.OVERDUE) "已过期" else "空档不足"}", fontSize = 12.sp, color = Color(0xFFC62828))

                    Feasibility.TIGHT ->
                        Text("⚠ 偏紧：预计 ${p.finishAt?.format(stamp)} 做完，离截止只剩 ${humanDuration((p.slackMinutes ?: 0).toLong())}", fontSize = 12.sp, color = Color(0xFFEF6C00))

                    Feasibility.COMFORTABLE ->
                        Text("预计 ${p.finishAt?.format(stamp)} 做完，比截止早 ${humanDuration((p.slackMinutes ?: 0).toLong())}", fontSize = 12.sp, color = Color(0xFF2E7D32))

                    else -> Unit
                }
            }

            if (task.notes.isNotBlank()) Text("备注：${task.notes}", fontSize = 12.sp)

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (!task.done) {
                    TextButton(onClick = { onProgress(task.id, 30) }) { Text("＋30 分钟") }
                    TextButton(onClick = { onProgress(task.id, 60) }) { Text("＋60 分钟") }
                    TextButton(onClick = { onDone(task.id, true) }) { Text("完成") }
                } else {
                    TextButton(onClick = { onDone(task.id, false) }) { Text("撤销完成") }
                }
                TextButton(onClick = onEdit) { Text("编辑") }
            }
        }
    }
}

@Composable
private fun PriorityTag(priority: Int) {
    val color = when (priority) {
        2 -> Color(0xFFC62828)
        1 -> Color(0xFFEF6C00)
        else -> Color(0xFF607D8B)
    }
    Box(Modifier.background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(priorityName(priority), fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

/**
 * 新增/编辑任务。
 * 截止时间故意用两个文本框而不是日历弹窗：填星期几的 23:59 是最常见的操作，
 * 旁边四个快捷按钮一次点击就够，比滚日历快。
 */
@Composable
internal fun TaskEditDialog(
    task: Task?,
    courses: List<Course>,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (Task) -> Unit,
    onDelete: ((Task) -> Unit)?,
) {
    val now = LocalDateTime.now()
    var title by remember { mutableStateOf(task?.title.orEmpty()) }
    var courseName by remember { mutableStateOf(task?.courseName.orEmpty()) }
    var estimate by remember { mutableStateOf((task?.estimateMinutes ?: settings.focusChunkMinutes).toString()) }
    var spent by remember { mutableStateOf((task?.spentMinutes ?: 0).toString()) }
    var date by remember { mutableStateOf(task?.deadlineTime?.toLocalDate()?.toString().orEmpty()) }
    var time by remember { mutableStateOf(task?.deadlineTime?.let { "%02d:%02d".format(it.hour, it.minute) }.orEmpty()) }
    var priority by remember { mutableStateOf(task?.priority ?: 1) }
    var notes by remember { mutableStateOf(task?.notes.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun applyQuick(daysAhead: Long, at: LocalDateTime = LocalDate.now().plusDays(daysAhead).atTime(23, 59)) {
        date = at.toLocalDate().toString()
        time = "%02d:%02d".format(at.hour, at.minute)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) "记一件事" else "修改任务") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("要做什么") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = estimate,
                    onValueChange = { estimate = it.filter { c -> c.isDigit() } },
                    label = { Text("大概需要多少分钟") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow {
                    listOf(30, 60, 120, 240).forEach { preset ->
                        TextButton(onClick = { estimate = preset.toString() }, modifier = Modifier.padding(end = 2.dp)) {
                            Text(if (preset < 60) "${preset}分" else "${preset / 60}小时", fontSize = 12.sp)
                        }
                    }
                }

                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("截止日期 yyyy-MM-dd（可空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text("截止时间 HH:mm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow {
                    TextButton(onClick = { applyQuick(0) }) { Text("今晚", fontSize = 12.sp) }
                    TextButton(onClick = { applyQuick(1) }) { Text("明晚", fontSize = 12.sp) }
                    TextButton(onClick = { applyQuick(2) }) { Text("后天晚", fontSize = 12.sp) }
                    TextButton(onClick = { applyQuick(7) }) { Text("一周后", fontSize = 12.sp) }
                }

                if (courses.isNotEmpty()) {
                    Text("科目（可空，用来对上课程空档）", fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(
                            selected = courseName.isBlank(),
                            onClick = { courseName = "" },
                            label = { Text("私人") },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        courses.map { it.name }.distinct().forEach { name ->
                            FilterChip(
                                selected = courseName == name,
                                onClick = { courseName = name },
                                label = { Text(name, fontSize = 11.sp) },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                }

                Text("优先级", fontSize = 12.sp)
                FlowRow {
                    (0..2).forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(priorityName(p)) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }

                OutlinedTextField(
                    value = spent,
                    onValueChange = { spent = it.filter { c -> c.isDigit() } },
                    label = { Text("已经投入多少分钟（可改）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("备注（可空）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = Color(0xFFC62828), fontSize = 12.sp) }
                Text("留空截止时间也能记，但不会排进时间表，也不会提醒。", fontSize = 11.sp, color = Color(0xFF546E7A))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val minutes = estimate.toIntOrNull() ?: 0
                val spentMinutes = spent.toIntOrNull() ?: 0
                // 解析失败要单独报错，所以这里保持可空，下面再落地成字符串
                val parsedDeadline: String? = if (date.isBlank()) "" else {
                    val t = if (time.isBlank()) "23:59" else time.trim()
                    parseDateTime("${date.trim()}T$t")?.let { formatDateTime(it) }
                }
                val deadline = parsedDeadline ?: ""
                when {
                    title.isBlank() -> error = "先写清楚要做什么"
                    minutes <= 0 -> error = "预估时间要大于 0 分钟"
                    date.isNotBlank() && parsedDeadline == null -> error = "截止时间填成 2026-10-20 / 23:59 这种格式"
                    else -> onSave(
                        Task(
                            id = task?.id ?: UUID.randomUUID().toString(),
                            title = title.trim(),
                            courseName = courseName.trim(),
                            estimateMinutes = minutes,
                            spentMinutes = spentMinutes,
                            deadline = deadline,
                            priority = priority,
                            notes = notes.trim(),
                            done = task?.done ?: false,
                            createdAt = task?.createdAt?.takeIf { it.isNotBlank() } ?: formatDateTime(now),
                            chunkMinutes = task?.chunkMinutes ?: -1,
                        )
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null && task != null) {
                    TextButton(onClick = { onDelete(task) }) { Text("删除", color = Color(0xFFC62828)) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
