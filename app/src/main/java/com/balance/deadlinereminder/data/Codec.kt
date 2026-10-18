package com.balance.deadlinereminder.data

/** 全部落盘数据：课表 + 设置 + DDL 任务。 */
data class ScheduleData(
    val courses: List<Course> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val tasks: List<Task> = emptyList(),
)

/**
 * 落盘格式：每行一条记录，制表符分隔，首行是版本号。
 * 故意不用 JSON/Room —— 数据量极小，纯 Kotlin 实现可以直接跑 JVM 单测。
 *
 * v2 起每行末尾都可以加字段：老文件（v1，只有课表）读进来时 DDL 任务为空，其余字段走默认值。
 */
object Codec {

    private const val VERSION = "v2"

    fun encode(data: ScheduleData): String = buildString {
        val s = data.settings
        appendLine(VERSION)
        appendLine(
            listOf(
                "S",
                esc(s.termStartDate),
                s.totalWeeks.toString(),
                s.defaultReminderMinutes.toString(),
                if (s.strongReminder) "1" else "0",
                s.manualWeek.toString(),
                esc(s.manualWeekMonday),
                s.wakeMinute.toString(),
                s.sleepMinute.toString(),
                s.dailyCapMinutes.toString(),
                s.focusChunkMinutes.toString(),
                s.breakMinutes.toString(),
                s.minSlotMinutes.toString(),
                if (s.sessionReminderEnabled) "1" else "0",
                s.ddlWarnHours.toString(),
                s.ddlUrgentHours.toString(),
                if (s.digestEnabled) "1" else "0",
                s.digestMinute.toString(),
                s.commuteMinutes.toString(),
                s.mealMinutes.toString(),
                s.sleepMinutes.toString(),
                s.themeMode.name,
            ).joinToString("\t")
        )
        s.periods.forEach { p ->
            appendLine(listOf("P", p.startMinute.toString(), p.endMinute.toString()).joinToString("\t"))
        }
        s.overrides.forEach { o ->
            appendLine(
                listOf(
                    "D", esc(o.date), o.kind.name, o.swapToDayOfWeek.toString(), esc(o.note),
                    o.swapToWeek.toString(),
                ).joinToString("\t")
            )
        }
        data.courses.forEach { c ->
            appendLine(
                listOf(
                    "C",
                    esc(c.id), esc(c.name), esc(c.location), esc(c.teacher),
                    c.dayOfWeek.toString(), c.startPeriod.toString(), c.endPeriod.toString(),
                    c.startWeek.toString(), c.endWeek.toString(), c.parity.name,
                    c.reminderMinutes.toString(), c.colorIndex.toString(), esc(c.weekSpec),
                    c.importance.toString(),
                ).joinToString("\t")
            )
        }
        data.tasks.forEach { t ->
            appendLine(
                listOf(
                    "T",
                    esc(t.id), esc(t.title), esc(t.courseName),
                    t.estimateMinutes.toString(), t.spentMinutes.toString(), esc(t.deadline),
                    t.priority.toString(), esc(t.notes), if (t.done) "1" else "0",
                    esc(t.createdAt), t.chunkMinutes.toString(),
                    if (t.daily) "1" else "0", esc(t.completedOn),
                    esc(t.earliestStart), t.dependencyIds.joinToString(",") { escListItem(it) },
                    if (t.estimateUnknown) "1" else "0",
                ).joinToString("\t")
            )
        }
    }

    /** 容忍损坏行与旧版本：解析不了的记录直接跳过，不抛异常。 */
    fun decode(text: String): ScheduleData {
        var settings = AppSettings()
        val periods = mutableListOf<PeriodTime>()
        val overrides = mutableListOf<DayOverride>()
        val courses = mutableListOf<Course>()
        val tasks = mutableListOf<Task>()
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r', '\n')
            if (line.isBlank() || line == VERSION || line == "v1") return@forEach
            val f = line.split('\t')
            when (f.getOrNull(0)) {
                "S" -> {
                    settings = settings.copy(
                        termStartDate = unesc(f.getOrNull(1).orEmpty()),
                        totalWeeks = f.getOrNull(2)?.toIntOrNull() ?: settings.totalWeeks,
                        defaultReminderMinutes = f.getOrNull(3)?.toIntOrNull() ?: settings.defaultReminderMinutes,
                        strongReminder = f.getOrNull(4) == "1",
                        manualWeek = f.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                        manualWeekMonday = unesc(f.getOrNull(6).orEmpty()),
                        wakeMinute = (f.getOrNull(7)?.toIntOrNull() ?: settings.wakeMinute).coerceIn(0, 1439),
                        sleepMinute = (f.getOrNull(8)?.toIntOrNull() ?: settings.sleepMinute).coerceIn(0, 1440),
                        dailyCapMinutes = (f.getOrNull(9)?.toIntOrNull() ?: settings.dailyCapMinutes)
                            .coerceIn(15, 1440),
                        focusChunkMinutes = (f.getOrNull(10)?.toIntOrNull() ?: settings.focusChunkMinutes)
                            .coerceIn(5, 600),
                        breakMinutes = (f.getOrNull(11)?.toIntOrNull() ?: settings.breakMinutes).coerceIn(0, 120),
                        minSlotMinutes = (f.getOrNull(12)?.toIntOrNull() ?: settings.minSlotMinutes).coerceIn(5, 240),
                        sessionReminderEnabled = f.getOrNull(13)?.let { it == "1" } ?: settings.sessionReminderEnabled,
                        ddlWarnHours = (f.getOrNull(14)?.toIntOrNull() ?: settings.ddlWarnHours).coerceIn(1, 336),
                        ddlUrgentHours = (f.getOrNull(15)?.toIntOrNull() ?: settings.ddlUrgentHours).coerceIn(1, 168),
                        digestEnabled = f.getOrNull(16)?.let { it == "1" } ?: settings.digestEnabled,
                        digestMinute = (f.getOrNull(17)?.toIntOrNull() ?: settings.digestMinute).coerceIn(0, 1439),
                        commuteMinutes = (f.getOrNull(18)?.toIntOrNull() ?: settings.commuteMinutes).coerceIn(0, 1440),
                        mealMinutes = (f.getOrNull(19)?.toIntOrNull() ?: settings.mealMinutes).coerceIn(0, 1440),
                        sleepMinutes = (f.getOrNull(20)?.toIntOrNull() ?: settings.sleepMinutes).coerceIn(0, 1440),
                        themeMode = runCatching { ThemeMode.valueOf(f.getOrNull(21).orEmpty()) }
                            .getOrDefault(ThemeMode.SYSTEM),
                    )
                }
                "P" -> {
                    val s = f.getOrNull(1)?.toIntOrNull() ?: return@forEach
                    val e = f.getOrNull(2)?.toIntOrNull() ?: return@forEach
                    periods += PeriodTime(s, e)
                }
                "D" -> {
                    val date = unesc(f.getOrNull(1).orEmpty())
                    if (date.isBlank()) return@forEach
                    overrides += DayOverride(
                        date = date,
                        kind = runCatching { DayKind.valueOf(f.getOrNull(2).orEmpty()) }
                            .getOrDefault(DayKind.NORMAL),
                        swapToDayOfWeek = (f.getOrNull(3)?.toIntOrNull() ?: 0).takeIf { it in 1..7 } ?: 0,
                        note = unesc(f.getOrNull(4).orEmpty()),
                        swapToWeek = f.getOrNull(5)?.toIntOrNull() ?: -1,
                    )
                }
                "C" -> {
                    val id = unesc(f.getOrNull(1).orEmpty())
                    if (id.isEmpty()) return@forEach
                    courses += Course(
                        id = id,
                        name = unesc(f.getOrNull(2).orEmpty()),
                        location = unesc(f.getOrNull(3).orEmpty()),
                        teacher = unesc(f.getOrNull(4).orEmpty()),
                        dayOfWeek = (f.getOrNull(5)?.toIntOrNull() ?: 1).coerceIn(1, 7),
                        startPeriod = (f.getOrNull(6)?.toIntOrNull() ?: 1).coerceAtLeast(1),
                        endPeriod = (f.getOrNull(7)?.toIntOrNull() ?: 1).coerceAtLeast(1),
                        startWeek = (f.getOrNull(8)?.toIntOrNull() ?: 1).coerceAtLeast(1),
                        endWeek = (f.getOrNull(9)?.toIntOrNull() ?: 16).coerceAtLeast(1),
                        parity = runCatching { WeekParity.valueOf(f.getOrNull(10).orEmpty()) }
                            .getOrDefault(WeekParity.ALL),
                        reminderMinutes = f.getOrNull(11)?.toIntOrNull() ?: -1,
                        colorIndex = f.getOrNull(12)?.toIntOrNull() ?: 0,
                        weekSpec = unesc(f.getOrNull(13).orEmpty()),
                        importance = (f.getOrNull(14)?.toIntOrNull() ?: 1).coerceIn(0, 2),
                    )
                }
                "T" -> {
                    val id = unesc(f.getOrNull(1).orEmpty())
                    if (id.isEmpty()) return@forEach
                    tasks += Task(
                        id = id,
                        title = unesc(f.getOrNull(2).orEmpty()),
                        courseName = unesc(f.getOrNull(3).orEmpty()),
                        estimateMinutes = (f.getOrNull(4)?.toIntOrNull() ?: 60).coerceIn(1, 100_000),
                        spentMinutes = (f.getOrNull(5)?.toIntOrNull() ?: 0).coerceAtLeast(0),
                        deadline = unesc(f.getOrNull(6).orEmpty()),
                        priority = (f.getOrNull(7)?.toIntOrNull() ?: 1).coerceIn(0, 2),
                        notes = unesc(f.getOrNull(8).orEmpty()),
                        done = f.getOrNull(9) == "1",
                        createdAt = unesc(f.getOrNull(10).orEmpty()),
                        chunkMinutes = f.getOrNull(11)?.toIntOrNull() ?: -1,
                        daily = f.getOrNull(12) == "1",
                        completedOn = unesc(f.getOrNull(13).orEmpty()),
                        earliestStart = unesc(f.getOrNull(14).orEmpty()),
                        dependencyIds = f.getOrNull(15).orEmpty()
                            .split(',')
                            .map(::unescListItem)
                            .filter(String::isNotBlank)
                            .distinct(),
                        estimateUnknown = f.getOrNull(16) == "1",
                    )
                }
            }
        }
        if (periods.isNotEmpty()) settings = settings.copy(periods = periods)
        if (overrides.isNotEmpty()) settings = settings.copy(overrides = overrides)
        return ScheduleData(courses, settings, tasks)
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    /** 任务 ID 通常是 UUID；仍转义列表分隔符，避免手工导入的数据破坏字段。 */
    private fun escListItem(s: String): String = esc(s).replace(",", "\\c")

    private fun unescListItem(s: String): String = unesc(s.replace("\\c", ","))

    private fun unesc(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    '\\' -> sb.append('\\')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
