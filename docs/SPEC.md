# 规格：DDL 提醒（DeadlineReminder）

包名 `com.balance.deadlinereminder`。Android 原生，Kotlin + Jetpack Compose，minSdk 26 / targetSdk 36 / `compileSdk` = `release(37)`，JDK 17。

本规格描述的是**当前代码实现的行为**，字段名与默认值直接抄自 `app/src/main/java/com/balance/deadlinereminder/`。

---

## 1. 目标与用户故事

**目标**：在已有课表能力之上，新增「任务（DDL）」这条主线——用户只要交代「做什么、大概要多久、什么时候必须交」，应用就用课程空档算出「什么时候做、来不来得及」，并在关键节点把人叫回来。

**用户故事**

| 编号 | 故事                                                  | 期望结果                                                                                                          |
| ---- | ----------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| US-1 | 作为学生，我录一条「周三交的数分作业，估计要 3 小时」 | 任务保存到本地，立刻出现在任务页，并被排进空档                                                                    |
| US-2 | 作为学生，我不知道这几天到底能不能赶完                | 任务卡上直接给出「预计 X 月 X 日 XX:XX 做完，比截止早/只剩 N 小时」，以及 `COMFORTABLE / TIGHT / IMPOSSIBLE` 结论 |
| US-3 | 作为学生，我排不下的时候希望被明确告知                | 任务页顶部出现「按现在的课表，这些事来不及」，列出排不下（`IMPOSSIBLE`）与已过期（`OVERDUE`）的任务               |
| US-4 | 作为学生，我不想为了登记进度打开应用                  | 通知上直接点「登记 N 分钟」或「整件完成」，进度写回本地并重排闹钟                                                 |
| US-5 | 作为学生，我希望一天开始时知道今天要干什么            | 每天早上（默认 07:30）一条播报：今天要推进几件事、共多少小时                                                      |
| US-6 | 作为学生，我不想被课程提醒打扰到错过 DDL              | 截止前 24 小时预警、前 3 小时紧急提醒、截止当刻再提醒一次                                                         |
| US-7 | 作为学生，我不希望课表和任务被上传                    | 不申请 `INTERNET` 权限；数据只写本机 `filesDir/planner.txt`                                                       |

---

## 2. 数据模型

### 2.1 `Task`（`data/Task.kt`）

| 字段              | 类型      | 默认值     | 说明                                                            |
| ----------------- | --------- | ---------- | --------------------------------------------------------------- |
| `id`              | `String`  | 无（必填） | `UUID.randomUUID().toString()`                                  |
| `title`           | `String`  | 无（必填） | 要做什么；保存时校验非空                                        |
| `courseName`      | `String`  | `""`       | 关联的科目，可空；用来和课程对上，留空即私人事务                |
| `estimateMinutes` | `Int`     | `60`       | 预估总耗时（分钟）                                              |
| `spentMinutes`    | `Int`     | `0`        | 已投入时间（分钟），登记进度时累加                              |
| `deadline`        | `String`  | `""`       | 截止时间，格式 `yyyy-MM-ddTHH:mm`；空串表示未填                 |
| `priority`        | `Int`     | `1`        | `0`=低 `1`=中 `2`=高（`PRIORITY_NAMES = ["低","中","高"]`）     |
| `notes`           | `String`  | `""`       | 备注                                                            |
| `done`            | `Boolean` | `false`    | 是否完成                                                        |
| `createdAt`       | `String`  | `""`       | 创建时间，`formatDateTime()` 写入                               |
| `chunkMinutes`    | `Int`     | `-1`       | 单个任务一次最多安排多久；`-1` 表示跟随全局 `focusChunkMinutes` |

派生属性（非存储字段）：

| 成员                           | 定义                                                                              |
| ------------------------------ | --------------------------------------------------------------------------------- |
| `remainingMinutes`             | `(estimateMinutes - spentMinutes).coerceAtLeast(0)`                               |
| `deadlineTime: LocalDateTime?` | `parseDateTime(deadline)`；`""` → `null`                                          |
| `progress: Float`              | `spentMinutes / estimateMinutes`，夹在 `0f..1f`；`estimateMinutes <= 0` 时为 `0f` |
| `isOverdue(now)`               | `!done && deadlineTime?.isBefore(now) == true`                                    |
| `chunk(settings)`              | `if (chunkMinutes > 0) chunkMinutes else settings.focusChunkMinutes`              |

解析容错（`parseDateTime`，`DATE_TIME_PATTERN = "yyyy-MM-dd'T'HH:mm"`）：把空格替换成 `T` 后依次尝试 `yyyy-MM-ddTHH:mm` → `LocalDateTime.parse` → 只有日期时补 `23:59`；都失败返回 `null`。

### 2.2 `AppSettings` 新增的排程字段（`data/Model.kt`）

| 字段                     | 类型      | 默认值                 | 含义                                         |
| ------------------------ | --------- | ---------------------- | -------------------------------------------- |
| `wakeMinute`             | `Int`     | `8 * 60`（08:00）      | 每天几点开始可用（当天第几分钟）             |
| `sleepMinute`            | `Int`     | `23 * 60`（23:00）     | 每天几点之后不排活                           |
| `dailyCapMinutes`        | `Int`     | `180`                  | 每天最多安排多少分钟，防止把 15 小时都当空闲 |
| `focusChunkMinutes`      | `Int`     | `60`                   | 单次专注时长，排程按它切块                   |
| `breakMinutes`           | `Int`     | `10`                   | 两块之间的休息                               |
| `minSlotMinutes`         | `Int`     | `20`                   | 短于这个长度的空档不排                       |
| `sessionReminderEnabled` | `Boolean` | `true`                 | 到点提醒「该做这件事了」                     |
| `ddlWarnHours`           | `Int`     | `24`                   | 提前多少小时预警                             |
| `ddlUrgentHours`         | `Int`     | `3`                    | 提前多少小时紧急提醒                         |
| `digestEnabled`          | `Boolean` | `true`                 | 每天早上播报今天的安排                       |
| `digestMinute`           | `Int`     | `7 * 60 + 30`（07:30） | 播报时间                                     |

派生属性：

- `workWindowStart = wakeMinute.coerceIn(0, 1439)`
- `workWindowEnd = sleepMinute.coerceIn(0, 1440).coerceAtLeast(workWindowStart + 30)`

### 2.3 `AppSettings` 原有字段（沿用课表）

| 字段                     | 类型                | 默认值             | 含义                                            |
| ------------------------ | ------------------- | ------------------ | ----------------------------------------------- |
| `termStartDate`          | `String`            | `""`               | 第一周的周一，ISO `yyyy-MM-dd`                  |
| `totalWeeks`             | `Int`               | `20`               | 总教学周数                                      |
| `defaultReminderMinutes` | `Int`               | `20`               | 上课提醒默认提前分钟数                          |
| `strongReminder`         | `Boolean`           | `false`            | 上课强提醒：独立渠道 + 响铃 + 长震动 + 独立放音 |
| `periods`                | `List<PeriodTime>`  | `defaultPeriods()` | 第 1–13 节的起止时间（华侨大学作息表）          |
| `overrides`              | `List<DayOverride>` | `emptyList()`      | 逐日例外：放假 / 按别的星期几上课               |
| `manualWeek`             | `Int`               | `0`                | `>0` 时按校准周递增，`0` 用开学基准             |
| `manualWeekMonday`       | `String`            | `""`               | 校准所在周的周一，保留原开学日期以便恢复        |

### 2.4 其他数据类

- `Course(id, name, location="", teacher="", dayOfWeek=1, startPeriod=1, endPeriod=1, startWeek=1, endWeek=16, parity=WeekParity.ALL, reminderMinutes=-1, colorIndex=0, weekSpec="")`。`weekSpec` 非空时优先于起止周（例：`"15,16"`、`"1-4,6-8"`、`"1-16双"`）。
- `DayOverride(date, kind, swapToDayOfWeek=0, note="", swapToWeek=-1)`；`DayKind = NORMAL | HOLIDAY | SWAP`；`swapToDayOfWeek = 0` 表示调休上班日尚未安排。
- `PeriodTime(startMinute, endMinute)`。

### 2.5 持久化

- `data/Store.kt`：进程内单例 `Store.get(context)`，持有 `courses` / `settings` / `tasks` 三份内存态，**每次写操作立即落盘**。
- 主文件 `filesDir/planner.txt`；外部备份 `getExternalFilesDir(null)/planner.txt`（`exportToExternal()` / `importFromExternal()`）。
- 冷启动时若内部文件不存在而外部备份存在，自动把备份拷回内部再加载。
- `data/Codec.kt`：纯文本行格式，首行版本号 `v2`；每条记录一行，行首标记区分——`S` 设置、`P` 节次、`D` 日例外、`C` 课程、`T` 任务；字段用制表符分隔，`\`、制表符、换行分别转义为 `\\`、`\t`、`\n`。
- 解码容忍坏行与旧文件：读到 `v1`（只有课表）时任务列表为空，其余字段走默认值；`blank` 行与无法解析的行直接跳过，不抛异常。**不改用 Room / JSON**：数据量极小（几十条），且纯 Kotlin 格式可直接跑 JVM 单测。
- 唯一使用 `SharedPreferences` 的地方是 `remind/ReminderScheduler.kt`，键 `reminder_pending` / `codes`，用来记住已挂出的闹钟 requestCode 以便取消。
- 不申请 `INTERNET` 权限（见 `app/src/main/AndroidManifest.xml`），也不申请 `READ_CALENDAR` 之外的外部数据权限。

---

## 3. 排程算法规格（`domain/PlanEngine.kt`）

`PlanEngine` 是 `object`，只做纯计算，不引用任何 Android API。入口：

```kotlin
fun build(
    settings: AppSettings,
    courses: List<Course>,
    tasks: List<Task>,
    now: LocalDateTime,
    horizonDays: Int = HORIZON_DAYS,   // HORIZON_DAYS = 14
): Plan
```

### 3.1 输入与窗口

| 输入                                                             | 作用                                                       |
| ---------------------------------------------------------------- | ---------------------------------------------------------- |
| `settings.workWindowStart` / `workWindowEnd`                     | 每天可排活的时间窗                                         |
| `settings.dailyCapMinutes`                                       | 每日总量上限                                               |
| `settings.focusChunkMinutes` / `breakMinutes` / `minSlotMinutes` | 切块粒度、块间休息、最小可用空档                           |
| `courses` + `settings`（含 `overrides`、`manualWeek`）           | 经 `WeekCalc.coursesOn()` 换算成当天的上课时间段，全部不排 |
| `tasks`                                                          | 待排的工作量与截止时间                                     |
| `now`                                                            | 所有判断的时间基准；显式传入以便单测固定时间               |

窗口计算：

- `horizon = now + 14 天`
- `planUntil = if (无任何截止时间) horizon else min(horizon, 最晚截止时间)`
- `freeMinutes` = `[now, planUntil]` 内所有空档分钟数之和
- `requiredMinutes` = **所有未完成任务**的 `remainingMinutes` 之和（包含没截止时间和已过期的）

每日空档（`freeSlots` → `daySlots`）规则：

1. 从 `max(当天 workWindowStart, from)` 到 `min(当天 workWindowEnd, to)`。
2. 逐条减去当天的上课时间段。
3. 每日上限：从当天**最早**的空档开始累计 `dailyCapMinutes`，超出部分直接砍掉。
4. 返回按时间升序的空档列表。

`freeMinutesOn(settings, courses, date, now)` 是给 UI 用的单日版本（范围 `max(now, 当天 00:00)` ～ 当天 `23:59`）。

### 3.2 排序规则

**参与排程的任务**（`active`）三重过滤：`!done` → `remainingMinutes > 0` → `deadlineTime` 晚于 `now`。

排序：`compareBy({ it.deadlineTime }, { -it.priority }, { it.title })` —— 先截止的先排，同截止比优先级（高在前），再按标题。

**结果列表**（`Plan.plans`）按同一顺序排列，但集合是**全部未完成任务**（含 `NO_DEADLINE` / `OVERDUE`，它们不参与填块）：`compareBy({ it.deadlineTime == null }, { it.deadlineTime ?: LocalDateTime.MAX }, { -it.priority }, { it.title })`。

### 3.3 块的时长规则

对每个空档，从空档起点开始循环：

```
candidate = 第一个 remaining > 0 且 deadlineTime > cursor 的任务（按 3.2 顺序）
left        = 该任务剩余分钟
untilDdl    = cursor 到该任务截止时间的分钟数
avail       = cursor 到空档结束的分钟数
若 min(avail, untilDdl) < min(minSlotMinutes, left) → 放弃这个空档（break）
give        = min(avail, untilDdl, left, chunk).coerceAtLeast(1)
产出块 [cursor, cursor + give)，任务剩余时间 -= give
cursor += give，且若 give >= chunk 再 += breakMinutes（排满一块才留休息）
```

- `chunk` = `task.chunk(settings)`，即任务自己的 `chunkMinutes`（>0 时）或全局 `focusChunkMinutes`。
- 一件任务排不下时「能排多少排多少」，差额记在 `TaskPlan.shortfallMinutes = remainingMinutes - plannedMinutes`。
- 输出 `Plan.blocks` 按时间升序，`WorkBlock(taskId, title, start, end)`，`minutes` 由起止时间算出。

### 3.4 每件任务的结论（`TaskPlan`）

| 字段             | 含义                                                      |
| ---------------- | --------------------------------------------------------- |
| `plannedMinutes` | 已排进去的分钟数；排不下时小于 `remainingMinutes`         |
| `finishAt`       | 全部做完的时刻；排不下为 `null`                           |
| `slackMinutes`   | `finishAt` 到 `deadline` 的分钟数；正数=富余，负数=来不及 |
| `feasibility`    | 见下表                                                    |

判定顺序（**短路的 `when`，顺序即优先级**）：

1. `remainingMinutes == 0` → `DONE`
2. `deadline == null` → `NO_DEADLINE`
3. `deadline.isBefore(now)` → `OVERDUE`
4. `finishAt == null` → `IMPOSSIBLE`
5. `slack < tightThreshold(task)` → `TIGHT`
6. 其余 → `COMFORTABLE`

`tightThreshold(task) = max(30, task.estimateMinutes / 4)`（分钟）。

| 状态          | 触发条件                           | 含义                               |
| ------------- | ---------------------------------- | ---------------------------------- |
| `DONE`        | `remainingMinutes == 0`            | 已做完（登记满了，或手动标记完成） |
| `NO_DEADLINE` | `deadlineTime == null` 且未做完    | 没法排程                           |
| `OVERDUE`     | `deadlineTime` 早于 `now` 且未做完 | 已过期                             |
| `IMPOSSIBLE`  | 窗口内没排出完整结果               | 按现在的时间表做不完               |
| `TIGHT`       | 富余 < `max(30, estimate/4)` 分钟  | 排得下但几乎踩点                   |
| `COMFORTABLE` | 其余                               | 排得下且富余充足                   |

### 3.5 边界情况

| 情况                                                         | 行为                                                                                                                                                                                                                               |
| ------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **无截止时间**（`deadline == ""`）                           | 不进入 `active`，永远不产生工作块；`feasibility = NO_DEADLINE`；但它计入 `Plan.requiredMinutes`（因此 `freeMinutes` 与 `requiredMinutes` 的对比会把它们算作需求）。UI 提示：「留空截止时间也能记，但不会排进时间表，也不会提醒。」 |
| **已过期**（`deadlineTime <= now`）                          | 不进入 `active`，不排块；`feasibility = OVERDUE`；出现在任务页顶部风险提示里。                                                                                                                                                     |
| **已完成**（`done == true`）                                 | 被 `!it.done` 过滤掉，既不出现在 `plans` 也不出现在 `requiredMinutes`。已完成任务不再有任何提醒。                                                                                                                                  |
| **已完成但只登记了一半**（`done == true, spent < estimate`） | 同样被整体排除。（`Store.setTaskDone(id, true)` 会把 `spentMinutes` 抬到 `max(spent, estimate)`，所以正常路径不会出现。）                                                                                                          |
| **`remainingMinutes == 0` 但 `done == false`**               | 进入 `plans`，判定为 `DONE`，不排块。`Store.addSpent` 在 `spent >= estimate` 时会顺手把 `done` 置 `true`。                                                                                                                         |
| **排不下**                                                   | 排出能排的部分（可能一个块都没有），`finishAt = null`，`feasibility = IMPOSSIBLE`，`shortfallMinutes > 0`。                                                                                                                        |
| **截止时间落在空档中间**                                     | `untilDdl` 截断该块，块在截止时刻前结束，不会跨越截止时间。                                                                                                                                                                        |
| **剩余空档短于 `minSlotMinutes`**（或短于任务剩余时间）      | 停止在这个空档继续切块（`break`，不是 `continue`），剩余时间留给后面的空档。                                                                                                                                                       |
| **空档不足 1 分钟 / 逾期 `from >= to`**                      | `freeSlots` 直接返回空列表。                                                                                                                                                                                                       |
| **`sleepMinute <= wakeMinute`**                              | `workWindowEnd` 兜底为 `workWindowStart + 30`，不会出现负长度窗口。                                                                                                                                                                |
| **一件任务被拆到多个空档**                                   | 允许；`blocks.filter { it.taskId == ... }` 汇总，`finishAt` 取最后一个块的 `end`。                                                                                                                                                 |
| **`give` 计算为 0**                                          | `coerceAtLeast(1)` 保证至少 1 分钟，配合上面的 `break` 条件不会死循环。                                                                                                                                                            |

### 3.6 `Plan` 上的查询

| 成员              | 行为                                                                      |
| ----------------- | ------------------------------------------------------------------------- |
| `planFor(taskId)` | 取某件任务的 `TaskPlan`                                                   |
| `blocksOn(date)`  | 某天的工作块                                                              |
| `nextBlock()`     | `blocks` 中第一个 `end` 晚于 `generatedAt` 的块（「下一件事」卡的数据源） |
| `riskyPlans`      | `feasibility` 为 `IMPOSSIBLE` 或 `OVERDUE` 的任务（风险提示卡的数据源）   |
| `tightPlans`      | `feasibility` 为 `TIGHT` 的任务                                           |

重算时机（`ui/AppRoot.kt`）：`remember(courses, settings, tasks, today, planRefresh)` 触发，即课表/设置/任务变化、跨日、或前台每约 5 分钟（`ticks % 10`，30 秒一次 tick）刷新一次。

---

## 4. 提醒规格

### 4.1 通知渠道（`remind/Notifier.kt`）

| 渠道 id                 | 名称         | 重要性               | 说明                                        |
| ----------------------- | ------------ | -------------------- | ------------------------------------------- |
| `class_reminder`        | 上课提醒     | `IMPORTANCE_HIGH`    | 课前 N 分钟提醒上课地点                     |
| `class_reminder_strong` | 上课强提醒   | `IMPORTANCE_HIGH`    | 渠道声音设为闹钟音 + 震动；另由代码自己放音 |
| `ddl_session`           | 该做这件事了 | `IMPORTANCE_HIGH`    | 排好的任务时段开始时提醒                    |
| `ddl_deadline`          | DDL 预警     | `IMPORTANCE_HIGH`    | 临近截止时间时提醒，并给出剩余工作量        |
| `ddl_digest`            | 今日计划播报 | `IMPORTANCE_DEFAULT` | 每天早上播报今天要推进什么                  |

所有通知：小图标 `R.drawable.ic_notify_clock`，`BigTextStyle`，点击打开 `MainActivity`，`setAutoCancel(true)`；强提醒额外 `PRIORITY_MAX`、`CATEGORY_ALARM`、震动序列 `0,600,300,600,300,600`。通知 id 由 `ReminderScheduler.notificationId(key) = key.hashCode() and 0x7fffffff` 算出（同一工作块重复提醒会覆盖而不是堆叠）。`areNotificationsEnabled()` 为 false 时静默放弃。

### 4.2 触发器

`remind/ReminderScheduler.kt` 在**应用启动、响铃、开机/换包/改时间、数据变更**时整体 `reschedule()`：先 `cancelAll()`，再重算全部闹钟。上课提醒、任务时段提醒、每日播报的窗口是 `HORIZON_DAYS = 8` 天；DDL 三级提醒逐件遍历未完成任务、不受窗口限制（只要求截止时间晚于 now）。全部闹钟总量上限 `MAX_ALARMS = 200` 条，按触发时刻排序后截断；早于 `now + 5 秒`（`MIN_LEAD_MILLIS`）的闹钟直接丢弃、不补发。

| #   | 提醒             | 触发时刻                                                       | 收件 Intent action                          | 开关                                                                 |
| --- | ---------------- | -------------------------------------------------------------- | ------------------------------------------- | -------------------------------------------------------------------- |
| 1   | 上课提醒         | 每节课开始前 `WeekCalc.reminderMinutes(course, settings)` 分钟 | `com.balance.deadlinereminder.REMIND_CLASS` | 始终开启                                                             |
| 2   | 任务时段开始     | 每个工作块的 `start`                                           | `...REMIND_SESSION`                         | `sessionReminderEnabled`，且存在 `remainingMinutes > 0` 的未完成任务 |
| 3   | DDL 预警（三级） | 见下表                                                         | `...REMIND_DEADLINE`                        | 任务有截止时间且晚于 now                                             |
| 4   | 每日播报         | 从今天起 8 天内每天 `digestMinute` 时刻                        | `...REMIND_DIGEST`                          | `digestEnabled`                                                      |

DDL 三级（`LEVEL_WARN / LEVEL_URGENT / LEVEL_FINAL`）：

| 级别     | 触发时刻                                   | 行为                                           |
| -------- | ------------------------------------------ | ---------------------------------------------- |
| `warn`   | `deadline - ddlWarnHours`（默认 24 小时）  | 发通知，正文含剩余工作量与「按计划能不能赶上」 |
| `urgent` | `deadline - ddlUrgentHours`（默认 3 小时） | 发通知，并 `playAlarm()` 响铃                  |
| `final`  | `deadline` 当刻                            | 发通知，并 `playAlarm()` 响铃                  |

`playAlarm()` 用 `MediaPlayer` 走 `USAGE_ALARM` 音频流循环播放系统闹钟音，20 秒后停止并释放（渠道声音在 HyperOS 上不一定响，所以自己放）。

闹钟类型：`RTC_WAKEUP`；`SDK < S` 或 `canScheduleExactAlarms()` 为 true 时用 `setExactAndAllowWhileIdle`，否则退化为 `setAndAllowWhileIdle`。

重排入口：`ReminderScheduler.reschedule(context)`；`BootReceiver` 监听 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` / `TIME_SET` / `TIMEZONE_CHANGED`。

### 4.3 通知动作（`remind/TaskActionReceiver.kt`）

| 通知                       | 按钮          | action                 | 效果                                                            |
| -------------------------- | ------------- | ---------------------- | --------------------------------------------------------------- |
| 任务时段（`showSession`）  | `登记 N 分钟` | `ACTION_TASK_PROGRESS` | `Store.addSpent(taskId, minutes)`，`minutes` = 这一段计划的长度 |
| 任务时段                   | `整件完成`    | `ACTION_TASK_DONE`     | `Store.setTaskDone(taskId, true)`                               |
| DDL 预警（`showDeadline`） | `完成`        | `ACTION_TASK_DONE`     | `Store.setTaskDone(taskId, true)`                               |

动作执行后取消对应通知并触发 `reschedule()`。`PendingIntent` 的 requestCode 由 `taskId.hashCode() * 31 + actionName.hashCode() + notificationId` 算出（`extras` 不参与 PendingIntent 身份判定，必须一条通知一个 code）。设置页另有 `Notifier.testNotification()`，固定 id `990_001`、走 `ddl_digest` 渠道，仅用于验证权限。

---

## 5. UI 结构（`ui/AppRoot.kt`）

底部 `NavigationBar` 与三页 `HorizontalPager` 共用 `PagerState.currentPage`；点击底栏动画切页，横滑同步选中项，预加载相邻一页。图片导入不再占主导航：

| #   | tab  | 文件                   | 主要内容                                                                                                                                                          |
| --- | ---- | ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 0   | 任务 | `ui/ScheduleScreen.kt` | 今日模式按时间合并课程与排程任务，可登记/完成工作块；待安排任务显示风险入口；周课表参考 ClassReminder，按星期分组展示课程卡片；提供新增任务、新增单节课与课程编辑 |
| 1   | 日历 | `ui/CalendarScreen.kt` | 月视图、逐日放假/调休、系统日历和内置 2026 安排、已设置例外删除                                                                                                   |
| 2   | 设置 | `ui/SettingsScreen.kt` | 分类次页：学期与作息、任务排程、图片导入、数据备份、提醒权限；`BackHandler` 仅在当前页次级页面生效                                                                |

**设置与导入细节**：

- 学期与作息保留第一周周一、当前周校准、总周数、课程提前量/响铃、各节次起止时间。
- 任务排程完整保留每天开始/结束时间、每日上限、专注时长、休息、最短空档、时段提醒、DDL 预警/紧急提醒、每日播报及时间。所有开关和步进均只修改草稿，点击「保存设置」统一提交全部分类草稿，「全部撤销」和「取消」恢复全部已保存值。未改学期信息时可单独保存排程，不强迫配置开学日。
- 分类间返回不主动保存；草稿是页面内存态，未承诺进程死亡或 Pager 回收后保留。图片应用或备份恢复改变已保存设置后，草稿会按新设置重置。
- 图片导入保留相册/文件选图、三类 OCR、课程核对编辑/删除/清空、明确导入课程/应用作息/应用校历及复制原文；识别中禁止切换类型。导入课程后返回首页。
- 备份包含课程、设置和 DDL 任务，恢复同步三份界面状态并重排提醒；保留通知/精确闹钟设置、测试通知及 HyperOS 提示。
- 任务卡操作、任务弹窗快捷项、导入类型和日历导入按钮使用可换行布局，避免窄屏强行挤在一行。

**本轮验证边界**：已执行 `testDebugUnitTest assembleDebug lintDebug`，BUILD SUCCESSFUL，且 `git diff --check` 通过、编辑器诊断无错误。Debug APK 已通过 `adb install -r` 覆盖安装到 `e7781e7b`，前台包名为 `com.balance.deadlinereminder`；已核对今日/周课表切换，周课表不再出现表格/列表控件。真实课程数据、通知投递、OCR 保存和备份恢复仍未完整验收；未卸载、清数据或关机。

`TaskEditDialog` 字段：`要做什么`（必填）、`大概需要多少分钟`、`截止日期 yyyy-MM-dd（可空）`、`截止时间 HH:mm`、快捷按钮 `今晚 / 明晚 / 后天晚 / 一周后`（设为当天 `23:59`）、`科目（可空，用来对上课程空档）`（含「私人」与各课程 chip）、`优先级`（低/中/高）、`已经投入多少分钟（可改）`、`备注（可空）`；底部提示「留空截止时间也能记，但不会排进时间表，也不会提醒。」；编辑已有任务时提供「删除」。

---

## 6. 验收标准

逐条可在真机或代码上核对。

### 构建与测试

- [ ] `cd /home/Balance/AgentWork/Projects/DeadlineReminder && ./gradlew :app:testDebugUnitTest :app:assembleDebug` 成功退出，`app/build/outputs/apk/debug/app-debug.apk` 生成。
- [ ] `app/src/test/java/com/balance/deadlinereminder/` 下 7 个测试类共 69 个 `@Test` 全绿（`PlanEngineTest.kt` 14 个覆盖排程规则）。
- [ ] `PlanEngineTest.kt` 不引用任何 Android API（`PlanEngine` 与 `WeekCalc` 是纯 Kotlin）。

### 排程

- [ ] 两件任务截止时间不同时，先截止的先被排块；截止时间相同时 `priority` 高的先排。
- [ ] 同一任务的两个工作块之间至少隔 `breakMinutes`（当上一块排满一个 `chunk` 时）；单块长度不超过 `chunk`。
- [ ] 每天排给任务的总分钟数不超过 `dailyCapMinutes`，且不超出 `[workWindowStart, workWindowEnd]`。
- [ ] 上课时间不出现在任何工作块里。
- [ ] 任务的 `deadline` 落在空档中间时，该块在截止时刻前结束。
- [ ] `deadline` 留空的任务：`feasibility == NO_DEADLINE`，`blocks` 中无该任务，任务页不出现提醒。
- [ ] 已过截止时间且未完成的任务：`feasibility == OVERDUE`，出现在任务页风险提示中。
- [ ] `done == true` 的任务不出现在 `Plan.plans` 也不出现在 `requiredMinutes`。
- [ ] 工作量超出窗口容量的任务：`feasibility == IMPOSSIBLE`，`finishAt == null`，`shortfallMinutes > 0`。
- [ ] 富余时间小于 `max(30, estimateMinutes / 4)` 时状态为 `TIGHT`，否则为 `COMFORTABLE`。
- [ ] `Plan.nextBlock()` 返回第一个 `end` 晚于 `generatedAt` 的块；跨过该时刻后重算不再返回它。

### 提醒

- [ ] 有截止时间且未完成的任务，在截止前 `ddlWarnHours`、前 `ddlUrgentHours`、截止当刻各收到一条 `ddl_deadline` 渠道的通知。
- [ ] 任务时段开始时收到 `ddl_session` 渠道通知，按钮文字为「登记 N 分钟」+「整件完成」；点「登记 N 分钟」后任务 `spentMinutes` 增加 N 且通知消失。
- [ ] 点「整件完成」后任务 `done == true`、`spentMinutes` 被抬到 `estimateMinutes`、该任务不再产生任何闹钟。
- [ ] `digestEnabled` 打开时，每天 `digestMinute` 收到一条 `ddl_digest` 通知；关掉后不收。
- [ ] `sessionReminderEnabled` 关掉后不再收到任务时段通知，DDL 三级提醒不受影响。
- [ ] 手机重启后闹钟自动重建（`BootReceiver`）。
- [ ] 通知权限被拒绝时不崩溃、不刷屏；设置页「发一条测试通知」在授权后能收到。
- [ ] 通知点开后进入应用主界面。

### 数据

- [ ] 关闭应用再打开，课程、设置、任务全部保留。
- [ ] 新增/修改任务后立刻杀进程，重新打开数据仍在（写操作同步落盘）。
- [ ] `filesDir/planner.txt` 首行为 `v2`；手工删掉应用内部数据但保留外部目录备份后重开，数据自动恢复。
- [ ] 把 `planner.txt` 中某一行改坏，应用仍能正常启动并加载其余记录。
- [ ] `app/src/main/AndroidManifest.xml` 中没有任何联网权限。
