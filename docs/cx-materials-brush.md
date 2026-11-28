# 学习通资料刷时长

> 2026-11-28 接入课程功能页「资料」模块（原「即将支持」）。
> 协议逐项对照老项目 `D:\code\chaoxing-tools\old\chaoxing-study-tool\main.py`
> （Playwright 抓包验证过），未自行简化参数与签名。

## 1. 协议

### 课程参数（`CxDocApi.params`）

```
GET https://mooc1-1.chaoxing.com/mooc-ans/visit/stucoursemiddle
    ?courseid=&clazzid=&vc=1&cpi=<personId>&ismooc2=1&v=2
```

从页面 hidden input 取 `enc`、`cpi`、`openc`。新版课程页可能没有 `enc`，
根目录请求退到老项目 `Browser` 的兜底常量
`360182fb3a145a6c260ed351d636fca9`；目录响应若带回新的 `enc`，下一层用它。

### 目录（`CxDocApi.list`）

```
根目录 GET https://mooc2-ans.chaoxing.com/mooc2-ans/coursedata/stu-datalist
       ?courseid&clazzid&type=1&enc&ut=s&t&microTopicId=0&isAjax=isAjax
子目录 再加 dataName(percent)/dataId/parent/flag=0/cpi
Referer https://mooc2-ans.chaoxing.com/
```

- 文件夹名按 Python `quote(name)` 编码：保留 `/`，空格 `%20`（`CxDocParser.percentEncode(allowSlash=true)`）；
- 解析 `<ul class="dataBody_td" dataName type id objectId>`，
  `dataBody_size_stu` / `dataBody_creater_stu` 按出现顺序对齐；
- `type=afolder` 是文件夹，可进入；面包屑 + 「上一级」回退。

### 文件元信息（`CxDocApi.meta`）

```
GET https://mooc1-1.chaoxing.com/ananas/status/{objectId}?k=9790&flag=normal&_dc=<ms>
```

返回 `objectid/pagenum/wordcount/filename`。解析失败按空对象处理，由引擎补
默认页数 100 / 字数 1659；401/403 仍上抛。

### 阅读次数（`CxDocApi.updateReadCount`）

```
GET https://mooc2-ans.chaoxing.com/mooc2-ans/coursedata/update/update-read-count
    ?courseId&clazzId&dataId&cpi&ut=s
Referer https://mooc2-ans.chaoxing.com/
```

非 200 只记日志（老项目同样忽略）。

### 阅读心跳（`CxDocApi.beat`）

```
GET https://data-xxt.aichaoxing.com/analysis/ac_mark
    ?&f=readPoint&u=<uid>&d=<quote(json)>&t=<ts>&enc=<签名>
Referer https://pan-yz.chaoxing.com/
```

`d` 为紧凑 JSON，字段顺序固定（手写序列化，不用 JSONObject 反序列化重排）：

```json
{"r":"<objectId>","t":"doc","l":1,"f":4,"p":1,"tp":1,
 "wc":<字数>,"ic":<页数>,"v":2,"s":<1 开始 / 2 进行中>,"h":<阅读高度>,
 "ext":"{\"_from_\":\"<courseId>_<clazzId>_<uid>_<fileEnc|dataId>\"}"}
```

签名（`CxDocParser.signHeartbeat`）：

```
enc = MD5(percentEncode(json) + "readPoint" + t + uid + "NrRzLDpWB2JkeodIVAn4")
```

`percentEncode` 等价 Python `quote(s, safe='')`：仅 `A-Z a-z 0-9 - _ . ~` 保留，
空格 `%20`（不是 `+`），中文按 UTF-8 百分号编码。

## 2. 刷时长引擎（`CxDocEngine`）

照搬老项目 `run()`：

- 起始心跳 `s=1, h=0, t=开始时间`；
- 每 5 秒一次 `s=2`，第 i 次的伪造时间 `t = start + i × speed × 5s`；
- 阅读高度满值 `pages × 100`：
  - 未满：`h = int(pages×100 × i×speed×5 / 3600)`，封顶满值；
  - 已满：`h = clamp(上次 h + random(-50,50), 0, 满值)`，模拟反复阅读；
- 倍速只改变上报时间，真实等待始终 5 秒；
- 用户停止 / 服务销毁时补一次 `h=满分` 的收尾心跳（老项目 Ctrl+C 语义）；
  异常退出不补，避免把失败伪装成完成；
- 心跳连续非 200 三次中止；302/401 → 登录失效，202/403 → 风控；
- 每个网络调用独立走 `CxRepository.withSession`，长跑中会话过期由单飞重登接上。

## 3. 服务与界面

- `CxDocService`：`specialUse` 前台服务，常驻通知显示课程、资料名、已上报分钟、
  进度条与「停止」。`START_NOT_STICKY`，进程被杀不自动续刷。
- 状态流 `CxDocTaskStatus`（`data/cx`）独立于刷课/签到的 `CxTaskStatus`；运行中镜像
  到共享 `BrushTaskRegistry`（id `cx.material.<objectId|dataId>`，来源
  `CX_MATERIAL`，route `cx.material`，进度 = `h / (页数×100)` 夹在 0..1），结束即
  `unregister`，浮球/徽标不残留。
- 浮球点击走 `BrushTaskRoutes.CX_MATERIAL`：用状态里的课程上下文回到该课程的资料页，
  不跳空页。
- 通知：进度通知 `cx_doc`（LOW，10 秒节流）；终态（完成/停止/失败）走独立社交频道
  `cx_brush_social`（HIGH、响铃/振动、`CATEGORY_SOCIAL`、`PRIORITY_MAX`），只在终态
  发一次。通知 id 2305/2306（避开知到的 2003/2301）。
- `CxMaterialsScreen`：目录（文件夹进入）、面包屑/上一级、文件信息（页数/字数）、
  倍速 1x/10x/60x/600x、开始/停止、运行中进度卡。
- 入口：课程功能页「资料」→ `CxPage.Materials`（`CxCourseModule.target` 已填）。

## 4. 验证

单测：`CxDocParserTest`（参数/目录/元信息、心跳 JSON 顺序与转义、签名参考值、
percentEncode）、`CxDocEngineTest`（起始/收尾心跳、倍速时间、线性与封顶抖动、
meta 兜底、认证失败与连续心跳失败中止）、`CxDocRegistryTest`（注册快照字段、
进度换算与夹取、结束移除、换文件覆盖）、`CxDocNoticeTest`（社交频道口径与终态文案）。

真机（须验收方另行授权后执行）：目录能进出、文件信息正常、1x 单文件跑 15 秒后
停止、后台/锁屏心跳不断、通知可停止；风险控制页立即中止。