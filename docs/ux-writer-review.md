# 管院通前端 UX 文案审查

审查日期：2026-11-25  
范围：Android Jetpack Compose 界面、内置 WebView、请销假编辑器、桌面小组件。包含当前工作区新增但尚未提交的「饮水码」入口。  
方式：静态代码审查，未启动 App 做运行时截图验证；未修改产品代码。

## 执行记录

- 饮水码入口、路由、页面与契约文件已删除；`docs/integrations/water-code.md` 仅作为接入评估记录保留。
- 其余可明确判断的文案与行为不一致已修改。
- 请销假的产品定位、隐私与安全承诺等仍需产品确认，未擅自改写。
- `:app:compileDebugKotlin` 编译通过；编辑器 3 段内联 JavaScript 语法检查通过；未做真机运行验证。

## 一、文案策略卡

| 项目 | 策略 |
| --- | --- |
| 一句话定位 | 这是给山东管理学院学生在手机上快速查课表、成绩、考试、选课，管理校园账号，以及使用一卡通、宿舍用电等服务的整合工具。 |
| 用户已经懂什么 | 懂学号、课表、考试、选课、一卡通、余额和电量；不需要解释这些词。 |
| 用户可能不懂什么 | 「数据来源」「原生」「H5」「缓存」「接口」「授权码」「Release」等实现术语。首次出现时也要说人话。 |
| 使用情境 | 每天高频使用，常在路上、课间单手操作；信息要能扫读，错误要说清下一步。 |
| 使用频次 | 引导只在第一次或空态出现；日常使用时不重复解释产品怎么工作。 |
| 语气原则 1 | 先给结果，再给下一步。错误不要只报技术原因。 |
| 语气原则 2 | 按钮写动作，不写进行时，不写「点击这里」。 |
| 语气原则 3 | 只解释会影响操作的事：登录顶号、切换数据来源、退课、清除缓存；不把开发过程写进界面。 |
| 服务叫法 | 教务系统；官方教务系统 / 抢课助手（仅作为数据来源）；山管 App；课表；一卡通付款码；宿舍用电；请销假；扫一扫；饮水码；小白超人。 |
| 状态叫法 | 未登录 / 已登录 / 登录失效；加载中；刷新失败；暂时无法连接；未开始 / 进行中 / 已结束。 |
| 推测项 | 用户未必知道「数据来源」是什么，也不一定知道饮水码与微信登录的绑定关系；这两处需要产品确认。 |

## 二、总体判断

主要流程是能用的：大部分入口名、页面标题和主要按钮都比较短，错误后也大多提供了重试或登录入口。

问题集中在三类：**新增功能把研发结论直接展示给用户**；**少量文案与实际行为不一致**；**同一对象在不同页面换了名字**。最值得先处理的是饮水码、请销假保存提示、技术错误原文，以及更新安装按钮。

## 三、按屏减法结果

| 界面 | 这一屏的一件事 | 该去掉或合并 | 该保留或改成控件 |
| --- | --- | --- | --- |
| 概览 | 看今天/下一节课，快速进入常用功能 | 空态里的图标说明；组件面板里的「真实预览」「添加到底部」；账号组件常驻的数据源逻辑说明 | 问候与日期可保留；添加、编辑交给按钮；来源用状态标签显示 |
| 功能 | 找到并打开一个校园服务 | 「全部功能」重复标题、搜索结果数量；功能副标题里的实现说明；多余的「教务首页」中转 | 搜索框保留；课表统一叫「课表」；请销假要明确是生成申请还是提交申请 |
| 我的 | 管理账号、检查更新、了解 App | GitHub/Release 术语；重复的版本脚注可收进关于页 | 检查更新、退出全部账号、关于；清除后果写完整 |
| 账号 | 登录、退出、选择教务数据来源 | 「上方」「原生」「共用登录态」等解释；来源 hint 与切换说明的重复 | 账号状态、登录按钮、来源选择；山管登录顶号提示只留一次 |
| 教务课表/成绩/考试/选课 | 查看数据或完成选课 | 「直连接口」「蹲余量」「点右上角刷新」等；考试日期重复 | 选中周次的真实标签；登录、重试、创建任务、退课按钮 |
| 山管应用列表 | 找到并打开山管服务 | 「原生工具」「内置清单」等内部说法；正常状态下常驻的数据来源脚注 | 官方分类可保留；错误/缓存状态按需显示 |
| 一卡通付款码 | 出示付款码、查看余额 | 异常原文；把刷新状态写进按钮 | 「付款码」「刷新」「重试」；支付成功用状态区表达 |
| 宿舍用电 | 选择房间并看剩余电量 | 技术错误原文；未解释的「闸位」「表具」 | 楼栋、楼层、房间、剩余电量；字段含义确认后再定标签 |
| 饮水码 | 在微信里使用饮水码 | 服务标识、登录链路、fixedToken、openId、appId、H5、解包结论整块删除 | 粘贴小程序分享链接、在微信中打开、复制官方地址 |
| 请销假 | 填写并生成请假申请图片 | JS 异常原文；错误的保存目录；把处理状态写进按钮 | 自动填写结果、生成图片、保存图片；空态说清需手动填写 |
| 桌面小组件 | 一眼看到课程或账号状态 | 「缓存」「使用缓存」；不可点区域提示；多余的零分钟 | 更新时间、未登录/登录失效区分、整卡可点 |

## 四、逐句问题（按严重程度）

### 高：会误导、卡住用户，或让用户找不到结果

| 位置 | 原文 | 问题 | 改为 | 理由 |
| --- | --- | --- | --- | --- |
| [SdgyWaterScreen.kt:92](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyWaterScreen.kt:92)、[:102](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyWaterScreen.kt:102)、[:113](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyWaterScreen.kt:113)、[:163](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyWaterScreen.kt:163) | `服务标识 sc=1089`、`登录链路`、`fixedToken`、`openId`、`appId` | 整页像研发接入笔记，用户无法从中知道下一步 | 删除整块研发说明，只保留「饮水码需要在微信中使用」与可用操作 | 这些是实现边界，不是用户任务 |
| [SdgyWaterScreen.kt:135](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyWaterScreen.kt:135)、[:173](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyWaterScreen.kt:173) | `打开微信`；字段标签是 URL 示例 | 「打开微信」只启动微信，并不进入饮水服务；URL 示例被当成字段标签 | 按钮改「在微信中打开」并绑定真实链接；输入框标签「小程序分享链接」，placeholder「粘贴微信分享链接」 | 按钮与字段都要说清实际动作和内容 |
| [AccountScreen.kt:157](app/src/main/java/com/sdmu/guanlitong/ui/account/AccountScreen.kt:157)、[ServiceWebScreen.kt:136](app/src/main/java/com/sdmu/guanlitong/ui/web/ServiceWebScreen.kt:136) | `请到「我的 → 应用设置 → 服务地址」中填写` | 指向不存在的入口；`SettingsScreen` 没有被任何页面调用 | 能恢复入口就补入口；否则改为「这个服务暂时不可用」 | 用户按提示也到不了目标，会直接卡住 |
| [MineScreen.kt:168](app/src/main/java/com/sdmu/guanlitong/ui/mine/MineScreen.kt:168) | `把学校散落的教务、门户、图书馆和第三方应用收进一个 App` | 当前界面没有门户、图书馆入口，属于过期产品描述 | `查课表、成绩、考试和选课，管理教务与山管 App 账号。` | 关于页不能承诺当前不存在的功能 |
| [editor.html:1018](app/src/main/assets/editor.html:1018) | `已保存到相册 Pictures/SDMULite` | 实际保存目录是 `Pictures/SDMU`，提示错误且暴露内部目录名 | `已保存到相册` | 用户会按当前提示找不到图片 |
| [SdgyWebScreen.kt:299](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyWebScreen.kt:299)、[SdgyPowerScreen.kt:136](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyPowerScreen.kt:136)、[SdgyPayScreen.kt:116](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyPayScreen.kt:116)、[SdgyXbcScreen.kt:173](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyXbcScreen.kt:173) | `net::ERR_*`、`lastErr`、`lastDebug`、`HTTP 401` 原样拼接 | 技术原文、异常类名或内部调试字段直接进入界面 | 网络异常统一「网络连接失败，请检查网络后重试」；业务异常只展示已确认的可读原因；详细原因写日志 | 用户需要可执行的下一步，不是协议细节 |
| [UpdateUi.kt:138](app/src/main/java/com/sdmu/guanlitong/ui/update/UpdateUi.kt:138)、[AppRoot.kt:196](app/src/main/java/com/sdmu/guanlitong/ui/AppRoot.kt:196) | 无安装权限时按钮仍叫「安装」 | 点击后实际打开系统设置，并不会安装 | 无权限时按钮改「去设置」；有权限时才叫「安装」 | 按钮必须准确说明点击后发生什么 |
| [JwxtScreen.kt:453](app/src/main/java/com/sdmu/guanlitong/ui/jwxt/JwxtScreen.kt:453)、[:584](app/src/main/java/com/sdmu/guanlitong/ui/jwxt/JwxtScreen.kt:584) | 切换到其他周后仍显示「本周 N 门课」 | 课程数量按所选周计算，标签却固定写「本周」 | 当前周显示「本周 N 门课」；其他周显示「第 X 周 N 门课」 | 这是数据标签错误，不只是啰嗦 |
| [JwxtScreen.kt:307](app/src/main/java/com/sdmu/guanlitong/ui/jwxt/JwxtScreen.kt:307)、[:394](app/src/main/java/com/sdmu/guanlitong/ui/jwxt/JwxtScreen.kt:394) | 数据来源为抢课助手时，按钮写「登录抢课助手」，标题却写「需要登录教务系统」 | 同一页面把登录对象说成两个服务 | 标题按来源显示「需要登录教务系统」或「需要登录抢课助手」 | 避免用户以为要登录两个账号 |

### 中：影响理解、一致性或操作预期

| 位置 | 原文 | 问题 | 改为 | 理由 |
| --- | --- | --- | --- | --- |
| [JwxtLoginPanel.kt:187](app/src/main/java/com/sdmu/guanlitong/ui/jwxt/JwxtLoginPanel.kt:187)、[AccountScreen.kt:518](app/src/main/java/com/sdmu/guanlitong/ui/account/AccountScreen.kt:518) | `原生课表与内嵌网页共用这份登录态` | 重复、且是实现说明 | 删除；需要时只写「登录后可查看课表」 | 用户不关心 Cookie 注入和页面实现 |
| [JwxtLoginPanel.kt:318](app/src/main/java/com/sdmu/guanlitong/ui/jwxt/JwxtLoginPanel.kt:318)、[GrabLoginCard.kt:267](app/src/main/java/com/sdmu/guanlitong/ui/account/GrabLoginCard.kt:267)、[SdgyLoginSection.kt:278](app/src/main/java/com/sdmu/guanlitong/ui/account/SdgyLoginSection.kt:278)、[SdgyPayScreen.kt:232](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyPayScreen.kt:232)、[SdgyXbcScreen.kt:512](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyXbcScreen.kt:512) | `正在登录…`、`刷新中…`、`再次点击确认开锁` 替代按钮动作 | 把状态写进按钮，按钮名称随状态变化 | 按钮保持「登录」「刷新」「确认开锁」并禁用；状态显示在按钮旁 | 动作应稳定，进度与危险确认属于状态 |
| [DesktopAppWidget.kt:190](app/src/main/java/com/sdmu/guanlitong/widget/DesktopAppWidget.kt:190) | `登录失效 · 使用缓存` | 组件拿到的是 `loggedIn=false`，未登录也可能显示成「登录失效」；「缓存」是实现术语 | 未登录：「未登录，显示上次课表」；登录失效单独判断后显示「登录失效」；附更新时间 | 两种状态的下一步不同，不能混为一谈 |
| [DesktopAppWidget.kt:100](app/src/main/java/com/sdmu/guanlitong/widget/DesktopAppWidget.kt:100)、[:242](app/src/main/java/com/sdmu/guanlitong/widget/DesktopAppWidget.kt:242) | `点击卡片，打开管院通`，实际只有标题可点 | 提示的可点范围与控件不一致 | 让整卡可点；只改文案则写「点标题打开管院通」 | 不要让用户点了没反应 |
| [OverviewScreen.kt:1187](app/src/main/java/com/sdmu/guanlitong/ui/overview/OverviewScreen.kt:1187)、[:2109](app/src/main/java/com/sdmu/guanlitong/ui/overview/OverviewScreen.kt:2109)、[:2174](app/src/main/java/com/sdmu/guanlitong/ui/overview/OverviewScreen.kt:2174) | `点「+」…`、`真实预览`、`添加到底部`、`只留一个重点数字` | 图标实际不是「+」；其余是布局与实现说明 | 保留「添加小组件」按钮；面板只写「选择小组件」；尺寸说明写用户能获得的信息 | 空态和面板应该教操作，不讲实现 |
| [OverviewScreen.kt:2066](app/src/main/java/com/sdmu/guanlitong/ui/overview/OverviewScreen.kt:2066) | `登录状态跟随「教务系统 → 数据来源」的选择` | 常驻的产品逻辑旁白 | 删除，或把当前来源直接显示成状态标签 | 用户需要知道当前状态，不需要知道联动规则 |
| [QuickActionContextMenu.kt:158](app/src/main/java/com/sdmu/guanlitong/ui/overview/QuickActionContextMenu.kt:158)、[:164](app/src/main/java/com/sdmu/guanlitong/ui/overview/QuickActionContextMenu.kt:164) | `更换此功能`、`调整顺序` | 两个入口实际打开同一个编辑器 | 合并为「编辑快捷功能」 | 不同承诺不应指向完全相同的结果 |
| [QuickActionEditor.kt:157](app/src/main/java/com/sdmu/guanlitong/ui/overview/QuickActionEditor.kt:157)、[:175](app/src/main/java/com/sdmu/guanlitong/ui/overview/QuickActionEditor.kt:175)、[:223](app/src/main/java/com/sdmu/guanlitong/ui/overview/QuickActionEditor.kt:223) | `点击 − 移除`、`从下方添加`、`搜索功能、来源或分组` | 实际移除图标是 ×；「来源/分组」是内部结构 | 删除操作说明，依靠拖动柄和 ×；写「添加快捷功能」「搜索功能」 | 控件已经表达动作，不必再念一遍 |
| [FeatureCatalog.kt:133](app/src/main/java/com/sdmu/guanlitong/data/FeatureCatalog.kt:133)、[JwxtDataScreens.kt:1835](app/src/main/java/com/sdmu/guanlitong/ui/jwxt/JwxtDataScreens.kt:1835) | `教务首页` 进入后又列出课表、成绩、考试、选课 | 功能页本身已经是入口总览，多一层中转 | 删除「教务首页」入口，或把它改成服务状态卡 | 减少一次没有必要的选择 |
| [FeatureCatalog.kt:128](app/src/main/java/com/sdmu/guanlitong/data/FeatureCatalog.kt:128)、[JwxtDataScreens.kt:1844](app/src/main/java/com/sdmu/guanlitong/ui/jwxt/JwxtDataScreens.kt:1844) | `课满时自动蹲余量` | 「蹲余量」不是自然中文，也容易被理解为不正式功能 | `课程满员后自动等待名额` | 说清行为，不玩口语梗 |
| [SdgyAppCatalog.kt:177](app/src/main/java/com/sdmu/guanlitong/data/sdgy/SdgyAppCatalog.kt:177)、[SdgyHubScreen.kt:232](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyHubScreen.kt:232)、[:349](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyHubScreen.kt:349) | `原生工具`、`暂未内置`、`内置清单` | 研发实现词直接出现在功能列表 | 分组改「常用工具」；不可用改「这个功能暂时不能在管院通里打开」；正常状态删除来源脚注 | 用户只关心能不能用、在哪里用 |
| [SdgyTimetableScreen.kt:166](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyTimetableScreen.kt:166)、[:373](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyTimetableScreen.kt:373)、[:489](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyTimetableScreen.kt:489) | `山管课表`、`上课人`、`点击课程可查看挂科率、平均分与上课名单` | 同一对象多种叫法；页脚说明过长 | 页面统一「课表」；统一「上课人数」「上课名单（N）」；页脚改「点课程查看详情」 | 减少记忆负担，也让名单与人数不混淆 |
| [SdgyTimetableScreen.kt:572](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyTimetableScreen.kt:572) | `暂无数据` | 同一个弹窗加载两种名单，空态无法说明缺什么；失败也被当成空列表 | 分别写「没有查到上课名单」「没有查到最高分名单」；请求失败单独显示「名单加载失败，请重试」 | 空态与失败要有区别 |
| [JwxtDataScreens.kt:896](app/src/main/java/com/sdmu/guanlitong/ui/jwxt/JwxtDataScreens.kt:896)、[:1154](app/src/main/java/com/sdmu/guanlitong/ui/jwxt/JwxtDataScreens.kt:1154)、[:1397](app/src/main/java/com/sdmu/guanlitong/ui/jwxt/JwxtDataScreens.kt:1397) | `创建`、`抢课任务`、`点「抢课任务」即可创建` | 确认按钮不完整；入口按钮像页面标题；空态让用户自己找路 | 按钮改「创建任务」「创建抢课任务」；空态提供「去选课」按钮 | 按钮应写动作，空态应给下一步 |
| [JwxtDataScreens.kt:1437](app/src/main/java/com/sdmu/guanlitong/ui/jwxt/JwxtDataScreens.kt:1437) | `共 N 条任务 · 点右上角刷新` | 动作交给图标即可，不必在句子里指向位置 | `共 N 个任务` | 避免「右上角」这类位置指引 |
| [JwxtDataScreens.kt:629](app/src/main/java/com/sdmu/guanlitong/ui/jwxt/JwxtDataScreens.kt:629) | `11月25日 2026-11-25 08:00` | 同一日期用两种格式重复出现 | 只保留一种，例如 `11月25日 08:00` | 考试卡空间有限，重复信息没有价值 |
| [SdgyPowerScreen.kt:269](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyPowerScreen.kt:269)、[:280](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyPowerScreen.kt:280)、[:284](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyPowerScreen.kt:284) | `总示数`、`抄表`、`闸位`、`表具` | 部分词是电表/财务系统术语，普通学生不一定理解 | 建议「电表读数」「最近抄表时间」「供电开关」「电表类型」；需先确认字段真实含义 | 不要为了简洁牺牲准确性 |
| [SdgyPayScreen.kt:321](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyPayScreen.kt:321) | `暂无数据` | 钱包弹窗里没有说明缺的是余额，也没有重试 | `暂时读不到钱包余额`，并提供「重试」 | 空态要对应具体对象 |
| [UpdateUi.kt:146](app/src/main/java/com/sdmu/guanlitong/ui/update/UpdateUi.kt:146)、[:174](app/src/main/java/com/sdmu/guanlitong/ui/update/UpdateUi.kt:174)、[:179](app/src/main/java/com/sdmu/guanlitong/ui/update/UpdateUi.kt:179) | `GitHub 上最新的正式 Release`、`正在从 GitHub 检查`、下载失败仍显示`检查失败` | 来源术语外露；重复弹框；检查与下载失败混用 | 行内显示「已是最新版本」即可，不弹框；写「正在检查更新」；区分「检查更新失败」「下载失败」 | 结果已经在行上，就不必再打断一次 |
| [editor.html:692](app/src/main/assets/editor.html:692)、[:960](app/src/main/assets/editor.html:960)、[:1016](app/src/main/assets/editor.html:1016) | `正在生成…`、`处理中…`、`失败：原始错误` | 处理状态写进按钮；错误漏出 JS/原生原文 | 按钮保持「生成图片」「保存图片」并禁用；错误改「图片生成失败，请重试」「保存失败，请检查存储权限或空间后重试」 | 动作稳定，错误可执行 |
| [editor.html:765](app/src/main/assets/editor.html:765) | `已自动填写：无` | 空态含糊，页面又保留示例内容，容易被误认为已填写 | `未读取到山管账号信息，请手动填写` | 自动填写失败要说清下一步 |
| [SdgyLoginSection.kt:396](app/src/main/java/com/sdmu/guanlitong/ui/account/SdgyLoginSection.kt:396)、[:403](app/src/main/java/com/sdmu/guanlitong/ui/account/SdgyLoginSection.kt:403) | 标题与正文都说明「登录会退出官方 App」 | 同一后果重复两次 | 标题「登录会退出官方山管 App」；正文只补充「掉线后可用保存的密码自动重连」 | 重要提示保留，但不重复 |

### 低：可以最后一轮统一清理

| 位置 | 原文 | 问题 | 改为 | 理由 |
| --- | --- | --- | --- | --- |
| [FeaturesScreen.kt:115](app/src/main/java/com/sdmu/guanlitong/ui/features/FeaturesScreen.kt:115)、[:287](app/src/main/java/com/sdmu/guanlitong/ui/features/FeaturesScreen.kt:287)、[:326](app/src/main/java/com/sdmu/guanlitong/ui/features/FeaturesScreen.kt:326) | `搜索所需功能`、`全部功能`、`找到 N 个功能` | 标题与结果重复 | `搜索功能`；删除「全部功能」和结果数量 | 页面结构已经表达分类与数量 |
| [FeatureCatalog.kt:96](app/src/main/java/com/sdmu/guanlitong/data/FeatureCatalog.kt:96) | `按周查看 · 本地缓存` | 「本地缓存」是实现术语 | `按周查看课程` | 说用户得到什么 |
| [FeatureCatalog.kt:219](app/src/main/java/com/sdmu/guanlitong/data/FeatureCatalog.kt:219) | 动态应用无描述时统一补「山管官方应用」 | 每个位置都填字，但没有解释功能 | 副标题留空，由大卡片样式决定是否显示 | 不是每个入口都需要一句说明 |
| [AccountScreen.kt:538](app/src/main/java/com/sdmu/guanlitong/ui/account/AccountScreen.kt:538) | `查看原生课表` | 「原生」对用户没有意义 | `查看课表` | 同一对象只用一个名字 |
| [JwxtLoginPanel.kt:121](app/src/main/java/com/sdmu/guanlitong/ui/jwxt/JwxtLoginPanel.kt:121)、[:131](app/src/main/java/com/sdmu/guanlitong/ui/jwxt/JwxtLoginPanel.kt:131) | `验证码图片解析失败，请点击刷新`、`未能自动识别` | 技术动作与公文腔 | `验证码加载失败`；`没识别出来，请输入图片中的验证码` | 短、直接、像人说的话 |
| [SdgyPayScreen.kt:378](app/src/main/java/com/sdmu/guanlitong/ui/sdgy/SdgyPayScreen.kt:378) | 用字符 `✓` 当成功图标 | 符号字体在不同设备上粗细、基线可能不一致 | 用标准 `CheckCircle` 图标，并保留「支付成功」文字 | 状态不要只靠一个字符 |
| [DesktopData.kt:61](app/src/main/java/com/sdmu/guanlitong/widget/DesktopData.kt:61) | `官方教务` | 与服务名「官方教务系统」不一致 | `教务系统` | 统一对象叫法 |
| [NextClassModel.kt:119](app/src/main/java/com/sdmu/guanlitong/widget/NextClassModel.kt:119) | `还有 1 小时 0 分` | 整点时输出无信息量的单位 | `还有 1 小时` | 小组件空间有限 |

## 五、饮水码页建议改法

如果当前仍然只能通过微信渠道取码，这一屏可以压缩成：

- 标题：`饮水码`
- 说明：`饮水码需要在微信中使用。`
- 操作一：输入框标签 `小程序分享链接`，placeholder `粘贴微信分享链接`，按钮 `在微信中打开`
- 操作二：次要按钮 `复制官方地址`
- 无效链接：`请粘贴微信官方小程序分享链接`
- 无法打开：`没有应用可以打开这个链接`

建议删除：`sc=1089`、`登录链路`、`wx.login`、`openId`、`fixedToken`、`appId`、`官方 H5`、解包与实测结论。`打开微信` 如果没有真实小程序跳转路径，不建议保留，否则用户打开微信后仍然不知道下一步。

## 六、需要产品确认

1. **饮水码定位**：这是临时过渡入口，还是准备做成原生功能？目前是否真的有小程序路径或可用的微信内打开方式？
2. **请销假定位**：当前只生成申请图片，还是也包含提交？入口叫「请销假」「请假申请」还是「请假申请图片」更准确？
3. **宿舍用电字段**：`state`、`valve`、`total` 在官方系统中的准确含义是什么？确认后再替换「闸位」「表具」「总示数」。
4. **清除全部账号的真实范围**：当前代码会同时清除账号记录、网页登录态和课表缓存。确认这是预期行为后，提示应写成「退出账号并删除登录信息与课表缓存」。
5. **隐私与安全承诺**：`密码只发往后端`、`系统密钥库加密`、`扫描结果只在本机处理`、`记住密码后被顶号会自动重连` 等句子属于承诺性文案，需要产品/安全同事确认后再保留。

## 七、建议落地顺序

1. 先改会误导的四类：饮水码研发笔记、请销假保存目录、技术错误原文、更新安装按钮。
2. 再修事实与导航：过期的关于页、不存在的设置入口、选中周标签、未登录与登录失效的区分。
3. 然后做减法：合并快捷功能编辑入口、删除教务首页中转、清理重复分类与实现说明。
4. 最后一轮统一词表、按钮状态、空态和标点。
