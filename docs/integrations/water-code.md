# 饮水码接入评估

## 结论

**不能直接把 `D:\Downloads\unpack\3` 的解包文件拷进工作区运行，也不能复用现有山管 App 的 token。**

这个小程序本质上是一个微信登录壳，真正业务在远端 H5：

```text
微信小程序
  └─ wx.login 获取临时 code
      └─ 打开 https://webapp.yktapi.com/wx_1089/#/?sc=1089&code=<code>
          └─ H5 用 code 换取 openId
              └─ 用 openId + tgcode 换取饮水码 token
                  └─ Authorization: Bearer <token> 调用业务接口
```

山管 App 使用的是联奕移动校园协议，域名、加密方式和 token 语义都不同。

## 实测结果

2026-11-25 使用模拟器里已登录的山管会话，在管院通进程内生成真实 `Access-Token`，只读请求饮水码接口：

```text
POST https://wxcloud.yktapi.com/wx_core/menu/list
Body: {"orgId":"1089","id":""}
```

| 请求头方式 | HTTP | 业务 code | 结果 |
| --- | --- | --- | --- |
| `Authorization: Bearer <山管 token>` | 200 | 401 | 认证失败 |
| `Access-Token: <山管 token>` + `Access-Token-Org: 1089` | 200 | 401 | 认证失败 |
| 山管原生 `Access-Token` + `Unique-Code` + `AppType` | 200 | 401 | 认证失败 |

未登录时同一接口也返回业务 `401`。测试没有输出 token，没有领取饮水码，也没有发起支付。

## 为什么不直接拷贝解包目录

解包目录包含三类东西：

1. 微信小程序运行时的 `app-service.js`、`page-frame.html` 等，依赖微信 `wx.login`、`wx.requestPayment` 等能力；
2. 远端 H5 的压缩/混淆代码，域名和接口由服务端动态控制；
3. 之前的逆向分析脚本和中间产物。

这些文件不是独立可运行的 Android 源码。直接放进 `app/src/main/assets` 最多只能得到一个无法登录的静态页面，还会把不可信脚本、过期接口和运维风险带进主工程。

## 饮水码真实鉴权链路

静态分析远端公开 H5 后确认：

- 业务接口基础地址：`https://wxcloud.yktapi.com/wx_core`
- `orgId` 来自 URL 参数 `sc`，学校值为 `1089`
- 换取 openId：`POST /mini/info/getMiniOpenId`，参数 `orgId + code`
- 换取 token：`POST /mini/info/getToken`，参数 `orgId + openId + tgcode`
- 业务请求头：`Authorization: Bearer <token>`
- token、openId、orgId 保存在 H5 的 `sessionStorage`
- 常见只读接口：`/menu/list`、`/balance`、`/payLogs`、`/qrcode`
- 支付仍然走微信 `wx.requestPayment`，不能绕过官方支付流程

### 当前解包快照的复核范围

2026-11-25 对 `D:\Downloads\unpack\3` 重新做静态复核后，主包能独立确认的接口只有：

```text
POST https://wxcloud.yktapi.com/wxapi/menu/list
POST https://wxcloud.yktapi.com/wxapi/org/getOrgInfo
POST https://wxcloud.yktapi.com/wxapi/org/getOrgConfig
POST https://wxcloud.yktapi.com/wxapi/mini/info/getMiniOpenId
```

请求体先按顶层 key 排序，再用 AES-128-ECB/PKCS7 生成 `sign`；主包请求包装器没有 Bearer、Cookie 或 Authorization 注入。`/mini/info/getMiniOpenId` 的 `data` 直接作为 `openId` 写入 `sessionStorage`。

`/qrcode`、`/individuation/qrcode`、`/ranCode`、`/infoBind`、`/changePwd` 在当前主包中只是路由名，对应懒加载 chunk 未包含在解包目录内。因此上文中 `/mini/info/getToken`、`/user/bind`、`/balance`、`/qrcode` 等更细端点来自此前的运行探针或其他资料，不能仅凭当前这份解包再次确认。

H5 还接受 URL 参数 `openid` 并直接写入会话；这是 handoff 分支，不是账号密码登录接口，服务端是否接受任意值也无法由静态包证明。`hook_js.js` 中的 `test_token`、`test_code` 均为 mock。

## 微信绑定与学号密码核验

### 学校 1089 的实际配置

只读查询 `POST /org/getOrgConfig`，请求体 `{"orgId":"1089"}`，返回：

```json
{
  "checkPhone": false,
  "bindCodeName": "学/工号",
  "enablePasswd": true,
  "enableMain": false,
  "agreementList": [],
  "enablePhone": false,
  "enableName": true,
  "enableCaptcha": true
}
```

也就是说，山东管理学院的饮水码绑定页面要求：

- 姓名 `accName`
- 学/工号 `bindCode`
- 查询密码 `passwd`
- 图形验证码 `captchaImageCode`
- 不要求手机号、不要求短信验证码

### 绑定接口链路

| 步骤 | 接口 | 主要参数 | 说明 |
| --- | --- | --- | --- |
| 获取绑定标题 | `POST /title/bindTittle` | `orgId` | 返回“请使用您的姓名以及学/工号进行账户绑定” |
| 获取表单配置 | `POST /org/getOrgConfig` | `orgId` | 控制姓名、密码、手机号、验证码开关 |
| 获取图形验证码 | `POST /main/captchaImage` | 无 | 返回图片与 `captchaImageUuid` |
| 核验身份并取绑定手机号 | `POST /user/getBindPhone` | `orgId`、`openId`、`accName`、`bindCode`、`passWord`、`captchaImageUuid`、`captchaImageCode` | 仅在机构要求核验已绑定手机号时使用；1089 未开启 |
| 发送绑定短信 | `POST /sms/getBindSmsCode` | `orgId`、`phoneNumber` | 1089 未开启 |
| 提交绑定 | `POST /user/bind` | 完整 `postForm` | 最终把微信 `openId` 与学校账户绑定 |
| 查询已绑定账户 | `POST /user/getInfoByOpenidOrgidAll` | `orgId`、`openId` | 登录后读取绑定关系 |
| 解绑 | `POST /user/unBind` | 绑定账户参数 | 需要相应验证流程 |
| 重新绑定 | `POST /user/reBind` | 完整绑定表单 | 需要相应验证流程 |

### 密码处理

页面把用户输入的查询密码先在客户端做 AES ECB/PKCS7 编码，再作为 `passWord` 提交。固定客户端密钥可以在公开 bundle 中看到，但这只是传输编码，不是安全边界；服务端仍会校验账户密码。

### 能否只用学号和密码登录

**当前服务不支持完全脱离微信的“学号 + 密码 + 验证码”直接登录。**

2026-11-25 对主包、绑定相关 chunk 和路由表做了补充审计：

- 主包没有 `/login`、`/signin` 等登录路由，也没有独立账号密码登录页；
- 已分析代码中没有 `/user/login`、`/account/login`、`/auth/login` 等纯凭据换 token 接口；
- 换取 token 只有 `/mini/info/getToken`，必须提交 `orgId + openId + tgcode`；
- `tgcode` 只是 URL 中可选透传的参数，没有发现由学号密码或学校统一身份认证生成它的流程；
- `enableMain` 不是已实现的账号密码登录入口，当前代码只把它作为机构配置字段读取；
- H5 虽能接收 URL 中的 `openid`，但仍要求该值来自合法授权流程，不能自行编造或从他人会话提取。

准确说法是：

- 学号 + 查询密码 + 姓名 + 图形验证码，可以通过饮水码的**账户身份核验**；
- 但所有绑定接口都必须携带微信 `openId`；
- `openId` 只能由微信登录流程取得，山管 token 不能替代；
- 因此无法绕过微信，直接用学号密码换取饮水码 token；
- 如果该微信已经绑定过学校账户，再次进入小程序时不会再要求学号密码，直接复用绑定关系。

因此，管院通可以实现“在 App 内填写姓名、学号、密码和图形验证码”的原生绑定体验，但前提仍是先通过官方授权取得该用户的 `openId`。这不等于纯账号密码登录，也不能通过伪造 `openId` 来实现。

## 推荐接入路线

### 路线 A：官方 API / SSO（首选）

向学校或饮水码运营方申请：

- 官方开放接口或学校统一身份认证 SSO；
- 正式 `orgId`、接口文档、调用频率限制；
- 测试账号和授权范围；
- 如需支付，提供官方商户/支付接入说明。

如果对方能把饮水码加入山管 App 的应用列表，现有 `SdgyWebScreen` 可以直接复用第三方 SSO。目前模拟器里的山管应用列表缓存中没有饮水码条目。

### 路线 B：微信授权 + 管院通原生绑定表单

这是最接近“在管院通里直接用学号密码登录”的合规方案，但仍包含微信授权步骤。

需要对方提供或明确授权：

- 小程序 `appId`，以及 Android App 是否允许使用同一授权体系；
- 应用签名、包名白名单；
- App 取得的 `code` 能否调用现有 `/mini/info/getMiniOpenId`，或提供 App 专用换取接口；
- 服务端 `appSecret` 或现成的 token 交换服务；
- 对 `/user/bind` 和 `/mini/info/getToken` 的正式调用授权。

关键点：

- `appSecret` 不能放进 APK；
- 微信登录只能拿到临时 `code`，必须由可信服务端完成换取；
- 小程序 `wx.login` 的 `code` 与 Android OpenSDK 的 `code` 不能默认认为互通，必须以运营方确认为准；
- 取得合法 `openId` 后，才在原生页面填写姓名、学号、查询密码和图形验证码并提交绑定；
- 饮水码 token 与山管 token 分开保存、分开刷新；
- 支付必须走微信官方 SDK，不自行拼接支付参数。

如果运营方不提供 App 级授权或 code 兑换能力，这条路线不能落地，应改用路线 C。

### 路线 C：小程序快捷入口（过渡方案）

暂时不接业务接口，只在管院通功能页放一个「饮水码」入口：

- 有小程序 `appId` 和页面路径时，可唤起微信小程序；
- 没有 `appId` 时，可展示官方小程序二维码，用户用微信扫码进入；
- 该方案只是入口整合，不会把余额、饮水码带进管院通原生页面。

## 工作区建议落点

获得正式授权后，建议新增独立模块，而不是复制解包目录：

```text
app/src/main/java/com/sdmu/guanlitong/
  data/water/
    WaterCodeClient.kt          # 饮水码 HTTP 客户端
    WaterCodeSessionStore.kt    # token 加密存储
    WaterCodeModels.kt          # 余额、饮水码、账单模型
  ui/water/
    WaterCodeScreen.kt          # 原生饮水码页面
    WaterCodeLoginScreen.kt     # 微信登录/授权页
```

入口放在 `FeatureCatalog`，账号状态与山管、教务分开管理。可参考现有：

- `SdgySessionStore`：Android Keystore 加密会话；
- `SdgyClient`：HTTP、Cookie、错误处理；
- `ServiceWebScreen`：网页兜底与 Cookie 持久化；
- `SdgyPayScreen`：付款码展示与刷新。

## 需要补充的材料

1. 饮水码小程序的 `appId`、页面路径或官方二维码；
2. 学校/运营方的接口授权或 SSO 文档；
3. 一个测试账号；
4. 是否包含支付，以及官方支付接入要求；
5. token 刷新、失效和调用频率限制。

## 直连账号密码路线实测（2026-11-25）

为验证“姓名 + 学号 + 密码 + 验证码直接登录”，补抓了当前线上懒加载块并做了只读/假数据探测：

1. `POST /wxapi/mini/info/getToken` 接受合成 `openId`，但只返回 `token=fixedToken`。
2. `POST /wxapi/user/bind` 使用正确的 CryptoJS 兼容 AES 密码加密、有效图形验证码和 `Authorization: Bearer fixedToken`，返回：
   ```json
   {"code":33,"msg":"token无效"}
   ```
3. 当前 H5 真正使用的 `/wx_core/mini/info/getToken` 对空值或伪造 `tgcode` 返回“渠道授权码不能为空 / 渠道授权码非法”。
4. `/wx_core/user/bind` 使用 `fixedToken` 返回 HTTP 401。

这说明 `wxapi` 与 `wx_core` 是两套鉴权面：前端可以合成 openId、可以加密密码、可以取验证码，但无法凭空生成服务端认可的微信渠道授权码。账号密码表单会在校验 credentials 之前被 token 校验挡住，因此没有上线一个必然失败的登录框。

## 工作区落地

- 解包运行产物已导入 `vendor/ykt-wxapp/raw/`，仅用于留存和研究，不打包进 APK。
- 饮水码原生入口、页面与 `WaterCodeContract` 已从主工程移除；当前 App 内没有饮水码功能。
- 保留本文与解包快照作为后续接入评估记录；重新落地前必须确认官方授权和微信 `code → openId` 链路。
- 当前没有实现“姓名/学号/密码/验证码直接换 token”，因为现有服务链路的 token 前置条件是微信 `code → openId`，而不是学号账号密码。

## 安全边界

- 不把 `appSecret`、token、Cookie 写进源码或日志；
- 不缓存明文身份信息；
- 不绕过微信环境检测或支付验证；
- 只申请必要权限，控制请求频率；
- 饮水码 token 与山管 token 使用不同存储键和刷新逻辑。
