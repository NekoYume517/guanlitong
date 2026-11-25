package com.sdmu.guanlitong.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.ElectricalServices
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdmu.guanlitong.data.ServiceCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 首次启动引导。
 *
 * 三页：这是什么 → 先登录教务系统 → 把常用内容放到首页。
 * 动画全部用 spring：卡片错峰弹入并缓慢浮动，翻页时按位移做缩放淡出。
 */
@Composable
fun OnboardingScreen(
    onSkip: () -> Unit,
    onAddAccount: () -> Unit,
    onFinish: () -> Unit,
) {
    BackHandler(onBack = onSkip)

    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val page = pager.currentPage

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 挡住下层页面的点击，避免隔着引导点到导航栏；只吃点击，不影响翻页拖动。
                .pointerInput(Unit) {
                    detectTapGestures { }
                }
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(visible = page < 2) {
                    TextButton(onClick = onSkip) { Text("跳过") }
                }
            }

            HorizontalPager(
                state = pager,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { index ->
                val offset = ((pager.currentPage - index) + pager.currentPageOffsetFraction)
                    .coerceIn(-1f, 1f)
                OnboardingPage(
                    index = index,
                    modifier = Modifier.graphicsLayer {
                        val distance = abs(offset)
                        alpha = 1f - 0.55f * distance
                        val scale = 1f - 0.08f * distance
                        scaleX = scale
                        scaleY = scale
                    },
                )
            }

            PageDots(page = page)
            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    when (page) {
                        0 -> scope.launch { pager.animateScrollToPage(1) }
                        1 -> onAddAccount()
                        else -> onFinish()
                    }
                },
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .height(52.dp),
            ) {
                Text(
                    text = when (page) {
                        0 -> "下一步"
                        1 -> "去登录教务系统"
                        else -> "开始使用"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            AnimatedVisibility(visible = page > 0) {
                TextButton(
                    onClick = { scope.launch { pager.animateScrollToPage(page - 1) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("上一步")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun OnboardingPage(index: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(232.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (index) {
                0 -> CardsIllustration()
                1 -> AccountsIllustration()
                else -> WidgetsIllustration()
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = when (index) {
                0 -> "课表、成绩、一卡通\n都在一个 App 里"
                1 -> "先登录教务系统"
                else -> "把常用的放到首页"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 32.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = when (index) {
                0 -> "教务系统和山管 App 的数据放在一起，不用来回切网页。"
                1 -> "登录一次，课表、成绩和考试都能直接看；也可以改用抢课助手的数据。"
                else -> "在概览里添加小组件，今天有课、电费余额一眼就能看到。"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
        )
    }
}

// ── 第一页：三张浮动的卡片 ──────────────────────────────────

@Composable
private fun CardsIllustration() {
    val jwxtAccent = ServiceCatalog.byId("jwxt")?.accent ?: MaterialTheme.colorScheme.primary
    val sdgyAccent = ServiceCatalog.byId("sdgy")?.accent ?: MaterialTheme.colorScheme.tertiary
    FloatingCard("课表", Icons.Outlined.CalendarMonth, jwxtAccent, (-82).dp, 42.dp, -8f, 0)
    FloatingCard("成绩", Icons.Outlined.Assessment, sdgyAccent, 82.dp, 46.dp, 8f, 1)
    FloatingCard("一卡通", Icons.Outlined.CreditCard, MaterialTheme.colorScheme.tertiary, 0.dp, (-26).dp, 0f, 2)
}

@Composable
private fun FloatingCard(
    label: String,
    icon: ImageVector,
    accent: Color,
    x: Dp,
    y: Dp,
    rotation: Float,
    index: Int,
) {
    val float = rememberFloatOffset(index)
    Staggered(index) { progress ->
        Column(
            modifier = Modifier
                .offset(x = x, y = y + float)
                .graphicsLayer {
                    alpha = progress
                    val scale = 0.72f + 0.28f * progress
                    scaleX = scale
                    scaleY = scale
                    rotationZ = rotation * progress
                    translationY = (1f - progress) * 36.dp.toPx()
                }
                .size(width = 148.dp, height = 92.dp)
                .shadow(12.dp, MaterialTheme.shapes.large)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(14.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ── 第二页：两张账号卡 ──────────────────────────────────────

@Composable
private fun AccountsIllustration() {
    val jwxtAccent = ServiceCatalog.byId("jwxt")?.accent ?: MaterialTheme.colorScheme.primary
    val sdgyAccent = ServiceCatalog.byId("sdgy")?.accent ?: MaterialTheme.colorScheme.tertiary
    Column(
        modifier = Modifier
            .width(300.dp)
            .shadow(12.dp, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        AccountPreview("教务系统", Icons.Outlined.School, jwxtAccent, index = 0)
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        AccountPreview("山管 App", Icons.Outlined.Bolt, sdgyAccent, index = 1)
    }
}

@Composable
private fun AccountPreview(
    name: String,
    icon: ImageVector,
    accent: Color,
    index: Int,
) {
    Staggered(index) { progress ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = progress
                    translationX = (1f - progress) * -24.dp.toPx()
                }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "登录后自动同步",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Staggered(index + 2) { check ->
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            alpha = check
                            scaleX = check
                            scaleY = check
                        },
                )
            }
        }
    }
}

// ── 第三页：小组件方块 ──────────────────────────────────────

@Composable
private fun WidgetsIllustration() {
    Column(
        modifier = Modifier.width(300.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WidgetTile("今日课程", Icons.Outlined.CalendarMonth, MaterialTheme.colorScheme.primary, 0, Modifier.weight(1f))
            WidgetTile("电费", Icons.Outlined.ElectricalServices, MaterialTheme.colorScheme.tertiary, 1, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WidgetTile("一卡通", Icons.Outlined.CreditCard, MaterialTheme.colorScheme.secondary, 2, Modifier.weight(1f))
            WidgetTile("成绩", Icons.Outlined.Assessment, MaterialTheme.colorScheme.primary, 3, Modifier.weight(1f))
        }
    }
}

@Composable
private fun WidgetTile(
    label: String,
    icon: ImageVector,
    accent: Color,
    index: Int,
    modifier: Modifier = Modifier,
) {
    Staggered(index) { progress ->
        Column(
            modifier = modifier
                .height(92.dp)
                .graphicsLayer {
                    alpha = progress
                    val scale = 0.7f + 0.3f * progress
                    scaleX = scale
                    scaleY = scale
                }
                .clip(MaterialTheme.shapes.large)
                .background(accent.copy(alpha = 0.14f))
                .padding(14.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ── 小工具 ──────────────────────────────────────────────────

@Composable
private fun PageDots(page: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val selected = index == page
            val width by animateDpAsState(
                targetValue = if (selected) 24.dp else 8.dp,
                animationSpec = spring(
                    dampingRatio = 0.7f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "dot",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        }
                    ),
            )
        }
    }
}

/** 错峰弹出：弹簧阻尼偏低，落地时会轻微回弹。 */
@Composable
private fun Staggered(
    index: Int,
    content: @Composable (Float) -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(90L * index)
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.52f,
            stiffness = Spring.StiffnessLow,
        ),
        label = "stagger",
    )
    content(progress)
}

/** 缓慢上下浮动，幅度 8dp，几张卡片节奏错开。 */
@Composable
private fun rememberFloatOffset(index: Int): Dp {
    val transition = rememberInfiniteTransition(label = "float")
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2600 + index * 600,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "floatY",
    )
    return (value * 8f - 4f).dp
}
