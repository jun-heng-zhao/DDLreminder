package com.balance.deadlinereminder.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.balance.deadlinereminder.data.AppSettings
import com.balance.deadlinereminder.data.Course
import com.balance.deadlinereminder.data.DAY_NAMES
import com.balance.deadlinereminder.data.DayKind
import com.balance.deadlinereminder.data.DayOverride
import com.balance.deadlinereminder.data.DeviceCalendar
import com.balance.deadlinereminder.data.dayName
import com.balance.deadlinereminder.domain.WeekCalc
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.YearMonth

private val COLOR_CLASS = Color(0xFF90CAF9)
private val COLOR_HOLIDAY = Color(0xFFEF9A9A)
private val COLOR_SWAP = Color(0xFFFFCC80)
private val COLOR_VACATION = Color(0xFFCFD8DC)

/**
 * 教学日历：看整月的上课情况，调整放假/调休/假期。
 *
 * 法定节假日走系统日历接口（CalendarContract）读取，读不到再退回内置的 2026 安排；
 * 学期区间（第一周周一到最后一周末）之外的日期按学生假期显示（寒暑假）。
 */
@Composable
fun CalendarScreen(
    settings: AppSettings,
    courses: List<Course>,
    active: Boolean = true,
    onChange: (AppSettings) -> Unit,
) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var editingDate by remember { mutableStateOf<LocalDate?>(null) }
    var holidayRows by remember { mutableStateOf<List<HolidayRow>?>(null) }
    var importMessage by remember { mutableStateOf("") }
    val termStartDate = WeekCalc.termMonday(settings)
    val termEndDate = termStartDate?.plusDays(settings.totalWeeks * 7L - 1)
    val queryFrom = termStartDate?.minusDays(14) ?: today.minusDays(30)
    val queryTo = termStartDate?.plusDays(settings.totalWeeks * 7L + 28) ?: today.plusDays(210)
    var phoneEvents by remember(queryFrom, queryTo) {
        mutableStateOf<List<DeviceCalendar.HolidayEvent>?>(null)
    }
    var calendarLoading by remember(queryFrom, queryTo) { mutableStateOf(false) }
    var calendarError by remember(queryFrom, queryTo) { mutableStateOf("") }
    var queryRequest by remember { mutableStateOf(0) }
    var importAfterQuery by remember(queryFrom, queryTo) { mutableStateOf(false) }
    // 提醒偏好、导入弹窗及加载状态变化都不影响整月课程，避免重复解析课程周次。
    val monthCourses = remember(month, courses, termStartDate, settings.periods, settings.overrides) {
        (1..month.lengthOfMonth()).associate { day ->
            val date = month.atDay(day)
            date to WeekCalc.coursesOn(settings, courses, date)
        }
    }

    fun applyHolidays(events: List<DeviceCalendar.HolidayEvent>) {
        if (events.isEmpty()) {
            importMessage = "手机日历中没有可导入的节假日"
            return
        }
        holidayRows = events.map { event ->
            HolidayRow(
                event = event,
                selected = true,
                kind = if (event.isWorkday) DayKind.SWAP else DayKind.HOLIDAY,
                // 标「班」的日子先不定上哪天的课，之后由用户安排
                swapToDayOfWeek = if (event.isWorkday) 0 else event.date.dayOfWeek.value,
                swapToWeek = if (event.isWorkday) -1
                else WeekCalc.teachingWeek(settings, event.date),
            )
        }
    }

    fun queryPhoneCalendar() {
        importAfterQuery = true
        queryRequest++
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) queryRequest++
        else {
            importAfterQuery = false
            importMessage = "未授予日历读取权限"
        }
    }

    fun ensurePermissionThenQuery() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) queryPhoneCalendar() else {
            importAfterQuery = true
            permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
        }
    }

    // 走系统日历接口：进页面查一次，有没标过的节假日就提示一键应用
    LaunchedEffect(active, queryFrom, queryTo, queryRequest) {
        if (!active) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            return@LaunchedEffect
        }
        calendarLoading = true
        calendarError = ""
        try {
            val events = DeviceCalendar.query(context, queryFrom, queryTo, refresh = importAfterQuery)
            phoneEvents = events
            if (importAfterQuery) applyHolidays(events)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            calendarError = "读取系统日历失败：${error.message ?: error.javaClass.simpleName}"
        } finally {
            calendarLoading = false
            importAfterQuery = false
        }
    }

    fun putOverride(date: LocalDate, kind: DayKind, swapTo: Int, note: String, swapWeek: Int) {
        val next = settings.overrides.filterNot { it.date == date.toString() }.toMutableList()
        if (kind != DayKind.NORMAL) {
            next += DayOverride(date.toString(), kind, swapTo, note, swapWeek)
        }
        onChange(settings.copy(overrides = next.sortedBy { it.date }))
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { month = month.minusMonths(1) }) { Text("◀") }
            Spacer(Modifier.weight(1f))
            Text(
                "${month.year} 年 ${month.monthValue} 月",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { month = month.plusMonths(1) }) { Text("▶") }
        }

        Row(Modifier.fillMaxWidth()) {
            DAY_NAMES.forEach { name ->
                Text(
                    name.removePrefix("周"),
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        val leading = month.atDay(1).dayOfWeek.value - 1
        val daysInMonth = month.lengthOfMonth()
        val rows = (leading + daysInMonth + 6) / 7

        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayNumber = row * 7 + col - leading + 1
                    if (dayNumber < 1 || dayNumber > daysInMonth) {
                        Box(Modifier.weight(1f).aspectRatio(0.85f))
                        continue
                    }
                    val date = month.atDay(dayNumber)
                    val override = WeekCalc.overrideOf(settings, date)
                    val classes = monthCourses[date].orEmpty()
                    val inTerm = termStartDate != null && termEndDate != null &&
                        !date.isBefore(termStartDate) && !date.isAfter(termEndDate)
                    val vacation = termStartDate != null && !inTerm

                    val bg = when {
                        override?.kind == DayKind.HOLIDAY -> COLOR_HOLIDAY
                        override?.kind == DayKind.SWAP -> COLOR_SWAP
                        vacation -> COLOR_VACATION
                        classes.isNotEmpty() -> COLOR_CLASS
                        else -> MaterialTheme.colorScheme.surface
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .aspectRatio(0.85f)
                            .padding(1.dp)
                            .background(bg, RoundedCornerShape(4.dp))
                            .border(
                                if (date == today) 2.5.dp else 0.5.dp,
                                if (date == today) Color(0xFF0D47A1) else Color(0xFF9E9E9E),
                                RoundedCornerShape(4.dp),
                            )
                            .clickable { editingDate = date }
                            .padding(3.dp)
                    ) {
                        Text("$dayNumber", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        when {
                            override?.kind == DayKind.HOLIDAY -> Text("放假", fontSize = 10.sp, color = Color(0xFF7F0000))
                            override?.kind == DayKind.SWAP && override.swapToDayOfWeek !in 1..7 ->
                                Text("待安排", fontSize = 9.sp, color = Color(0xFF7A3E00))
                            override?.kind == DayKind.SWAP -> Text(
                                "上" + dayName(override.swapToDayOfWeek) + "课",
                                fontSize = 9.sp,
                                color = Color(0xFF7A3E00),
                            )
                            classes.isNotEmpty() -> Text(
                                "有课",
                                fontSize = 9.sp,
                                color = Color(0xFF0D47A1),
                                maxLines = 1,
                            )
                            vacation -> Text(vacationLabel(date), fontSize = 9.sp, color = Color(0xFF37474F))
                        }
                    }
                }
            }
        }

        if (calendarLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            Text(if (phoneEvents == null) "正在读取系统日历…" else "正在刷新系统日历…", fontSize = 11.sp)
        }
        if (calendarError.isNotBlank()) {
            Text(calendarError, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
        FlowRow(Modifier.padding(top = 8.dp)) {
            Button(onClick = { ensurePermissionThenQuery() }, enabled = !calendarLoading) {
                Text(if (phoneEvents == null) "从手机日历导入" else "刷新并导入手机日历")
            }
        }
        if (importMessage.isNotBlank()) {
            Text(importMessage, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.heightIn(min = 8.dp))
    }

    editingDate?.takeIf { active }?.let { date ->
        val existing = WeekCalc.overrideOf(settings, date)
        val week = WeekCalc.teachingWeek(settings, date)
        DayEditDialog(
            date = date,
            currentWeek = week,
            existing = existing,
            onDismiss = { editingDate = null },
            onSave = { kind, swapTo, swapWeek, note ->
                putOverride(date, kind, swapTo, note, swapWeek)
                editingDate = null
            },
        )
    }

    holidayRows?.takeIf { active }?.let { rows ->
        HolidayImportDialog(
            rows = rows,
            onChangeRows = { holidayRows = it },
            onDismiss = { holidayRows = null },
            onApply = { chosen ->
                val next = settings.overrides.filterNot { o -> chosen.any { it.event.date.toString() == o.date } }
                    .toMutableList()
                chosen.forEach { row ->
                    next += DayOverride(
                        date = row.event.date.toString(),
                        kind = row.kind,
                        swapToDayOfWeek = row.swapToDayOfWeek,
                        note = row.event.title,
                        swapToWeek = row.swapToWeek,
                    )
                }
                onChange(settings.copy(overrides = next.sortedBy { it.date }))
                importMessage = "已导入 ${chosen.size} 天"
                holidayRows = null
            },
        )
    }
}

/** 学期之外的日期是学生假期，按月份给个说法。 */
private fun vacationLabel(date: LocalDate): String = when (date.monthValue) {
    1, 2 -> "寒假"
    7, 8 -> "暑假"
    else -> "假期"
}

private data class HolidayRow(
    val event: DeviceCalendar.HolidayEvent,
    val selected: Boolean,
    val kind: DayKind,
    val swapToDayOfWeek: Int,
    val swapToWeek: Int,
)

@Composable
private fun HolidayImportDialog(
    rows: List<HolidayRow>,
    onChangeRows: (List<HolidayRow>) -> Unit,
    onDismiss: () -> Unit,
    onApply: (List<HolidayRow>) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("节假日（${rows.size} 条）") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                rows.forEachIndexed { index, row ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = row.selected,
                                onCheckedChange = { checked ->
                                    onChangeRows(rows.toMutableList().also { it[index] = row.copy(selected = checked) })
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text("${row.event.date} ${row.event.title}", fontSize = 13.sp)
                                Text(row.event.calendarName, fontSize = 10.sp)
                            }
                            FilterChip(
                                selected = row.kind == DayKind.HOLIDAY,
                                onClick = {
                                    onChangeRows(rows.toMutableList().also { it[index] = row.copy(kind = DayKind.HOLIDAY) })
                                },
                                label = { Text("放假") },
                            )
                            Spacer(Modifier.width(4.dp))
                            FilterChip(
                                selected = row.kind == DayKind.SWAP,
                                onClick = {
                                    onChangeRows(rows.toMutableList().also { it[index] = row.copy(kind = DayKind.SWAP) })
                                },
                                label = { Text("上课") },
                            )
                        }
                        if (row.kind == DayKind.SWAP) {
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                FilterChip(
                                    selected = row.swapToDayOfWeek !in 1..7,
                                    onClick = {
                                        onChangeRows(rows.toMutableList().also { it[index] = row.copy(swapToDayOfWeek = 0) })
                                    },
                                    label = { Text("待安排") },
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                                (1..7).forEach { d ->
                                    FilterChip(
                                        selected = row.swapToDayOfWeek == d,
                                        onClick = {
                                            onChangeRows(rows.toMutableList().also { it[index] = row.copy(swapToDayOfWeek = d) })
                                        },
                                        label = { Text(dayName(d).removePrefix("周")) },
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                }
                                OutlinedTextField(
                                    value = if (row.swapToWeek > 0) row.swapToWeek.toString() else "",
                                    onValueChange = { text ->
                                        val w = text.filter { it.isDigit() }.toIntOrNull() ?: -1
                                        onChangeRows(rows.toMutableList().also { it[index] = row.copy(swapToWeek = w) })
                                    },
                                    label = { Text("第几周") },
                                    singleLine = true,
                                    modifier = Modifier.width(110.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(rows.filter { it.selected }) }) { Text("应用选中的") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DayEditDialog(
    date: LocalDate,
    currentWeek: Int,
    existing: DayOverride?,
    onDismiss: () -> Unit,
    onSave: (DayKind, Int, Int, String) -> Unit,
) {
    var kind by remember(date) { mutableStateOf(existing?.kind ?: DayKind.NORMAL) }
    var swapTo by remember(date) { mutableStateOf(existing?.swapToDayOfWeek ?: 0) }
    var swapWeek by remember(date) { mutableStateOf(existing?.swapToWeek ?: currentWeek) }
    var note by remember(date) { mutableStateOf(existing?.note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$date（${dayName(date.dayOfWeek.value)}，第 $currentWeek 周）") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(
                        selected = kind == DayKind.NORMAL,
                        onClick = { kind = DayKind.NORMAL },
                        label = { Text("正常上课") },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    FilterChip(
                        selected = kind == DayKind.HOLIDAY,
                        onClick = { kind = DayKind.HOLIDAY },
                        label = { Text("放假") },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    FilterChip(
                        selected = kind == DayKind.SWAP,
                        onClick = { kind = DayKind.SWAP },
                        label = { Text("调休（按别的星期上）") },
                    )
                }

                if (kind == DayKind.SWAP) {
                    Text(
                        "按星期几的课表上（不确定就先「待安排」，之后再来定）",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(
                            selected = swapTo !in 1..7,
                            onClick = { swapTo = 0 },
                            label = { Text("待安排") },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        (1..7).forEach { d ->
                            FilterChip(
                                selected = swapTo == d,
                                onClick = { swapTo = d },
                                label = { Text(dayName(d)) },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                    if (swapTo in 1..7) {
                        Text("按第几周的课表上", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            FilterChip(
                                selected = swapWeek <= 0,
                                onClick = { swapWeek = -1 },
                                label = { Text("这天所在的周（第 $currentWeek 周）") },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            (1..6).forEach { w ->
                                val value = currentWeek - 1 + w
                                if (value in 1..30) {
                                    FilterChip(
                                        selected = swapWeek == value,
                                        onClick = { swapWeek = value },
                                        label = { Text("第 $value 周") },
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可不填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(kind, swapTo, swapWeek, note) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
