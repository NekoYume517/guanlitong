package com.sdmu.guanlitong.ui.cx

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sdmu.guanlitong.data.cx.CxApi
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.CxWorkApi
import com.sdmu.guanlitong.data.cx.CxWorkDetail
import com.sdmu.guanlitong.data.cx.CxWorkQType
import com.sdmu.guanlitong.data.cx.CxWorkQuestion
import com.sdmu.guanlitong.data.cx.CxWorkResult
import com.sdmu.guanlitong.data.cx.cxMessage
import com.sdmu.guanlitong.ui.sdgy.SdgyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val TAG = "CxWorkScreen"
private const val IMAGE_REFERER = "https://mooc1.chaoxing.com/"

/**
 * 作业答题页：从收件箱作业消息的 [entryUrl]（intoexamorwork）拉取 doWork 页，
 * 解析题目后在 App 内作答并提交。
 *
 * 题目只支持单选 / 多选 / 判断 / 填空 / 简答（含计算等文本题型）；图片经 coil
 * 直连加载并附带学习通 Referer，LaTeX 目前以原文内联显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CxWorkAnswerScreen(
    entryUrl: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember(context) { CxRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var detail by remember(entryUrl) { mutableStateOf<CxWorkDetail?>(null) }
    var loadError by remember(entryUrl) { mutableStateOf<String?>(null) }
    var reloadKey by remember(entryUrl) { mutableIntStateOf(0) }
    var answers by remember(entryUrl) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }

    var submitting by remember(entryUrl) { mutableStateOf(false) }
    var submitError by remember(entryUrl) { mutableStateOf<String?>(null) }
    var result by remember(entryUrl) { mutableStateOf<CxWorkResult?>(null) }
    var showSuccess by remember(entryUrl) { mutableStateOf(false) }
    var askConfirm by remember(entryUrl) { mutableStateOf(false) }

    LaunchedEffect(entryUrl, reloadKey) {
        detail = null
        loadError = null
        answers = emptyMap()
        submitError = null
        result = null
        try {
            val loaded = repo.withSession { session ->
                CxWorkApi(CxApi(session.cookies)).fetchDetail(entryUrl)
            }
            detail = loaded
            answers = loaded.questions.associate { it.id to initialValues(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "作业页加载失败（${e.javaClass.simpleName}）", e)
            loadError = "${e.cxMessage()}（${e.javaClass.simpleName}）"
        }
    }

    fun doSubmit() {
        val loaded = detail ?: return
        if (submitting) return
        submitting = true
        submitError = null
        scope.launch {
            try {
                val outcome = repo.withSession { session ->
                    CxWorkApi(CxApi(session.cookies)).submit(loaded, answers)
                }
                result = outcome
                if (outcome.success) {
                    showSuccess = true
                } else {
                    submitError = outcome.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "作业提交失败（${e.javaClass.simpleName}）", e)
                submitError = "${e.cxMessage()}（${e.javaClass.simpleName}）"
            } finally {
                submitting = false
            }
        }
    }

    val loaded = detail
    val total = loaded?.questions?.size ?: 0
    val answeredCount = loaded?.questions?.count { question ->
        answers[question.id]?.any { it.isNotBlank() } == true
    } ?: 0
    val submitted = result?.success == true

    Scaffold(
        modifier = modifier,
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Text(
                        title.ifBlank { "作业" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                subtitle = {
                    Text(
                        loaded?.courseName?.takeIf { it.isNotBlank() }
                            ?: loaded?.title?.takeIf { it.isNotBlank() }
                            ?: "学习通作业",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (loaded == null) {
                        IconButton(onClick = { reloadKey++ }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "重新加载")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            if (loaded != null && !submitted && loaded.questions.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        submitError?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已答 $answeredCount / $total",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                enabled = !submitting,
                                onClick = {
                                    if (total > answeredCount) {
                                        askConfirm = true
                                    } else {
                                        doSubmit()
                                    }
                                },
                            ) {
                                if (submitting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("提交中")
                                } else {
                                    Text("提交")
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loaded == null && loadError == null -> CxColdStartLoading(
                    text = "正在读取作业",
                    resetKey = reloadKey,
                    onRetry = { reloadKey++ },
                )

                loaded == null -> SdgyMessage(
                    text = loadError.orEmpty(),
                    modifier = Modifier.fillMaxSize(),
                    onRetry = { reloadKey++ },
                )

                loaded.questions.isEmpty() -> SdgyMessage(
                    text = "没有解析到题目。可能作业已结束，或页面结构已经变化。",
                    modifier = Modifier.fillMaxSize(),
                    onRetry = { reloadKey++ },
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(loaded.questions, key = { it.id }) { question ->
                        QuestionCard(
                            question = question,
                            values = answers[question.id].orEmpty(),
                            onPick = { key ->
                                answers = answers.withPick(question, key)
                            },
                            onBlankChange = { blank, value ->
                                answers = answers + (
                                    question.id to
                                        updateBlank(
                                            answers[question.id].orEmpty(),
                                            blank,
                                            value,
                                        )
                                    )
                            },
                            onTextChange = { value ->
                                answers = answers + (question.id to listOf(value))
                            },
                        )
                    }
                    if (submitted) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    result?.message?.ifBlank { "已提交" } ?: "已提交",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSuccess) {
        AlertDialog(
            onDismissRequest = { showSuccess = false },
            title = { Text("提交成功") },
            text = { Text(result?.message?.ifBlank { "答案已提交" } ?: "答案已提交") },
            confirmButton = {
                TextButton(onClick = {
                    showSuccess = false
                    onBack()
                }) { Text("返回") }
            },
            dismissButton = {
                TextButton(onClick = { showSuccess = false }) { Text("留在本页") }
            },
        )
    }

    if (askConfirm) {
        val unanswered = total - answeredCount
        AlertDialog(
            onDismissRequest = { askConfirm = false },
            title = { Text("还有题目没答") },
            text = { Text("还有 $unanswered 道题未作答，仍然提交吗？") },
            confirmButton = {
                TextButton(onClick = {
                    askConfirm = false
                    doSubmit()
                }) { Text("仍然提交") }
            },
            dismissButton = {
                TextButton(onClick = { askConfirm = false }) { Text("继续作答") }
            },
        )
    }
}

@Composable
private fun QuestionCard(
    question: CxWorkQuestion,
    values: List<String>,
    onPick: (String) -> Unit,
    onBlankChange: (Int, String) -> Unit,
    onTextChange: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = typeColor(question.qtype),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        question.qtype.label,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "第 ${question.index} 题",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            // 题干文本没解析出来、但有配图时，直接把图片当内容展示，不显示占位文案。
            if (question.title.isNotBlank()) {
                Text(question.title, style = MaterialTheme.typography.bodyLarge)
            } else if (question.titleImages.isEmpty() && question.options.isEmpty()) {
                Text(
                    "（题干为空）",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            question.titleImages.forEach { url ->
                Spacer(Modifier.height(8.dp))
                WorkImage(url)
            }

            when (question.qtype) {
                CxWorkQType.SINGLE, CxWorkQType.MULTI -> {
                    if (question.options.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "该题选项未能解析，可能页面结构已经变化。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    val selected = values.firstOrNull().orEmpty()
                    question.options.forEach { option ->
                        val checked = if (question.qtype == CxWorkQType.MULTI) {
                            selected.contains(option.key)
                        } else {
                            selected == option.key
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(option.key) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (question.qtype == CxWorkQType.MULTI) {
                                Checkbox(checked = checked, onCheckedChange = { onPick(option.key) })
                            } else {
                                RadioButton(selected = checked, onClick = { onPick(option.key) })
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${option.key}. ${option.text}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                option.images.forEach { url ->
                                    Spacer(Modifier.height(4.dp))
                                    WorkImage(url)
                                }
                            }
                        }
                    }
                }

                CxWorkQType.JUDGE -> {
                    val selected = values.firstOrNull().orEmpty()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        FilterChip(
                            selected = selected == "true",
                            onClick = { onPick("true") },
                            label = { Text("正确") },
                        )
                        FilterChip(
                            selected = selected == "false",
                            onClick = { onPick("false") },
                            label = { Text("错误") },
                        )
                    }
                }

                CxWorkQType.FILL -> {
                    val blanks = maxOf(question.fillCount, values.size, 1)
                    repeat(blanks) { index ->
                        OutlinedTextField(
                            value = values.getOrElse(index) { "" },
                            onValueChange = { onBlankChange(index, it) },
                            label = { Text("第 ${index + 1} 空") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                }

                else -> {
                    OutlinedTextField(
                        value = values.firstOrNull().orEmpty(),
                        onValueChange = onTextChange,
                        label = { Text("作答") },
                        minLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkImage(url: String) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .addHeader("Referer", IMAGE_REFERER)
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = "题目图片",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .clip(RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun typeColor(type: CxWorkQType) = when (type) {
    CxWorkQType.SINGLE, CxWorkQType.MULTI -> MaterialTheme.colorScheme.primaryContainer
    CxWorkQType.JUDGE -> MaterialTheme.colorScheme.tertiaryContainer
    else -> MaterialTheme.colorScheme.secondaryContainer
}

private fun initialValues(question: CxWorkQuestion): List<String> = when (question.qtype) {
    CxWorkQType.FILL -> {
        val size = maxOf(question.fillCount, question.fillAnswers.size, 1)
        List(size) { question.fillAnswers.getOrElse(it) { "" } }
    }

    else -> question.answer.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
}

private fun Map<String, List<String>>.withPick(
    question: CxWorkQuestion,
    key: String,
): Map<String, List<String>> = when (question.qtype) {
    CxWorkQType.MULTI -> {
        val current = this[question.id]?.firstOrNull().orEmpty()
        val keys = current.filter { it.isLetterOrDigit() }.toSet()
        val ch = key.first()
        val next = if (ch in keys) keys - ch else keys + ch
        this + (question.id to listOf(next.sorted().joinToString("")))
    }

    else -> this + (question.id to listOf(key))
}

private fun updateBlank(current: List<String>, index: Int, value: String): List<String> {
    val size = maxOf(current.size, index + 1)
    return List(size) { i -> if (i == index) value else current.getOrElse(i) { "" } }
}