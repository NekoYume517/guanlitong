# 管院通

当前版本内置**官方教务系统**、自建**抢课助手**、**山管app**（原生功能 + 官方网页）与**口袋校园**（活动与学时），其余校园服务按计划逐个加回。

源码与 Release 都在 GitHub：[NekoYume517/guanlitong](https://github.com/NekoYume517/guanlitong)。
App 内「我的 → 检查更新」会从 Release 拉取最新版本，下载后交给系统安装器覆盖安装。

- 原生 **Jetpack Compose + Material 3 Expressive（M3E）**
- 不做强制登录门：默认直接进首页，账号在「我的」里按服务单独管理
- 网页兜底用 WebView 打开并持久化 Cookie，与原生页面共用登录态
- **官方教务系统支持原生登录**：学号密码 + 验证码（ML Kit 离线识别，可手动改），可记住密码；登录后的 Cookie 同时注入 WebView
- **教务数据来源可切换**：默认直连官方教务，也可以改用自建抢课助手（`jwxt.bansonic.top`，即 sdmu-website-2）；「功能」页只保留一个「教务系统」二级 tab
- **教务课表已原生实现**：原生页面复用同一份登录态调教务接口，按周渲染课表，带本地缓存（两种来源各存一份）
- **山管app 已内置**：原生登录山管账号（RSA 加密密码），课表 / 一卡通 / 宿舍用电 / 请销假为原生页面；官方应用总页由服务端 `api/app/list` 驱动，网页条目走内置 WebView
- **统一课表**：功能集任意 tab、快捷卡片和山管「我的课表」都进入同一个课表页；教务数据优先，山管课表在教务无数据时兜底，并在课程详情补充挂科率与上课名单
- **快捷功能可编辑**：长按卡片弹出菜单，编辑页顶部拖动排序；候选区只显示已登录服务的功能，已添加的卡片退出登录后仍保留
- **口袋校园已内置**：账号密码原生登录（`Bearer <token>:<go_id>`），活动列表与详情、我的活动、学时统计、好友、个人二维码、扫一扫、生成签到码全部为原生页面；支持活动报名与取消、签到签退、扫码识别签到码 / 个人码
- **四端账号统一管理**：官方教务、抢课助手、山管app 的会话都在回到 App 时自动检查，被顶号或过期自动重登，失败也不清除登录状态；口袋校园的 token 长期有效，不需要定时保活，401 时才提示重新登录
- **功能可以收藏**：「功能」页最前面是「收藏」。在教务系统、山管 App、口袋校园或搜索结果里长按一个功能，弹出菜单里选「收藏」；已收藏的再长按选「取消收藏」。收藏保存在本机，退出登录不影响

## 页面结构

底部是三个 tab，覆盖页（网页 / 账号 / 课表 / 设置）打开时带原生前进、后退滑动动画。

| 页面 | 内容 |
|---|---|
| 概览 | 像桌面小组件一样自由组合：今日课程、快捷功能、教务概览、账号状态，每项都有小 / 中 / 大三种尺寸；底部面板真实预览添加、长按弹簧拖动排序、拖到右下角删除；顶部标题固定不随滚动，右上角直达添加面板与「我的」 |
| 功能 | 顶部搜索（教务功能 + 山管app 网页功能）；分类胶囊（收藏 / 教务系统 / 山管app / 口袋校园），右侧功能胶囊；搜索时收起分类；山管app 的原生工具与官方应用总页、口袋校园的原生入口都在这里；长按功能可收藏 |
| 我的 | 账号管理（教务系统 / 山管app / 口袋校园 三个入口）、检查更新（从 GitHub Release 拉取新版本）、重看启动引导、清除登录状态、关于 |
| 口袋校园 | 活动列表（搜索 + 分页 + 批量报名）、活动详情（报名 / 取消 / 签到 / 签退 / 生成签到码）、我的活动、学时统计、好友、个人二维码（10s 换码）、扫一扫、生成签到码 |
| 原生课表 | 统一入口：学期与周次切换、7 天 × 节次网格、课程详情；教务数据优先、山管课表兜底，详情补充山管挂科率与上课名单；本地缓存优先、后台刷新；登录失效时引导登录 |
| 山管app | 官方应用总页：常用应用 + 全部分组由服务端驱动，图标远程加载；原生工具（课表 / 一卡通 / 宿舍用电 / 请销假）替换同名的官方网页入口 |
| 山管一卡通 | 卡号与钱包余额、动态付款码；付款码倒计时自动刷新，识别到支付成功后提示「再付一笔」 |
| 宿舍用电 | 选楼栋 / 楼层 / 房间，查剩余电量、表状态、电费余额与近 14 天用量 |
| 请销假 | 内置离线 HTML 编辑器（`assets/editor.html`）填写申请，html2canvas 生成图片，保存到相册或系统分享 |
| 原生成绩 | 按学期筛选，显示加权平均、学分、绩点与课程性质；失败时可转网页 |
| 原生考试 | 考试时间、考场、形式与「未开始 / 进行中 / 已结束」状态 |
| 原生选课 | 可选课程搜索、展开教学班查看时间/地点/容量，支持选课、退课与已选课程列表；抢课助手来源下，已满的教学班可以直接创建抢课任务 |
| 抢课任务 | 仅抢课助手来源时显示入口（「功能 → 全部功能」与「教务首页」）；按状态筛选，点进任务可看详情与执行日志，日志对进行中的任务每 2 秒自动刷新，排队中 / 抢课中的任务可直接取消 |
| 教务首页 | 原生入口页：课表 / 成绩 / 考试 / 选课，另留「改用网页版」兜底 |
| 账号详情 | 数据来源切换（官方 / 抢课助手）；切到抢课助手时直接在 tab 下方填站点账号密码登录；网页登录入口、记住密码、退出登录、登录状态检测 |
| 内嵌网页 | WebView + 进度条 + 刷新 + 用系统浏览器打开，返回键优先回退网页 |
| 桌面小组件 | 系统桌面只提供小 / 中 / 大三种尺寸；长按卡片选择「编辑」可切换下一节课、今日课程、教务概览、快捷功能或账号状态，编辑页直接显示真实数据的实时预览 |

> 功能目录集中在 `data/FeatureCatalog.kt`：每项声明所属服务、图标和去向（原生课表 / 网页 / 账号页 / 独立 App），「功能」页与概览的「快捷功能」都从这里取。

## 概览与功能页

- **概览**：像桌面小组件一样自由组合。每个功能都有**小 / 中 / 大**三种尺寸：小号两块并排，中号和大号各占一行；同一功能可以用不同尺寸出现多次。顶部「概览 / 问候 / 日期」固定，只有小组件区域滚动。
  - 右上角按钮从底部弹出**小组件面板**，按小 / 中 / 大分组，用 `FlowRow` 流式排布预览；预览直接复用首页组件，与真实数据、样式和尺寸一致（小号按半宽预览）。点一下预览就关闭面板，直接滚到底部，并用主色描边短暂高亮新加的组件。
  - **长按卡片**进入拖动：卡片浮到顶层跟手移动，虚线占位与其他组件用弹簧动画平滑移动到将要落下的位置（两块小卡互换、大卡跨过小卡也不会闪烁）。中号 / 大号独占一行，跨行时会把对面整行一起跨过去，并排的两块小号不会被拆开。小号自己也能跨行：手指挪到别的行上时，按竖直方向最近的那一行落，行内再按横向就近选左边或右边的槽位（三块小卡「上二下一」时，左上那块可以直接拖到第二行）。落点判断按行/列分开：同一行只看横向、跨行只看纵向，手指纵向抖一下不会被误判成换位。跟手位移、让位、高亮都放在绘制阶段（`graphicsLayer`），拖动期间既不重排也不整页重组，弹簧被新的落点打断时会接着当前速度走。松手后卡片继续用弹簧飞回占位，动画结束才保存新顺序。布局在拖动期间不重排，避免反复测量导致跳动。
  - 拖动时右下角**自动弹出删除图标**，把卡片拖到图标上（图标变红）卡片会平滑缩到 1/2；松手后从 1/2 继续缩小并淡出，动画结束后才真正移除。
  - 组件清单与顺序存在 `DashboardStore`（JSON，含 `id` / `type` / `size`）；旧版本逗号分隔的 key 列表会在首次读取时自动迁移。全部移除后显示空状态。今日课程直接读课表缓存，按当前周与星期过滤。
- **功能**：顶部搜索框跨所有服务匹配功能名、副标题与服务名，结果按服务分组；不搜索时显示左侧选中服务的全部功能。左侧只显示服务名，选中项用 `secondaryContainer` 胶囊高亮。
- **桌面小组件**：对系统桌面只注册 **小号 2×2 / 中号 3×2 / 大号 3×4** 三个入口，内部其他卡片不会出现在系统选择器里。长按卡片选择「编辑」进入配置页，页面按组件预览排列五种内容，选中即时更新；「下一节课」与概览共用同一套课表缓存和周次推算。打开应用、刷新课表、登录或退出、切换数据来源都会主动刷新桌面，系统每 30 分钟也会更新一次倒计时。数据全部来自本地缓存，卡片底部标出「使用缓存 / 登录失效」，点击标题可打开 App。
- **页面动画**：`AppRoot` 用外层 `AnimatedContent` 包住「根页面 ↔ 覆盖页」。前进时新页面从右侧滑入、旧页面向左轻移淡出；后退时旧页面向右滑出、根页面从左侧回位（320ms 滑动 + 200ms 淡入淡出），与 Android 原生的页面转场一致。

## 服务一览

目前内置官方教务系统、抢课助手、山管app 与口袋校园；山管助手、图书馆、官网已从 UI 移除，等逐项确认后再加回。

| 服务 | 地址 | 登录方式 |
|---|---|---|
| 官方教务系统 | `https://jwxt.sdmu.edu.cn/jwglxt/xtgl/login_slogin.html` | 原生登录（验证码自动识别，可记住密码）或 WebView 登录；课表/成绩/考试/选课均为原生页 |
| 抢课助手 | `https://jwxt.bansonic.top` | 站点账号密码登录（`POST /api/login`），Cookie 注入 WebView；可作为教务数据来源，后台用它配置的教务账号代查 |
| 山管app | `http://222.206.86.42:8081`（`mobileapi_ydxy`） | 原生登录（RSA 公钥加密密码），会话与密码本地加密保存；课表 / 一卡通 / 用电 / 请销假为原生页，官方应用与其余功能走内置 WebView |
| 口袋校园 | `https://apis.pocketuni.net`（`go_id = 208791531159552`） | 原生登录（`POST /uc/user/login`，`Authorization: Bearer <token>:<go_id>`）；活动报名、签到签退、学时统计、个人二维码、扫码、好友全部为原生页，token 长期有效 |

## 教务数据来源

「我的 → 教务系统 → 数据来源」可以在两个 tab 之间切换，默认是官方教务系统：

- **官方教务系统**：原生页直接带 `jwglxt` Cookie 请求 `jwxt.sdmu.edu.cn`，成绩、考试、选课接口见下文。
- **抢课助手**：原生页改用抢课助手的站点会话请求 `jwxt.bansonic.top`，由后端拿站内配置的教务账号代查：
  - 课表：`GET /api/timetable/semester` → `GET /api/timetable?kblx=2` → `GET /api/timetable/jc_times`
  - 成绩 / 考试：`GET /api/jwxt/grades`、`GET /api/jwxt/exams`
  - 选课：`GET /api/courses/list|jxb_list|chosen|status`，`POST /api/courses/add|drop`
  - 抢课任务：`GET /api/tasks/list`，`GET /api/tasks/<id>`，`POST /api/tasks/create`，`POST /api/tasks/<id>/cancel`
  - 任务日志：`GET /api/logs/list?task_id=`（首次全量）与 `GET /api/logs/recent?after_id=`（运行中增量轮询）

两种来源各用各的 Cookie 域、各存一份课表缓存；切换后课表、成绩、考试、选课以及概览的「今日课程」都会跟着换。

抢课助手**只作为数据来源**，不在「我的」里单独占账号入口：账号管理始终只有「教务系统」一个入口，切到「抢课助手」tab 后，登录框（账号 / 密码 / 记住密码）直接嵌在 tab 下方；原生选课页在抢课来源下未登录时，也直接内嵌同一个登录框。登录成功后 Cookie 注入抢课助手站点域，并立即刷新当前页。抢课助手还需要在它的网页版里配置好教务账号。

唯一账号入口的登录状态跟随当前数据来源：来源是抢课助手时，账号页顶部、「我的」账号管理和概览的「账号状态」都显示抢课助手账号的登录状态；切回官方来源则显示教务账号状态。

### 掉线自动重登

只要用户没有主动退出、且勾选过「记住密码」，官方教务、抢课助手和山管app 掉线后都会自动重新登录：

- **触发时机**：打开 App / 切换数据来源时，如果本地会话已经没了，会先补登录；访问课表、成绩、考试、选课、抢课任务时，遇到会话失效会先自动重登再重放这次请求，页面不需要用户手动登录。
- **重试策略**：默认 3 次，间隔 1s / 2s / 4s 指数退避；官方教务每轮自动识别新验证码，抢课助手每轮重新提交账号密码。
- 重登成功后会把新 Cookie 注入 WebView 并刷新登录时间；3 次都失败才退回「登录失效」提示。主动退出登录或没保存密码时不会自动重试。
- **山管app** 走同一套策略：回到 App 时由 `SessionKeeper` 统一检查三个账号，山管会话失效就用本地密码重新登录；失败只把状态标成「稍后重试」，登录状态和密码都保留，绝不清除。

> 选课页之外的教务原生页（课表、成绩、考试）在抢课助手来源下显示「登录抢课助手」，会跳到唯一的账号入口（教务系统 → 数据来源 → 抢课助手）；「用网页打开」也会打开抢课助手站点。

## 原生课表

所有课表入口（教务功能、山管app「我的课表」、快捷卡片）都进入同一个页面：优先显示教务来源的数据；教务未登录或暂时没有数据时，用山管课表缓存兜底撑起同一页面，并在顶部注明来源。打开课程详情时会按课程名、教师、星期与节次匹配山管课程，补充挂科率、平均分、最高分、上课人数与上课名单。

教务课表数据由 App 直接向教务系统请求，不经过任何中转服务器：

1. 进入页面时读取 WebView `CookieManager` 中的 `jwglxt` Cookie；没有 Cookie 时显示「需要登录教务系统」，可直接弹原生登录框，或改用网页登录。
2. `GET /kbcx/xskbcxZccx_cxXskbcxIndex.html?gnmkdm=N2154&layout=default` 解析学年/学期、校区等预渲染字段；被 302 到登录页即视为会话失效。
3. 依次请求周次、全学期课表与节次时间；课表优先网页版接口，返回空则回退 Mobile 接口。
4. 周次描述：抢课助手返回的 `zcd` 已是纯逗号列表，并附带压缩好的 `zcd_desc`，优先使用；官方教务的 `zcd` 按分段解析单双周（如 `1-3周,5-7周(单),8-16周`），再压缩成连续区间。节次优先读 `jcs` / `jc`，缺失时才退回 `jcor`。
   > 真实数据里 `jcor` 常常是当天的时间块（如 `1-8`），实际节次在 `jcs`（如 `3-4`）。只读 `jcor` 会把每门课都画成整天。
5. 页面内按周过滤；课程块按节次偏移渲染，同一天时间重叠的课程自动并排；点课程可看教师、教室、周次与上下课时间。
6. 课表写入本地缓存（SharedPreferences，按数据来源地址区分，官方与抢课助手各一份）：再次进入先显示缓存，再后台刷新；网络失败或登录失效时保留缓存并显示状态条。退出教务账号或「清除所有登录状态」会一并清掉缓存。

> 页面 GET 不能带 `X-Requested-With`，否则学校网关会直接返回 `901`；该请求头只用于 AJAX 接口。

接口路径与参数参考抢课助手仓库的 `app/kbcx_api.py`。

## 教务原生登录

登录协议移植自 sdmu-website-2 的 `app/jwxt_login.py`：

1. `GET /jwglxt/xtgl/login_slogin.html` 建立会话并取出 `csrftoken`（顺序不能反，登录页会刷新验证码会话）。
2. `GET /jwglxt/kaptcha?time=…` 取图片，先在像素层做**灰度化 → Otsu 自适应二值化 → 连通域剔除噪点与干扰线 → 裁切放大补白边**，再交给 **ML Kit 离线文字识别**（Latin 模型，随 APK 打包，不需要 Google Play 服务）；结果只保留 6 位 ASCII 数字/字母并填入输入框，可手动修改，验证码错误时自动换一张。
3. `GET /jwglxt/xtgl/login_getPublicKey.html` 取 RSA modulus/exponent，用 `RSA/ECB/PKCS1Padding` 加密密码。
4. `POST /jwglxt/xtgl/login_slogin.html` 提交 `yhm/mm/yzm/csrftoken` 等字段；302 到 `index_initMenu` / `auth_loginSuccess` 视为成功，跳回登录页时再跟随一跳确认。
5. 成功后把会话 Cookie 写入 WebView（先清掉 `jwglxt` 路径下同名旧 Cookie，避免一次请求带两个 `JSESSIONID`），原生课表与网页共用登录态。勾选「记住密码」时密码用 Android Keystore AES/GCM 加密保存。

> 账号只发往 `jwxt.sdmu.edu.cn`；OCR 在本机完成，验证码图片不会上传到第三方。

## 账号模型

- 每个服务独立一条账号记录：账号名、登录状态、最近登录时间；抢课助手的记录随数据来源 tab 一起管理，不单独出现在「我的」列表里。
- 密码只在勾选「记住密码」时保存，使用 **Android Keystore AES/GCM** 加密后落盘，不依赖第三方加密库。
- 网页登录状态存在 WebView 的 Cookie 中；「退出登录」会逐个把该域名下的 Cookie 置为过期。
- 不做全局登录门，未登录也能先浏览概览与功能入口。

## 应用内更新

更新包不走应用商店，直接从 GitHub Releases 取：

- **自动检查**：打开 App 后延迟约 1 秒在后台查一次，12 小时内不重复打扰；检查失败静默，不影响正常使用。发现新版本时弹出对话框，显示版本号、更新说明和安装包大小，可以「下载并安装」或「稍后」。
- **手动检查**：「我的 → 检查更新」随时触发，副标题会显示当前版本、正在检查、已是最新或发现新版本。
- **并发拉取**：`releases/latest` 与 `releases?per_page=10` 两个 GitHub 接口在 IO 线程池上**并发请求**，先拿到可用结果的为准；单个接口超时或被限流不会把整次检查卡住。版本号按段比较（`1.2.10` 比 `1.2.9` 新）。只认非 draft、非 prerelease 且带 `.apk` 资产的 Release。
- **下载**：全程在 IO 线程流式写入 `cache/updates/`，每 64 KB 回调一次进度，界面按百分比刷新；下载完成后校验文件大小。中途可以取消。
- **安装权限**：`AndroidManifest.xml` 声明了 `REQUEST_INSTALL_PACKAGES`。下载完成时如果系统还没允许「安装未知应用」，对话框会变成引导页，按钮「去开启」跳到系统的「安装未知应用」开关；允许后回到 App 再点一次即可。已有权限时直接拉起系统安装器。
- **交给系统安装**：APK 通过 `FileProvider`（`${applicationId}.updates`，只暴露 `cache/updates/`）以 `content://` 交给安装器，不落外部存储。覆盖安装会保留账号、看板顺序和设置。
- 调试包与发布包使用同一套签名（见下文「发布签名」），可以直接覆盖安装并保留数据。

> 发布新版本的流程：改 `app/build.gradle.kts` 里的 `versionCode` / `versionName` → `./gradlew.bat :app:assembleRelease` → 在 GitHub 建一个 tag 为 `v<versionName>` 的 Release 并上传 `app-release.apk`。App 只认带 `.apk` 资产的正式 Release。

## 构建

环境：JDK 17、Android SDK（`compileSdk 37.1`）、Gradle 9.9.x（仓库内已带 wrapper）。

首次需要安装 37.1 平台：

```powershell
<SDK 目录>\cmdline-tools\latest\bin\sdkmanager.bat "platforms;android-37.1"
```

然后：

```powershell
cd <项目目录>
.\gradlew.bat :app:assembleDebug
```

如果本机使用 Clash 等代理，`gradle.properties` 已配置 `127.0.0.1:7890`，按需修改；不需要代理时把文末四行 `systemProp.*.proxy*` 删掉即可。

产物：

- 调试包：`app/build/outputs/apk/debug/app-debug.apk`
- 发布包：`./gradlew.bat :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`

### 发布签名

发布包刻意沿用调试证书：和本地调试包同一套签名，应用内更新下载回来后可以直接覆盖安装，不需要用户先卸载。

- 密钥文件放在项目的 `keystore/guanlitong-debug.jks`，密码写在 `keystore.properties`。
- 这两个路径都写进了 `.gitignore`，**不会随源码公开**；换机器时把这两个文件拷过去即可保持同一签名。
- 没有 `keystore.properties` 时构建会自动退回 Android 默认调试签名，证书相同，构建结果一致。
- 密钥只在本机存在，公开仓库里只有「没有密钥也能构建」的配置逻辑。

> 验证码识别使用打包进 APK 的 ML Kit Latin 模型（不依赖 Google Play 服务），APK 会比不带 OCR 的版本大约 40 MB。

> 项目目录含中文，`gradle.properties` 中已开启 `android.overridePathCheck=true`。

## 目录

```
app/src/main/java/com/sdmu/guanlitong/
├── MainActivity.kt
├── data/
│   ├── Models.kt            # SchoolService / Account / LoginMode
│   ├── ServiceCatalog.kt    # 校园服务清单（官方教务 + 抢课助手 + 山管app + 口袋校园）
│   ├── FeatureCatalog.kt    # 服务下的功能目录（课表 / 成绩 / 一卡通 / 请销假…）
│   ├── DashboardStore.kt    # 概览小组件（类型 / 尺寸 / 顺序）
│   ├── QuickActionStore.kt  # 概览快捷卡片（顺序 / 绑定入口）
│   ├── AccountStore.kt      # 账号状态 + 加密密码
│   ├── SessionKeeper.kt     # 三个账号回前台时统一保活（口袋校园 token 长期有效，不参与）
│   ├── SettingsStore.kt     # 服务地址 + 教务数据来源
│   ├── Secrets.kt           # Android Keystore AES/GCM
│   ├── CookieUtils.kt       # WebView Cookie 注入 / 清理 / 登录判断
│   ├── ApiClient.kt         # 抢课助手 /api/login
│   ├── update/
│   │   └── UpdateClient.kt  # GitHub Release 检查 / 下载 / 安装
│   ├── widget/              # 桌面小组件（三种尺寸 + 长按编辑 + 共享下一节课）
│   ├── jwxt/
│   │   ├── JwxtModels.kt    # 课表模型 + 周次/节次解析
│   │   ├── JwxtClient.kt    # 官方教务课表接口（复用 WebView Cookie）
│   │   ├── JwxtLoginClient.kt # 教务原生登录（csrftoken / RSA / 验证码）
│   │   ├── JwxtApiClient.kt # 官方成绩 / 考试 / 选课接口
│   │   ├── JwxtParsing.kt   # 教务字段解析（官方与抢课助手共用）
│   │   ├── JwxtSource.kt    # 数据来源枚举 + JwxtAccess
│   │   ├── JwxtDataSource.kt # 统一数据源接口与工厂
│   │   ├── GrabApiClient.kt # 抢课助手站点接口（代查教务 + 抢课任务）
│   │   ├── SessionRelogin.kt # 掉线自动重登（3 次指数退避）
│   │   ├── CaptchaOcr.kt    # 验证码灰度/Otsu/去干扰 + ML Kit 识别
│   │   └── JwxtCache.kt     # 课表本地缓存（按来源分开）
│   └── sdgy/
│       ├── SdgyClient.kt    # 山管接口（登录 / 课表 / 应用列表 / 详情）
│       ├── SdgyProtocol.kt  # 请求加密与响应解密
│       ├── SdgyCrypto.kt    # RSA 分段加密
│       ├── SdgySessionManager.kt # 山管会话保活（被顶号自动重登）
│       ├── SdgyAppCatalog.kt # api/app/list 分组解析 + 原生工具注入
│       ├── SdgyAppsStore.kt # 应用列表离线缓存
│       ├── SdgyTimetableSource.kt # 山管课表缓存 / 兜底 / 详情补充
│       ├── SdgyPayApi.kt    # 一卡通余额与付款码
│       ├── SdgyPowerApi.kt  # 宿舍用电
│   └── pu/
│       ├── PuModels.kt      # 活动 / 学时 / 好友 / 二维码模型
│       ├── PuException.kt   # 错误类型 + 文案映射
│       ├── PuApi.kt         # 口袋校园接口（Bearer token + 签名）
│       ├── PuLoginApi.kt    # POST /uc/user/login
│       ├── PuCredStore.kt   # token / go_id 本地存储
│       ├── PuActions.kt     # 报名 / 签到 / 签退等写操作
│       ├── PuSign.kt        # X-Sign（native + StringEncryptor）
│       ├── PuPersonalCode.kt # 个人码 DES 解密 + 10s 换码
│       ├── PuScan.kt        # 扫码结果分类（签到码 / 个人码）
│       └── PuRepository.kt  # 页面共用的数据入口
└── ui/
    ├── AppRoot.kt           # 三 tab + 覆盖页前进/后退动画
    ├── theme/Theme.kt       # MaterialExpressiveTheme
    ├── overview/            # 概览组件 + 快捷卡片编辑（QuickActionEditor 等）
    ├── features/FeaturesScreen.kt # 左侧服务栏 + 功能胶囊 + 搜索
    ├── mine/MineScreen.kt
    ├── update/UpdateUi.kt   # 更新对话框与状态文案
    ├── account/AccountScreen.kt
    ├── account/GrabLoginCard.kt # 抢课助手登录卡片（数据来源 tab / 选课页共用）
    ├── account/SdgyLoginSection.kt # 山管app 登录区
    ├── account/PuLoginSection.kt # 口袋校园登录区
    ├── web/ServiceWebScreen.kt
    ├── jwxt/JwxtScreen.kt   # 原生课表页（全 App 唯一课表页）
    ├── jwxt/JwxtDataScreens.kt # 原生成绩 / 考试 / 选课 / 教务首页
    ├── jwxt/JwxtLoginPanel.kt # 原生登录面板 / 登录弹窗
├── sdgy/                # 山管应用总页 / 一卡通 / 用电 / 请销假
    ├── pu/                  # 口袋校园：列表 / 详情 / 我的活动 / 学时 / 好友 / 个人码 / 扫码 / 签到码
    └── components/          # 通用组件（FlowChipRow / RemoteIcon…）
app/src/main/assets/editor.html # 请销假离线编辑器（含 html2canvas）
```

## 说明

本 App 为非官方个人整合工具，与山东管理学院官方无关。请合理使用学校系统，不要对教务服务器造成过大压力。