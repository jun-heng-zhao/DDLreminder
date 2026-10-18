package com.balance.deadlinereminder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.balance.deadlinereminder.data.AppSettings
import com.balance.deadlinereminder.data.Course
import com.balance.deadlinereminder.data.Store
import com.balance.deadlinereminder.data.Task
import com.balance.deadlinereminder.data.ThemeMode
import com.balance.deadlinereminder.data.taskDayAt
import com.balance.deadlinereminder.domain.PlanEngine
import com.balance.deadlinereminder.domain.WeekCalc
import com.balance.deadlinereminder.remind.Notifier
import com.balance.deadlinereminder.remind.ReminderScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Composable
fun AppRoot(onThemeModeChange: (ThemeMode) -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { Store.get(context) }
    val lifecycleOwner = LocalLifecycleOwner.current // 仅在前台恢复与检查日期

    var courses by remember { mutableStateOf(store.courses) }
    var settings by remember { mutableStateOf(store.settings) }
    val tasks by store.taskFlow.collectAsState()
    val pager = rememberPagerState(initialPage = 0, pageCount = { 3 }) // 任务、日历、设置；首屏就是今日任务
    val scope = rememberCoroutineScope()
    val tab = pager.currentPage
    fun openPage(page: Int) { scope.launch { pager.animateScrollToPage(page) } }
    var weekOffset by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Course?>(null) }
    var newSlot by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var settingsSecondaryPage by remember { mutableStateOf(false) } // 二级设置页自行处理右滑返回
    var today by remember { mutableStateOf(LocalDate.now()) } // 跨日后触发今日课程与周数重算
    var taskDay by remember { mutableStateOf(taskDayAt(LocalDateTime.now())) } // 每日任务固定在凌晨 4 点换日
    var planRefresh by remember { mutableIntStateOf(0) }     // 隔几分钟重排一次，时间推移不会让"预计做完"变旧

    fun persist(newCourses: List<Course> = courses, newSettings: AppSettings = settings) {
        if (newCourses == courses && newSettings == settings) return
        courses = newCourses
        settings = newSettings
        store.update(newCourses, newSettings)
        onThemeModeChange(newSettings.themeMode)
        ReminderScheduler.reschedule(context)
    }

    /** 任务改动由 Store 发布给界面，完成落盘后再重排闹钟。 */
    fun persistTasks(update: () -> List<Task>) {
        update()
        ReminderScheduler.reschedule(context)
    }

    // 排程引擎是纯函数：课表 / 设置 / 任务一变就重算，算出来的就是"什么时候做什么"
    val plan = remember(courses, settings, tasks, today, planRefresh) {
        PlanEngine.build(settings, courses, tasks, LocalDateTime.now())
    }

    LaunchedEffect(Unit) {
        Notifier.ensureChannels(context)
        ReminderScheduler.reschedule(context)
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // 后台取消循环，恢复立即刷新；不注册解锁广播、不写课表文件。
            var ticks = 0
            while (true) {
                val now = LocalDateTime.now()
                val date = now.toLocalDate() // 课程日期仍按自然日切换
                if (date != today) {
                    today = date
                }
                val nextTaskDay = taskDayAt(now)
                if (nextTaskDay != taskDay) {
                    taskDay = nextTaskDay
                    store.refreshDailyTasks(now)
                    ReminderScheduler.reschedule(context)
                }
                ticks++
                if (ticks % 10 == 0) planRefresh++ // 约每 5 分钟重排，已过去的时段自动消失
                delay(30_000)
            }
        }
    }

    val currentWeek = WeekCalc.teachingWeek(settings, today)
    val shownWeek = (currentWeek + weekOffset).coerceAtLeast(1)

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = {
                        openPage(0)
                    },
                    icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                    label = { Text("任务") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = {
                        openPage(1)
                    },
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    label = { Text("日历") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { openPage(2) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("设置") },
                )
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            userScrollEnabled = !(tab == 2 && settingsSecondaryPage),
            modifier = Modifier.padding(padding),
        ) { page ->
            when (page) {
                0 -> Column(modifier = Modifier.fillMaxSize()) {
                    TaskScreen(
                        tasks = tasks,
                        courses = courses,
                        settings = settings,
                        plan = plan,
                        onSave = { task -> persistTasks { store.upsertTask(task) } },
                        onDelete = { taskId -> persistTasks { store.deleteTask(taskId) } },
                        onDone = { taskId, done -> persistTasks { store.setTaskDone(taskId, done) } },
                        onAddCourse = { newSlot = 1 to 1 },
                    )
                }

                1 -> CalendarScreen(
                    settings = settings,
                    courses = courses,
                    active = tab == 1,
                    onChange = { persist(newSettings = it) },
                )

                else -> SettingsScreen(
                    settings = settings,
                    courses = courses,
                    active = tab == 2,
                    onImportCourses = { parsed ->
                        val added = parsed.map { it.copy(id = UUID.randomUUID().toString()) }
                        persist(courses + added)
                        openPage(0)
                    },
                    onChange = { persist(newSettings = it) },
                    onSecondaryPageChange = { settingsSecondaryPage = it },
                    timetable = {
                        CourseSettingsScreen(
                            courses = courses,
                            settings = settings,
                            week = shownWeek,
                            currentWeek = currentWeek,
                            onWeekOffset = { weekOffset += it },
                            onBackToNow = { weekOffset = 0 },
                            onAdd = { newSlot = 1 to 1 },
                            onEdit = { editing = it },
                        )
                    },
                    onRestore = {
                        courses = store.courses
                        settings = store.settings
                        weekOffset = 0
                        ReminderScheduler.reschedule(context)
                    },
                    onTestNotification = { Notifier.testNotification(context) },
                )
            }
        }
    }

    newSlot?.let { (day, period) ->
        EditCourseDialog(
            course = Course(
                id = UUID.randomUUID().toString(),
                name = "",
                dayOfWeek = day,
                startPeriod = period,
                endPeriod = period,
                startWeek = shownWeek.coerceAtLeast(1),
                endWeek = settings.totalWeeks,
                reminderMinutes = -1,
            ),
            title = "添加课程",
            totalWeeks = settings.totalWeeks,
            onDismiss = { newSlot = null },
            onSave = { saved ->
                persist(newCourses = courses.filterNot { it.id == saved.id } + saved)
                newSlot = null
            },
            onDelete = null,
        )
    }

    editing?.let { course ->
        EditCourseDialog(
            course = course,
            title = "编辑课程",
            totalWeeks = settings.totalWeeks,
            onDismiss = { editing = null },
            onSave = { saved ->
                val next = courses.map { if (it.id == saved.id) saved else it } // 保留顺序，无改动时不写盘
                persist(newCourses = next)
                editing = null
            },
            onDelete = { deleted ->
                persist(newCourses = courses.filterNot { it.id == deleted.id })
                editing = null
            },
        )
    }
}
