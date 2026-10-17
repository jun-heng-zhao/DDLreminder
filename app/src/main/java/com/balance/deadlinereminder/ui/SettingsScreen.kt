// 设置草稿、教学周校准与本地备份管理。
package com.balance.deadlinereminder.ui

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import com.balance.deadlinereminder.data.Store
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.balance.deadlinereminder.data.AppSettings
import com.balance.deadlinereminder.data.Course
import com.balance.deadlinereminder.data.PeriodTime
import com.balance.deadlinereminder.data.ThemeMode
import com.balance.deadlinereminder.data.humanDuration
import com.balance.deadlinereminder.domain.WeekCalc
import java.time.LocalDate

@Composable
fun SettingsScreen(
    settings: AppSettings,
    courses: List<Course>,
    onChange: (AppSettings) -> Unit,
    onTestNotification: () -> Unit,
    onRestore: (() -> Unit)? = null,
    active: Boolean = true,
    onImportCourses: (List<Course>) -> Unit = {},
    timetable: @Composable () -> Unit = {},
    onSecondaryPageChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var secondaryPage by rememberSaveable { mutableStateOf("") } // 设置次页，不改变主导航页码
    val initialCalibrationText = remember(settings) {
        if (settings.manualWeek > 0) WeekCalc.teachingWeek(settings, LocalDate.now()).toString() else ""
    } // 显示当前周，不能把历史锚点周当作今天的周数
    var termStartText by remember(settings) { mutableStateOf(settings.termStartDate) } // 日期原文
    var totalWeeksText by remember(settings) { mutableStateOf(settings.totalWeeks.toString()) } // 总周数原文
    var reminderText by remember(settings) { mutableStateOf(settings.defaultReminderMinutes.toString()) } // 提前量原文
    var calibrationText by remember(settings) { mutableStateOf(initialCalibrationText) } // 当前周原文
    var calibrationEnabled by remember(settings) { mutableStateOf(settings.manualWeek > 0) } // 显式恢复才能停用校准
    var strongReminderDraft by remember(settings) { mutableStateOf(settings.strongReminder) } // 开关也只修改草稿
    var periodsDraft by remember(settings) {
        mutableStateOf(settings.periods.map { it.startLabel to it.endLabel })
    }
    var scheduleDraft by remember(settings) { mutableStateOf(settings) } // DDL 排程草稿，保存按钮统一提交
    var dataMessage by remember { mutableStateOf("") }
    var wheelSetting by remember { mutableStateOf<WheelSetting?>(null) } // 当前打开的数字滚轮
    var clockSetting by remember { mutableStateOf<ClockSetting?>(null) } // 当前打开的时钟双滚轮
    var editingPeriod by remember { mutableStateOf<Pair<Int, Boolean>?>(null) } // 节次索引与是否编辑开始时间
    val notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val exactAlarmOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
    } else true

    BackHandler(enabled = active && secondaryPage.isNotBlank()) { secondaryPage = "" }
    fun closeSecondaryPage() { secondaryPage = "" }

    androidx.compose.runtime.LaunchedEffect(secondaryPage) {
        onSecondaryPageChange(secondaryPage.isNotBlank())
    }

    if (secondaryPage == "图片导入") {
        Column(Modifier.fillMaxSize().swipeRightToBack(onBack = ::closeSecondaryPage).padding(12.dp)) {
            TextButton(onClick = ::closeSecondaryPage) { Text("返回设置") }
            ImportScreen(
                settings = settings,
                onImportCourses = { imported ->
                    secondaryPage = ""
                    onImportCourses(imported)
                },
                onChangeSettings = { updated ->
                    secondaryPage = ""
                    onChange(updated)
                },
            )
        }
        return
    }
    if (secondaryPage == "课表") {
        Column(Modifier.fillMaxSize().swipeRightToBack(onBack = ::closeSecondaryPage).padding(12.dp)) {
            TextButton(onClick = ::closeSecondaryPage) { Text("返回设置") }
            timetable()
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .swipeRightToBack(enabled = secondaryPage.isNotBlank(), onBack = ::closeSecondaryPage)
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Text(secondaryPage.ifBlank { "设置" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (secondaryPage.isBlank()) {
            listOf(
                "课表",
                "学期与作息",
                "任务",
                "外观",
                "图片导入",
                "数据备份",
                "提醒权限",
            ).forEach { title ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    TextButton(onClick = { secondaryPage = title }, modifier = Modifier.fillMaxWidth()) {
                        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().padding(8.dp))
                    }
                }
            }
            return@Column
        }
        Row {
            TextButton(onClick = { secondaryPage = "" }) { Text("返回设置") }
            if (secondaryPage == "学期与作息" || secondaryPage == "任务") {
            TextButton(onClick = {
                termStartText = settings.termStartDate
                totalWeeksText = settings.totalWeeks.toString()
                reminderText = settings.defaultReminderMinutes.toString()
                calibrationText = initialCalibrationText
                calibrationEnabled = settings.manualWeek > 0
                strongReminderDraft = settings.strongReminder
                periodsDraft = settings.periods.map { it.startLabel to it.endLabel }
                scheduleDraft = settings
                dataMessage = "已撤销所有未保存修改"
            }) { Text("全部撤销") }
            }
        }

        if (secondaryPage == "外观") {
            FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                listOf(
                    ThemeMode.SYSTEM to "跟随系统",
                    ThemeMode.LIGHT to "浅色",
                    ThemeMode.DARK to "深色",
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { onChange(settings.copy(themeMode = mode)) },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }

        if (secondaryPage == "学期与作息") {
        Text("学期与作息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = termStartText,
            onValueChange = { termStartText = it },
            label = { Text("第一周的周一（yyyy-MM-dd）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(onClick = {
                termStartText = WeekCalc.mondayOf(LocalDate.now()).toString()
            }) { Text("设为本周一") }
            TextButton(onClick = {
                termStartText = WeekCalc.mondayOf(LocalDate.now()).plusWeeks(1).toString()
            }) { Text("设为下周一") }
        }

        WheelSettingRow(
            label = "当前教学周",
            value = calibrationText.toIntOrNull()?.let { "第 $it 周" } ?: "按开学日计算",
            onClick = {
                val total = totalWeeksText.toIntOrNull()?.coerceIn(1, 60) ?: 20
                wheelSetting = WheelSetting("当前教学周", (1..total).toList(), calibrationText.toIntOrNull() ?: 1, "周") {
                    calibrationText = it.toString()
                    calibrationEnabled = true
                }
            },
        )
        Row {
            TextButton(onClick = {
                calibrationText = WeekCalc.teachingWeek(settings, LocalDate.now()).coerceAtLeast(1).toString()
                calibrationEnabled = true
            }) { Text("校准为本周") }
            TextButton(onClick = {
                calibrationText = ""
                calibrationEnabled = false
            }) { Text("恢复按开学日") }
        }
        WheelSettingRow("总周数", "${totalWeeksText.toIntOrNull() ?: 20} 周") {
            wheelSetting = WheelSetting("总周数", (1..60).toList(), totalWeeksText.toIntOrNull() ?: 20, "周") {
                totalWeeksText = it.toString()
            }
        }
        WheelSettingRow("默认提前提醒", "${reminderText.toIntOrNull() ?: 20} 分钟") {
            wheelSetting = WheelSetting("默认提前提醒", (0..1440 step 5).toList(), reminderText.toIntOrNull() ?: 20, "分钟") {
                reminderText = it.toString()
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("响铃提醒", fontSize = 14.sp)
            }
            Switch(
                checked = strongReminderDraft,
                onCheckedChange = { strongReminderDraft = it },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("上课节次时间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        periodsDraft.forEachIndexed { index, period ->
            PeriodRow(
                index = index,
                start = period.first,
                end = period.second,
                onEditStart = { editingPeriod = index to true },
                onEditEnd = { editingPeriod = index to false },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        }
        if (secondaryPage == "任务") {
        Text("任务规划", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        WheelSettingRow("每天开始时间", clockText(scheduleDraft.wakeMinute)) {
            clockSetting = ClockSetting("每天开始时间", scheduleDraft.wakeMinute, 0, scheduleDraft.sleepMinute - 30) {
                scheduleDraft = scheduleDraft.copy(wakeMinute = it)
            }
        }
        WheelSettingRow("每天结束时间", clockText(scheduleDraft.sleepMinute)) {
            clockSetting = ClockSetting("每天结束时间", scheduleDraft.sleepMinute, scheduleDraft.wakeMinute + 30, 1440) {
                scheduleDraft = scheduleDraft.copy(sleepMinute = it)
            }
        }
        WheelSettingRow("每天最多安排", humanDuration(scheduleDraft.dailyCapMinutes.toLong())) {
            wheelSetting = minuteSetting("每天最多安排", scheduleDraft.dailyCapMinutes, 30, 720, 30) {
                scheduleDraft = scheduleDraft.copy(dailyCapMinutes = it)
            }
        }
        WheelSettingRow("每天通勤", humanDuration(scheduleDraft.commuteMinutes.toLong())) {
            wheelSetting = minuteSetting("每天通勤", scheduleDraft.commuteMinutes, 0, 360, 15) {
                scheduleDraft = scheduleDraft.copy(commuteMinutes = it)
            }
        }
        WheelSettingRow("每天饮食", humanDuration(scheduleDraft.mealMinutes.toLong())) {
            wheelSetting = minuteSetting("每天饮食", scheduleDraft.mealMinutes, 0, 360, 15) {
                scheduleDraft = scheduleDraft.copy(mealMinutes = it)
            }
        }
        WheelSettingRow("每天睡眠", humanDuration(scheduleDraft.sleepMinutes.toLong())) {
            wheelSetting = minuteSetting("每天睡眠", scheduleDraft.sleepMinutes, 240, 720, 30) {
                scheduleDraft = scheduleDraft.copy(sleepMinutes = it)
            }
        }
        WheelSettingRow("单次专注时长", "${scheduleDraft.focusChunkMinutes} 分钟") {
            wheelSetting = minuteSetting("单次专注时长", scheduleDraft.focusChunkMinutes, 15, 240, 15) {
                scheduleDraft = scheduleDraft.copy(focusChunkMinutes = it)
            }
        }
        WheelSettingRow("两块之间休息", "${scheduleDraft.breakMinutes} 分钟") {
            wheelSetting = minuteSetting("两块之间休息", scheduleDraft.breakMinutes, 0, 30, 5) {
                scheduleDraft = scheduleDraft.copy(breakMinutes = it)
            }
        }
        WheelSettingRow("最短可用空档", "${scheduleDraft.minSlotMinutes} 分钟") {
            wheelSetting = minuteSetting("最短可用空档", scheduleDraft.minSlotMinutes, 5, 60, 5) {
                scheduleDraft = scheduleDraft.copy(minSlotMinutes = it)
            }
        }

        ToggleRow(
            label = "到点提醒该做这件事",
            checked = scheduleDraft.sessionReminderEnabled,
            onCheckedChange = { scheduleDraft = scheduleDraft.copy(sessionReminderEnabled = it) },
        )
        WheelSettingRow("DDL 提前预警", "${scheduleDraft.ddlWarnHours} 小时") {
            wheelSetting = WheelSetting("DDL 提前预警", (scheduleDraft.ddlUrgentHours..168).toList(), scheduleDraft.ddlWarnHours, "小时") {
                scheduleDraft = scheduleDraft.copy(ddlWarnHours = it)
            }
        }
        WheelSettingRow("DDL 紧急提醒", "${scheduleDraft.ddlUrgentHours} 小时") {
            wheelSetting = WheelSetting("DDL 紧急提醒", (1..scheduleDraft.ddlWarnHours.coerceAtLeast(1)).toList(), scheduleDraft.ddlUrgentHours, "小时") {
                scheduleDraft = scheduleDraft.copy(ddlUrgentHours = it)
            }
        }
        ToggleRow(
            label = "每天早上播报",
            checked = scheduleDraft.digestEnabled,
            onCheckedChange = { scheduleDraft = scheduleDraft.copy(digestEnabled = it) },
        )
        WheelSettingRow("播报时间", clockText(scheduleDraft.digestMinute)) {
            clockSetting = ClockSetting("播报时间", scheduleDraft.digestMinute, 0, 1435) {
                scheduleDraft = scheduleDraft.copy(digestMinute = it)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        }
        if (secondaryPage == "学期与作息" || secondaryPage == "任务") {
        Row(Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = {
                val academicChanged = termStartText != settings.termStartDate ||
                    totalWeeksText != settings.totalWeeks.toString() ||
                    reminderText != settings.defaultReminderMinutes.toString() ||
                    calibrationText != initialCalibrationText || calibrationEnabled != (settings.manualWeek > 0) ||
                    periodsDraft != settings.periods.map { it.startLabel to it.endLabel }
                val error = if (academicChanged) validateSettingsDraft(
                    termStartText, totalWeeksText, reminderText, calibrationText, calibrationEnabled, periodsDraft,
                ) else null // 未配置学期也允许单独保存任务排程，不强迫先填课表
                if (error != null) {
                    dataMessage = error
                    return@OutlinedButton
                }
                val totalWeeks = totalWeeksText.trim().toInt() // 已通过集中校验
                val calibrationChanged = calibrationEnabled &&
                    (settings.manualWeek <= 0 || calibrationText.trim() != initialCalibrationText)
                val manualWeek = when {
                    !calibrationEnabled -> 0
                    calibrationChanged -> calibrationText.trim().toInt()
                    else -> settings.manualWeek
                } // 未修改校准时连同历史锚点一并保留，避免跨周倒退
                val next = settings.copy(
                    termStartDate = termStartText.trim(),
                    totalWeeks = totalWeeks,
                    defaultReminderMinutes = reminderText.trim().toInt(),
                    strongReminder = strongReminderDraft,
                    periods = if (academicChanged) periodsDraft.map {
                        PeriodTime(requireNotNull(parseStrictMinute(it.first)), requireNotNull(parseStrictMinute(it.second)))
                    } else settings.periods,
                    manualWeek = manualWeek,
                    manualWeekMonday = when {
                        manualWeek == 0 -> ""
                        calibrationChanged -> WeekCalc.mondayOf(LocalDate.now()).toString()
                        else -> settings.manualWeekMonday
                    },
                    wakeMinute = scheduleDraft.wakeMinute,
                    sleepMinute = scheduleDraft.sleepMinute,
                    dailyCapMinutes = scheduleDraft.dailyCapMinutes,
                    commuteMinutes = scheduleDraft.commuteMinutes,
                    mealMinutes = scheduleDraft.mealMinutes,
                    sleepMinutes = scheduleDraft.sleepMinutes,
                    focusChunkMinutes = scheduleDraft.focusChunkMinutes,
                    breakMinutes = scheduleDraft.breakMinutes,
                    minSlotMinutes = scheduleDraft.minSlotMinutes,
                    sessionReminderEnabled = scheduleDraft.sessionReminderEnabled,
                    ddlWarnHours = scheduleDraft.ddlWarnHours,
                    ddlUrgentHours = scheduleDraft.ddlUrgentHours,
                    digestEnabled = scheduleDraft.digestEnabled,
                    digestMinute = scheduleDraft.digestMinute,
                )
                onChange(next)
                dataMessage = ""
                Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
            }) { Text("保存设置") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                termStartText = settings.termStartDate
                totalWeeksText = settings.totalWeeks.toString()
                reminderText = settings.defaultReminderMinutes.toString()
                calibrationText = initialCalibrationText
                calibrationEnabled = settings.manualWeek > 0
                strongReminderDraft = settings.strongReminder
                periodsDraft = settings.periods.map { it.startLabel to it.endLabel }
                scheduleDraft = settings
                dataMessage = "已取消本次修改"
            }) { Text("取消") }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        }
        if (secondaryPage == "数据备份") {
        Text("全部本地数据", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        FlowRow(Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = {
                val path = Store.get(context).exportToExternal()
                dataMessage = path?.let { "已导出到：$it" } ?: "导出失败（外部存储不可用）"
            }) { Text("导出备份") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                if (Store.get(context).importFromExternal()) {
                    onRestore?.invoke()
                    dataMessage = if (onRestore != null) "已从备份恢复，课程、设置和任务已同步"
                    else "备份已恢复，请重新打开应用刷新课程、设置和任务"
                    Toast.makeText(context, "备份已恢复", Toast.LENGTH_SHORT).show()
                } else dataMessage = "外部目录里没有备份文件"
            }) { Text("从备份恢复") }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        }
        if (secondaryPage == "提醒权限") {
        Text("提醒权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "通知权限：" + if (notificationsOn) "已开启" else "未开启（收不到提醒）",
            fontSize = 13.sp,
        )
        Text(
            "精确闹钟：" + if (exactAlarmOn) "已允许" else "未允许（提醒可能晚几分钟）",
            fontSize = 13.sp,
        )
        FlowRow(Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }) { Text("通知设置") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        )
                    }
                } else {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null))
                    )
                }
            }) { Text("精确闹钟设置") }
        }
        TextButton(onClick = onTestNotification) { Text("发一条测试通知") }

        OutlinedButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
            )
        }) { Text("应用系统设置") }
        }
        if (dataMessage.isNotBlank()) {
            Text(dataMessage, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }

    wheelSetting?.let { setting ->
        NumberWheelDialog(setting = setting, onDismiss = { wheelSetting = null })
    }
    clockSetting?.let { setting ->
        ClockWheelDialog(setting = setting, onDismiss = { clockSetting = null })
    }
    editingPeriod?.let { (index, editStart) ->
        val period = periodsDraft[index]
        WheelTimeDialog(
            initial = if (editStart) period.first else period.second,
            onDismiss = { editingPeriod = null },
            onConfirm = { selected ->
                periodsDraft = periodsDraft.toMutableList().also {
                    it[index] = if (editStart) selected to period.second else period.first to selected
                }
                editingPeriod = null
            },
        )
    }
}

private fun Modifier.swipeRightToBack(enabled: Boolean = true, onBack: () -> Unit): Modifier {
    if (!enabled) return this
    return pointerInput(onBack) {
        var dragDistance = 0f
        detectHorizontalDragGestures(
            onDragStart = { dragDistance = 0f },
            onHorizontalDrag = { change, amount ->
                if (amount > 0f || dragDistance > 0f) {
                    dragDistance = (dragDistance + amount).coerceAtLeast(0f)
                    change.consume()
                }
            },
            onDragEnd = {
                if (dragDistance >= 96.dp.toPx()) onBack()
                dragDistance = 0f
            },
            onDragCancel = { dragDistance = 0f },
        )
    }
}

@Composable
private fun PeriodRow(index: Int, start: String, end: String, onEditStart: () -> Unit, onEditEnd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("第 ${index + 1} 节", Modifier.width(58.dp), fontSize = 12.sp)
        Card(Modifier.weight(1f).clickable(onClick = onEditStart)) {
            Text(start, modifier = Modifier.padding(14.dp))
        }
        Spacer(Modifier.width(6.dp))
        Card(Modifier.weight(1f).clickable(onClick = onEditEnd)) {
            Text(end, modifier = Modifier.padding(14.dp))
        }
    }
}

internal fun validateSettingsDraft(
    termStart: String,
    totalWeeks: String,
    reminder: String,
    calibration: String,
    calibrationEnabled: Boolean,
    periods: List<Pair<String, String>>,
): String? {
    val total = parseStrictInteger(totalWeeks) // 保留原文，拒绝空白、符号和混入的字符
    val reminderMinutes = parseStrictInteger(reminder)
    val calibrationWeek = parseStrictInteger(calibration)
    if (!(calibrationEnabled && termStart.isBlank()) &&
        (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(termStart.trim()) || WeekCalc.parseDate(termStart.trim()) == null)) {
        return "第一周日期必须是有效的 yyyy-MM-dd"
    }
    if (total == null) return "总周数必须填写数字"
    if (total !in 1..60) return "总周数必须在 1～60 之间"
    if (reminderMinutes == null) return "默认提前提醒必须填写数字"
    if (reminderMinutes !in 0..1440) return "默认提前提醒必须在 0～1440 分钟之间"
    if (calibrationEnabled) {
        if (calibrationWeek == null) return "校准周不能为空且必须填写数字；取消校准请点恢复按开学日"
        if (calibrationWeek !in 1..total) return "校准周必须在 1～总周数之间"
    }
    periods.forEachIndexed { index, (start, end) ->
        val startMinute = parseStrictMinute(start) ?: return "第 ${index + 1} 节开始时间无效"
        val endMinute = parseStrictMinute(end) ?: return "第 ${index + 1} 节结束时间无效"
        if (endMinute <= startMinute) return "第 ${index + 1} 节必须满足结束时间晚于开始时间"
    }
    return null
}

private fun parseStrictInteger(text: String): Int? =
    text.trim().takeIf { Regex("[0-9]+").matches(it) }?.toIntOrNull()

private fun parseStrictMinute(text: String): Int? {
    val match = Regex("^(\\d{1,2})[:：](\\d{2})$").matchEntire(text.trim()) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}

private fun clockText(minute: Int): String {
    if (minute == 1440) return "24:00"
    val m = ((minute % 1440) + 1440) % 1440
    return "%02d:%02d".format(m / 60, m % 60)
}

@Composable
private fun WheelSettingRow(label: String, value: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 6.dp).clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private data class WheelSetting(
    val title: String,
    val values: List<Int>,
    val selected: Int,
    val suffix: String,
    val onSelect: (Int) -> Unit,
)

private data class ClockSetting(
    val title: String,
    val selected: Int,
    val first: Int,
    val last: Int,
    val onSelect: (Int) -> Unit,
)

private fun minuteSetting(title: String, selected: Int, first: Int, last: Int, step: Int, onSelect: (Int) -> Unit) =
    WheelSetting(title, (first..last step step).toList(), selected, "分钟", onSelect)

@Composable
private fun ClockWheelDialog(setting: ClockSetting, onDismiss: () -> Unit) {
    val validMinutes = remember(setting) {
        (setting.first..setting.last).filter { it % 5 == 0 || it == 1440 }
    } // 仅展示边界内的五分钟刻度，午夜结束值额外保留 24:00
    val initial = setting.selected.takeIf { it in validMinutes }
        ?: validMinutes.minByOrNull { kotlin.math.abs(it - setting.selected) }
        ?: setting.first
    var hour by remember(setting) { mutableIntStateOf(initial / 60) }
    var minute by remember(setting) { mutableIntStateOf(initial % 60) }
    val hours = remember(validMinutes) { validMinutes.map { it / 60 }.distinct() }
    val minutes = validMinutes.filter { it / 60 == hour }.map { it % 60 }.distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(setting.title) },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                NumberWheel(hours, hour, "时", Modifier.weight(1f)) { selectedHour ->
                    val available = validMinutes.filter { it / 60 == selectedHour }.map { it % 60 }
                    hour = selectedHour
                    minute = available.minByOrNull { kotlin.math.abs(it - minute) } ?: 0
                }
                NumberWheel(minutes, minute, "分", Modifier.weight(1f)) { minute = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { setting.onSelect(hour * 60 + minute); onDismiss() }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun NumberWheelDialog(setting: WheelSetting, onDismiss: () -> Unit) {
    var selected by remember(setting) { mutableStateOf(setting.selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(setting.title) },
        text = {
            NumberWheel(
                values = setting.values,
                selected = selected,
                suffix = setting.suffix,
                modifier = Modifier.fillMaxWidth(),
                onSelect = { selected = it },
            )
        },
        confirmButton = {
            TextButton(onClick = { setting.onSelect(selected); onDismiss() }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
