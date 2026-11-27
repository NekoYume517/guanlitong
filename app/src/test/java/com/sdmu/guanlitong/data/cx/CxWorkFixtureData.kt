package com.sdmu.guanlitong.data.cx

/**
 * 作业做题页测试样本。
 *
 * 结构按老项目 `fetch_work_detail` 依赖的字段手工构造：`div#question{qid}`
 * （含 typename）、`h3.mark_name`、`div.answerBg` + `span.num_option[data]` +
 * `div.answer_p`、`input[name=tiankongsize{qid}]`、
 * `textarea#answerEditor{qid}N`、`input/textarea#answer{qid}`。
 * 样本覆盖单选 / 多选 / 判断 / 填空 / 简答、LaTeX、图片、无 typename 的编码兜底。
 * 真实 doWork 页样本尚未抓到，字段容错以老项目逻辑为准。
 */
internal object CxWorkFixtureData {

    const val DOWORK_URL =
        "https://mooc1.chaoxing.com/mooc-ans/mooc2/work/dowork?courseId=123&workId=456"

    val workPageHtml: String = """
        <!DOCTYPE html>
        <html>
        <head><title>第一章 作业</title></head>
        <body>
        <form action="/mooc-ans/mooc2/work/submit" method="post">
          <input type="hidden" name="courseId" value="123456">
          <input type="hidden" name="clazzId" value="7890">
          <input type="hidden" name="workId" value="404252409">
          <input type="hidden" name="enc" value="abc%2Bdef">
          <span class="courseName">高等数学</span>

          <div id="question111" typename="单选题">
            <h3 class="mark_name"> 1. 下列哪个是质数？</h3>
            <input type="hidden" id="answertype111" value="0">
            <div class="answerBg">
              <span class="num_option" data="A">A</span>
              <div class="answer_p"> 4 </div>
            </div>
            <div class="answerBg">
              <span class="num_option" data="B">B</span>
              <div class="answer_p"> <img data="%5Csqrt%7B4%7D"> 的值 </div>
            </div>
            <div class="answerBg">
              <span class="num_option" data="C">C</span>
              <div class="answer_p"> 9 </div>
            </div>
            <input id="answer111" value="A">
          </div>

          <div id="question222" typename="多选题">
            <h3 class="mark_name"> 2. 下列哪些是偶数？</h3>
            <input type="hidden" id="answertype222" value="1">
            <div class="answerBg">
              <span class="num_option" data="A">A</span>
              <div class="answer_p">2</div>
            </div>
            <div class="answerBg">
              <span class="num_option" data="B">B</span>
              <div class="answer_p">3</div>
            </div>
            <div class="answerBg">
              <span class="num_option" data="C">C</span>
              <div class="answer_p">4</div>
            </div>
            <input id="answer222" value="AC">
          </div>

          <div id="question333" typename="判断题">
            <h3 class="mark_name">3. 地球是圆的。</h3>
            <input type="hidden" id="answertype333" value="2">
            <input id="answer333" value="true">
          </div>

          <div id="question444" typename="填空题">
            <h3 class="mark_name">4. 计算的结果是
              <img data="%22%5C%5Cfrac%7B1%7D%7B2%7D%5Cmspace%7B3mu%7D%22"> 克。
            </h3>
            <input type="hidden" id="answertype444" value="3">
            <input type="hidden" name="tiankongsize444" value="2">
            <textarea id="answerEditor4441">第一空</textarea>
            <textarea id="answerEditor4442">第二空</textarea>
          </div>

          <div id="question555" typename="简答题">
            <h3 class="mark_name">5. 请简述牛顿第一定律。</h3>
            <input type="hidden" id="answertype555" value="4">
            <textarea id="answer555">惯性</textarea>
          </div>

          <div id="question666" typename="单选题">
            <h3 class="mark_name">6. 看图选择 <img src="/images/fig.png"> </h3>
            <input type="hidden" id="answertype666" value="6">
            <div class="answerBg">
              <span class="num_option" data="A">A</span>
              <div class="answer_p">左图</div>
            </div>
            <div class="answerBg">
              <span class="num_option" data="B">B</span>
              <div class="answer_p">右图</div>
            </div>
          </div>

          <div id="question777">
            <h3 class="mark_name">7. 没有 typename 的多选题。</h3>
            <input type="hidden" id="answertype777" value="1">
            <div class="answerBg">
              <span class="num_option" data="A">A</span>
              <div class="answer_p">甲</div>
            </div>
            <div class="answerBg">
              <span class="num_option" data="B">B</span>
              <div class="answer_p">乙</div>
            </div>
          </div>
        </form>
        </body>
        </html>
    """.trimIndent()

    val barePageHtml: String = """
        <html>
        <head><title>学习通</title></head>
        <body><p>当前没有可做的作业</p></body>
        </html>
    """.trimIndent()

    /** 200 的 meta refresh 跳转页，用于 fetchDetail 的客户端跳转兜底。 */
    val redirectPageHtml: String = """
        <html>
        <head>
          <meta http-equiv="refresh" content="0;url=/doWork?workId=456">
        </head>
        <body></body>
        </html>
    """.trimIndent()

    val submitSuccessJson: String = """{"status":true,"msg":"提交成功"}"""

    val submitFailureJson: String = """{"status":0,"msg":"作业已截止"}"""
}