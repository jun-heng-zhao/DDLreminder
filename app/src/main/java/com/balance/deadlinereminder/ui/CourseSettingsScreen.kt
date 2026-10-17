package com.balance.deadlinereminder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.balance.deadlinereminder.data.AppSettings
import com.balance.deadlinereminder.data.Course
import com.balance.deadlinereminder.data.dayName
import com.balance.deadlinereminder.domain.WeekCalc

/** 设置中的周课表管理页，只负责课程查看与编辑。 */
@Composable
fun CourseSettingsScreen(
    courses: List<Course>,
    settings: AppSettings,
    week: Int,
    currentWeek: Int,
    onWeekOffset: (Int) -> Unit,
    onBackToNow: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Course) -> Unit,
) {
    val weekCourses = courses.filter { WeekCalc.weekMatches(it, week) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("课表", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onWeekOffset(-1) }) { Text("◀ 上一周") }
            Spacer(Modifier.weight(1f))
            Text("第 $week 周", fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onWeekOffset(1) }) { Text("下一周 ▶") }
        }
        if (week != currentWeek) {
            TextButton(onClick = onBackToNow) { Text("回到本周（第 $currentWeek 周）") }
        }
        TextButton(onClick = onAdd) { Text("＋ 新增课程") }
        var anyCourse = false
        (1..7).forEach { day ->
            val dayCourses = weekCourses.filter { it.dayOfWeek == day }.sortedBy { it.startPeriod }
            if (dayCourses.isNotEmpty()) {
                anyCourse = true
                Text(dayName(day), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                dayCourses.forEach { course ->
                    CourseSettingsCard(course, settings, onEdit)
                }
            }
        }
        if (!anyCourse) Text("第 $week 周没有课程。", modifier = Modifier.padding(vertical = 16.dp))
    }
}

@Composable
private fun CourseSettingsCard(course: Course, settings: AppSettings, onEdit: (Course) -> Unit) {
    val color = when (course.importance) {
        2 -> Color(0xFFFF0000)
        1 -> Color(0xFFFFC107)
        else -> Color(0xFF00B000)
    }
    val time = (course.startPeriod..course.endPeriod).mapNotNull { period ->
        settings.periods.getOrNull(period - 1)?.let { "${it.startLabel}-${it.endLabel}" }
    }.joinToString("  ")
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onEdit(course) }) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(6.dp).height(42.dp).background(color))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(course.name, fontWeight = FontWeight.Bold)
                Text("$time · 第 ${course.startPeriod}-${course.endPeriod} 节", fontSize = 12.sp)
                Text(
                    if (course.weekSpec.isNotBlank()) "不规则周：${course.weekSpec}" else "${course.startWeek}-${course.endWeek} 周 · ${course.parity.name}",
                    fontSize = 11.sp,
                )
                if (course.location.isNotBlank()) Text(course.location, fontSize = 11.sp)
            }
            Text("修改", color = color, fontSize = 12.sp)
        }
    }
}
