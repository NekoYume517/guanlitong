# 学习通收件箱（P1 收窄版）

> 2026-11-27 范围收窄：只做「收件箱（手动刷新 + 按类型路由）+ 课程 + 刷课」。
> 作业/考试临期提醒、通知中心的任务/日志汇总已按验收要求删除（代码与单测）。
> 原方案的提醒/任务日志章节不再适用。

## 1. 收件箱同步

**接口**（对照老项目 `cx_api.py:1242 get_inbox_list`）：

```
GET https://notice.chaoxing.com/mobile/notice/getNoticeList?type=2
Referer: https://notice.chaoxing.com/res/pc/mobileHtml/html/inbox.html
```

**每次固定拉最新首页，请求不带 `lastValue`。** `lastValue` 在超星接口里是「取更早一页」
的翻页游标，若拿上一轮的 `lastGetId` 当同步游标，每次刷新都会往后翻历史（实测连续刷新
本地 20 → 40 → 60 → 80 条全不重复），新通知反而永远拉不到。`lastGetId` 只在本地存一份
作诊断，不参与请求。

- `notices.list` 与顶层 `topNotices` 合并，按 `idCode` 去重；
- `sourceType` → `14 作业 / 12 考试 / 2 签到 / 3、5 互动 / 0 系统 / 其他`，标题加类型 emoji；
- `isread` 只做 `remoteRead` 镜像，不回写超星；本地已读是独立字段；
- `attachment` 可能是 JSON 字符串或数组，取第一条 `att_web` 的 url/logo/content；
- `insertTime` 支持毫秒/秒时间戳与日期字符串；
- `status` falsy 且 `list`/`topNotices` 同时为空 → 空页 + `Log.w`（带响应前 200 字）；
  falsy 却带列表 → 502；避免「无消息」被当成失败反复重试。

**同步时机**：

- 后台：WorkManager 唯一周期任务 30 分钟，`NetworkType.CONNECTED`，`KEEP`；WorkManager
  持久化，重启后自动恢复；
- 手动：收件箱页面顶部刷新按钮，直接跑一次 Runner，**不发系统通知**。

## 2. 本地存储

SharedPreferences `guanlitong_cx_notice`（不引 Room）：

- `notices:<accountKey>`：JSON 数组，上限 200 条（先淘汰最旧已读，未读硬顶 300）；
- `inbox_cursor:<accountKey>` / `inbox_synced_at:<accountKey>`：最新页的 `lastGetId`
  诊断值与同步时间（`inbox_cursor` 不再作为翻页器）。

去重键 = accountKey + `cxInboxId`，换账号不串数据；退出登录不删历史。

## 3. 后台通知

- 新渠道 `cx_notice_bg`（旧 `cx_notice` 可能已创建，渠道不能降级，必须新 id）；
- `IMPORTANCE_LOW` + 无声音、无振动：后台同步新增条目时只发一条**静默摘要**
  「学习通收件箱 · 新增 N 条」，点开进收件箱；
- 没有新增不发；手动刷新不发；401/403 只记日志并中止本轮（不重试风暴）；
- 网络错误最多 `Result.retry` 两次。

## 4. UI

**入口**：`FeatureCatalog` 新增 `cx.notices`「收件箱 / 通知 · 签到 · 作业」，放在
`cx.checkin`（自动签到）旁边；`CxPage.Inbox`。冷启动 intent extra `cx_page=inbox` 直接进收件箱。

**列表** `CxInboxScreen`：

- 按 `timeMs` 倒序；未读高亮（`secondaryContainer` + 圆点 + 加粗标题）；
- 顶部手动刷新按钮；刷新中提示、失败提示（保留旧列表）；
- 点击条目即标本地已读并进详情。

**详情** `CxInboxDetailScreen`：标题 / 类型 / 时间 / 正文（可选中）/ 链接原文。

按类型路由（`inboxAction`）：

| 类型/链接 | 行为 |
|---|---|
| `inbox_checkin` 或 actionUrl 含 `sign` | 签到区：展示活动信息 +「点击签到」；点了才提交（`CxCheckinApi.activeList` 找到活动 → `checkin`）。自动签到开着时只展示「后台自动处理」，不要求手点；活动已结束/解析不到课程参数时给明确提示 |
| `inbox_work` | 「去完成作业」按钮 → `onOpenWork(actionUrl, title)` 回调；答题页路由由集成轮接 |
| 其它且有链接 | 只读 + 「用浏览器打开」 |
| 其它无链接 | 只读 |

签到参数从 actionUrl 解析：`courseId/courseid`、`classId/clazzId/clazzid`、
`activeId/id/activePrimaryId`；缺课程、班级或活动 id 则提示到课程里签到（拿不到活动 id
时不做「签第一条签到」的兜底，避免签错活动）。

## 5. 删除清单（本轮）

- 作业/考试提醒整条链：`data/cx/CxReminderApi.kt`、`CxReminderParser.kt`、
  `CxNoticePolicy.kt`、`CxNoticeText.kt`，以及 `CxNoticeModels` 里的 `CxExamItem` /
  `CxTimeSource` / `CxReminderRecord` / 提醒窗口常量 / 提醒设置项；对应单测
  `CxReminderParserTest`、`CxNoticePolicyTest`、`CxNoticeTextTest`；
- 任务与日志：`data/cx/CxTaskModels.kt`、`CxTaskStore.kt` 及 Runner 里的写入，诊断改为
  Logcat（`CxTaskService.kt` 与 `cx_important` 渠道属于签到通知，未动）；
- 通知中心的 `SIGN` / `STUDY` 汇总：`CxNoticeSource` 移除，条目只来自收件箱；
- 收件箱开关：收件箱是常驻功能，无开关（后台同步始终启用）。

## 6. 待集成 / 待验证

- 作业答题页路由：`onOpenWork` 回调当前只弹提示，等集成轮接 `CxWorkAnswerScreen`；
- App 已在前台时的通知点击：`MainActivity.onNewIntent` 消费 `cx_page=inbox`，留给集成轮
  （当前只处理冷启动 intent）；
- 真机验证：收件箱接口成功路径是否 302（若是需改 `getFollowing`）；`cx_notice_bg`
  静默通知实测；WorkManager 30 分钟触发。

## 7. 验证

- 单测：日期解析、收件箱解析（置顶合并/去重/两形态 attachment/布尔 isread/status falsy）、
  接口错误码（302→401、202→403、500→502）、本地编解码、Runner 固定拉最新首页
  （重复同页无新增、后来页只加新条目）、去重与账号隔离、详情路由与签到参数解析；
- 构建：`:app:assembleDebug`。