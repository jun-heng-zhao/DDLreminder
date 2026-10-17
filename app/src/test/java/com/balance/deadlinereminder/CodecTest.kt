package com.balance.deadlinereminder

import com.balance.deadlinereminder.data.AppSettings
import com.balance.deadlinereminder.data.Codec
import com.balance.deadlinereminder.data.Course
import com.balance.deadlinereminder.data.DayKind
import com.balance.deadlinereminder.data.DayOverride
import com.balance.deadlinereminder.data.ScheduleData
import com.balance.deadlinereminder.data.Task
import com.balance.deadlinereminder.data.ThemeMode
import com.balance.deadlinereminder.data.WeekParity
import com.balance.deadlinereminder.data.defaultPeriods
import org.junit.Assert.assertEquals
import org.junit.Test

class CodecTest {

    private val course = Course(
        id = "a\tb",
        name = "高等数学\n下",
        location = "教三\t201",
        teacher = "张老师",
        dayOfWeek = 3,
        startPeriod = 2,
        endPeriod = 3,
        startWeek = 2,
        endWeek = 9,
        parity = WeekParity.EVEN,
        reminderMinutes = 15,
        colorIndex = 4,
    )

    private val task = Task(
        id = "t\t1",
        title = "写传播学报告\n带数据",
        courseName = "传播学",
        estimateMinutes = 180,
        spentMinutes = 60,
        deadline = "2026-10-20T23:59",
        priority = 2,
        notes = "用去年的模板",
        done = false,
        createdAt = "2026-10-15T10:00",
        chunkMinutes = 45,
        daily = true,
        completedOn = "2026-10-17",
    )

    private val settings = AppSettings(
        termStartDate = "2026-09-07",
        totalWeeks = 18,
        defaultReminderMinutes = 25,
        strongReminder = true,
    )

    @Test
    fun encodeDecode_roundTripsIncludingSpecialChars() {
        val text = Codec.encode(ScheduleData(listOf(course), settings))
        val decoded = Codec.decode(text)

        assertEquals(listOf(course), decoded.courses)
        assertEquals(settings, decoded.settings.copy(periods = defaultPeriods()))
    }

    @Test
    fun tasks_roundTripWithAllFields() {
        val text = Codec.encode(ScheduleData(emptyList(), settings, listOf(task)))
        assertEquals(listOf(task), Codec.decode(text).tasks)
    }

    @Test
    fun 排程参数也一起落盘() {
        val tuned = settings.copy(
            wakeMinute = 7 * 60,
            sleepMinute = 22 * 60 + 30,
            dailyCapMinutes = 240,
            focusChunkMinutes = 45,
            breakMinutes = 15,
            minSlotMinutes = 25,
            sessionReminderEnabled = false,
            ddlWarnHours = 48,
            ddlUrgentHours = 6,
            digestEnabled = false,
            digestMinute = 8 * 60,
        )
        val decoded = Codec.decode(Codec.encode(ScheduleData(emptyList(), tuned))).settings
        assertEquals(7 * 60, decoded.wakeMinute)
        assertEquals(22 * 60 + 30, decoded.sleepMinute)
        assertEquals(240, decoded.dailyCapMinutes)
        assertEquals(45, decoded.focusChunkMinutes)
        assertEquals(15, decoded.breakMinutes)
        assertEquals(25, decoded.minSlotMinutes)
        assertEquals(false, decoded.sessionReminderEnabled)
        assertEquals(48, decoded.ddlWarnHours)
        assertEquals(6, decoded.ddlUrgentHours)
        assertEquals(false, decoded.digestEnabled)
        assertEquals(8 * 60, decoded.digestMinute)
    }

    @Test
    fun 主题模式落盘且旧备份默认跟随系统() {
        val dark = settings.copy(themeMode = ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, Codec.decode(Codec.encode(ScheduleData(settings = dark))).settings.themeMode)
        assertEquals(ThemeMode.SYSTEM, Codec.decode("v2\nS\t2026-09-07\t20\t20\t0").settings.themeMode)
    }

    @Test
    fun decode_toleratesUnknownAndBrokenLines() {
        val text = buildString {
            appendLine("v2")
            appendLine("这不是一条记录")
            appendLine("S\t2026-09-07\t20\t20\t0")
            appendLine("C\tx1\t高数\t\t\t1\t1\t1\t1\t16\tALL\t-1\t0")
            appendLine("T\tx2\t写作业\t\t60\t\t2026-10-20T23:59\t1\t\t0\t\t-1")
        }
        val data = Codec.decode(text)

        assertEquals(1, data.courses.size)
        assertEquals("高数", data.courses[0].name)
        assertEquals("2026-09-07", data.settings.termStartDate)
        assertEquals(1, data.tasks.size)
        assertEquals("写作业", data.tasks[0].title)
    }

    @Test
    fun decode_ofEmptyTextGivesDefaults() {
        val data = Codec.decode("")
        assertEquals(0, data.courses.size)
        assertEquals(0, data.tasks.size)
        assertEquals(20, data.settings.totalWeeks)
        assertEquals(defaultPeriods(), data.settings.periods)
    }

    @Test
    fun 坏掉的任务记录会被跳过而不是崩掉() {
        val text = "v2\nT\t\t没有 id 的任务\nT\tok\t正常任务\t\t30\t\t\t1\t\t0\t\t-1"
        val tasks = Codec.decode(text).tasks
        assertEquals(1, tasks.size)
        assertEquals("正常任务", tasks[0].title)
    }

    @Test
    fun calibrationAndPendingSwap_roundTrip() {
        val calibrated = settings.copy(
            manualWeek = 6,
            manualWeekMonday = "2026-10-12",
            overrides = listOf(DayOverride("2026-10-17", DayKind.SWAP)),
        )
        assertEquals(calibrated, Codec.decode(Codec.encode(ScheduleData(emptyList(), calibrated))).settings)
        assertEquals(0, Codec.decode("v2\nS\t2026-09-07\t20\t20\t0").settings.manualWeek)
    }

    @Test
    fun oldSwapRecords_preservePendingAndExplicitWeeks() {
        val text = "v2\nS\t2026-09-07\t20\t20\t0\nD\t2026-10-17\tSWAP\t0\t待安排\nD\t2026-10-18\tSWAP\t3\t补课\t5"
        val decoded = Codec.decode(text).settings // 旧备份没有校准字段与部分调休周字段
        assertEquals("", decoded.manualWeekMonday)
        assertEquals(0, decoded.overrides[0].swapToDayOfWeek)
        assertEquals(-1, decoded.overrides[0].swapToWeek)
        assertEquals(3, decoded.overrides[1].swapToDayOfWeek)
        assertEquals(5, decoded.overrides[1].swapToWeek)
        assertEquals(decoded, Codec.decode(Codec.encode(ScheduleData(emptyList(), decoded))).settings)
    }

    @Test
    fun brokenCalibrationFields_useSafeDefaults() {
        val broken = Codec.decode("v2\nS\t2026-09-07\t20\t20\t0\tbad").settings // 损坏字段不得阻止旧数据恢复
        val negative = Codec.decode("v2\nS\t2026-09-07\t20\t20\t0\t-2\t2026-10-12").settings
        assertEquals(0, broken.manualWeek)
        assertEquals("", broken.manualWeekMonday)
        assertEquals(0, negative.manualWeek)
    }

    @Test
    fun v1备份也能恢复() {
        val data = Codec.decode("v1\nS\t2026-09-07\t20\t20\t0\nC\tx1\t高数\t\t\t1\t1\t1\t1\t16\tALL\t-1\t0")
        assertEquals(1, data.courses.size)
        assertEquals(0, data.tasks.size)
    }
}
