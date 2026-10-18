package com.balance.deadlinereminder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.balance.deadlinereminder.data.AppSettings
import com.balance.deadlinereminder.data.Course
import com.balance.deadlinereminder.data.Task
import com.balance.deadlinereminder.data.Urgency
import com.balance.deadlinereminder.data.countdownLabel
import com.balance.deadlinereminder.data.deadlineLabel
import com.balance.deadlinereminder.data.formatDateTime
import com.balance.deadlinereminder.data.humanDuration
import com.balance.deadlinereminder.data.parseDateTime
import com.balance.deadlinereminder.data.priorityName
import com.balance.deadlinereminder.data.urgencyFor
import com.balance.deadlinereminder.domain.Feasibility
import com.balance.deadlinereminder.domain.Plan
import com.balance.deadlinereminder.domain.WeekCalc
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 已完成任务的删除按钮与标题操作行共享此中心线，不依赖卡片展开后的整体高度。 */
private val TaskActionCenterLine = HorizontalAlignmentLine(::minOf)

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
    onDone: (String, Boolean) -> Unit,
    onAddCourse: () -> Unit,
    compact: Boolean = false,
) {
    val now = LocalDateTime.now()
    val todayCourses = remember(settings, courses, now.toLocalDate()) {
        WeekCalc.coursesOn(settings, courses, now.toLocalDate())
    }
    var editing by remember { mutableStateOf<Task?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showTodayCourses by remember { mutableStateOf(true) }
    var showTasks by remember { mutableStateOf(true) }
    var showActiveTasks by remember { mutableStateOf(true) }
    var showCompleted by remember { mutableStateOf(false) }
    val sortedTasks = tasks.sortedWith(
        compareBy<Task>(
            {
                plan?.planFor(it.id)?.feasibility in setOf(
                    Feasibility.NEEDS_ESTIMATE,
                    Feasibility.NOT_STARTED,
                    Feasibility.BLOCKED,
                )
            },
            { it.deadlineTime == null },
            {
                val taskPlan = plan?.planFor(it.id)
                val inactive = taskPlan?.feasibility in setOf(
                    Feasibility.NEEDS_ESTIMATE,
                    Feasibility.NOT_STARTED,
                    Feasibility.BLOCKED,
                )
                if (inactive) 0 else -(taskPlan?.let { p -> taskUrgency(it, p.availableMinutes).ordinal } ?: Urgency.ATTENTION.ordinal)
            },
            { -it.priority },
            { it.deadlineTime ?: LocalDateTime.MAX },
        )
    )
    val activeTasks = sortedTasks.filterNot { it.done }
    val completedTasks = sortedTasks.filter { it.done }

    val screenModifier = if (compact) {
        Modifier.fillMaxWidth().padding(horizontal = 6.dp)
    } else {
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)
    }
    Column(screenModifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("任务", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { showAddMenu = true }) { Text("＋ 新增") }
                DropdownMenu(
                    expanded = showAddMenu,
                    onDismissRequest = { showAddMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("新增任务") },
                        onClick = { showAddMenu = false; creating = true },
                    )
                    DropdownMenuItem(
                        text = { Text("新增课程") },
                        onClick = { showAddMenu = false; onAddCourse() },
                    )
                }
            }
        }
        if (!compact) {
            TextButton(onClick = { showTodayCourses = !showTodayCourses }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("今日课程 · ${now.toLocalDate()} · ${todayCourses.size} 门", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(if (showTodayCourses) "▲" else "▼")
                }
            }
            if (showTodayCourses) Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (todayCourses.isEmpty()) {
                        Text("今天没有课程", fontSize = 13.sp)
                    } else {
                        todayCourses.forEach { occurrence ->
                            Text(
                                "${occurrence.start.format(DateTimeFormatter.ofPattern("HH:mm"))}-${occurrence.end.format(DateTimeFormatter.ofPattern("HH:mm"))}  ${occurrence.course.name}",
                                fontSize = 13.sp,
                            )
                            Text(
                                courseSubtitle(occurrence.course),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        TextButton(onClick = { showTasks = !showTasks }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "全部任务 · 进行中 ${tasks.count { !it.done }} · 已完成 ${tasks.count { it.done }}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(if (showTasks) "▲" else "▼")
            }
        }

        if (showTasks) {
            TextButton(onClick = { showActiveTasks = !showActiveTasks }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("进行中任务（${activeTasks.size}）", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(if (showActiveTasks) "▲" else "▼")
                }
            }
        }

        if (showTasks && showActiveTasks && activeTasks.isEmpty()) {
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("没有进行中的任务。", fontWeight = FontWeight.Medium)
                    Text("点右上角的「＋ 新增」添加任务或课程。", fontSize = 13.sp)
                }
            }
        }

        if (showTasks && showActiveTasks) activeTasks.forEach { task ->
            TaskCard(
                task = task,
                planFor = plan?.planFor(task.id),
                allTasks = tasks,
                now = now,
                onDone = onDone,
                onEdit = { editing = task },
            )
        }

        if (showTasks && completedTasks.isNotEmpty()) {
            TextButton(onClick = { showCompleted = !showCompleted }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已完成任务（${completedTasks.size}）", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(if (showCompleted) "▲" else "▼")
                }
            }
            if (showCompleted) {
                completedTasks.forEach { task ->
                    CompletedTaskSwipe(
                        task = task,
                        planFor = plan?.planFor(task.id),
                        allTasks = tasks,
                        now = now,
                        onDone = onDone,
                        onEdit = { editing = task },
                        onDelete = { onDelete(task.id) },
                    )
                }
            }
        }
        if (!compact) Spacer(Modifier.height(60.dp))
    }

    if (creating) {
        TaskEditDialog(
            task = null,
            tasks = tasks,
            settings = settings,
            onDismiss = { creating = false },
            onSave = { onSave(it); creating = false },
            onDelete = null,
        )
    }
    editing?.let { task ->
        TaskEditDialog(
            task = task,
            tasks = tasks,
            settings = settings,
            onDismiss = { editing = null },
            onSave = { onSave(it); editing = null },
            onDelete = { onDelete(task.id); editing = null },
        )
    }
}

@Composable
private fun CompletedTaskSwipe(
    task: Task,
    planFor: com.balance.deadlinereminder.domain.TaskPlan?,
    allTasks: List<Task>,
    now: LocalDateTime,
    onDone: (String, Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val revealWidth = with(LocalDensity.current) { 96.dp.toPx() } // 只露出一个操作按钮，不把整张卡片滑走
    var offsetX by remember(task.id) { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta ->
        offsetX = (offsetX + delta).coerceIn(-revealWidth, 0f)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStopped = {
                    offsetX = if (offsetX <= -revealWidth / 2f) -revealWidth else 0f
                },
            ),
    ) {
        CompletedTaskSwipeLayout(
            deleteButton = {
                if (offsetX < 0f) {
                    Button(
                        onClick = onDelete,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text("删除", fontWeight = FontWeight.Bold)
                    }
                }
            },
            taskCard = {
                Box(Modifier.offset { IntOffset(offsetX.toInt(), 0) }) {
                    TaskCard(task, planFor, allTasks, now, onDone, onEdit)
                }
            },
        )
    }
}

@Composable
private fun CompletedTaskSwipeLayout(
    deleteButton: @Composable () -> Unit,
    taskCard: @Composable () -> Unit,
) {
    Layout(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
        content = {
            Box(contentAlignment = Alignment.CenterEnd, content = { deleteButton() })
            taskCard()
        },
    ) { measurables, constraints ->
        val card = measurables[1].measure(constraints)
        val button = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val actionCenter = card[TaskActionCenterLine].takeIf { it != AlignmentLine.Unspecified } ?: card.height / 2
        layout(card.width, card.height) {
            button.placeRelative(card.width - button.width, actionCenter - button.height / 2)
            card.placeRelative(0, 0)
        }
    }
}

@Composable
private fun TaskCard(
    task: Task,
    planFor: com.balance.deadlinereminder.domain.TaskPlan?,
    allTasks: List<Task>,
    now: LocalDateTime,
    onDone: (String, Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val stamp = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }
    val overdue = task.isOverdue(now)
    val displayState = taskDisplayState(task, planFor)
    val riskColor = taskRiskColor(displayState)
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = riskColor.copy(alpha = if (task.done) 0.06f else 0.14f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(
                        width = placeable.width,
                        height = placeable.height,
                        alignmentLines = mapOf(TaskActionCenterLine to placeable.height / 2),
                    ) { placeable.placeRelative(0, 0) }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
                    Text(task.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PriorityTag(task.priority)
                        Text(
                            "${taskDisplayLabel(displayState)}${if (task.daily) " · 每日" else ""}",
                            color = taskDisplayColor(displayState),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (task.done) {
                    TextButton(onClick = { onDone(task.id, false) }) { Text("撤销完成") }
                    TextButton(onClick = onEdit) { Text("编辑") }
                } else {
                    TextButton(onClick = { onDone(task.id, true) }) { Text("完成") }
                    TextButton(onClick = onEdit) { Text("编辑") }
                }
            }

            if (expanded) {
                Text(
                    "截止 ${deadlineLabel(task.deadlineTime)} · ${countdownLabel(task.deadlineTime, now)}",
                    fontSize = 12.sp,
                    color = if (overdue) Color(0xFFC62828) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (task.courseName.isNotBlank()) Text("科目：${task.courseName}", fontSize = 12.sp)
                task.earliestStartTime?.let { Text("最早开始：${it.format(stamp)}", fontSize = 12.sp) }
                if (task.dependencyIds.isNotEmpty()) {
                    val dependencyNames = task.dependencyIds.map { id -> allTasks.firstOrNull { it.id == id }?.title ?: "已删除任务" }
                    Text("前置任务：${dependencyNames.joinToString("、")}", fontSize = 12.sp)
                }
                if (task.estimateUnknown) {
                    Text("预计耗时待估，暂不生成工作时段", fontSize = 12.sp)
                } else {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp),
                    )
                    Text(
                        "已投入 ${humanDuration(task.spentMinutes.toLong())} / 预估 ${humanDuration(task.estimateMinutes.toLong())} · 还剩 ${humanDuration(task.remainingMinutes.toLong())}",
                        fontSize = 12.sp,
                    )
                }
                val suggestion = when {
                    task.done -> "已完成"
                    task.estimateUnknown -> "先补充预计耗时，再自动拆分长期任务的工作时段"
                    task.remainingMinutes == 0 -> "工作量已登记满，可以标完成了"
                    planFor?.firstBlock != null ->
                        "建议 ${planFor.firstBlock!!.start.format(stamp)} 开始 · 共 ${planFor.blocks.size} 段 / ${humanDuration(planFor.plannedMinutes.toLong())}"

                    planFor?.feasibility == Feasibility.NOT_STARTED -> "未到最早开始时间，暂不参与安排"
                    planFor?.feasibility == Feasibility.BLOCKED -> "等待前置任务完成后再安排"
                    task.deadlineTime == null -> "无截止时间，将在未来 14 天滚动安排"
                    else -> "当前空档排不下，缺 ${humanDuration((planFor?.shortfallMinutes ?: task.remainingMinutes).toLong())}"
                }
                Text(suggestion, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                planFor?.let { p ->
                    when (p.feasibility) {
                        Feasibility.IMPOSSIBLE, Feasibility.OVERDUE ->
                            Text("⚠ 来不及：${if (p.feasibility == Feasibility.OVERDUE) "已过期" else "空档不足"}", fontSize = 12.sp, color = Color(0xFFC62828))
                        Feasibility.TIGHT ->
                            Text("⚠ 偏紧：预计 ${p.finishAt?.format(stamp)} 做完，离截止只剩 ${humanDuration((p.slackMinutes ?: 0).toLong())}", fontSize = 12.sp, color = Color(0xFFEF6C00))
                        Feasibility.COMFORTABLE ->
                            Text("预计 ${p.finishAt?.format(stamp)} 做完，比截止早 ${humanDuration((p.slackMinutes ?: 0).toLong())}", fontSize = 12.sp, color = Color(0xFF2E7D32))
                        Feasibility.NO_DEADLINE ->
                            Text("已排入滚动计划，预计 ${p.finishAt?.format(stamp) ?: "暂未排完"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Feasibility.NOT_STARTED ->
                            Text("等待最早开始时间到达", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Feasibility.BLOCKED ->
                            Text("等待 ${p.blockedByIds.size} 个前置任务完成", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Feasibility.NEEDS_ESTIMATE ->
                            Text("耗时未知，截止提醒保留，但不安排工作时段", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Unit
                    }
                }
                if (task.notes.isNotBlank()) Text("备注：${task.notes}", fontSize = 12.sp)
            }

        }
    }
}

/** 卡片只做轻量风险提示；红色严格保留给“高重要且紧急”。 */
@Composable
private fun taskRiskColor(state: TaskDisplayState): Color = when (state) {
    TaskDisplayState.CRITICAL -> MaterialTheme.colorScheme.error
    TaskDisplayState.URGENT -> Color(0xFFEF6C00)
    else -> MaterialTheme.colorScheme.surfaceVariant
}

private fun taskUrgency(task: Task, availableMinutes: Int): Urgency =
    urgencyFor(availableMinutes, task)

private fun taskDisplayLabel(state: TaskDisplayState): String = when (state) {
    TaskDisplayState.DONE -> "已完成"
    TaskDisplayState.NEEDS_ESTIMATE -> "待估时"
    TaskDisplayState.NOT_STARTED -> "未到开始时间"
    TaskDisplayState.BLOCKED -> "等待前置任务"
    TaskDisplayState.RELAXED -> "不紧急"
    TaskDisplayState.ATTENTION -> "需关注"
    TaskDisplayState.URGENT -> "紧急"
    TaskDisplayState.CRITICAL -> "紧急且重要"
}

@Composable
private fun taskDisplayColor(state: TaskDisplayState): Color = when (state) {
    TaskDisplayState.CRITICAL -> MaterialTheme.colorScheme.error
    TaskDisplayState.URGENT -> Color(0xFFEF6C00)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
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
    tasks: List<Task>,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (Task) -> Unit,
    onDelete: ((Task) -> Unit)?,
) {
    val now = LocalDateTime.now()
    var title by remember { mutableStateOf(task?.title.orEmpty()) }
    var estimate by remember { mutableStateOf((task?.estimateMinutes ?: settings.focusChunkMinutes).toString()) }
    var estimateUnknown by remember { mutableStateOf(task?.estimateUnknown ?: false) }
    var daily by remember { mutableStateOf(task?.daily ?: false) }
    var priority by remember { mutableIntStateOf(task?.priority ?: 1) }
    var date by remember { mutableStateOf(task?.deadlineTime?.toLocalDate()?.toString().orEmpty()) }
    var time by remember { mutableStateOf(task?.deadlineTime?.let { "%02d:%02d".format(it.hour, it.minute) }.orEmpty()) }
    var earliestDate by remember { mutableStateOf(task?.earliestStartTime?.toLocalDate()?.toString().orEmpty()) }
    var earliestTime by remember { mutableStateOf(task?.earliestStartTime?.let { "%02d:%02d".format(it.hour, it.minute) }.orEmpty()) }
    var dependencyIds by remember { mutableStateOf(task?.dependencyIds.orEmpty().toSet()) }
    var notes by remember { mutableStateOf(task?.notes.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showEarliestDatePicker by remember { mutableStateOf(false) }
    var showEarliestTimePicker by remember { mutableStateOf(false) }
    val dependencyCandidates = tasks.filter { it.id != task?.id && !it.daily }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) "记一件事" else "修改任务") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("任务名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("重要程度", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..2).forEach { value ->
                        TextButton(onClick = { priority = value }) {
                            Text(if (priority == value) "✓ ${priorityName(value)}" else priorityName(value))
                        }
                    }
                }
                OutlinedTextField(
                    value = estimate,
                    onValueChange = { estimate = it.filter { c -> c.isDigit() } },
                    label = { Text("预估分钟") },
                    singleLine = true,
                    enabled = !estimateUnknown,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("预计耗时未知")
                        Text("保留截止提醒，估时前不安排工作时段", fontSize = 11.sp)
                    }
                    Switch(checked = estimateUnknown, onCheckedChange = { estimateUnknown = it })
                }
                if (task == null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("每日任务")
                        Text("每天重新出现，完成状态只保留到当天", fontSize = 11.sp)
                    }
                    Switch(checked = daily, onCheckedChange = { daily = it })
                }

                if (!daily) Card(Modifier.fillMaxWidth().clickable { showDatePicker = true }) {
                    Column(Modifier.padding(12.dp)) {
                        Text("截止日期与时间", fontSize = 12.sp)
                        Text(
                            if (date.isBlank()) "点击选择日历" else "$date  ${time.ifBlank { "23:59" }}",
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Card(Modifier.fillMaxWidth().clickable { showEarliestDatePicker = true }) {
                    Column(Modifier.padding(12.dp)) {
                        Text("最早开始时间（可空）", fontSize = 12.sp)
                        Text(
                            if (earliestDate.isBlank()) "现在即可开始" else "$earliestDate  ${earliestTime.ifBlank { "08:00" }}",
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                if (dependencyCandidates.isNotEmpty()) {
                    Text("前置任务（完成后解锁）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    dependencyCandidates.forEach { candidate ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(candidate.title, modifier = Modifier.weight(1f), fontSize = 13.sp)
                            Switch(
                                checked = candidate.id in dependencyIds,
                                onCheckedChange = { checked ->
                                    dependencyIds = if (checked) dependencyIds + candidate.id else dependencyIds - candidate.id
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("内容（可空）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = Color(0xFFC62828), fontSize = 12.sp) }
                Text("留空截止时间仍会排入未来 14 天计划并按时段提醒，但不会发送 DDL 到期提醒。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val minutes = estimate.toIntOrNull() ?: 0
                // 解析失败要单独报错，所以这里保持可空，下面再落地成字符串
                val parsedDeadline: String? = if (daily || date.isBlank()) "" else {
                    val t = if (time.isBlank()) "23:59" else time.trim()
                    parseDateTime("${date.trim()}T$t")?.let { formatDateTime(it) }
                }
                val deadline = parsedDeadline ?: ""
                val parsedEarliestStart: String? = if (earliestDate.isBlank()) "" else {
                    val t = if (earliestTime.isBlank()) "08:00" else earliestTime.trim()
                    parseDateTime("${earliestDate.trim()}T$t")?.let { formatDateTime(it) }
                }
                val createsCycle = task != null && dependencyIds.any { dependencyId ->
                    dependsOn(tasks, dependencyId, task.id)
                }
                when {
                    title.isBlank() -> error = "先写清楚要做什么"
                    !estimateUnknown && minutes <= 0 -> error = "预估分钟必须大于 0"
                    !daily && date.isNotBlank() && parsedDeadline == null -> error = "截止时间填成 2026-10-20 / 23:59 这种格式"
                    earliestDate.isNotBlank() && parsedEarliestStart == null -> error = "最早开始时间格式不正确"
                    createsCycle -> error = "前置任务不能形成循环依赖"
                    else -> onSave(
                        Task(
                            id = task?.id ?: UUID.randomUUID().toString(),
                            title = title.trim(),
                            courseName = task?.courseName.orEmpty(),
                            estimateMinutes = if (estimateUnknown) task?.estimateMinutes ?: settings.focusChunkMinutes else minutes,
                            spentMinutes = task?.spentMinutes ?: 0,
                            deadline = deadline,
                            priority = priority,
                            notes = notes.trim(),
                            done = task?.done ?: false,
                            createdAt = task?.createdAt?.takeIf { it.isNotBlank() } ?: formatDateTime(now),
                            chunkMinutes = task?.chunkMinutes ?: -1,
                            daily = daily,
                            completedOn = if (daily) task?.completedOn.orEmpty() else "",
                            earliestStart = parsedEarliestStart.orEmpty(),
                            dependencyIds = dependencyIds.toList(),
                            estimateUnknown = estimateUnknown,
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
    if (showDatePicker) {
        val selectedMillis = runCatching {
            LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = selectedMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                        showDatePicker = false
                        showTimePicker = true
                    }
                }) { Text("下一步：选择时间") }
            },
            dismissButton = {
                TextButton(onClick = { date = ""; time = ""; showDatePicker = false }) { Text("清除截止时间") }
            },
        ) { DatePicker(state = pickerState) }
    }
    if (showTimePicker) {
        WheelTimeDialog(
            initial = time.ifBlank { "23:55" },
            onDismiss = { showTimePicker = false },
            onConfirm = { selected -> time = selected; showTimePicker = false },
        )
    }
    if (showEarliestDatePicker) {
        val selectedMillis = runCatching {
            LocalDate.parse(earliestDate).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = selectedMillis)
        DatePickerDialog(
            onDismissRequest = { showEarliestDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        earliestDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                        showEarliestDatePicker = false
                        showEarliestTimePicker = true
                    }
                }) { Text("下一步：选择时间") }
            },
            dismissButton = {
                TextButton(onClick = { earliestDate = ""; earliestTime = ""; showEarliestDatePicker = false }) { Text("清除") }
            },
        ) { DatePicker(state = pickerState) }
    }
    if (showEarliestTimePicker) {
        WheelTimeDialog(
            initial = earliestTime.ifBlank { "08:00" },
            onDismiss = { showEarliestTimePicker = false },
            onConfirm = { selected -> earliestTime = selected; showEarliestTimePicker = false },
        )
    }
}

/** 从候选前置任务向上追溯，确认加入新边后不会回到当前任务。 */
private fun dependsOn(tasks: List<Task>, fromId: String, targetId: String): Boolean {
    val tasksById = tasks.associateBy(Task::id)
    val pending = ArrayDeque<String>()
    val visited = mutableSetOf<String>()
    pending.add(fromId)
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        if (id == targetId) return true
        if (visited.add(id)) tasksById[id]?.dependencyIds?.forEach(pending::addLast)
    }
    return false
}

/** 小时与分钟两个可滚动列表，分钟按 5 分钟步进。 */
@Composable
internal fun WheelTimeDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val parts = initial.split(':')
    var hour by remember { mutableIntStateOf(parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 23) }
    var minute by remember { mutableIntStateOf((parts.getOrNull(1)?.toIntOrNull() ?: 55).coerceIn(0, 59) / 5 * 5) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("滑动选择截止时间") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberWheel((0..23).toList(), hour, "时", Modifier.weight(1f)) { hour = it }
                NumberWheel((0..55 step 5).toList(), minute, "分", Modifier.weight(1f)) { minute = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm("%02d:%02d".format(hour, minute)) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回日期") } },
    )
}

@Composable
internal fun NumberWheel(values: List<Int>, selected: Int, suffix: String, modifier: Modifier, onSelect: (Int) -> Unit) {
    val initialIndex = values.indexOf(selected).coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = state)
    LaunchedEffect(state, values) {
        snapshotFlow {
            val center = (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2
            state.layoutInfo.visibleItemsInfo
                .minByOrNull { item -> kotlin.math.abs(item.offset + item.size / 2 - center) }
                ?.index
        }.collect { index ->
            index?.let { values.getOrNull(it) }?.let(onSelect)
        }
    }
    Column(modifier) {
        Text(suffix, modifier = Modifier.align(Alignment.CenterHorizontally), fontSize = 12.sp)
        LazyColumn(
            state = state,
            modifier = Modifier.height(180.dp),
            contentPadding = PaddingValues(vertical = 66.dp),
            flingBehavior = flingBehavior,
        ) {
            items(values) { value ->
                TextButton(onClick = { onSelect(value) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(
                        "%02d".format(value),
                        fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (value == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
