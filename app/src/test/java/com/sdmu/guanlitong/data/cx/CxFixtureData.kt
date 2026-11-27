package com.sdmu.guanlitong.data.cx

/**
 * 收件箱/提醒的测试样本。
 *
 * 收件箱 JSON 按老项目 `get_inbox_list` 的真实字段构造（含置顶、重复 idCode、两种
 * attachment 形态）。作业 HTML 按老项目 `get_work_list` 的正则结构构造。
 * 考试 HTML 是「区间 / 开始时间 / 单个时间 / 截止标签」四种卡片，用于固化时间取值策略；
 * 真实 exam-list HTML 尚未抓到（老仓库 test_exam_list.html 实为自测页），真机补样本后复核。
 */
internal object CxFixtureData {

    val inboxJson: String = """
        {
          "status": 1,
          "topNotices": [
            {
              "idCode": "top-1",
              "sourceType": 14,
              "title": "期中作业",
              "content": "",
              "createrName": "张老师",
              "isread": 0,
              "insertTime": 1766209200000,
              "logo": "",
              "attachment": "[{\"att_web\":{\"url\":\"https://mooc1.chaoxing.com/mooc2/work/list?x=1\",\"logo\":\"\",\"content\":\"提交一份报告\"}}]"
            }
          ],
          "notices": {
            "list": [
              {
                "idCode": "n-1",
                "sourceType": "12",
                "title": "期末考试通知",
                "content": "请按时参加",
                "createrName": "学习通知",
                "isread": 1,
                "insertTime": "2026-12-01 09:30:00",
                "attachment": "[]"
              },
              {
                "idCode": "top-1",
                "sourceType": 14,
                "title": "列表里的重复置顶项",
                "content": "",
                "createrName": "张老师",
                "isread": 0,
                "insertTime": 1766209200000,
                "attachment": "[]"
              },
              {
                "idCode": "n-2",
                "sourceType": 0,
                "title": "系统维护",
                "content": "今晚维护",
                "createrName": "学习通知",
                "isread": 1,
                "insertTime": "",
                "attachment": "",
                "logo": "https://p.ananas.chaoxing.com/logo.png"
              }
            ],
            "lastGetId": "abc123",
            "lastPage": 1
          }
        }
    """.trimIndent()

    val inboxJsonWithObjectAttachment: String = """
        {
          "status": true,
          "notices": {
            "list": [
              {
                "idCode": "n-9",
                "sourceType": "2",
                "title": "签到开始",
                "content": "",
                "createrName": "李老师",
                "isread": 0,
                "insertTime": "2026-12-20 08:00",
                "attachment": [
                  {"att_web": {"url": "https://mobilelearn.chaoxing.com/page/active/show?id=1", "content": "快去签到"}}
                ]
              },
              {
                "idCode": "n-10",
                "sourceType": "0",
                "title": "布尔未读",
                "content": "isread 返回 JSON 布尔",
                "isread": false,
                "insertTime": "2026-12-20 09:00"
              }
            ],
            "lastGetId": "",
            "lastPage": 0
          }
        }
    """.trimIndent()

    /** status=false 但还带列表：接口行为异常，按 502。 */
    val failedInboxJson: String = """
        {"status": 0, "notices": {"list": [{"idCode": "x", "sourceType": 0, "title": "x"}]}}
    """.trimIndent()

    /** status=false 且没有任何列表：老项目的「无消息」形态，空页 + WARN，不抛 502。 */
    val emptyFalsyInboxJson: String = """{"status": 0, "notices": {"list": []}, "topNotices": [], "msg": "暂无数据"}"""

    val malformedJson: String = "<html><body>系统繁忙</body></html>"

    val workHtml: String = """
        <div class="work-list">
          <div class="work-item">
            <a href="/mooc2/work/dowork?workId=1001&courseId=1">第一次作业</a>
            <span class="deadline">截止时间：2026-12-20 23:59</span>
          </div>
          <div class="work-item">
            <a href="https://mooc1.chaoxing.com/mooc2/work/dowork?workId=1002">第二次作业</a>
            <span>截止时间：2026/12/22 08:00</span>
          </div>
        </div>
    """.trimIndent()

    val workLinkOnlyHtml: String = """
        <div class="empty">
          <a class="btn" href="/mooc2/work/dowork?workId=1">补交作业</a>
        </div>
    """.trimIndent()

    val examHtml: String = """
        <ul class="examList">
          <li class="exam-item">
            <span class="title">高等数学期末考试</span>
            <p class="status">未开始</p>
            <p class="time">考试时间：2026-12-20 09:00 ~ 2026-12-20 11:00</p>
          </li>
          <li class="exam-item">
            <span class="title">大学英语考试</span>
            <p class="status">进行中</p>
            <p class="time">开始时间：2026-11-28 14:00</p>
          </li>
          <li class="exam-item">
            <span class="title">线性代数考试</span>
            <p class="status">已结束</p>
            <p class="time">2026-11-01 09:00</p>
          </li>
          <li class="exam-item">
            <span class="title">C语言考试</span>
            <p class="status">未开始</p>
            <p class="time">截止时间：2026-12-25 18:00 结束时间：2026-12-25 20:00</p>
          </li>
        </ul>
    """.trimIndent()

    /** 没有 span.title，退回 `p.overHidden2`；标题里的 HTML 实体要还原。 */
    val examFallbackTitleHtml: String = """
        <li class="exam-item">
          <p class="overHidden2 fl">离散数学&amp;图论</p>
          <p class="status">待提交</p>
          <p class="time">考试时间：2026-12-01 10:00</p>
        </li>
    """.trimIndent()
}