package com.sdmu.guanlitong.data.zhs

/**
 * 课程「成绩」tab 的数据，对照参考实现 `get_report`。
 *
 * 上游把分数以字符串下发（缺省 "0"），这里统一成 Double?：
 * 缺失或不是数字就是 null，界面显示「—」，不当成 0 分。
 */
data class ZhsReport(
    val totalScore: Double? = null,
    val usualScore: Double? = null,
    val examScore: Double? = null,
    val meetScore: Double? = null,
    val finalScore: Double? = null,
    val courseScore: Double? = null,
    val bbsScore: Double? = null,
    /** 我的学习进度；上游可能是 0..1 或 0..100，界面按 [progressPercent] 归一。 */
    val learnProgress: Double? = null,
    val avgProgress: Double? = null,
    val habit: ZhsHabit? = null,
    val qaStat: ZhsQaStat? = null,
    val exams: List<ZhsExam> = emptyList(),
    /** 知识点掌握度；上游可能是 0..1 或 0..100。 */
    val knowledgeMaster: Double? = null,
    val courseStartTime: String = "",
    val courseEndTime: String = "",
)

data class ZhsHabit(
    val score: Double?,
    val regularDays: Int,
    val needDays: Int,
    /** 每天建议学习时长（分钟）。 */
    val suggestTime: Int,
    val totalStudyDay: Int,
)

data class ZhsQaStat(
    val score: Double?,
    val myAsk: Int,
    val myAnswer: Int,
    val validAsk: Int?,
    val validAnswer: Int?,
    val teacherAnswer: Int,
    val hotQuestion: String = "",
    val hotAnswer: String = "",
)

data class ZhsExam(
    val name: String,
    val score: String?,
    val status: String?,
    val note: String?,
)

/**
 * 进度类数值可能是 0..1 的比例，也可能是 0..100 的百分数；
 * ≤ 1 的按比例处理，其余截断到 0..100。
 */
internal fun progressPercent(value: Double?): Int? {
    if (value == null || value.isNaN()) return null
    val percent = if (value <= 1.0) value * 100.0 else value
    return percent.coerceIn(0.0, 100.0).toInt()
}