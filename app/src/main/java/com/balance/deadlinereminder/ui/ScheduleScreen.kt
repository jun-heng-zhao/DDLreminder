package com.balance.deadlinereminder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.app.AlarmManager
import android.os.Build
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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.balance.deadlinereminder.data.AppSettings
import com.balance.deadlinereminder.data.Course
import com.balance.deadlinereminder.data.DayKind
import com.balance.deadlinereminder.data.Task
import com.balance.deadlinereminder.data.countdownLabel
import com.balance.deadlinereminder.data.dayName
import com.balance.deadlinereminder.data.humanDuration
import com.balance.deadlinereminder.domain.Feasibility
import com.balance.deadlinereminder.domain.Plan
import com.balance.deadlinereminder.domain.WeekCalc
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val PALETTE = listOf(
    Color(0xFFBBDEFB), Color(0xFFC8E6C9), Color(0xFFFFE0B2), Color(0xFFF8BBD0),
    Color(0xFFD1C4E9), Color(0xFFB2EBF2), Color(0xFFFFF9C4), Color(0xFFD7CCC8),
)

@Composable
fun ScheduleScreen(
    courses: List<Course>,
    settings: AppSettings,
    week: Int,
    currentWeek: Int,
    onWeekOffset: (Int) -> Unit,
    onBackToNow: () -> Unit,
    onAddAt: (Int, Int) -> Unit,
    onEdit: (Course) -> Unit,
    today: LocalDate = LocalDate.now(),
    tasks: List<Task> = emptyList(),
    plan: Plan? = null,
    onRegisterProgress: (String, Int) -> Unit = { _, _ -> },
    onTaskSave: (Task) -> Unit = {},
    onTaskDelete: (String) -> Unit = {},
    onTaskProgress: (String, Int) -> Unit = { _, _ -> },
    onTaskDone: (String, Boolean) -> Unit = { _, _ -> },
) {
    val periods = settings.periods
    val context = LocalContext.current
    val notificationsOff = !NotificationManagerCompat.from(context).areNotificationsEnabled()
    val exactAlarmOff = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !(context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false)
    val todayCourses = remember(settings, courses, today) { WeekCalc.coursesOn(settings, courses, today) } // 不随浏览周切换
    val configured = WeekCalc.termMonday(settings) != null // 有效校准无需先填开学日
    val todayOverride = remember(settings, today) { WeekCalc.overrideOf(settings, today) } // 放假与待安排分别提示
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm") } // 时间固定两位，便于扫读
    val weekCourses = remember(courses, week) { courses.filter { WeekCalc.weekMatches(it, week) } } // 仅相关输入变化时过滤
    var viewMode by rememberSaveable { mutableStateOf("今日") }
    val todayScroll = rememberScrollState() // 与周课表分别保留浏览位置
    val weekScroll = rememberScrollState()
    var creatingTask by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<Task?>(null) }
    data class TimelineItem(
        val start: LocalDateTime,
        val end: LocalDateTime,
        val title: String,
        val subtitle: String,
        val taskId: String? = null,
        val course: Course? = null,
        val minutes: Int = 0,
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("任务与课表", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            if (configured) "第 $currentWeek 周 · 课程和要做的事排在一起，照着时间线走吧～"
            else "教学周还没配置，任务仍可记录；课程请到设置里导入或手动添加。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = viewMode == "今日", onClick = { viewMode = "今日" }, label = { Text("今日") }, modifier = Modifier.weight(1f))
            FilterChip(selected = viewMode == "周课表", onClick = { viewMode = "周课表" }, label = { Text("周课表") }, modifier = Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).verticalScroll(if (viewMode == "今日") todayScroll else weekScroll)) {
        if (viewMode == "今日") {
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("今日安排 · $today ${dayName(today.dayOfWeek.value)}", style = MaterialTheme.typography.titleMedium)
                    when {
                        todayOverride?.kind == DayKind.HOLIDAY -> Text("今天放假，安心补充能量～${todayOverride.note.takeIf { it.isNotBlank() }?.let { "\n备注：$it" }.orEmpty()}")
                        WeekCalc.needsArrangement(settings, today) -> Text("今天是调休日，请到日历指定上哪天的课；安排前不会提醒。")
                        todayCourses.isEmpty() && plan?.blocksOn(today).orEmpty().isEmpty() -> Text(if (courses.isEmpty()) "还没有课程，去设置导入或点下方新增单节课。" else "今天没有课程或已排任务，自由时间解锁～")
                        else -> {
                            val timeline = buildList {
                                todayCourses.forEach { occurrence ->
                                    add(TimelineItem(occurrence.start, occurrence.end, occurrence.course.name, "课程 · ${occurrence.course.location.ifBlank { "地点待补充" }}", course = occurrence.course))
                                }
                                plan?.blocksOn(today).orEmpty().forEach { block ->
                                    add(TimelineItem(block.start, block.end, block.title, "任务 · ${block.minutes} 分钟", taskId = block.taskId, minutes = block.minutes))
                                }
                            }.sortedBy { it.start }
                            timeline.forEach { item ->
                                Card(Modifier.fillMaxWidth().clickable {
                                    item.course?.let(onEdit)
                                    item.taskId?.let { id -> editingTask = tasks.firstOrNull { it.id == id } }
                                }) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text("${item.start.format(timeFormat)}–${item.end.format(timeFormat)}  ${item.title}", fontWeight = FontWeight.Bold)
                                        Text(item.subtitle, fontSize = 12.sp)
                                        item.taskId?.let { id ->
                                            FlowRow {
                                                TextButton(onClick = { onTaskProgress(id, item.minutes) }) { Text("登记 ${item.minutes} 分钟") }
                                                TextButton(onClick = { onTaskDone(id, true) }) { Text("完成") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val unscheduled = tasks.filter { !it.done && (it.deadlineTime == null || plan?.planFor(it.id)?.feasibility == Feasibility.IMPOSSIBLE || plan?.planFor(it.id)?.feasibility == Feasibility.OVERDUE) }
            if (unscheduled.isNotEmpty()) {
                Text("待安排 / 需要留意", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                unscheduled.take(5).forEach { task ->
                    TextButton(onClick = { editingTask = task }) { Text("${task.title} · ${if (task.deadlineTime == null) "未设截止时间" else "请检查排程"}") }
                }
            }
            FlowRow {
                TextButton(onClick = { creatingTask = true }) { Text("＋ 新增任务") }
                TextButton(onClick = { onAddAt(today.dayOfWeek.value, 1) }) { Text("＋ 新增单节课") }
            }
        }
        if (notificationsOff || exactAlarmOff) {
            Card(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text("提醒现在收不到", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (notificationsOff) Text("· 通知权限没开，去「设置」页打开", fontSize = 12.sp)
                    if (exactAlarmOff) Text("· 精确闹钟没允许，去「设置」页打开", fontSize = 12.sp)
                }
            }
        }
        if (viewMode == "周课表") Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onWeekOffset(-1) }) { Text("◀ 上一周") }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (week >= 1) "第 $week 周" else "第 ? 周",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onWeekOffset(1) }) { Text("下一周 ▶") }
        }

        if (viewMode == "周课表" && !configured) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("还没配置教学周", fontWeight = FontWeight.Bold)
                    Text("去「设置」填写开学日期或校准当前周，课表与提醒会使用同一基准。", fontSize = 13.sp)
                }
            }
        } else if (viewMode == "周课表" && week != currentWeek) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("正在看第 $week 周（本周是第 $currentWeek 周）", fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBackToNow) { Text("回到本周") }
            }
        }

        if (viewMode == "周课表") {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("本周课程", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onAddAt(1, 1) }) { Text("＋ 加课") }
            }
            var anyThisWeek = false
            (1..7).forEach { day ->
                val dayCourses = weekCourses
                    .filter { it.dayOfWeek == day }
                    .sortedBy { it.startPeriod }
                if (dayCourses.isEmpty()) return@forEach
                anyThisWeek = true
                Text(
                    dayName(day) + "（${dayCourses.size} 门）",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
                dayCourses.forEach { course ->
                    // 每一节自己的上下课时间分别列出来，中间的课间不算进课程时间
                    val slots = (course.startPeriod..course.endPeriod).mapNotNull { p ->
                        periods.getOrNull(p - 1)?.let { "${it.startLabel}-${it.endLabel}" }
                    }
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onEdit(course) }
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .width(6.dp)
                                    .height(38.dp)
                                    .background(PALETTE[course.colorIndex.mod(PALETTE.size)])
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(course.name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                                Text(
                                    slots.joinToString("  ") + " · 第 ${course.startPeriod}" +
                                        (if (course.endPeriod != course.startPeriod) "-${course.endPeriod}" else "") +
                                        " 节",
                                    fontSize = 12.sp,
                                )
                                Text(courseSubtitle(course), fontSize = 11.sp)
                            }
                            Text("修改", fontSize = 12.sp)
                        }
                    }
                }
            }
            if (!anyThisWeek) {
                Text("第 $week 周没有课", fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            }
        }

        if (viewMode == "周课表") {
            val countThisWeek = weekCourses.size
            Text(
                text = "第 $week 周共 $countThisWeek 门课 · 点课可改",
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = { onAddAt(1, 1) }) { Text("＋ 新增一节其他时间的课") }
        }
        }
    }
    if (creatingTask || editingTask != null) {
        TaskEditDialog(
            task = editingTask,
            courses = courses,
            settings = settings,
            onDismiss = { creatingTask = false; editingTask = null },
            onSave = { task ->
                onTaskSave(task)
                creatingTask = false
                editingTask = null
            },
            onDelete = { task ->
                onTaskDelete(task.id)
                creatingTask = false
                editingTask = null
            },
        )
    }
}

/**
 * 今日安排卡片：把排程引擎的结果摊开给用户看。
 * 左边是"今天几点做什么"，右边一行一件任务说清"按现在的时间表来不来得及"。
 */
@Composable
private fun TodayPlanCard(
    plan: Plan?,
    tasks: List<Task>,
    today: LocalDate,
    onOpenTasks: () -> Unit,
    onRegisterProgress: (String, Int) -> Unit,
) {
    val now = LocalDateTime.now()
    val clock = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val stamp = remember { DateTimeFormatter.ofPattern("M月d日 HH:mm") }
    val todayBlocks = plan?.blocksOn(today).orEmpty()
    val active = tasks.filter { !it.done && it.remainingMinutes > 0 }

    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "今日安排 · 已排 ${humanDuration(todayBlocks.sumOf { it.minutes }.toLong())}",
                style = MaterialTheme.typography.titleMedium,
            )
            when {
                active.isEmpty() -> Text(
                    "还没有要推进的事。去「任务」记一件：写清大概要多久、什么时候必须交，这里就会自动排出时间段。",
                    fontSize = 13.sp,
                )

                todayBlocks.isEmpty() -> Text("今天没有排定的任务时段，可以歇一歇。", fontSize = 13.sp)

                else -> todayBlocks.forEach { block ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${block.start.format(clock)}–${block.end.format(clock)}  ${block.title}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text("这一段 ${block.minutes} 分钟", fontSize = 11.sp)
                        }
                        TextButton(onClick = { onRegisterProgress(block.taskId, block.minutes) }) { Text("登记") }
                    }
                }
            }

            plan?.plans?.filter { it.task.remainingMinutes > 0 }?.take(3)?.forEach { taskPlan ->
                val line = when (taskPlan.feasibility) {
                    Feasibility.OVERDUE -> "「${taskPlan.task.title}」已过期，还差 ${humanDuration(taskPlan.remainingMinutes.toLong())}"
                    Feasibility.IMPOSSIBLE -> "「${taskPlan.task.title}」按现在的空档排不完，缺 ${humanDuration(taskPlan.shortfallMinutes.toLong())}"
                    Feasibility.TIGHT -> "「${taskPlan.task.title}」偏紧：预计 ${taskPlan.finishAt?.format(stamp)} 做完"
                    Feasibility.DONE -> "「${taskPlan.task.title}」已完成"
                    else -> "「${taskPlan.task.title}」预计 ${taskPlan.finishAt?.format(stamp)} 做完 · ${countdownLabel(taskPlan.deadline, now)}"
                }
                Text(line, fontSize = 12.sp, color = if (taskPlan.feasibility == Feasibility.IMPOSSIBLE || taskPlan.feasibility == Feasibility.OVERDUE) Color(0xFFC62828) else Color(0xFF37474F))
            }

            TextButton(onClick = onOpenTasks) { Text("＋ 记一件事 / 查看全部任务") }
        }
    }
}
