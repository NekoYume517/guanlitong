# F-PU 功能整合方案（guanlitong ← pu-clean）

> 状态：**待实施**　编写：规划会话　目标仓库：`D:\Code\PUCampus\guanlitong`（HEAD `88c6ad8`）
> 源仓库：`D:\Code\PUCampus\pu-clean\android`（无 VCS，改动前必须备份）
> 本文是实施契约：签名、常量、判定文案、验收判据一旦写入，实施会话不得自行改动，需要变更必须回到规划会话。

---

## 1. 目标与边界

### 1.1 目标

把 F-PU（`com.pu.lite`，WebView 单页壳 + Java 原生后端）的全部业务能力，以**原生 Compose 页面 + Kotlin 数据层**的形式整合进 guanlitong（`com.sdmu.guanlitong`），使 guanlitong 成为「教务系统 + 抢课助手 + 山管 App + 口袋校园」四端合一。

### 1.2 边界

| 项 | 决策 |
|---|---|
| 移植形态 | **重写为 Kotlin + Compose**，不引入 WebView，不移植 `assets/web/index.html`、`qr.js` |
| 复用范围 | 直接复用 F-PU 的 Java 业务实现（`Api`/`Actions`/`LoginApi`/`Sign`/`StringEncryptor`）作为**权威语义参考**，逐函数对等重写 |
| 原生库 | 原样拷贝 `libsecure_lib_v2.so` + `libcstring_encrypt.so`（仅 arm64-v8a） |
| 扫码 | 复用 guanlitong 已有的 `SdgyScannerDialog`（zxing-android-embedded），不移植 Camera2/ScanActivity/zxing-core jar |
| 二维码生成 | 复用 `SdgyCommon.qrBitmap()`，不移植 `qr.js` |
| 登录态 | 新增独立 `PuCredStore`，不并入 `AccountStore` 的密码体系（token 单独存） |
| 保活 | **不加入 `SessionKeeper`**：PU token 为长期 32 位 hex，无保活端点 |
| 配置化 URL | `configurable = false`，`defaultUrl` 留空（PU 走原生 API，无网页入口） |

### 1.3 不做什么

- 不改 `ApiProxy.java`（那是 F-PU 的本地 HTTP 代理层，在原生实现里没有对应物）
- 不改 F-PU 源码（只读参考）；如需改动，先 `cp -r pu-clean pu-clean.bak-YYYYMMDD`
- 不动 `SessionKeeper`、`SettingsStore`、`QuickActionStore`、`QuickActionEditor`、`OverviewScreen`、`FeaturesScreen`

---

## 2. 原理层：PU 协议栈

### 2.1 鉴权

```
Authorization: Bearer <token>:<school_go_id>
```
- `token`：`POST /uc/user/login` 返回，32 位 hex，长期有效
- `school_go_id`：山东管理学院 `208791531159552`（int64）
- **401 的形态是 HTTP 200 + body `{"code":401,...}`**，必须查 body，不能只看状态码

登录请求：

```http
POST https://apis.pocketuni.net/uc/user/login
Content-Type: application/json

{"sid":208791531159552,"type":0,"device":"app","userName":"<account>","password":"<password>"}
```

响应 `data.token` 必须是 `[0-9A-Fa-f]{32}`，否则视为失败。无验证码、无 X-Sign 要求。

### 2.2 X-Sign（写操作门）

```
User-Agent: client:Android version:7.1.99 Brand:<Build.BRAND> Product:<Build.MODEL> OsVersion:<Build.VERSION.RELEASE>
X-Sign:     StringEncryptor.encrypt({"echo":"<uuid4>","client":"android","timestamp":"<unix 秒>"})
```

- 加密由 `libsecure_lib_v2.so` 完成，JNI **静态注册**，符号名 `Java_com_pu_encrypt_StringEncryptor_obf_1encrypt_117d4a1`
- 因此类必须是 **Java 源码**、包名 `com.pu.encrypt`、类名 `StringEncryptor`、方法名 `obf_encrypt_17d4a1` —— **不能用 Kotlin 重写**（Kotlin 的 companion/object 会产生 `$` 后缀类名，符号对不上）
- 原生库带反调试（字符串 `Debugger detected in encryption function`），不 hook、不调试，只在正常进程调用
- 缺失 X-Sign 时写操作返回 `{"code":500,"message":"请更新到最新版本"}`

**各端点对 X-Sign 的实际依赖（F-PU 实测结论）**：

| 端点 | 需要 X-Sign |
|---|---|
| `/apis/activity/join` | **必须** |
| `/apis/activity/cancel` | 否（实测免签） |
| `/apis/activity/signInGps` / `signOutGps` / `signIn` / `signIn/activity/code` | 否（实测免签） |
| `/apis/relations/follow` / `unfollow` | 否 |
| 全部只读 GET | 否 |

实现策略：`PuApi` **无条件尝试**附加 X-Sign（`available` 为真时），与 F-PU `Api.api()` 完全一致；不可用时静默不带。

### 2.3 个人二维码

```
明文  = "xyhui://user/{uid}/{服务器秒级时间戳}/{username}"
算法  = DES/ECB/PKCS5Padding，key = "TG123!@#"
        （源码常量是 "TG123!@#qazDES*&^956367encode7788TR"，但 DESKeySpec 只取前 8 字节）
输出  = Base64(NO_WRAP)，固定 76 字符
```
- `ts` 取 `GET /apis/system/time` 的 `timestamp`（>1e11 时除以 1000）
- 换码周期取 `GET /apis/activity/qrCode/time` 的 `time`，钳制到 `5..600`，越界回落 30
- 客户端自 v1.7 起固定 10s 刷新（服务端仍可能返回 30）
- 显示尺寸 352dp

解码（扫码识别个人码）：Base64 白名单过滤（`A-Za-z0-9+/=`，长度 %4==0）→ DES 解密 → 明文必须以 `xyhui://user/` 开头，否则判定为非个人码。

### 2.4 活动签到码（主办方）

服务端**没有**生成接口，payload 完全由客户端拼：

```json
{"code":"<毫秒时间戳>","id":"<activityId>","title":"<活动名>","type":1}
```

UTF-8 字节数上限 `QR_MAX_BYTES = 120`，四级降级：

1. `payload` —— 完整（含 title）
2. `slim` —— 去掉 title
3. `fit` —— 截断 title 并加 `…` 到刚好不超限
4. `raw` —— 只剩 `{"code":"...","id":"..."}`

`mid` 取 `GET /apis/system/time` 毫秒值，失败回退本机 `System.currentTimeMillis()` 并标记 `src="local"`。

### 2.5 扫码分类（纯本地，零网络）

| 输入特征 | kind | 输出 |
|---|---|---|
| 76 字符 Base64（可 DES 解出 `xyhui://user/`） | `user` | `uid` / `ts` / `username` / `plain` |
| 明文 `xyhui://user/...` | `user` | 同上 |
| `{` 开头且含 `code`/`mid` 或 `id`/`activityId`/`aid` | `activity` | `mid` / `id`（0 表示无）/ `title` / `type` |
| 纯数字 10–16 位 | `activity` | `mid` = 原文，`id` = 0 |
| 其他 | `text` | 原文 |

`id` 为 0 时，前端让用户从「未签到活动」里选一个再提交。

### 2.6 上游数据坑（必须照抄，否则功能错乱）

| 坑 | 正确做法 |
|---|---|
| `pageInfo.count` 是**记录数**，`total` 是**页数** | 翻页终止条件：`items.size >= count \|\| page >= total` |
| `myList/new` 的 `type` 是唯一真实过滤器 | `0`=全部、`3`=已结束未完结、`4`=已完结获学时 |
| `totalCredit` 53.9 ≠ 明细求和 58 | 两个数都展示，标注来源 |
| `/apis/credit/*` 全部 404 | 学时只能从 `/apis/personal/credit` + 活动明细聚合 |
| `/uc/user/infocurr` 的学时分恒为 0 | 不读该字段 |
| `/apis/activity/info` 的 `id` 必须是 int64 | `Long`，字符串会 type mismatch |
| `mid` 必须是 int64 | `mid` 能转数字就传数字，否则传字符串 |
| `/apis/relations/*/list` 的 `page` 必填 | 缺省报 `code:500`；`limit ≤ 100` |

---

## 3. 组件层：源码 → 目标模块映射

### 3.1 数据层（新增 `app/src/main/java/com/sdmu/guanlitong/data/pu/`）

| F-PU 源文件 | 行数 | 目标文件 | 职责 |
|---|---|---|---|
| `Api.java` | 205 | `PuApi.kt` | HTTP 客户端：BASE/超时/鉴权头/X-Sign/`Page`/分页/`mask`/`creditOf` |
| `LoginApi.java` | 143 | `PuLoginApi.kt` | `/uc/user/login`，token 正则校验 |
| `Sign.java` | 51 | `PuSign.kt` | `VERSION="7.1.99"`、`userAgent()`、`xSign()` |
| `Cred.java` | — | `PuCredStore.kt` | token/goId 持久化（Keystore 加密） |
| `Actions.java` | 432 | `PuActions.kt` | 9 个业务动作 + 全部判定文案 |
| `ApiProxy.java` 路由段 | ~300 | `PuActions.kt` / `PuScan.kt` | 24 条本地路由的对等实现 |
| `ApiProxy.personalCode*` | ~50 | `PuPersonalCode.kt` | DES 编解码 |
| `ApiProxy.scanClassify` | ~45 | `PuScan.kt` | 扫码分类 |
| — | — | `PuModels.kt` | 数据类（`PuSession`/`PuPage`/`PuActivity`/`PuCredit`/`PuScanResult`/`PuSignState`） |
| — | — | `PuRepository.kt` | 聚合入口（仿 `SdgyRepository`），持有 `PuApi` 实例 |
| — | — | `PuException.kt` | 业务异常 + `puMessage()` 文案映射 |

### 3.2 JNI 桥（新增，**Java 源码**）

| 源 | 目标 | 说明 |
|---|---|---|
| `com/pu/encrypt/StringEncryptor.java` | `app/src/main/java/com/pu/encrypt/StringEncryptor.java` | **逐字节照抄，不改包名/类名/方法名**。AGP 支持 `src/main/java` 下 Kotlin/Java 混编 |
| `app/src/main/jniLibs/arm64-v8a/libsecure_lib_v2.so` | 同路径 | 3.1 MB 级，必须与 `libcstring_encrypt.so` 同放 |
| `app/src/main/jniLibs/arm64-v8a/libcstring_encrypt.so` | 同路径 | `libsecure_lib_v2.so` 的 DT_NEEDED 依赖 |

**不要加 `ndk { abiFilters }`**：guanlitong 的 ML Kit（`text-recognition`）自带多 ABI native 库，加 `abiFilters` 会剥离非 arm64 的 ML Kit 库，导致教务验证码识别在 armeabi-v7a 设备上崩溃。只放 `arm64-v8a` 目录即可。

### 3.3 UI 层（新增 `app/src/main/java/com/sdmu/guanlitong/ui/pu/`）

| 目标文件 | 对应 F-PU 前端区块 | 内容 |
|---|---|---|
| `PuHubScreen.kt` | `index.html` 首页 | 账号卡 + 8 个入口卡片 |
| `PuActivitiesScreen.kt` | 「活动」tab | 列表 + grep 搜索 + 分页 + 批量报名 |
| `PuActivityDetailScreen.kt` | 活动详情弹层 | 详情 + 报名/取消/签到/签退/签到状态/生成签到码 |
| `PuMineScreen.kt` | 「我的活动」 | type 0/3/4 切换 + 分页 |
| `PuCreditScreen.kt` | 「学时」 | 总学时 + 按年聚合 + 明细 |
| `PuFriendsScreen.kt` | 「好友」 | friend/followee/follower 三 tab + 关注/取关 |
| `PuPersonalCodeScreen.kt` | 「我的二维码」 | 二维码 + 倒计时换码 |
| `PuScanScreen.kt` | 「扫一扫」 | 复用 `SdgyScannerDialog` + 分类结果 + 跳转 |
| `PuSignCodeScreen.kt` | 「签到码」 | 主办方动态码（大屏显示 + 倒计时） |
| `ui/account/PuLoginSection.kt` | 登录区 | 仿 `SdgyLoginSection`，学校 ID + 账号 + 密码 |

### 3.4 复用（零新增）

| 资产 | 位置 | 用途 |
|---|---|---|
| `SdgyScannerDialog` | `ui/sdgy/SdgyScanScreen.kt:62` | PU 扫码 |
| `qrBitmap` / `QrImage` | `ui/sdgy/SdgyCommon.kt:123` / `:138` | PU 二维码渲染 |
| `SdgyScaffold` / `SdgyLoading` / `SdgyMessage` / `SdgySectionTitle` / `SdgyKeyValueRow` | `ui/sdgy/SdgyCommon.kt` | 二级页外壳 |
| `rememberPermissionRequester` / `Context.hasPermission` | `ui/sdgy/SdgyCommon.kt:153` / `:160` | 定位权限 |
| `Secrets` | `data/Secrets.kt` | token 加密存储 |
| `ServiceBadge` / `StatusDot` / `StatusText` / `SectionTitle` | `ui/components/Components.kt` | 列表项 |

> 跨模块引用 `ui.sdgy.*` 会形成 `ui.pu → ui.sdgy` 依赖。**可选优化 P1**：把 `SdgyScannerDialog` 提到 `ui/components/ScannerDialog.kt`，在 `SdgyScanScreen.kt` 里留一行 `@Composable fun SdgyScannerDialog(...) = ScannerDialog(...)` 转发。若时间紧，先直接 import，标记 `// TODO(P1): 上提到 ui/components`。

---

## 4. 流程层：状态机与页面流

### 4.1 导航接入（`ui/AppRoot.kt`）

```
Dest.Pu(page: PuPage = Hub, activityId: Long? = null)
```
- `openFeature()` 增加 `is FeatureAction.Pu -> goForward(Dest.Pu(action.page))`
- 覆盖页 `when (dest)` 增加 `is Dest.Pu -> when (dest.page) { ... }`
- `AnimatedContent` 转场由 `pageDirection` 自动生效，无需额外处理

### 4.2 页面跳转图

```
PuHub ─┬─→ Activities ─→ ActivityDetail ─┬─→ SignCode
       ├─→ Mine                          └─→ Scan(结果回填 mid)
       ├─→ Credit
       ├─→ Friends
       ├─→ PersonalCode
       └─→ Scan ─┬─ kind=user     → 好友主页（otherUser → follow/unfollow）
                 ├─ kind=activity → 有 id：ActivityDetail(签到)
                 │                  无 id：选未签到活动 → scanSignIn
                 └─ kind=text     → 提示「无法识别」+ 复制
```

### 4.3 未登录门

- `PuHub` 在 `!loggedIn` 时只显示登录引导卡，其余入口置灰
- 其余 PU 页面进入前检查 `PuCredStore.token` 为空 → 跳 `Dest.Account("pu")`
- 401（body `code:401`）→ 清 token、`AccountStore.signOut("pu")`、提示「登录已失效」

### 4.4 签到/签退时间窗判定（`PuActions` 内部，文案逐字照抄）

```
whyJoin:     已报名 / 报名未开始（<joinStartTime>起）/ 报名已截止（<joinEndTime>）
whySignIn:   未报名 / 已签到，请勿重复 / 签到未开始 / 活动已结束
             （窗口 = signStartTime ?: startTime → endTime）
whySignOut:  未报名 / 尚未签到 / 已签退，请勿重复 / 签退未开始 / 签退已截止
             （起点 = signOutStartTime ?: shift(endTime, -canSignOutTime)
               终点 = shift(endTime, +60min)）
```

`FMT = "yyyy-MM-dd HH:mm:ss"`；`SIGN_OUT_GRACE_MIN = 60`；地址回退 `signPlace → address → "定位签到"`。

### 4.5 时间戳解析（`tsMs`）

| 形态 | 处理 |
|---|---|
| 10 位数字 | 视为秒，×1000 |
| 13 位数字 | 原样 |
| ≥19 字符 | 按 `yyyy-MM-dd HH:mm:ss` 解析 |

### 4.6 `parseMid(raw)`

1. JSON：取 `code` 或 `mid`，同时取 `id`/`activityId` → `(mid, aid)`
2. 否则按**最后一个** `,` 或 `|` 切分
3. 否则整串作为 mid，aid 为空

---

## 5. 文件级改动清单

### 5.1 新增（13 个文件 + 2 个 .so）

```
app/src/main/java/com/pu/encrypt/StringEncryptor.java      ← 从 pu-clean 逐字节拷贝
app/src/main/jniLibs/arm64-v8a/libsecure_lib_v2.so         ← 拷贝
app/src/main/jniLibs/arm64-v8a/libcstring_encrypt.so       ← 拷贝
app/src/main/java/com/sdmu/guanlitong/data/pu/PuModels.kt
app/src/main/java/com/sdmu/guanlitong/data/pu/PuException.kt
app/src/main/java/com/sdmu/guanlitong/data/pu/PuSign.kt
app/src/main/java/com/sdmu/guanlitong/data/pu/PuCredStore.kt
app/src/main/java/com/sdmu/guanlitong/data/pu/PuApi.kt
app/src/main/java/com/sdmu/guanlitong/data/pu/PuLoginApi.kt
app/src/main/java/com/sdmu/guanlitong/data/pu/PuActions.kt
app/src/main/java/com/sdmu/guanlitong/data/pu/PuPersonalCode.kt
app/src/main/java/com/sdmu/guanlitong/data/pu/PuScan.kt
app/src/main/java/com/sdmu/guanlitong/data/pu/PuRepository.kt
app/src/main/java/com/sdmu/guanlitong/ui/account/PuLoginSection.kt
app/src/main/java/com/sdmu/guanlitong/ui/pu/PuHubScreen.kt
app/src/main/java/com/sdmu/guanlitong/ui/pu/PuActivitiesScreen.kt
app/src/main/java/com/sdmu/guanlitong/ui/pu/PuActivityDetailScreen.kt
app/src/main/java/com/sdmu/guanlitong/ui/pu/PuMineScreen.kt
app/src/main/java/com/sdmu/guanlitong/ui/pu/PuCreditScreen.kt
app/src/main/java/com/sdmu/guanlitong/ui/pu/PuFriendsScreen.kt
app/src/main/java/com/sdmu/guanlitong/ui/pu/PuPersonalCodeScreen.kt
app/src/main/java/com/sdmu/guanlitong/ui/pu/PuScanScreen.kt
app/src/main/java/com/sdmu/guanlitong/ui/pu/PuSignCodeScreen.kt
docs/INTEGRATION-PU.md                                     ← 本文
```

### 5.2 修改（6 个文件，逐处锚点）

| 文件 | 位置 | 改动 |
|---|---|---|
| `data/ServiceCatalog.kt` | `services` 列表 | 追加 `pu` 条目（见 §6.1） |
| `data/ServiceCatalog.kt` | `featureServices` | 条件加 `\|\| it.id == "pu"` |
| `data/ServiceCatalog.kt` | `accountServices` | 条件加 `\|\| it.id == "pu"` |
| `data/FeatureCatalog.kt` | 文件头 | 加 `enum class PuPage { Hub, Activities, Mine, Credit, Friends, PersonalCode, Scan, SignCode }` |
| `data/FeatureCatalog.kt` | `FeatureAction` | 加 `data class Pu(val page: PuPage) : FeatureAction` |
| `data/FeatureCatalog.kt` | `targetServiceId` | 加 `is FeatureAction.Pu -> serviceId`（**穷尽 when，漏写编译失败**） |
| `data/FeatureCatalog.kt` | `features` 列表 | 追加 8 条（见 §6.2） |
| `ui/AppRoot.kt` | `Dest` 密封接口 | 加 `data class Pu(...)` |
| `ui/AppRoot.kt` | `openFeature()` | 加 `is FeatureAction.Pu -> goForward(Dest.Pu(action.page))` |
| `ui/AppRoot.kt` | 覆盖页 `when (dest)` | 加 `is Dest.Pu -> ...` |
| `ui/account/AccountScreen.kt` | `when { ... }` 登录分发 | 在 `sdgy` 分支旁加 `service.id == "pu" -> PuLoginSection(...)`（**必须，否则误落 CREDENTIAL 分支调抢课助手后端**） |
| `ui/mine/MineScreen.kt` | 清除对话框文案（~147 行） | 改为「教务系统、抢课助手、山管 App 和口袋校园的账号、密码、网页登录状态和课表缓存都会被删除，需要重新登录。」 |
| `ui/mine/MineScreen.kt` | `onClearAll`（在 `AppRoot.kt:399`） | 追加 `PuCredStore(context).clear()` |
| `app/build.gradle.kts` | `android { }` 或 `defaultConfig` | 无必需改动；建议加 `packaging { jniLibs { useLegacyPackaging = false } }`（默认即可，可省） |
| `app/proguard-rules.pro` | 末尾 | 加 `-keep class com.pu.encrypt.StringEncryptor { *; }` |
| `README.md` | 功能章节 | 「三端统一保活」→「四端」；补口袋校园功能说明 |

### 5.3 零改动（已确认）

`ui/features/FeaturesScreen.kt`（PU 走默认 `FeatureChipRows` 分支）、`ui/overview/OverviewScreen.kt`（账号 widget 遍历 `accountServices` 自动出现）、`ui/overview/QuickActionEditor.kt`（候选来自 `featureServices`）、`data/QuickActionStore.kt`（`isValidId` 用 `FeatureCatalog.byId`）、`data/SettingsStore.kt`（`configurable=false` 回落 `defaultUrl`）、`data/SessionKeeper.kt`、`data/AccountStore.kt`、`data/ApiClient.kt`、`app/src/main/AndroidManifest.xml`（已有 CAMERA + 定位权限，PU 无需新 Activity）。

---

## 6. 冻结的代码契约

### 6.1 `ServiceCatalog` 新增条目

```kotlin
SchoolService(
    id = "pu",
    name = "口袋校园",
    summary = "活动报名 · 签到签退 · 学时统计",
    defaultUrl = "",
    loginMode = LoginMode.CREDENTIAL,
    icon = Icons.Outlined.EventAvailable,
    accent = Color(0xFFE0457B),
    configurable = false,
    requiresAccount = true,
)
```

> `icon` 不得与 `jwxt` 的 `Icons.Outlined.School` 重复；`accent` 与现有四色（`2F6BFF`/`F08A24`/`1E9E6A`/`7657A5`）区分。

### 6.2 `FeatureCatalog` 新增 8 条

```kotlin
ServiceFeature(id = "pu.hub",          serviceId = "pu", title = "活动中心",   subtitle = "报名 · 签到 · 学时", icon = Icons.Outlined.Dashboard,      action = FeatureAction.Pu(PuPage.Hub))
ServiceFeature(id = "pu.activities",   serviceId = "pu", title = "活动列表",   subtitle = "全部活动 · 搜索",   icon = Icons.Outlined.EventNote,      action = FeatureAction.Pu(PuPage.Activities))
ServiceFeature(id = "pu.mine",         serviceId = "pu", title = "我的活动",   subtitle = "进行中 · 已结束",   icon = Icons.Outlined.History,        action = FeatureAction.Pu(PuPage.Mine))
ServiceFeature(id = "pu.credit",       serviceId = "pu", title = "学时统计",   subtitle = "总量 · 按年聚合",   icon = Icons.Outlined.WorkspacePremium, action = FeatureAction.Pu(PuPage.Credit))
ServiceFeature(id = "pu.friends",      serviceId = "pu", title = "好友",       subtitle = "关注 · 粉丝 · 互关", icon = Icons.Outlined.People,         action = FeatureAction.Pu(PuPage.Friends))
ServiceFeature(id = "pu.personalcode", serviceId = "pu", title = "我的二维码", subtitle = "个人码 · 10s 换码", icon = Icons.Outlined.QrCode2,        action = FeatureAction.Pu(PuPage.PersonalCode))
ServiceFeature(id = "pu.scan",         serviceId = "pu", title = "扫一扫",     subtitle = "签到码 · 个人码",   icon = Icons.Outlined.QrCodeScanner,  action = FeatureAction.Pu(PuPage.Scan))
ServiceFeature(id = "pu.signcode",     serviceId = "pu", title = "生成签到码", subtitle = "主办方 · 动态码",   icon = Icons.Outlined.AddBox,         action = FeatureAction.Pu(PuPage.SignCode))
```

`quickFeatures` 保持不动（仍为 `jwxt.timetable` / `jwxt.grades` / `jwxt.selection`）。

### 6.3 数据层公开签名（A/B 会话的接口边界）

```kotlin
// PuModels.kt
data class PuSession(val token: String, val goId: String, val name: String, val account: String)
data class PuPage(val list: List<PuActivity>, val count: Int, val totalPages: Int)
data class PuActivity(
    val id: Long, val name: String, val startTime: String, val endTime: String,
    val joinStartTime: String, val joinEndTime: String, val signStartTime: String,
    val signOutStartTime: String, val address: String, val signPlace: String,
    val credit: Double, val canSignOutTime: Int, val raw: JSONObject,
)
data class PuCreditTotal(val total: Double, val raw: JSONObject)
data class PuCreditYear(val year: String, val count: Int, val sum: Double)
data class PuCreditSummary(
    val total: PuCreditTotal, val finished: List<PuActivity>, val unfinished: List<PuActivity>,
    val years: List<PuCreditYear>, val rawSum: Double,
)
data class PuUser(val id: String, val username: String, val realname: String,
                  val mobile: String, val school: String, val grade: String)
data class PuFriend(val uid: String, val nickname: String, val avatar: String,
                    val isFollower: Boolean, val isFollowee: Boolean, val raw: JSONObject)
data class PuPersonalCode(val uid: String, val username: String, val code: String,
                          val ts: Long, val refresh: Int, val name: String)
data class PuSignStatus(val isSignIn: Boolean, val signInTime: String, val signInAddress: String,
                        val isSignOut: Boolean, val signOutTime: String)
data class PuSignCode(val code: String, val mid: String, val payload: String,
                      val level: String, val src: String, val refresh: Int)
sealed interface PuScanResult {
    data class User(val uid: String, val ts: String, val username: String, val plain: String) : PuScanResult
    data class Activity(val mid: String, val id: Long, val title: String, val type: Int) : PuScanResult
    data class Text(val raw: String) : PuScanResult
}
data class PuSignState(val libLoaded: Boolean, val sign: String, val ua: String)

// PuApi.kt
class PuApi(private val token: String, private val goId: String) {
    suspend fun api(method: String, path: String, body: JSONObject? = null): JSONObject
    suspend fun fetch(path: String, body: JSONObject?): PuPage
    suspend fun minePage(type: Int, page: Int, limit: Int): PuPage
    suspend fun collectMine(type: Int): PuPage
}
fun mask(value: String): String          // 前3 + "***" + 后4，长度 <8 时全星号

// PuLoginApi.kt
object PuLoginApi {
    const val PATH = "/uc/user/login"
    suspend fun login(account: String, password: String, schoolId: Long): PuSession
}

// PuCredStore.kt
class PuCredStore(context: Context) {
    var token: String?; var goId: String?
    fun save(session: PuSession); fun clear()
    val loggedIn: Boolean
}

// PuActions.kt（全部 suspend，全部返回强类型）
suspend fun join(id: Long): JSONObject
suspend fun cancel(id: Long): JSONObject
suspend fun signIn(id: Long, address: String? = null): JSONObject
suspend fun signOut(id: Long, address: String? = null): JSONObject
suspend fun signStatus(id: Long): PuSignStatus
suspend fun canSignList(): List<PuActivity>
suspend fun signCode(id: Long): PuSignCode
suspend fun scanSignIn(id: Long, raw: String): JSONObject
suspend fun codeSignIn(id: Long, code: String, address: String? = null): JSONObject
suspend fun autoSign(ids: Long[]?, address: String? = null): JSONObject
suspend fun autoJoin(ids: Long[]): JSONObject
suspend fun me(): Pair<PuUser, PuCreditTotal>
suspend fun personalCode(): PuPersonalCode
suspend fun credit(): PuCreditSummary
suspend fun mine(type: Int, page: Int = 1, limit: Int = 20, all: Boolean = false): PuPage
suspend fun activities(page: Int = 1, limit: Int = 20, grep: String = ""): PuPage
suspend fun activityDetail(id: Long): PuActivity
suspend fun otherUser(uid: String): PuFriend
suspend fun follow(uid: String): Boolean
suspend fun unfollow(uid: String): Boolean
suspend fun friends(type: String = "friend", page: Int = 1, limit: Int = 30): PuPage

// PuScan.kt
object PuScan {
    fun classify(raw: String): PuScanResult
    fun decryptPersonalCode(b64: String): String   // 非法返回 ""
}

// PuPersonalCode.kt
object PuPersonalCode {
    const val DES_KEY = "TG123!@#"
    fun encode(uid: String, username: String, tsSeconds: Long): String   // 76 字符
}

// PuSign.kt
object PuSign {
    const val VERSION = "7.1.99"
    fun userAgent(): String
    fun xSign(): String?          // 库不可用返回 null
    fun available(): Boolean
}
```

### 6.4 常量表（逐字照抄，不得改值）

| 常量 | 值 |
|---|---|
| `BASE` | `https://apis.pocketuni.net` |
| `CONNECT_MS` / `READ_MS` | `15000` / `25000` |
| `PU_SCHOOL_GO_ID` | `208791531159552` |
| `UA_VERSION` | `7.1.99` |
| `QR_MAX_BYTES` | `120` |
| `SIGN_OUT_GRACE_MIN` | `60` |
| `FMT` | `yyyy-MM-dd HH:mm:ss` |
| `PERSONAL_DES_KEY` | `TG123!@#` |
| `QR_REFRESH_DEFAULT` | `30`（钳制 `5..600`） |
| 批量报名上限 | `50` |

### 6.5 arm64 降级策略

`PuSign.available() == false`（非 arm64 设备）时：

- 只读功能（列表/我的/学时/个人码/扫码/签到/签退/取消）**全部照常工作**
- `join()` / `autoJoin()` 直接抛 `PuException("当前设备架构不支持报名，请使用官方 App 报名")`
- `PuHub` 顶部显示一条低干扰提示条：「本机不支持签名，报名功能不可用」
- 不弹崩溃、不静默失败

---

## 7. 构建与验证命令

### 7.1 前置

```powershell
cd D:\Code\PUCampus\guanlitong
git checkout -b feature/pu-integration
```

`gradle.properties` 已写死 `127.0.0.1:7890` 代理；构建前确认代理可用，否则临时注释这两行。

### 7.2 构建

```powershell
.\gradlew.bat :app:assembleDebug
```

### 7.3 产物断言（必须全部通过）

```powershell
$apk = "app\build\outputs\apk\debug\app-debug.apk"
tar -tf $apk | Select-String "lib/arm64-v8a/lib(secure_lib_v2|cstring_encrypt)\.so"
tar -tf $apk | Select-String "classes.*\.dex"
```

期望：两条 `.so` 都在；`lib/armeabi-v7a` 下**没有**这两个文件。

### 7.4 路由对等检查

```powershell
Select-String -Path "app\src\main\java\com\sdmu\guanlitong\data\pu\*.kt" -Pattern '"/apis/|"/uc/' | ForEach-Object { $_.Line.Trim() }
```

期望输出覆盖以下 20 个上游路径（缺一不可）：

```
/uc/user/login              /uc/user/infocurr
/apis/system/time           /apis/activity/qrCode/time
/apis/activity/list         /apis/activity/myList/new
/apis/activity/info         /apis/activity/canSignList
/apis/activity/join         /apis/activity/cancel
/apis/activity/signInGps    /apis/activity/signOutGps
/apis/activity/sign/result  /apis/activity/signIn
/apis/activity/signIn/activity/code
/apis/personal/credit       /apis/other/index/
/apis/relations/follow/     /apis/relations/unfollow/
/apis/relations/{friend|followee|follower}/list
```

### 7.5 单元测试（`app/src/test/java/.../pu/`）

| 测试 | 断言 |
|---|---|
| `PuPersonalCodeTest` | `encode("208791531159552","test",1700000000)` 输出长度 == 76，且 `PuScan.decryptPersonalCode` 能还原 `xyhui://user/208791531159552/1700000000/test` |
| `PuScanTest` | 四类输入分别得到 `User`/`Activity`/`Activity`/`Text`；非法 Base64 返回 `Text` |
| `TsMsTest` | `"1700000000"`→1700000000000；`"1700000000000"`→原值；19 字符日期串解析正确 |
| `ParseMidTest` | `{"code":"123","id":"456"}`→(123,456)；`"123,456"`→(123,456)；`"123"`→(123,null) |
| `QrRefreshTest` | 3→30；5→5；600→600；601→30 |
| `QrPayloadFitTest` | 长 title 触发降级，输出 UTF-8 字节数 ≤ 120 |

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.sdmu.guanlitong.data.pu.*"
```

### 7.6 需真机的验证（`adb devices` 当前为空，实施时另行安排）

| 场景 | 判据 |
|---|---|
| 登录 | 输入真实账号 → `PuCredStore.token` 为 32 hex → 「我的」显示已登录 |
| 活动列表 | 首屏 20 条，下拉加载更多；grep 搜索能命中 |
| 报名（arm64） | 成功返回，服务端 `/apis/activity/myList/new` 出现该活动 |
| 签到 | 定位权限授予 → `signInGps` 返回 `code:0` |
| 个人码 | 用官方 PU App 扫 guanlitong 生成的码 → 显示本机用户 |
| 反向 | 用 guanlitong 扫官方 App 的个人码 → 识别为 `kind=user` 并显示昵称 |
| 签到码 | guanlitong 生成 → 官方 App 扫 → 签到成功 |
| 非 arm64 设备 | 列表可用；报名按钮提示「当前设备架构不支持报名」 |

### 7.7 logcat 判据

```powershell
adb logcat -s PuLite:V PuKt:V AndroidRuntime:E
```
- 无 `UnsatisfiedLinkError`
- 无 `Debugger detected in encryption function`
- 无明文密码/完整 token 打印（只允许 `mask()` 后的账号名）

---

## 8. 验收标准

### 8.1 自动可验证（必须全绿才算完成）

1. `.\gradlew.bat :app:assembleDebug` 成功，无 warning 升级为 error
2. §7.3 产物断言通过（两个 `.so` 在 `arm64-v8a` 下）
3. §7.4 路由对等检查覆盖 20 个上游路径
4. §7.5 单元测试全绿
5. `git diff --stat` 只触及 §5 列出的文件（多改一个文件即视为越界，需说明）
6. 现有功能未回归：教务/抢课/山管三个入口编译通过、`FeatureCatalog.byId` 仍能解析全部旧 id

### 8.2 需真机

§7.6 表格逐项打勾。

### 8.3 代码质量门

- `PuApi` 不打印 token/密码原文
- 所有网络调用在 `Dispatchers.IO`
- 所有页面有 loading / error / empty 三态
- 401 统一走「清 token → 跳登录」

---

## 9. 分派计划（三个实施会话 + 检查点）

### 9.1 会话 A：数据层（无 UI）

**范围**：§5.1 中 `data/pu/*`（11 个文件）+ `com/pu/encrypt/StringEncryptor.java` + 2 个 `.so` + `app/proguard-rules.pro`
**禁止**：碰 `ui/`、`ServiceCatalog.kt`、`FeatureCatalog.kt`、`AppRoot.kt`
**契约**：严格按 §6.3 / §6.4 实现，签名不得改
**完成判据**：`.\gradlew.bat :app:compileDebugKotlin` 通过 + §7.5 单元测试全绿 + §7.4 路由对等
**自检**：`tar -tf` 确认 `.so` 已进 APK（可先跑 `assembleDebug`）

### 9.2 会话 B：UI 层（依赖 A 的契约，可与 A 并行）

**范围**：§5.1 中 `ui/pu/*`（9 个文件）+ `ui/account/PuLoginSection.kt`
**做法**：先按 §6.3 的签名写调用代码（A 未完成时编译失败属正常），A 合入后立即能编译
**禁止**：碰 `data/`、`ServiceCatalog.kt`、`FeatureCatalog.kt`、`AppRoot.kt`、`AccountScreen.kt`
**完成判据**：A 合入后 `assembleDebug` 通过；每个页面有 loading/error/empty 三态

### 9.3 会话 C：接线（必须在 A、B 之后）

**范围**：`ServiceCatalog.kt`、`FeatureCatalog.kt`、`AppRoot.kt`、`AccountScreen.kt`、`MineScreen.kt`、`README.md`
**做法**：按 §5.2 逐处改，每处改完立刻 `assembleDebug`
**完成判据**：§8.1 六项全绿
**特别注意**：`FeatureCatalog.targetServiceId` 是穷尽 `when`，加 `FeatureAction.Pu` 后不加分支会编译失败 —— 这是设计上的保护，不是 bug

### 9.4 检查点

| # | 时机 | 检查内容 | 不通过动作 |
|---|---|---|---|
| CP1 | A 完成 | 单元测试 + 路由对等 + `.so` 打包 | 打回 A |
| CP2 | B 完成 | 页面三态齐全 + 无 `ui.pu → ui.sdgy` 以外的新跨模块依赖 | 打回 B |
| CP3 | C 完成 | §8.1 全绿 + `git diff --stat` 无越界 | 打回 C |
| CP4 | 真机 | §7.6 八项 | 记录问题，回到对应会话 |

---

## 10. 回滚

```powershell
# 整体回滚（丢弃全部改动）
cd D:\Code\PUCampus\guanlitong
git checkout main
git branch -D feature/pu-integration

# 保留分支、回到基线
git reset --hard 88c6ad8
```

**F-PU 侧**（只读参考，本方案不改它；若实施中被迫改动）：

```powershell
cd D:\Code\PUCampus
cp -r pu-clean pu-clean.bak-20261125   # 改动前先做
# 回滚
rm -rf pu-clean && mv pu-clean.bak-20261125 pu-clean
```

`pu-clean` 无 VCS，备份是唯一回滚手段。

---

## 11. 风险登记

| 风险 | 影响 | 缓解 |
|---|---|---|
| `.so` 依赖缺失导致 `UnsatisfiedLinkError` | 报名全挂 | CP1 用 `tar -tf` 断言；`StringEncryptor` 用 `catch (Throwable)` 兜底返回 false |
| 原生库反调试在 debug 构建触发 | 加密返回 null | 不在 debugger 下运行；logcat 监控 `Debugger detected` |
| 非 arm64 设备 | 无法报名 | §6.5 降级策略 + UI 提示条 |
| PU 服务端改版（`myList/new` type 语义） | 我的活动筛选错乱 | `PuActions` 的 type 常量集中定义，改一处即可 |
| `pageInfo.count/total` 语义被误读 | 翻页死循环 | 单元测试覆盖终止条件；`collectMine` 加最大页数保护（100） |
| 与官方 App 同时登录导致 token 互踢 | 随机 401 | 401 统一清 token 跳登录；文档提示用户 |

---

## 附录 A：F-PU 24 条本地路由 → Kotlin 对等

| F-PU 路由 | Kotlin |
|---|---|
| `/api/login` | `PuLoginApi.login` |
| `/api/logout` | `PuCredStore.clear()` |
| `/api/health` | 不需要（用 `PuSign.available()`） |
| `/api/xsign` | `PuSignState(libLoaded, sign, ua)` |
| `/api/join` | `PuActions.join` |
| `/api/cancel` | `PuActions.cancel` |
| `/api/signin` | `PuActions.signIn` |
| `/api/signout` | `PuActions.signOut` |
| `/api/signstatus` | `PuActions.signStatus` |
| `/api/cansign` | `PuActions.canSignList` |
| `/api/signcode` | `PuActions.signCode` |
| `/api/scansignin` | `PuActions.scanSignIn` |
| `/api/codesignin` | `PuActions.codeSignIn` |
| `/api/scan` | `PuScan.classify` |
| `/api/otheruser` | `PuActions.otherUser` |
| `/api/follow` | `PuActions.follow` |
| `/api/unfollow` | `PuActions.unfollow` |
| `/api/friends` | `PuActions.friends` |
| `/api/autosign` | `PuActions.autoSign` |
| `/api/autojoin` | `PuActions.autoJoin` |
| `/api/me` | `PuActions.me` |
| `/api/personalcode` | `PuActions.personalCode` |
| `/api/credit` | `PuActions.credit` |
| `/api/mine` | `PuActions.mine` |
| `/api/activities` | `PuActions.activities` |
| `/api/activity/{id}` | `PuActions.activityDetail` |

## 附录 B：不移植清单

| 项 | 原因 |
|---|---|
| `assets/web/index.html`（67 KB） | Compose 原生重画 |
| `assets/web/qr.js`（8.9 KB） | 用 `SdgyCommon.qrBitmap` |
| `ScanActivity.java` + Camera2 全链路 | 用 `SdgyScannerDialog` |
| `libs/zxing-core-3.5.3.jar` | guanlitong 已有 `zxing-android-embedded:4.3.0` |
| `ApiProxy.java`（本地 HTTP 代理） | 原生实现无此层 |
| `MainActivity.java`（WebView 壳） | guanlitong 有自己的 Activity |
| `noCompress 'html'` | 无 html 资源 |
