package com.balance.deadlinereminder.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** 任务列表的唯一可观察状态，后台刷新与前台操作共用同一份数据。 */
class TaskState(initial: List<Task> = emptyList()) {
    private val mutableFlow = MutableStateFlow(initial)

    val flow: StateFlow<List<Task>> = mutableFlow.asStateFlow()
    val value: List<Task> get() = mutableFlow.value

    fun replace(tasks: List<Task>) {
        mutableFlow.value = tasks
    }

    fun refreshDaily(today: LocalDate): List<Task> {
        val refreshed = refreshDailyTaskStates(value, today)
        if (refreshed != value) replace(refreshed)
        return refreshed
    }
}

/**
 * 仓库：内存态 + 一个文本文件。数据量极小（几十条课、几十条任务），不需要数据库。
 * 所有写操作立即落盘，避免进程被杀丢数据。
 */
class Store private constructor(private val appContext: Context) {

    private val file = File(appContext.filesDir, "planner.txt")
    private val taskState = TaskState()

    /** 外部目录里的备份：换包/换手机时用它，非 debuggable 的正式包也能靠它恢复数据。 */
    private val externalFile: File?
        get() = appContext.getExternalFilesDir(null)?.let { File(it, "planner.txt") }

    var courses: List<Course> = emptyList()
        private set

    var settings: AppSettings = AppSettings()
        private set

    val taskFlow: StateFlow<List<Task>> = taskState.flow
    var tasks: List<Task>
        get() = taskState.value
        private set(value) = taskState.replace(value)

    init {
        load()
        refreshDailyTasks()
    }

    private fun load() {
        // 刚装完/刚换包时内部数据是空的，但外部目录里可能留着上次的备份 → 自动恢复
        if (!file.exists()) {
            externalFile?.takeIf { it.exists() }?.let { backup ->
                runCatching { file.writeText(backup.readText()) }
            }
        }
        if (!file.exists()) return
        val data = runCatching { Codec.decode(file.readText()) }.getOrNull() ?: return
        courses = data.courses
        settings = data.settings
        tasks = data.tasks
    }

    fun update(
        courses: List<Course> = this.courses,
        settings: AppSettings = this.settings,
        tasks: List<Task> = this.tasks,
        taskDay: LocalDate = taskDayAt(LocalDateTime.now()),
    ) {
        val maintainedTasks = refreshDailyTaskStates(tasks, taskDay)
        this.courses = courses
        this.settings = settings
        this.tasks = maintainedTasks
        runCatching { file.writeText(Codec.encode(ScheduleData(courses, settings, maintainedTasks))) }
    }

    /** 导出一份到外部目录，返回路径；失败返回 null。 */
    fun exportToExternal(): String? {
        val target = externalFile ?: return null
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeText(Codec.encode(ScheduleData(courses, settings, tasks)))
            target.absolutePath
        }.getOrNull()
    }

    fun externalBackupPath(): String? = externalFile?.takeIf { it.exists() }?.absolutePath

    /** 从外部目录恢复。 */
    fun importFromExternal(): Boolean {
        val source = externalFile?.takeIf { it.exists() } ?: return false
        val parsed = runCatching { Codec.decode(source.readText()) }.getOrNull() ?: return false
        update(parsed.courses, parsed.settings, parsed.tasks)
        return true
    }

    // ---- 课表 ----

    fun upsert(course: Course): List<Course> {
        val next = courses.toMutableList()
        val idx = next.indexOfFirst { it.id == course.id }
        if (idx >= 0) next[idx] = course else next += course
        update(courses = next)
        return next
    }

    fun delete(courseId: String): List<Course> {
        val next = courses.filterNot { it.id == courseId }
        update(courses = next)
        return next
    }

    // ---- DDL 任务 ----

    fun upsertTask(task: Task): List<Task> {
        val next = tasks.toMutableList()
        val idx = next.indexOfFirst { it.id == task.id }
        if (idx >= 0) next[idx] = task else next += task
        update(tasks = next)
        return tasks
    }

    fun deleteTask(taskId: String): List<Task> {
        val next = tasks
            .filterNot { it.id == taskId }
            .map { task -> task.copy(dependencyIds = task.dependencyIds.filterNot { it == taskId }) }
        update(tasks = next)
        return next
    }

    /** 登记投入：完成一段就累加，累加到预估耗时即视为做完。 */
    fun addSpent(taskId: String, minutes: Int): List<Task> {
        val taskDay = taskDayAt(LocalDateTime.now())
        val next = tasks.map { task ->
            if (task.id != taskId) task
            else {
                val spent = (task.spentMinutes + minutes).coerceAtLeast(0)
                val done = task.done || spent >= task.estimateMinutes
                task.copy(
                    spentMinutes = spent,
                    done = done,
                    completedOn = if (task.daily && done) taskDay.toString() else task.completedOn,
                )
            }
        }
        update(tasks = next, taskDay = taskDay)
        return tasks
    }

    fun setTaskDone(taskId: String, done: Boolean): List<Task> {
        val taskDay = taskDayAt(LocalDateTime.now())
        val next = tasks.map { task ->
            if (task.id != taskId) task
            else if (done) task.copy(
                done = true,
                spentMinutes = maxOf(task.spentMinutes, task.estimateMinutes),
                completedOn = if (task.daily) taskDay.toString() else task.completedOn,
            )
            else task.copy(done = false, spentMinutes = 0, completedOn = if (task.daily) "" else task.completedOn)
        }
        update(tasks = next, taskDay = taskDay)
        return tasks
    }

    /** 每天凌晨 4 点跨任务日，使上一任务日完成的每日任务重新出现。 */
    fun refreshDailyTasks(now: LocalDateTime = LocalDateTime.now()): List<Task> {
        val taskDay = taskDayAt(now)
        val previous = tasks
        val next = taskState.refreshDaily(taskDay)
        if (next != previous) update(tasks = next, taskDay = taskDay)
        return next
    }

    companion object {
        @Volatile
        private var instance: Store? = null

        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(context.applicationContext).also { instance = it }
        }
    }
}

/** 清除普通已完成任务并复位过期的每日任务；同一天重复调用不会改变数据。 */
fun refreshDailyTaskStates(tasks: List<Task>, today: LocalDate): List<Task> {
    val date = today.toString()
    val completedTaskIds = tasks.filter { !it.daily && it.done }.mapTo(mutableSetOf(), Task::id)
    return tasks.mapNotNull { task ->
        if (task.id in completedTaskIds) return@mapNotNull null
        if (task.daily && task.done && task.completedOn != date) {
            task.copy(done = false, spentMinutes = 0, completedOn = "")
        } else {
            task.copy(dependencyIds = task.dependencyIds.filterNot(completedTaskIds::contains))
        }
    }
}

/** 凌晨 4 点前仍算前一个任务日，避免熬夜完成后刚过午夜就立刻复位。 */
fun taskDayAt(now: LocalDateTime): LocalDate = now.minusHours(4).toLocalDate()

/** 严格晚于当前时刻的下一次凌晨 4 点刷新。 */
fun nextTaskRefreshAt(now: LocalDateTime): LocalDateTime {
    val todayAtFour = now.toLocalDate().atTime(4, 0)
    return if (todayAtFour.isAfter(now)) todayAtFour else todayAtFour.plusDays(1)
}
