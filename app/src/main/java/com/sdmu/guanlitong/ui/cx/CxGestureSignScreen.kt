package com.sdmu.guanlitong.ui.cx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.cx.CxApi
import com.sdmu.guanlitong.data.cx.CxCheckinApi
import com.sdmu.guanlitong.data.cx.CxException
import com.sdmu.guanlitong.data.cx.CxGesturePattern
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.CxSignKind
import com.sdmu.guanlitong.data.cx.CxSignRecord
import com.sdmu.guanlitong.data.cx.CxSignRecordStatus
import com.sdmu.guanlitong.data.cx.CxSignResult
import com.sdmu.guanlitong.data.cx.CxSignStore
import com.sdmu.guanlitong.data.cx.gestureSuccessHistory
import com.sdmu.guanlitong.data.cx.signAccountKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 手势签到：老师每个活动给的图案都不一样，不预存任何密码，由用户当场划。
 *
 * 九宫格 1–9 按阅读顺序编号（左上 1，右下 9）。按住拖动，手指经过的点依次进入
 * 序列（同一点不重复），抬手得到如 "12369" 的 signCode；点「确认签到」提交后
 * 结果通过 [onDone] 回给调用方。只做签到，不做手势签退。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CxGestureSignScreen(
    activeId: String,
    courseId: String,
    classId: String,
    /** 从收件箱/签到卡片进来时已知课程名、活动名；通知直达时为空。 */
    courseName: String = "",
    activeName: String = "",
    onBack: () -> Unit,
    onDone: (Boolean, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(context) { CxRepository(context.applicationContext) }
    val store = remember(context) { CxSignStore(context.applicationContext) }

    var points by remember { mutableStateOf(emptyList<Int>()) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var submitting by remember { mutableStateOf(false) }
    // 只显示失败：成功由集成层 Toast「签到成功」，页面再显示一遍会重复。
    var errorText by remember { mutableStateOf<String?>(null) }

    val lineColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
    val shape = RoundedCornerShape(28.dp)

    fun submit() {
        val code = CxGesturePattern.code(points)
        if (code.isEmpty() || submitting) return
        submitting = true
        errorText = null
        scope.launch {
            val result = try {
                repo.withSession { session ->
                    val api = CxCheckinApi(CxApi(session.cookies), session.uid)
                    val signed = api.gestureCheckin(activeId, courseId, classId, code)
                    // 手动提交成功也要落台账：否则轮询那边一直停在 MANUAL，
                    // 用户点开的手动页面与自动状态就对不上。
                    if (signed.success) {
                        val now = System.currentTimeMillis()
                        store.save(
                            signAccountKey(session),
                            activeId,
                            CxSignKind.CHECKIN,
                            CxSignRecord(
                                status = CxSignRecordStatus.SUCCESS,
                                attempts = 1,
                                atMs = now,
                            ),
                        )
                        store.appendHistory(
                            signAccountKey(session),
                            gestureSuccessHistory(
                                activeId = activeId,
                                courseId = courseId,
                                clazzId = classId,
                                courseName = courseName,
                                activeName = activeName,
                                nowMs = now,
                            ).copy(detail = signed.message),
                        )
                    }
                    signed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CxException) {
                // 底层文案通常已带原因（如「预签到失败（HTTP 500）」），直接上抛。
                CxSignResult(false, e.message ?: "网络异常")
            } catch (e: Exception) {
                CxSignResult(false, "提交失败（${e.javaClass.simpleName}）")
            }
            submitting = false
            // 失败时保留用户已划的图案，方便对照原因后直接重试。
            errorText = if (result.success) null else failureText(result.message)
            onDone(result.success, result.message)
        }
    }

    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("手势签到") },
                subtitle = { Text("按老师给的图案划一次") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp)
                .padding(bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "从图案的第一个点按住，顺势划过其余点，抬手后点「确认签到」。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val signTitle = listOf(courseName, activeName)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { "学习通签到" }
            Text(
                signTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 结果反馈放在九宫格上方：底部按钮之后的位置在小屏上会被裁掉，
            // 用户点完「确认签到」看不到任何变化。
            errorText?.let { message ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(shape)
                    .background(surfaceColor)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    .pointerInput(submitting) {
                        if (submitting) return@pointerInput
                        val board = size.width.toFloat()
                        awaitEachGesture {
                            // 每次按下都是一次新手势：抬起后再按从第一个点重新来。
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            errorText = null
                            points = CxGesturePattern.append(
                                emptyList(),
                                CxGesturePattern.hit(down.position.x, down.position.y, board),
                            )
                            finger = down.position
                            drag(down.id) { change ->
                                change.consume()
                                finger = change.position
                                points = CxGesturePattern.append(
                                    points,
                                    CxGesturePattern.hit(change.position.x, change.position.y, board),
                                )
                            }
                            finger = null
                        }
                    },
            ) {
                val boardWidth = maxWidth
                Canvas(Modifier.matchParentSize()) {
                    if (points.isEmpty()) return@Canvas
                    val cell = size.width / CxGesturePattern.COLUMNS
                    fun center(index: Int) = Offset(
                        (((index - 1) % CxGesturePattern.COLUMNS) + 0.5f) * cell,
                        (((index - 1) / CxGesturePattern.COLUMNS) + 0.5f) * cell,
                    )
                    val path = Path().apply {
                        val first = center(points.first())
                        moveTo(first.x, first.y)
                        points.drop(1).forEach { index ->
                            val point = center(index)
                            lineTo(point.x, point.y)
                        }
                        finger?.let { lineTo(it.x, it.y) }
                    }
                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(
                            width = cell * 0.12f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }

                Column(Modifier.matchParentSize()) {
                    for (row in 0 until CxGesturePattern.COLUMNS) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            for (column in 0 until CxGesturePattern.COLUMNS) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    GestureDot(
                                        number = row * CxGesturePattern.COLUMNS + column + 1,
                                        selected = row * CxGesturePattern.COLUMNS + column + 1 in points,
                                        diameter = boardWidth / CxGesturePattern.COLUMNS * 0.6f,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val code = CxGesturePattern.code(points)
            Text(
                if (code.isEmpty()) "还没有划过任何点" else "当前图案：$code",
                style = MaterialTheme.typography.titleMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    onClick = {
                        points = emptyList()
                        finger = null
                        errorText = null
                    },
                    enabled = !submitting && points.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("重划") }
                Button(
                    onClick = ::submit,
                    enabled = !submitting && code.isNotEmpty(),
                    modifier = Modifier.weight(2f),
                ) {
                    if (submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text(" 提交中…")
                    } else {
                        Text("确认签到")
                    }
                }
            }
        }
    }
}

/** 失败文案统一成「签到失败：原因」；业务层已带「签到」前缀时不重复。 */
private fun failureText(message: String): String =
    if (message.startsWith("签到失败") || message.startsWith("签到结果")) {
        message
    } else {
        "签到失败：$message"
    }

@Composable
private fun GestureDot(number: Int, selected: Boolean, diameter: Dp) {
    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$number",
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}