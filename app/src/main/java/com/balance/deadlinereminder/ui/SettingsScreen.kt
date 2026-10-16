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
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import com.balance.deadlinereminder.data.Store
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.balance.deadlinereminder.data.AppSettings
import com.balance.deadlinereminder.data.Course
import com.balance.deadlinereminder.data.PeriodTime
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
    val notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val exactAlarmOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
    } else true

    BackHandler(enabled = active && secondaryPage.isNotBlank()) { secondaryPage = "" }

    if (secondaryPage == "图片导入") {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            TextButton(onClick = { secondaryPage = "" }) { Text("返回设置") }
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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Text(secondaryPage.ifBlank { "设置" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "输入、开关与步进只改草稿；「保存设置」提交全部分类草稿，「全部撤销」取消全部草稿。图片导入与备份恢复点击应用后立即生效。",
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        if (secondaryPage.isBlank()) {
            listOf(
                "学期与作息" to "教学周校准、上课时间与课程提醒",
                "任务排程" to "每日工作量、专注休息、DDL 预警与播报",
                "图片导入" to "课表、作息时间与校历，本地识别后核对",
                "数据备份" to "导出或恢复课程、设置和 DDL 任务",
                "提醒权限" to "通知、精确闹钟与 HyperOS 后台权限",
            ).forEach { (title, hint) ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    TextButton(onClick = { secondaryPage = title }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(8.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(hint, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text("已有 ${courses.size} 门课 · 数据仅保存在本机", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Row {
            TextButton(onClick = { secondaryPage = "" }) { Text("返回设置") }
            if (secondaryPage == "学期与作息" || secondaryPage == "任务排程") {
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

        val nowWeek = WeekCalc.weekOf(WeekCalc.parseDate(termStartText), LocalDate.now()) // 开学日推算预览
        Text(
            if (termStartText.isBlank()) "开学日期未知？可以只校准当前教学周喵"
            else "按这个日期算，今天是第 $nowWeek 周",
            fontSize = 12.sp,
        )

        OutlinedTextField(
            value = calibrationText,
            onValueChange = { calibrationText = it; calibrationEnabled = true },
            label = { Text("当前教学周校准（1～总周数）") },
            supportingText = { Text(if (calibrationEnabled) "校准后每周一递增；恢复按开学日请点下方按钮" else "当前按开学日计算；输入周数即可校准") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
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
        Text("以上修改和恢复操作均需点击「保存设置」才生效。", fontSize = 12.sp)

        Row(Modifier.padding(top = 8.dp)) {
            OutlinedTextField(
                value = totalWeeksText,
                onValueChange = { totalWeeksText = it },
                label = { Text("总周数") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = reminderText,
                onValueChange = { reminderText = it },
                label = { Text("默认提前提醒(分钟)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("响铃提醒（默认关，只需要通知就别开）", fontSize = 14.sp)
                Text("打开后会额外放系统闹钟铃声；只想要通知就不用打开", fontSize = 11.sp)
            }
            Switch(
                checked = strongReminderDraft,
                onCheckedChange = { strongReminderDraft = it },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("上课节次时间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("共 ${settings.periods.size} 节。时间填成 8:00 这种格式。", fontSize = 12.sp)
        periodsDraft.forEachIndexed { index, period ->
            PeriodRow(
                index = index,
                start = period.first,
                end = period.second,
                onChange = { start, end ->
                    periodsDraft = periodsDraft.toMutableList().also { it[index] = start to end }
                },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        }
        if (secondaryPage == "任务排程") {
        Text("任务排程", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "这些数字先保存在草稿里，点击保存后才会重新排程。系统会扣掉上课时间，再把任务填进空档。",
            fontSize = 12.sp,
        )

        StepperRow(
            label = "每天开始时间",
            hint = "这之前不排任务",
            value = clockText(scheduleDraft.wakeMinute),
            dec = { scheduleDraft = scheduleDraft.copy(wakeMinute = (scheduleDraft.wakeMinute - 30).coerceAtLeast(0)) },
            inc = { scheduleDraft = scheduleDraft.copy(wakeMinute = (scheduleDraft.wakeMinute + 30).coerceAtMost(scheduleDraft.sleepMinute - 30)) },
        )
        StepperRow(
            label = "每天结束时间",
            hint = "这之后不再排任务",
            value = clockText(scheduleDraft.sleepMinute),
            dec = { scheduleDraft = scheduleDraft.copy(sleepMinute = (scheduleDraft.sleepMinute - 30).coerceAtLeast(scheduleDraft.wakeMinute + 30)) },
            inc = { scheduleDraft = scheduleDraft.copy(sleepMinute = (scheduleDraft.sleepMinute + 30).coerceAtMost(1440)) },
        )
        StepperRow(
            label = "每天最多安排",
            hint = "防止把一整天都当成空闲",
            value = humanDuration(scheduleDraft.dailyCapMinutes.toLong()),
            dec = { scheduleDraft = scheduleDraft.copy(dailyCapMinutes = (scheduleDraft.dailyCapMinutes - 30).coerceAtLeast(30)) },
            inc = { scheduleDraft = scheduleDraft.copy(dailyCapMinutes = (scheduleDraft.dailyCapMinutes + 30).coerceAtMost(720)) },
        )
        StepperRow(
            label = "单次专注时长",
            hint = "一段最多连着做多久",
            value = "${scheduleDraft.focusChunkMinutes} 分钟",
            dec = { scheduleDraft = scheduleDraft.copy(focusChunkMinutes = (scheduleDraft.focusChunkMinutes - 15).coerceAtLeast(15)) },
            inc = { scheduleDraft = scheduleDraft.copy(focusChunkMinutes = (scheduleDraft.focusChunkMinutes + 15).coerceAtMost(240)) },
        )
        StepperRow(
            label = "两块之间休息",
            value = "${scheduleDraft.breakMinutes} 分钟",
            dec = { scheduleDraft = scheduleDraft.copy(breakMinutes = (scheduleDraft.breakMinutes - 5).coerceAtLeast(0)) },
            inc = { scheduleDraft = scheduleDraft.copy(breakMinutes = (scheduleDraft.breakMinutes + 5).coerceAtMost(30)) },
        )
        StepperRow(
            label = "最短可用空档",
            hint = "太碎的缝隙不排，省得来回切",
            value = "${scheduleDraft.minSlotMinutes} 分钟",
            dec = { scheduleDraft = scheduleDraft.copy(minSlotMinutes = (scheduleDraft.minSlotMinutes - 5).coerceAtLeast(5)) },
            inc = { scheduleDraft = scheduleDraft.copy(minSlotMinutes = (scheduleDraft.minSlotMinutes + 5).coerceAtMost(60)) },
        )

        ToggleRow(
            label = "到点提醒该做这件事",
            hint = "每个排好的时段开始时通知，通知里能直接登记进度",
            checked = scheduleDraft.sessionReminderEnabled,
            onCheckedChange = { scheduleDraft = scheduleDraft.copy(sessionReminderEnabled = it) },
        )
        StepperRow(
            label = "DDL 提前预警",
            value = "${scheduleDraft.ddlWarnHours} 小时",
            dec = { scheduleDraft = scheduleDraft.copy(ddlWarnHours = (scheduleDraft.ddlWarnHours - 1).coerceAtLeast(scheduleDraft.ddlUrgentHours)) },
            inc = { scheduleDraft = scheduleDraft.copy(ddlWarnHours = (scheduleDraft.ddlWarnHours + 1).coerceAtMost(168)) },
        )
        StepperRow(
            label = "DDL 紧急提醒",
            value = "${scheduleDraft.ddlUrgentHours} 小时",
            hint = "到这个点还没做完就强提醒",
            dec = { scheduleDraft = scheduleDraft.copy(ddlUrgentHours = (scheduleDraft.ddlUrgentHours - 1).coerceAtLeast(1)) },
            inc = {
                scheduleDraft = scheduleDraft.copy(
                        ddlUrgentHours = (scheduleDraft.ddlUrgentHours + 1).coerceAtMost(scheduleDraft.ddlWarnHours.coerceAtLeast(1)),
                    )
            },
        )
        ToggleRow(
            label = "每天早上播报",
            hint = "把今天要推进的事和最近的 DDL 播一次",
            checked = scheduleDraft.digestEnabled,
            onCheckedChange = { scheduleDraft = scheduleDraft.copy(digestEnabled = it) },
        )
        StepperRow(
            label = "播报时间",
            value = clockText(scheduleDraft.digestMinute),
            dec = { scheduleDraft = scheduleDraft.copy(digestMinute = (scheduleDraft.digestMinute - 30 + 1440) % 1440) },
            inc = { scheduleDraft = scheduleDraft.copy(digestMinute = (scheduleDraft.digestMinute + 30) % 1440) },
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        }
        if (secondaryPage == "学期与作息" || secondaryPage == "任务排程") {
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
                dataMessage = "设置保存好了喵"
                Toast.makeText(context, "设置保存好了，周次继续往前走喵", Toast.LENGTH_SHORT).show()
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
        Text("换手机、重装应用前先导出；内部数据空了会从这份备份自动恢复。", fontSize = 12.sp)
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
                    Toast.makeText(context, "备份回来啦喵", Toast.LENGTH_SHORT).show()
                } else dataMessage = "外部目录里没有备份文件"
            }) { Text("从备份恢复") }
        }
        Text("备份路径：Android/data/com.balance.deadlinereminder/files/planner.txt", fontSize = 10.sp)

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

        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text("小米 / HyperOS 额外要做的事", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "1. 设置 → 应用设置 → DDL 提醒 → 允许「自启动」，省电策略改成「无限制」。\n" +
                        "2. 设置 → 应用设置 → DDL 提醒 → 通知管理 → 允许通知、允许横幅。\n" +
                        "3. 如果长时间没收到提醒，进最近任务把本应用加锁。",
                    fontSize = 12.sp,
                )
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        )
                    },
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text("打开本应用系统设置") }
            }
        }

        Text(
            "已有 ${courses.size} 门课。数据只存在手机本地，不上传。",
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
        }
        if (dataMessage.isNotBlank()) {
            Text(dataMessage, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun PeriodRow(index: Int, start: String, end: String, onChange: (String, String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("第 ${index + 1} 节", Modifier.width(58.dp), fontSize = 12.sp)
        OutlinedTextField(
            value = start,
            onValueChange = { onChange(it, end) },
            label = { Text("开始") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(
            value = end,
            onValueChange = { onChange(start, it) },
            label = { Text("结束") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
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

/** 一行「减号 · 值 · 加号」的设置项。排程参数都是整数分钟，点加减比手输错得少。 */
@Composable
private fun StepperRow(
    label: String,
    value: String,
    dec: () -> Unit,
    inc: () -> Unit,
    hint: String = "",
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            if (hint.isNotBlank()) Text(hint, fontSize = 11.sp)
        }
        OutlinedButton(onClick = dec) { Text("−") }
        Text(value, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp))
        OutlinedButton(onClick = inc) { Text("＋") }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            if (hint.isNotBlank()) Text(hint, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
