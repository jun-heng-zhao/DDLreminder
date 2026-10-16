# DDL 提醒

源码仓库：[jun-heng-zhao/DDLreminder](https://github.com/jun-heng-zhao/DDLreminder)

仓库中的校历、示例课表和图标素材来自公开资源；这些第三方资源不自动继承本项目的 MIT 许可，使用时请遵守其原始许可或来源要求。

把课表截图识别成课程，再把「作业 / 论文 / 复习」这类任务填进课程之间的空档，算出「什么时候做、来不来得及」；截止前三级提醒，每天早上播报今天要推进什么。全程离线，课表和任务只写在手机本地。

包名 `com.balance.deadlinereminder`，Android 原生（Kotlin + Jetpack Compose），minSdk 26。

## 功能

### 课程（沿用原课表项目的能力）

| 能力     | 说明                                                                                                                                   |
| -------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| 课表录入 | 在课表页点格子手动加/编辑课程；或从课表图片 OCR 识别后核对导入                                                                         |
| 作息时间 | 内置华侨大学作息表（第 1–13 节，`data/Model.kt` 的 `defaultPeriods()`），也能从「作息时间表」图片识别覆盖                              |
| 教学周   | 从校历图片识别第一周周一与教学周数；支持起止周、单双周、以及不规则周次 `Course.weekSpec`（如 `1-4,6-8`、`1-16双`）                     |
| 调休     | 按天覆盖（`DayOverride`）：放假 / 按别的星期几上课。可读系统日历里的法定节假日，也可一键应用内置 2026 安排                             |
| 上课提醒 | 课前 N 分钟通知；每门课可用 `Course.reminderMinutes` 单独设，`-1` 表示跟随全局默认；可选强提醒（独立渠道 + 自己放 20 秒铃声 + 长震动） |
| 日历页   | 月视图，标出放假、调休「待安排」、每天有几节课                                                                                         |

### DDL 任务（本次新增的主线）

| 能力     | 说明                                                                                                                                        |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| 录入任务 | 标题、科目（可空）、预计需要多少分钟、截止时间、优先级（低/中/高）、备注；快捷按钮「今晚 / 明晚 / 后天晚 / 一周后」把截止一键设成当天 23:59 |
| 排程     | `domain/PlanEngine.kt` 纯 Kotlin 计算，把任务摊到课程空档上，见下一节                                                                       |
| 进度登记 | 任务卡上「＋30 分钟 / ＋60 分钟 / 完成 / 撤销完成」；通知上「登记 N 分钟 / 整件完成」                                                       |
| 风险提示 | 任务页顶部「按现在的课表，这些事来不及」，列出排不下（IMPOSSIBLE）和已过期（OVERDUE）的任务                                                 |
| 下一件事 | 任务页顶部一张「下一件事」卡：下一个还没结束的工作块，几点开始、做什么                                                                      |
| 今日安排 | 课表页「今日安排 · 已排 X」卡：今天排了哪几块、各多长时间，每块可一键「登记」                                                               |
| 进度条   | 任务卡按 `spentMinutes / estimateMinutes` 画进度条                                                                                          |

## 排程算法（`domain/PlanEngine.kt`）

纯函数，不碰 Android API，可在 JVM 上直接单测。

**输入**

1. 课程表（什么时候有课）+ 设置（几点起、几点睡、每天最多干多久）+ 任务（要多久、什么时候截止）。
2. 只在 `[wakeMinute, sleepMinute]` 之间排活（默认 08:00–23:00，`workWindowEnd` 至少比 `workWindowStart` 晚 30 分钟）；每天再封顶 `dailyCapMinutes`（默认 180 分钟）。
3. 上课时间一律不排；每日上限从当天最早的空档开始累计，超出的部分直接砍掉。
4. 计算窗口 = `min(now + 14 天, 最晚的一个截止时间)`。

**排序规则**

- 参与排程的任务：未完成、`remainingMinutes > 0`、截止时间在当前时刻之后。
- 顺序：**先截止的先排** → 同一截止时间比优先级（高在前）→ 再比标题。
- 剩余时间用完、或窗口内没有可用空档，就停下。

**块的时长规则**

- 一块的长度 = `min(空档剩余, 距该任务截止还有多久, 任务剩余时间, chunk)`。
- `chunk` = 任务自己的 `chunkMinutes`（>0 时），否则用全局 `focusChunkMinutes`（默认 60）。
- 排满一个完整 chunk 后，光标额外加 `breakMinutes`（默认 10）休息；没排满就直接接着排。
- 当 `min(空档剩余, 距截止时间) < min(minSlotMinutes, 任务剩余时间)` 就停止切块，短于 `minSlotMinutes`（默认 20）的碎缝不排。
- 排不下的一件任务会「能排多少排多少」，差额记在 `TaskPlan.shortfallMinutes`。

**可行性状态**

| 状态          | 含义                                                                                          |
| ------------- | --------------------------------------------------------------------------------------------- |
| `DONE`        | `remainingMinutes == 0`，已经做完（登记满了或手动完成）                                       |
| `COMFORTABLE` | 排得下，且富余时间 ≥ 阈值                                                                     |
| `TIGHT`       | 排得下，但富余时间 < 阈值（阈值 = `max(30, estimateMinutes / 4)` 分钟），一次意外就会踩过截止 |
| `IMPOSSIBLE`  | 计算窗口内排不下                                                                              |
| `OVERDUE`     | 截止时间已过且没做完                                                                          |
| `NO_DEADLINE` | 没填截止时间，不参与排程                                                                      |

除了状态，排程还会给出「预计完成时间」（`finishAt`）、「富余/还差多少分钟」（`slackMinutes`）。

**三级 DDL 提醒的触发时机**

| 级别 | 触发时刻                              | 渠道     | 通知动作           |
| ---- | ------------------------------------- | -------- | ------------------ |
| 预警 | 截止前 `ddlWarnHours`（默认 24）小时  | DDL 预警 | 「完成」           |
| 紧急 | 截止前 `ddlUrgentHours`（默认 3）小时 | DDL 预警 | 「完成」，并放响铃 |
| 截止 | 截止时刻                              | DDL 预警 | 「完成」，并放响铃 |

另外两类提醒：

- **任务时段开始**：每个排好的工作块开始时提醒（`sessionReminderEnabled`，默认开），通知上直接带「登记 N 分钟」（N = 这一段计划的长度）与「整件完成」两个按钮，点一下就把进度写回本地。
- **每日播报**：默认 07:30（`digestMinute`），播报今天要推进哪几件事、共多少小时、最近的 DDL 还有多久。

所有闹钟在应用启动、响铃、开机、改数据时整体重排一次；已经过去的时刻不补发。

## 目录结构

```
DeadlineReminder/
├── app/
│   ├── build.gradle.kts                 # AGP 9.4.0 / Kotlin 2.4.20 / JDK 17 / minSdk 26
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      # 权限、4 个 BroadcastReceiver
│       │   ├── java/com/balance/deadlinereminder/
│       │   │   ├── MainActivity.kt                    # 单 Activity，挂 AppRoot
│       │   │   ├── data/
│       │   │   │   ├── Task.kt                        # Task 数据类 + 时间/时长格式化
│       │   │   │   ├── Model.kt                       # Course / DayOverride / AppSettings / 默认作息
│       │   │   │   ├── Store.kt                       # 单例仓库，写 filesDir/planner.txt
│       │   │   │   ├── Codec.kt                       # 纯文本行格式编解码（可单测）
│       │   │   │   ├── BuiltInHolidays.kt             # 内置 2026 节假日/调休
│       │   │   │   ├── DeviceCalendar.kt              # 读系统日历里的节假日
│       │   │   │   └── CalendarQueryCache.kt          # 日历查询缓存（IO 线程）
│       │   │   ├── domain/
│       │   │   │   ├── PlanEngine.kt                  # 排程引擎（纯 Kotlin）
│       │   │   │   └── WeekCalc.kt                    # 教学周 / 单双周 / 调休 / 找下一节课
│       │   │   ├── ocr/
│       │   │   │   ├── TimetableOcr.kt                # ML Kit 中文识别入口
│       │   │   │   ├── GridParser.kt                  # 课表网格解析
│       │   │   │   ├── PeriodParser.kt                # 作息时间表解析
│       │   │   │   ├── CalendarParser.kt              # 校历解析（第一周周一、教学周数）
│       │   │   │   └── OcrModels.kt
│       │   │   ├── remind/
│       │   │   │   ├── ReminderScheduler.kt           # 四类提醒挂 AlarmManager
│       │   │   │   ├── ReminderReceiver.kt            # 到点组装并发出通知
│       │   │   │   ├── TaskActionReceiver.kt          # 通知按钮 → 登记进度 / 完成
│       │   │   │   ├── BootReceiver.kt                # 开机 / 改时间后重排
│       │   │   │   └── Notifier.kt                    # 5 个通知渠道 + 发通知
│       │   │   └── ui/
│       │   │       ├── AppRoot.kt                     # 四页横滑导航 + 状态 + 排程重算
│       │   │       ├── ScheduleScreen.kt              # 课表页（今日课程、今日安排）
│       │   │       ├── TaskScreen.kt                  # 任务页（下一件事、风险提示、任务卡、编辑弹窗）
│       │   │       ├── ImportScreen.kt                # 导入页（相册/文件选图 + OCR 核对）
│       │   │       ├── CalendarScreen.kt              # 日历页（月视图 + 调休设置）
│       │   │       ├── SettingsScreen.kt              # 设置页（学期 / 作息 / 任务排程 / 通知）
│       │   │       ├── EditCourseDialog.kt            # 课程编辑弹窗
│       │   │       └── Format.kt                      # 时间格式化 + 输入校验
│       │   └── res/                                   # 应用图标与通知小图标 ic_notify_clock.xml
│       └── test/java/com/balance/deadlinereminder/
│           ├── PlanEngineTest.kt                      # 排程规则（14 个用例）
│           ├── WeekCalcTest.kt / GridParserTest.kt / PeriodAndCalendarParserTest.kt
│           ├── CodecTest.kt / CalendarQueryCacheTest.kt / SettingsValidationTest.kt
│           └── （测试结果以实际运行输出为准）
├── docs/
│   ├── SPEC.md                          # 规格文档
│   ├── icon-source.jpg                  # 应用图标原图（用户提供）
│   ├── sample-timetable.png             # 示例课表图片
│   └── 华侨大学2026-2027秋季校历.jpg
├── tools/make_sample_timetable.py       # 生成示例课表图片
├── gradle/wrapper/                      # Gradle wrapper（9.6.0）
└── settings.gradle.kts
```

## 构建 / 跑测试 / 装 APK

需要 JDK 17。

```bash
cd /home/Balance/AgentWork/Projects/DeadlineReminder

# 跑单元测试 + 打 debug 包
./gradlew :app:testDebugUnitTest :app:assembleDebug

# 产物
ls app/build/outputs/apk/debug/app-debug.apk

# 装到已连接的设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

发布签名从仓库外的 `keystore.properties` 读（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`），文件不存在时 release 构建不带签名。

## 使用步骤

1. **打开「设置」分类配置**：「学期与作息」填写第一周周一、总周数或校准当前周；「任务排程」调整每日工作量、专注休息、DDL 提醒与播报。输入、开关与加减按钮只改草稿，点击「保存设置」统一提交全部分类修改，「全部撤销」取消全部草稿。「提醒权限」中打开权限并发送测试通知。
2. **导入课表**：进入「设置 → 图片导入」，选图 → 核对识别结果 → 「导入这 N 门课」。作息图片和校历图片仍支持识别后明确应用；没有截图就在首页课表网格点空格子手动加课。
3. **记一件事**：首页任务区或「任务」页都可新增、编辑任务，填写标题、耗时、截止时间和优先级；首页不是仅有跳转入口。
4. **照着做、随手登记**：首页保留今日课程、今日安排、实际任务卡和周课表；计划块可直接「登记」，任务卡可「＋30 分钟 / ＋60 分钟 / 完成 / 编辑」。任务页另有下一件事与风险汇总；通知登记方式不变。

### 界面导航与本次验证边界

- 底栏为「任务 / 日历 / 设置」，与 3 页 `HorizontalPager` 共用页码；默认进入任务页，支持横滑并预加载相邻页。
- 任务页是首屏，直接合并今日课程、今日安排和任务区；页面只提供新增单节课与新增任务，不提供课表图片导入。低频课表图片、作息和校历导入放在设置次页。
- 操作按钮适度换行以适配窄屏；不改数据模型、文本存储、排程引擎、签名或版本号。
- 本轮已执行 `testDebugUnitTest assembleDebug lintDebug`，BUILD SUCCESSFUL，并通过 `git diff --check`；Debug APK 已用 `adb install -r` 覆盖安装到设备，前台包名为 `com.balance.deadlinereminder`，未卸载或清空数据。已核对今日/周课表切换，周课表不再显示表格/列表切换。真实课程数据、通知投递、OCR 保存和备份恢复仍未完整验收。

## 已知限制

1. **提醒窗口 8 天**：上课提醒、任务时段提醒、每日播报只往后排 8 天（`ReminderScheduler.HORIZON_DAYS`，全部闹钟总量上限 200 条，超出按时间先后截断）；DDL 的预警/紧急/截止三级提醒不受这个窗口限制，每一件没做完、有截止时间的任务都会挂上。窗口靠每次响铃、开机、改数据时整体重排向前滚——如果连续 8 天以上一次都不响（比如关掉每日播报又没有别的提醒），更远的时段提醒不会提前挂上。
2. **不会因为「今天没做」自动顺延**：排程是每次数据/日期变化时整体重算一次（前台约每 5 分钟刷新一次），不做累积欠账重排。
3. **过点的提醒不补发**：重排时已经早于「当前时刻 + 5 秒」的闹钟直接丢掉，避免开机后连环弹一堆通知。
4. **OCR 依赖原图清晰度**：小字号、合并单元格、彩色底纹容易漏识别；导入前必须人工核对，识别原文可复制出来排查。
5. **调休上班日默认「待安排」**：周末上班那天不会自动知道按星期几的课表上，要在「日历」页点那天手动指定。
6. **法定节假日来源有限**：优先读系统日历，没有数据时用内置的 2026 安排。
7. **国产 ROM 的省电策略**：小米/HyperOS 等需要手动允许自启动、后台运行，否则闹钟可能被推迟或杀掉。
8. **无联网、无登录、无云同步**：数据只写本机 `filesDir/planner.txt`，另有一份外部目录备份用于换包/换机恢复；换机需要自己拷 `planner.txt`。
9. **`compileSdk` 用 `release(37)`**：需要对应的 Android SDK 平台已安装，否则先同步 SDK。
