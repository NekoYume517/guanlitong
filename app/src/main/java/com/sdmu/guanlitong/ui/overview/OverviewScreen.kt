package com.sdmu.guanlitong.ui.overview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.Account
import com.sdmu.guanlitong.data.DashboardItem
import com.sdmu.guanlitong.data.DashboardStore
import com.sdmu.guanlitong.data.FeatureCatalog
import com.sdmu.guanlitong.data.SchoolService
import com.sdmu.guanlitong.data.ServiceCatalog
import com.sdmu.guanlitong.data.ServiceFeature
import com.sdmu.guanlitong.data.WidgetSize
import com.sdmu.guanlitong.data.WidgetType
import com.sdmu.guanlitong.data.targetServiceId
import com.sdmu.guanlitong.data.jwxt.CachedTimetable
import com.sdmu.guanlitong.data.jwxt.Course
import com.sdmu.guanlitong.data.jwxt.JwxtCache
import com.sdmu.guanlitong.data.jwxt.JwxtSource
import com.sdmu.guanlitong.data.jwxt.Timetable
import com.sdmu.guanlitong.ui.components.ServiceBadge
import com.sdmu.guanlitong.ui.components.StatusDot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 概览页：像桌面小组件一样自由组合的面板。
 *
 * 每个功能都有小 / 中 / 大三种尺寸，小号两块并排、中号和大号占满一行。
 * 右上角按钮从底部弹出小组件面板，点预览即可追加到列表末尾；
 * 长按卡片可以拖动换位，拖到右下角弹出的删除图标上松手即移除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    accounts: Map<String, Account>,
    dashboard: DashboardStore,
    jwxtBaseUrl: String,
    onOpenFeature: (ServiceFeature) -> Unit,
    onOpenTimetable: () -> Unit,
    onOpenAccount: (SchoolService) -> Unit,
    onOpenFeatures: () -> Unit,
    onOpenMine: () -> Unit,
    modifier: Modifier = Modifier,
    jwxtSource: JwxtSource = JwxtSource.Official,
) {
    val context = LocalContext.current
    val items by dashboard.items.collectAsState()
    val currentItems by rememberUpdatedState(items)
    val cached = remember(jwxtBaseUrl) {
        if (jwxtBaseUrl.isBlank()) null else JwxtCache.load(context, jwxtBaseUrl)
    }
    val today = remember { LocalDate.now(ZoneId.of("Asia/Shanghai")) }
    var galleryOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    val drag = remember { DashboardDragState() }
    val previewMotion = remember { PreviewMotionState() }
    var highlightId by remember { mutableStateOf<String?>(null) }
    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val deleteSlop = with(density) { 16.dp.toPx() }
    val widgetGap = 12.dp
    val widgetGapPx = with(density) { widgetGap.toPx() }

    // 新加的小组件：直接滚到底部（不用平滑滚动），再短暂高亮。
    LaunchedEffect(highlightId) {
        val id = highlightId ?: return@LaunchedEffect
        scrollState.scrollTo(scrollState.maxValue)
        delay(1500)
        if (highlightId == id) highlightId = null
    }

    // 点一下预览：关掉面板，把新组件追加到底部并高亮。
    val addWidget: (WidgetType, WidgetSize) -> Unit = { type, size ->
        val added = dashboard.add(type, size)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        galleryOpen = false
        highlightId = added.id
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { containerOrigin = it.positionInRoot() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            // 标题固定，不跟着小组件一起滚走。
            OverviewHeader(
                today = today,
                onAddWidget = { galleryOpen = true },
                onOpenMine = onOpenMine,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState, enabled = drag.draggedId == null)
                    .padding(top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (items.isEmpty()) {
                    EmptyWidgets(onAddWidget = { galleryOpen = true })
                }

                WidgetBoard(
                    items = items,
                    gap = widgetGap,
                    // 看板自己的位置和间距：拖动时按同样的规则模拟新顺序的排版。
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned {
                            drag.setBoard(it.boundsInRoot(), widgetGapPx)
                        },
                ) { item ->
                    val isDragged = drag.draggedId == item.id
                    val highlighted = highlightId == item.id
                    val ringColor = MaterialTheme.colorScheme.primary
                    Box(
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                drag.recordSlot(item.id, coords.boundsInRoot())
                            }
                            .drawBehind {
                                if (!highlighted) return@drawBehind
                                drawRoundRect(
                                    color = ringColor,
                                    cornerRadius = CornerRadius(22.dp.toPx()),
                                    style = Stroke(width = 2.dp.toPx()),
                                )
                            }
                            .pointerInput(item.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { touch ->
                                        val rect = drag.slots[item.id]
                                        if (rect != null) {
                                            drag.start(item.id, currentItems, touch, rect)
                                            haptics.performHapticFeedback(
                                                HapticFeedbackType.LongPress
                                            )
                                        }
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        drag.drag(amount.x, amount.y, currentItems, deleteSlop)
                                    },
                                    onDragEnd = { drag.release(currentItems) },
                                    onDragCancel = {
                                        // 手势被打断也要把预览偏移归零，否则组件会一直歪着。
                                        drag.cancel()
                                        previewMotion.snapAll()
                                    },
                                )
                            },
                    ) {
                        // 整块内容跟着布局位置的变化做弹簧位移；
                        // 被拖动时内容透明、留下虚线占位，真正跟手的卡片在顶层。
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    val preview = previewMotion.offsetOf(item.id)
                                    translationX = preview.x
                                    translationY = preview.y
                                    if (highlighted) {
                                        scaleX = 1.02f
                                        scaleY = 1.02f
                                    }
                                },
                        ) {
                            // 拖到删除区时不留占位：这块组件马上就要消失了。
                            if (isDragged && !drag.hoverDelete) {
                                DashedPlaceholder(Modifier.matchParentSize())
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer { alpha = if (isDragged) 0f else 1f },
                            ) {
                                WidgetContent(
                                    item = item,
                                    cached = cached,
                                    today = today,
                                    accounts = accounts,
                                    jwxtSource = jwxtSource,
                                    onOpenFeature = onOpenFeature,
                                    onOpenTimetable = onOpenTimetable,
                                    onOpenAccount = onOpenAccount,
                                    onOpenMine = onOpenMine,
                                )
                            }
                        }
                    }
                }

                TextButton(
                    onClick = onOpenFeatures,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("查看全部功能")
                }
            }
        }

        // 预览平移的驱动（本身不画任何东西）。
        PreviewMotionDriver(drag = drag, items = items, previewMotion = previewMotion)

        items.firstOrNull { it.id == drag.draggedId }?.let { dragged ->
            DraggedCardOverlay(
                item = dragged,
                drag = drag,
                containerOrigin = containerOrigin,
                cached = cached,
                today = today,
                accounts = accounts,
                jwxtSource = jwxtSource,
                onOpenFeature = onOpenFeature,
                onOpenTimetable = onOpenTimetable,
                onOpenAccount = onOpenAccount,
                onOpenMine = onOpenMine,
                                onCommitMove = {
                    drag.commitMove(currentItems, dashboard::move)
                    previewMotion.snapAll()
                },
                onCommitDelete = {
                    drag.commitDeletion(dashboard::remove)
                    previewMotion.snapAll()
                },
            )
        }

        AnimatedVisibility(
            visible = drag.draggedId != null,
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.6f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 28.dp),
        ) {
            val hovered = drag.hoverDelete
            val fabScale by animateFloatAsState(
                targetValue = if (hovered) 1.12f else 1f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
                label = "deleteFabScale",
            )
            Surface(
                shape = CircleShape,
                color = if (hovered) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                shadowElevation = if (hovered) 16.dp else 10.dp,
                modifier = Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        scaleX = fabScale
                        scaleY = fabScale
                    }
                    .onGloballyPositioned { drag.deleteZone = it.boundsInRoot() },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "删除小组件",
                        tint = if (hovered) {
                            MaterialTheme.colorScheme.onError
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }

    if (galleryOpen) {
        ModalBottomSheet(
            onDismissRequest = { galleryOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            WidgetGallery(
                cached = cached,
                today = today,
                accounts = accounts,
                jwxtSource = jwxtSource,
                onAdd = addWidget,
                onDismiss = { galleryOpen = false },
            )
        }
    }
}

/** 拖动的阶段：跟手 → 松手回位 / 缩小删除。 */
private enum class DragPhase { Idle, Dragging, Returning, Deleting }

private const val DragLogTag = "DashDrag"

/**
 * 拖动排序 / 拖到右下角删除的临时状态。
 *
 * 拖动过程中不重排真实布局，只算出目标下标；其他组件和虚线占位靠平移预览让位，
 * 松手动画结束后才一次性提交顺序。这样两块小卡互换不会因反复测量而闪烁。
 */
private class DashboardDragState {
    var draggedId by mutableStateOf<String?>(null)
        private set
    var targetIndex by mutableIntStateOf(0)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var hoverDelete by mutableStateOf(false)
        private set
    var phase by mutableStateOf(DragPhase.Idle)
        private set

    /** 回位动画要飞到的地方（根坐标系）。 */
    var releaseTarget = Offset.Zero
        private set

    /** 松手那一刻卡片的位置，回位动画从这里起步。 */
    var releaseFrom = Offset.Zero
        private set

    private var startIndex = 0
    private var startRect = Rect.Zero
    private var pointerAnchor = Offset.Zero

    /** 每个插槽在根坐标系里的位置，拖动时用来找落点。 */
    val slots = mutableMapOf<String, Rect>()
    private val loggedSlots = mutableMapOf<String, Rect>()

    /** 看板本体的位置与间距，用来模拟「换顺序之后」的排版。 */
    private var boardRect = Rect.Zero
    private var boardGap = 0f

    /** 右下角删除按钮的位置。 */
    var deleteZone = Rect.Zero

    val cardWidth: Float get() = startRect.width
    val cardHeight: Float get() = startRect.height
    val startTopLeft: Offset get() = startRect.topLeft

    /** 跟手卡片在根坐标系里的位置。 */
    val floatingRect: Rect get() = Rect(startRect.topLeft + offset, startRect.size)

    fun start(id: String, items: List<DashboardItem>, touch: Offset, rect: Rect) {
        draggedId = id
        startRect = rect
        startIndex = items.indexOfFirst { it.id == id }.coerceAtLeast(0)
        targetIndex = startIndex
        offset = Offset.Zero
        hoverDelete = false
        phase = DragPhase.Dragging
        pointerAnchor = rect.topLeft + touch
        android.util.Log.d(DragLogTag, "start id=$id index=$startIndex rect=$rect")
    }

    fun setBoard(rect: Rect, gapPx: Float) {
        boardRect = rect
        boardGap = gapPx
    }

    /** 记录布局坐标；一帧内突然跳很远就打日志。 */
    fun recordSlot(id: String, rect: Rect) {
        val previous = loggedSlots.put(id, rect)
        if (previous != null && previous != rect) {
            val jump = (rect.topLeft - previous.topLeft).getDistance()
            if (jump > 240f) {
                android.util.Log.w(
                    DragLogTag,
                    "slotJump id=$id from=${previous.topLeft} to=${rect.topLeft} distance=${jump.toInt()}",
                )
            }
        }
        slots[id] = rect
    }

    /** 假想把被拖动的条目放到 targetIndex，用来计算其他条目将要站到的位置。 */
    fun arrangementOf(items: List<DashboardItem>, index: Int): List<DashboardItem> {
        val id = draggedId ?: return items
        val from = items.indexOfFirst { it.id == id }
        if (from < 0 || items.size < 2) return items
        val reordered = items.toMutableList()
        val moved = reordered.removeAt(from)
        reordered.add(index.coerceIn(0, reordered.size), moved)
        return reordered
    }

    /**
     * 按当前落点找出每个条目应该平移到哪里。
     *
     * 目标位置不是「某个原有插槽」，而是把新顺序交给同一套排版规则重算一遍：
     * 小号永远只占半行，中号/大号占满一行。这样大卡跨过小卡时，小卡会先挪到
     * 它真正会落到的半宽位置，松手提交后不会再有第二跳。
     */
    fun previewOffsets(items: List<DashboardItem>): Map<String, Offset> {
        if (draggedId == null) return emptyMap()
        return offsetsFor(arrangementOf(items, targetIndex))
    }

    /** 删除后其他条目要往上补位，松手缩小时先让他们挪好。 */
    fun removalOffsets(items: List<DashboardItem>): Map<String, Offset> {
        val id = draggedId ?: return emptyMap()
        return offsetsFor(items.filterNot { it.id == id })
    }

    private fun offsetsFor(ordered: List<DashboardItem>): Map<String, Offset> {
        val targets = layoutOf(ordered) ?: return emptyMap()
        val result = mutableMapOf<String, Offset>()
        ordered.forEach { item ->
            val target = targets[item.id] ?: return@forEach
            val current = slots[item.id]?.topLeft ?: return@forEach
            result[item.id] = target.topLeft - current
        }
        return result
    }

    /** 按看板规则切行：相邻两块小号并排算一行，其余各自一行。 */
    private fun rowsOf(ordered: List<DashboardItem>): List<List<Int>> {
        val rows = mutableListOf<List<Int>>()
        var index = 0
        while (index < ordered.size) {
            if (ordered[index].size == WidgetSize.Small &&
                index + 1 < ordered.size &&
                ordered[index + 1].size == WidgetSize.Small
            ) {
                rows += listOf(index, index + 1)
                index += 2
            } else {
                rows += listOf(index)
                index += 1
            }
        }
        return rows
    }

    /**
     * 按看板的排版规则，算出给定顺序下每块组件的目标位置（根坐标）。
     * 高度直接用现有插槽里量好的高度，不重新测量，所以不会打断拖动。
     */
    private fun layoutOf(ordered: List<DashboardItem>): Map<String, Rect>? {
        val board = boardRect
        if (board.width <= 0f || ordered.isEmpty()) return null
        val gap = boardGap
        val halfWidth = ((board.width - gap) / 2f).coerceAtLeast(0f)

        fun rectOf(item: DashboardItem, x: Float, top: Float): Rect {
            val width = if (item.size == WidgetSize.Small) halfWidth else board.width
            return Rect(Offset(x, top), Size(width, slots[item.id]?.height ?: 0f))
        }

        val result = mutableMapOf<String, Rect>()
        var y = board.top
        rowsOf(ordered).forEach { row ->
            var x = board.left
            var rowHeight = 0f
            row.forEach { position ->
                val item = ordered[position]
                val rect = rectOf(item, x, y)
                result[item.id] = rect
                rowHeight = maxOf(rowHeight, rect.height)
                x += rect.width + gap
            }
            y += rowHeight + gap
        }
        return result
    }

    /** 某一行的合并范围：整行组件跨行时以它为准，两块小号算作一个整体。 */
    private fun unionOf(
        rects: Map<String, Rect>,
        row: List<Int>,
        ordered: List<DashboardItem>,
    ): Rect? {
        var union: Rect? = null
        row.forEach { position ->
            val rect = rects[ordered[position].id] ?: return null
            union = union?.let {
                Rect(
                    left = minOf(it.left, rect.left),
                    top = minOf(it.top, rect.top),
                    right = maxOf(it.right, rect.right),
                    bottom = maxOf(it.bottom, rect.bottom),
                )
            } ?: rect
        }
        return union
    }

    fun drag(amountX: Float, amountY: Float, items: List<DashboardItem>, deleteSlop: Float) {
        if (phase != DragPhase.Dragging) return
        offset += Offset(amountX, amountY)

        // 比较的是「预览后」的位置：邻居已经被弹簧挪到新位置，还拿原来的插槽判断会来回抖。
        // 中号 / 大号独占一行，跨行时把对面整行一起跨过去，否则一行里的两块小号会被拆开。
        val arrangement = arrangementOf(items, targetIndex)
        val index = arrangement.indexOfFirst { it.id == draggedId }
        if (index >= 0) {
            val dragged = arrangement[index]
            val preview = layoutOf(arrangement).orEmpty()
            val own = preview[dragged.id]
            val center = floatingRect.center
            var next = index
            if (dragged.size == WidgetSize.Small) {
                // 小号也要能换行：手指挪到别的行上时，直接落到那一行离得最近的槽位。
                // 只和相邻下标比的话，三个小卡（上两块、下一块）里左上那块永远换不到第二行——
                // 它的邻居只有同行的右卡，往下拖再怎么拖也越不过去。
                val rows = rowsOf(arrangement)
                val ownRow = rows.indexOfFirst { index in it }
                var landingRow = -1
                var landingDistance = Float.MAX_VALUE
                rows.forEachIndexed { rowIndex, row ->
                    val union = unionOf(preview, row, arrangement) ?: return@forEachIndexed
                    // 行与行之间留着间距：手指落在间隙里时按竖直方向最近的那行算。
                    val vertical = when {
                        center.y < union.top -> union.top - center.y
                        center.y > union.bottom -> center.y - union.bottom
                        else -> 0f
                    }
                    if (vertical < landingDistance) {
                        landingDistance = vertical
                        landingRow = rowIndex
                    }
                }
                if (landingRow >= 0 && landingRow != ownRow) {
                    var closestX = Float.MAX_VALUE
                    rows[landingRow].forEach { position ->
                        if (position == index) return@forEach
                        val rect = preview[arrangement[position].id] ?: return@forEach
                        val horizontal = kotlin.math.abs(center.x - rect.center.x)
                        if (horizontal < closestX) {
                            closestX = horizontal
                            next = position
                        }
                    }
                } else {
                    // 还在自己这一行：按原来的相邻中心判定左右换位。
                    if (index > 0) {
                        val previous = preview[arrangement[index - 1].id]
                        if (previous != null && crossedBefore(center, own, previous)) next = index - 1
                    }
                    if (next == index && index < arrangement.lastIndex) {
                        val after = preview[arrangement[index + 1].id]
                        if (after != null && crossedAfter(center, own, after)) next = index + 1
                    }
                }
            } else {
                // 整行组件：和相邻的整行比，然后整行跨过去。
                val rows = rowsOf(arrangement)
                val row = rows.indexOfFirst { index in it }
                if (row > 0) {
                    val previousRow = rows[row - 1]
                    val previous = unionOf(preview, previousRow, arrangement)
                    if (previous != null && crossedBefore(center, own, previous)) {
                        next = index - previousRow.size
                    }
                }
                if (next == index && row in 0 until rows.lastIndex) {
                    val nextRow = rows[row + 1]
                    val after = unionOf(preview, nextRow, arrangement)
                    if (after != null && crossedAfter(center, own, after)) {
                        next = index + nextRow.size
                    }
                }
            }
            if (next != index) {
                val from = targetIndex
                targetIndex = next
                val dest = draggedId?.let { id -> layoutOf(arrangementOf(items, next))?.get(id) }
                android.util.Log.d(
                    DragLogTag,
                    "target $from -> $next center=$center dest=${dest?.topLeft} " +
                        "distance=${dest?.let { (it.center - center).getDistance().toInt() }}",
                )
            }
        }

        hoverDelete = deleteZone.width > 0f &&
            deleteZone.inflate(deleteSlop).contains(pointerAnchor + offset)
    }

    /** 松手：要么缩小删除，要么飞回目标插槽。 */
    fun release(items: List<DashboardItem>) {
        if (phase != DragPhase.Dragging) return
        releaseFrom = floatingRect.topLeft
        if (hoverDelete) {
            phase = DragPhase.Deleting
            return
        }
        val target = draggedId?.let { id -> layoutOf(arrangementOf(items, targetIndex))?.get(id)?.topLeft }
        val jump = target?.let { (it - floatingRect.topLeft).getDistance() }
        if (jump != null && jump > 300f) {
            android.util.Log.w(DragLogTag, "releaseJump distance=${jump.toInt()} target=$target")
        }
        releaseTarget = target ?: startRect.topLeft
        phase = DragPhase.Returning
    }

    fun commitMove(items: List<DashboardItem>, onMove: (Int, Int) -> Unit) {
        if (targetIndex in items.indices && targetIndex != startIndex) {
            onMove(startIndex, targetIndex)
        }
        reset()
    }

    fun commitDeletion(onRemove: (String) -> Unit) {
        draggedId?.let(onRemove)
        reset()
    }

    fun cancel() = reset()

    private fun reset() {
        draggedId = null
        phase = DragPhase.Idle
        offset = Offset.Zero
        hoverDelete = false
        startIndex = 0
        targetIndex = 0
        startRect = Rect.Zero
    }
}

/** 两个插槽是不是同一行：竖直方向有重叠就算。 */
private fun sameRow(own: Rect?, other: Rect): Boolean =
    own != null && own.top < other.bottom && other.top < own.bottom

/**
 * 卡片中心是否已经越到相邻插槽前面。
 *
 * 同一行里换位只该看 x，跨行才看 y。把两个条件用 `||` 并在一起的话，
 * 并排两块小卡时手指纵向抖 1px 就会被当成「跨过去了」，落点反复横跳、
 * 弹簧一遍遍重启——横向晃动看着就一卡一卡的。
 */
private fun crossedBefore(center: Offset, own: Rect?, other: Rect): Boolean =
    if (sameRow(own, other)) center.x < other.center.x else center.y < other.center.y

/** 反过来：卡片中心是否已经越到相邻插槽后面。 */
private fun crossedAfter(center: Offset, own: Rect?, other: Rect): Boolean =
    if (sameRow(own, other)) center.x > other.center.x else center.y > other.center.y

/**
 * 拖动时其他组件的平移预览；真实布局保持不动，松手提交后再清零。
 *
 * 落点来回变的时候不手动 cancel 上一个动画：Animatable 内部用 MutatorMutex 打断，
 * 并且默认把打断那一刻的速度当作新动画的初速度（官方文档说的「spring 保证打断时速度连续」），
 * 左右晃才不会被一次次「从零起步」的弹簧拖成一卡一卡。
 */
private class PreviewMotionState {
    private val shifts = mutableStateMapOf<String, Animatable<Offset, AnimationVector2D>>()
    private val jobs = mutableMapOf<String, Job>()

    fun offsetOf(id: String): Offset = shifts[id]?.value ?: Offset.Zero

    fun animateTo(id: String, target: Offset, scope: CoroutineScope) {
        val animation = shifts.getOrPut(id) { Animatable(Offset.Zero, Offset.VectorConverter) }
        // 位置没变就别再起一个动画，省掉大部分空转。
        if (animation.targetValue == target && jobs[id]?.isActive == true) return
        val job = scope.launch {
            val jump = (target - animation.value).getDistance()
            if (jump > 300f) {
                android.util.Log.w(DragLogTag, "previewJump id=$id distance=${jump.toInt()} to=$target")
            }
            animation.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = 0.85f,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
        jobs[id] = job
    }

    /**
     * 提交顺序的那一刻把预览偏移清零。
     *
     * 直接清空 map（缺省值就是 Offset.Zero）：这样「真实布局重排」和「偏移归零」
     * 落在同一帧里，不会出现先归零再重排、或者先重排再归零的错位闪动。
     */
    fun snapAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        shifts.clear()
    }
}

/**
 * 预览平移的驱动，本身不画任何东西。
 *
 * 单独拆成一个 composable 是有原因的：它读 drag.phase / targetIndex，读在谁身上就只让谁重组。
 * 放在 OverviewScreen 顶层读，落点每变一次整个概览页连同所有小组件都要重组一遍，
 * 左右来回晃的时候就是一路掉帧。
 */
@Composable
private fun PreviewMotionDriver(
    drag: DashboardDragState,
    items: List<DashboardItem>,
    previewMotion: PreviewMotionState,
) {
    val scope = rememberCoroutineScope()
    val phase = drag.phase
    val targetIndex = drag.targetIndex
    LaunchedEffect(phase, targetIndex, items) {
        val offsets = when (phase) {
            DragPhase.Dragging -> drag.previewOffsets(items)
            DragPhase.Deleting -> drag.removalOffsets(items)
            else -> return@LaunchedEffect
        }
        items.forEach { item ->
            previewMotion.animateTo(item.id, offsets[item.id] ?: Offset.Zero, scope)
        }
    }
}

/**
 * 概览看板的流式排版：小号半宽、两块并排，中号/大号各占一行。
 *
 * 所有组件都是同一个 Layout 的子项（用 id 作 key），拖动换行时也不会被重建，
 * 于是长按手势不会断，位置变化还能交给弹簧补偿做平滑过渡。
 */
@Composable
private fun WidgetBoard(
    items: List<DashboardItem>,
    gap: Dp,
    modifier: Modifier = Modifier,
    slot: @Composable (DashboardItem) -> Unit,
) {
    val density = LocalDensity.current
    Layout(
        modifier = modifier,
        content = {
            items.forEach { item ->
                key(item.id) { slot(item) }
            }
        },
    ) { measurables, constraints ->
        val gapPx = with(density) { gap.roundToPx() }
        val fullWidth = constraints.maxWidth.coerceAtLeast(0)
        val halfWidth = ((fullWidth - gapPx) / 2).coerceAtLeast(0)

        val widths = items.map { item ->
            if (item.size == WidgetSize.Small) halfWidth else fullWidth
        }
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(
                Constraints(
                    minWidth = widths[index],
                    maxWidth = widths[index],
                    minHeight = 0,
                    maxHeight = Constraints.Infinity,
                )
            )
        }

        // 相邻两个小号并排，单独的小号只占左半边。
        val rows = mutableListOf<MutableList<Int>>()
        var index = 0
        while (index < items.size) {
            if (items[index].size == WidgetSize.Small &&
                index + 1 < items.size &&
                items[index + 1].size == WidgetSize.Small
            ) {
                rows += mutableListOf(index, index + 1)
                index += 2
            } else {
                rows += mutableListOf(index)
                index += 1
            }
        }

        val rowHeights = rows.map { row -> row.maxOf { placeables[it].height } }
        val contentHeight = rowHeights.sum() + gapPx * (rows.size - 1).coerceAtLeast(0)
        val height = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(fullWidth, height) {
            var y = 0
            rows.forEachIndexed { rowIndex, row ->
                var x = 0
                row.forEach { position ->
                    placeables[position].placeRelative(x, y)
                    x += widths[position] + gapPx
                }
                y += rowHeights[rowIndex] + gapPx
            }
        }
    }
}

/**
 * 跟手的浮动卡片。
 *
 * 拖动时直接跟随手指；悬停删除区时用弹簧缩到 1/2；松手后飞回目标插槽，
 * 或者从 1/2 继续缩到 0、淡出后再提交删除。
 */
@Composable
private fun DraggedCardOverlay(
    item: DashboardItem,
    drag: DashboardDragState,
    containerOrigin: Offset,
    cached: CachedTimetable?,
    today: LocalDate,
    accounts: Map<String, Account>,
    jwxtSource: JwxtSource,
    onOpenFeature: (ServiceFeature) -> Unit,
    onOpenTimetable: () -> Unit,
    onOpenAccount: (SchoolService) -> Unit,
    onOpenMine: () -> Unit,
    onCommitMove: () -> Unit,
    onCommitDelete: () -> Unit,
) {
    val density = LocalDensity.current
    // 松手后的位移增量：从 0 开始，所以松手那一帧的位置和跟手时完全一致，不会闪一下。
    val returnShift = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scale = remember { Animatable(1f) }
    val alpha = remember { Animatable(1f) }
    val width = with(density) { drag.cardWidth.toDp() }
    val height = with(density) { drag.cardHeight.toDp() }

    // 悬停删除区时缩到一半，离开时再弹回原大小。
    LaunchedEffect(drag.hoverDelete) {
        if (drag.phase != DragPhase.Dragging) return@LaunchedEffect
        scale.animateTo(
            targetValue = if (drag.hoverDelete) 0.5f else 1f,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        )
    }

    LaunchedEffect(drag.phase) {
        when (drag.phase) {
            DragPhase.Returning -> {
                // 缩放和位移同时跑，别让卡片先愣在原地等缩放结束。
                coroutineScope {
                    launch {
                        scale.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(
                                dampingRatio = 0.78f,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        )
                    }
                    launch {
                        returnShift.animateTo(
                            targetValue = drag.releaseTarget - drag.releaseFrom,
                            animationSpec = spring(
                                dampingRatio = 0.86f,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                }
                onCommitMove()
            }

            DragPhase.Deleting -> {
                // 从 1/2 继续缩到 0：阻尼拉满，避免弹回负数。
                coroutineScope {
                    launch {
                        scale.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
                        )
                    }
                    launch { alpha.animateTo(0f, tween(durationMillis = 120)) }
                }
                onCommitDelete()
            }

            else -> Unit
        }
    }

    Box(
        modifier = Modifier
            .size(width, height)
            .graphicsLayer {
                // 跟手位移直接在绘制阶段算：手一动这一帧卡片就到位，
                // 不像走回调、协程那样要等下一帧，左右晃时才不会粘手。
                val topLeft = if (drag.phase == DragPhase.Dragging) {
                    drag.startTopLeft + drag.offset
                } else {
                    drag.releaseFrom + returnShift.value
                }
                translationX = topLeft.x - containerOrigin.x
                translationY = topLeft.y - containerOrigin.y
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
                shadowElevation = 22.dp.toPx()
                shape = RoundedCornerShape(24.dp)
                clip = false
            },
    ) {
        WidgetContent(
            item = item,
            cached = cached,
            today = today,
            accounts = accounts,
            jwxtSource = jwxtSource,
            onOpenFeature = onOpenFeature,
            onOpenTimetable = onOpenTimetable,
            onOpenAccount = onOpenAccount,
            onOpenMine = onOpenMine,
        )
    }
}

@Composable
private fun OverviewHeader(
    today: LocalDate,
    onAddWidget: () -> Unit,
    onOpenMine: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "概览",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${greeting()} · ${today.format(DatePattern)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onAddWidget) {
            Icon(Icons.Outlined.Tune, contentDescription = "添加小组件")
        }
        IconButton(onClick = onOpenMine) {
            Icon(Icons.Outlined.Person, contentDescription = "我的")
        }
    }
}

@Composable
private fun EmptyWidgets(onAddWidget: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "概览还没有小组件",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "点「+」从底部面板挑选今日课程、下一节课、快捷功能、教务概览或账号状态。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAddWidget) { Text("添加小组件") }
        }
    }
}

@Composable
private fun WidgetContent(
    item: DashboardItem,
    cached: CachedTimetable?,
    today: LocalDate,
    accounts: Map<String, Account>,
    jwxtSource: JwxtSource,
    onOpenFeature: (ServiceFeature) -> Unit,
    onOpenTimetable: () -> Unit,
    onOpenAccount: (SchoolService) -> Unit,
    onOpenMine: () -> Unit,
) {
    when (item.type) {
        WidgetType.TodayCourses -> TodayCoursesWidget(
            cached = cached,
            today = today,
            size = item.size,
            onOpenTimetable = onOpenTimetable,
        )

        WidgetType.NextClass -> NextClassWidget(
            cached = cached,
            size = item.size,
            accounts = accounts,
            jwxtSource = jwxtSource,
            onOpenTimetable = onOpenTimetable,
        )

        WidgetType.QuickActions -> QuickActionsWidget(
            size = item.size,
            onOpenFeature = onOpenFeature,
        )

        WidgetType.Semester -> SemesterWidget(
            cached = cached,
            size = item.size,
            onOpenTimetable = onOpenTimetable,
        )

        WidgetType.Accounts -> AccountsWidget(
            accounts = accounts,
            jwxtSource = jwxtSource,
            size = item.size,
            onOpenAccount = onOpenAccount,
            onOpenMine = onOpenMine,
        )
    }
}

@Composable
private fun WidgetCard(
    title: String,
    size: WidgetSize,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (size == WidgetSize.Small) Modifier.height(128.dp) else Modifier),
    ) {
        if (size == WidgetSize.Small) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
            ) {
                WidgetTitle(title = title, size = size)
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        content = content,
                    )
                }
            }
        } else {
            Column(modifier = Modifier.padding(18.dp)) {
                WidgetTitle(
                    title = title,
                    size = size,
                    actionText = actionText,
                    onAction = onAction,
                )
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

@Composable
private fun WidgetTitle(
    title: String,
    size: WidgetSize,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = if (size == WidgetSize.Small) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionText != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

// ── 今日课程 ────────────────────────────────────────────────

@Composable
private fun TodayCoursesWidget(
    cached: CachedTimetable?,
    today: LocalDate,
    size: WidgetSize,
    onOpenTimetable: () -> Unit,
) {
    val timetable = cached?.timetable
    val courses = timetable?.let { todayCourses(it, today) }.orEmpty()

    WidgetCard(
        title = "今日课程",
        size = size,
        actionText = "打开课表",
        onAction = onOpenTimetable,
    ) {
        when {
            timetable == null -> {
                if (size == WidgetSize.Small) {
                    Text("—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = "打开一次课表后显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = "还没有课表缓存。打开课表登录一次后，这里会显示今天的课。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            courses.isEmpty() -> {
                if (size == WidgetSize.Small) {
                    Text("没课", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = "今天可以休息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "今天没有课。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            size == WidgetSize.Small -> {
                Text(
                    text = "${courses.size} 节",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = courses.first().name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            else -> {
                val visible = if (size == WidgetSize.Medium) courses.take(2) else courses
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    visible.forEach { course ->
                        TodayCourseRow(
                            course = course,
                            timeLabel = timetable.jcTimes[course.startPeriod]?.let {
                                "${it.start}–${it.end}"
                            },
                        )
                    }
                    val hidden = courses.size - visible.size
                    if (hidden > 0) {
                        Text(
                            text = "还有 $hidden 节",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayCourseRow(course: Course, timeLabel: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = "${course.startPeriod}–${course.endPeriod}节",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = course.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (course.room.isNotBlank()) {
                Text(
                    text = "@${course.room}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (timeLabel != null) {
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 下一节课 ────────────────────────────────────────────────

private val ShanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")

/** 当前时间；进入页面后每 30 秒刷新一次，「下一节课」不会停在进入页面那一刻。 */
@Composable
private fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now(ShanghaiZone)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = LocalDateTime.now(ShanghaiZone)
        }
    }
    return now
}

/** 从缓存课表里挑出来的一节课。 */
private data class UpcomingClass(
    val course: Course,
    val date: LocalDate,
    val start: LocalTime?,
    val end: LocalTime?,
    val isOngoing: Boolean,
    val minutesUntil: Long?,
    val isToday: Boolean,
) {
    val timeLabel: String
        get() = when {
            start != null && end != null -> "${formatClock(start)}–${formatClock(end)}"
            start != null -> formatClock(start)
            else -> "${course.startPeriod}–${course.endPeriod} 节"
        }
}

/**
 * 按当前时间找接下来要上的课：正在上的排在前面，然后是今天剩下的、再往后一周。
 *
 * 缓存里存的是保存那一刻的周次（`currentWeek`），这里用缓存时间反推每个日期属于第几周，
 * 缓存放了几天也不会把课表算错位。
 */
private fun upcomingClasses(
    timetable: Timetable,
    cachedAt: Long,
    now: LocalDateTime,
    limit: Int = 2,
): List<UpcomingClass> {
    val today = now.toLocalDate()
    val baseDate = if (cachedAt > 0) {
        Instant.ofEpochMilli(cachedAt).atZone(ShanghaiZone).toLocalDate()
    } else {
        today
    }
    val baseMonday = baseDate.minusDays((baseDate.dayOfWeek.value - 1).toLong())

    fun weekOf(date: LocalDate): Int {
        val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
        return timetable.currentWeek + ChronoUnit.WEEKS.between(baseMonday, monday).toInt()
    }

    val result = mutableListOf<UpcomingClass>()
    for (offset in 0L..6L) {
        val date = today.plusDays(offset)
        val courses = timetable.coursesInWeek(weekOf(date))
            .filter { it.dayOfWeek == date.dayOfWeek.value }
            .sortedBy { it.startPeriod }
        for (course in courses) {
            val start = parseClock(timetable.jcTimes[course.startPeriod]?.start)
            val end = parseClock(timetable.jcTimes[course.endPeriod]?.end)
            val startsAt = start?.let { date.atTime(it) }
            val endsAt = end?.let { date.atTime(it) }
            val ongoing = startsAt != null && endsAt != null &&
                !now.isBefore(startsAt) && now.isBefore(endsAt)
            // 缓存里缺节次时间时（jcTimes 为空），按节次顺序兜底成「还没上」。
            val upcoming = startsAt == null || now.isBefore(startsAt)
            if (!ongoing && !upcoming) continue
            result += UpcomingClass(
                course = course,
                date = date,
                start = start,
                end = end,
                isOngoing = ongoing,
                minutesUntil = startsAt?.let { Duration.between(now, it).toMinutes().coerceAtLeast(0) },
                isToday = offset == 0L,
            )
            if (result.size >= limit) return result
        }
    }
    return result
}

private fun parseClock(raw: String?): LocalTime? {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return null
    val parts = text.split(':')
    if (parts.size < 2) return null
    return runCatching { LocalTime.of(parts[0].toInt(), parts[1].toInt()) }.getOrNull()
}

private fun formatClock(time: LocalTime): String =
    String.format(Locale.CHINA, "%02d:%02d", time.hour, time.minute)

private fun dayPrefix(item: UpcomingClass, today: LocalDate): String = when {
    item.date == today -> ""
    item.date == today.plusDays(1) -> "明天"
    else -> "${item.date.monthValue}/${item.date.dayOfMonth}"
}

private fun countdownLabel(item: UpcomingClass): String = when {
    item.isOngoing -> "正在上课"
    item.minutesUntil == null -> "待上课"
    item.minutesUntil <= 0L -> "马上开始"
    item.minutesUntil < 60L -> "${item.minutesUntil} 分钟后"
    else -> "还有 ${item.minutesUntil / 60} 小时 ${item.minutesUntil % 60} 分"
}

/**
 * 「下一节课」：小号显示最近一节，中号显示接下来两节（第一节为主）。
 *
 * 数据一律来自课表缓存，所以卡片底部标明「使用缓存」；当前来源的账号没有登录时
 * 再加一枚「登录失效」，提醒需要重新登录才能刷新课表。
 */
@Composable
private fun NextClassWidget(
    cached: CachedTimetable?,
    size: WidgetSize,
    accounts: Map<String, Account>,
    jwxtSource: JwxtSource,
    onOpenTimetable: () -> Unit,
) {
    val timetable = cached?.timetable
    val now = rememberNow()
    val upcoming = remember(timetable, cached?.savedAt, now) {
        timetable?.let { upcomingClasses(it, cached?.savedAt ?: 0L, now, limit = 2) }.orEmpty()
    }
    val account = accounts[if (jwxtSource == JwxtSource.Grab) "grab" else "jwxt"]
    val sessionLost = account?.loggedIn != true
    val today = now.toLocalDate()

    WidgetCard(
        title = "下一节课",
        size = size,
        actionText = if (size == WidgetSize.Small) null else "打开课表",
        onAction = if (size == WidgetSize.Small) null else onOpenTimetable,
    ) {
        when {
            timetable == null -> {
                if (size == WidgetSize.Small) {
                    Text("—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = "打开一次课表后显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = "还没有课表缓存。打开课表登录一次后，这里会显示接下来要上的课。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            upcoming.isEmpty() -> {
                if (size == WidgetSize.Small) {
                    Text("没课", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = "接下来一周没有课",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = "接下来一周没有课。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            size == WidgetSize.Small -> {
                val next = upcoming.first()
                Text(
                    text = if (next.isOngoing) "上课中" else next.start?.let { formatClock(it) } ?: "下一节",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = next.course.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildList {
                        dayPrefix(next, today).takeIf { it.isNotBlank() }?.let(::add)
                        next.course.room.takeIf { it.isNotBlank() }?.let { add("@$it") }
                        if (next.isOngoing) add(next.timeLabel) else add(countdownLabel(next))
                        add(if (sessionLost) "登录失效" else "使用缓存")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (sessionLost) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            else -> {
                val first = upcoming.first()
                val second = upcoming.getOrNull(1)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = listOf(dayPrefix(first, today), first.timeLabel)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" "),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = first.course.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildList {
                                    first.course.room.takeIf { it.isNotBlank() }?.let { add("@$it") }
                                    add(countdownLabel(first))
                                }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (second != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = buildList {
                                    add(second.course.name)
                                    second.course.room.takeIf { it.isNotBlank() }?.let { add("@$it") }
                                }.joinToString(" · "),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildList {
                                    dayPrefix(second, today).takeIf { it.isNotBlank() }?.let(::add)
                                    add(second.start?.let { formatClock(it) } ?: second.timeLabel)
                                    add(countdownLabel(second))
                                }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            text = "接下来没有别的课了",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row {
                        if (sessionLost) {
                            Text(
                                text = "登录失效",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = " · ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Text(
                            text = "使用缓存" + (cached?.savedAt?.takeIf { it > 0 }?.let {
                                " · ${formatUpdatedAt(it)} 更新"
                            } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ── 快捷功能 ────────────────────────────────────────────────

@Composable
private fun QuickActionsWidget(
    size: WidgetSize,
    onOpenFeature: (ServiceFeature) -> Unit,
) {
    val features = FeatureCatalog.quickFeatures
    WidgetCard(title = "快捷功能", size = size) {
        when (size) {
            WidgetSize.Small -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                features.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { feature ->
                            MiniActionTile(
                                feature = feature,
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenFeature(feature) },
                            )
                        }
                        repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            WidgetSize.Medium -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                features.forEach { feature ->
                    QuickActionTile(
                        feature = feature,
                        compact = true,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenFeature(feature) },
                    )
                }
            }

            WidgetSize.Large -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                features.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { feature ->
                            QuickActionCard(
                                feature = feature,
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenFeature(feature) },
                            )
                        }
                        repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniActionTile(
    feature: ServiceFeature,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = accentOf(feature)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = feature.title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuickActionTile(
    feature: ServiceFeature,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = accentOf(feature)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(
                vertical = if (compact) 9.dp else 12.dp,
                horizontal = 4.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 28.dp else 36.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = feature.icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(if (compact) 16.dp else 19.dp),
                )
            }
            Spacer(Modifier.height(if (compact) 4.dp else 7.dp))
            Text(
                text = feature.title,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuickActionCard(
    feature: ServiceFeature,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = accentOf(feature)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = feature.icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = feature.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = feature.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── 教务概览 ────────────────────────────────────────────────

@Composable
private fun SemesterWidget(
    cached: CachedTimetable?,
    size: WidgetSize,
    onOpenTimetable: () -> Unit,
) {
    val timetable = cached?.timetable
    WidgetCard(
        title = "教务概览",
        size = size,
        actionText = if (timetable != null) "查看课表" else null,
        onAction = if (timetable != null) onOpenTimetable else null,
    ) {
        if (timetable == null) {
            if (size == WidgetSize.Small) {
                Text("—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "暂无课表",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "登录教务系统后显示当前学期、周次与课程数。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@WidgetCard
        }

        when (size) {
            WidgetSize.Small -> {
                Text(
                    text = "第 ${timetable.currentWeek} 周",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = "${termLabel(timetable)} · ${timetable.courses.map { it.name }.distinct().size} 门课",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            WidgetSize.Medium -> {
                Text(
                    text = "${semesterLabel(timetable)} · 第 ${timetable.currentWeek} 周",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                val updated = cached.savedAt
                    .takeIf { it > 0 }
                    ?.let { " · 更新于 ${formatUpdatedAt(it)}" }
                    .orEmpty()
                Text(
                    text = "本学期 ${timetable.courses.map { it.name }.distinct().size} 门课$updated",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            WidgetSize.Large -> {
                Text(
                    text = "${semesterLabel(timetable)} · 第 ${timetable.currentWeek} 周",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(10.dp))
                WeekdayLoad(timetable)
                Spacer(Modifier.height(10.dp))
                val weekCourses = timetable.coursesInWeek(timetable.currentWeek)
                val updated = cached.savedAt
                    .takeIf { it > 0 }
                    ?.let { " · 更新于 ${formatUpdatedAt(it)}" }
                    .orEmpty()
                Text(
                    text = "本周 ${weekCourses.size} 节课 · 本学期 ${timetable.courses.map { it.name }.distinct().size} 门课$updated",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WeekdayLoad(timetable: Timetable) {
    val weekCourses = timetable.coursesInWeek(timetable.currentWeek)
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val count = weekCourses.count { it.dayOfWeek == index + 1 }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (count > 0) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    modifier = Modifier.size(30.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── 账号状态 ────────────────────────────────────────────────

@Composable
private fun AccountsWidget(
    accounts: Map<String, Account>,
    jwxtSource: JwxtSource,
    size: WidgetSize,
    onOpenAccount: (SchoolService) -> Unit,
    onOpenMine: () -> Unit,
) {
    val services = ServiceCatalog.accountServices
    WidgetCard(
        title = "账号状态",
        size = size,
        actionText = "管理",
        onAction = onOpenMine,
    ) {
        if (size == WidgetSize.Small) {
            services.firstOrNull()?.let { service ->
                val account = accountOf(service, accounts, jwxtSource)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenAccount(service) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ServiceBadge(service = service, size = 26.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = service.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (account?.loggedIn == true) "已登录" else "未登录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusDot(loggedIn = account?.loggedIn == true)
                }
            }
            return@WidgetCard
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            services.forEach { service ->
                // 唯一账号入口的状态跟随当前教务数据来源。
                val account = accountOf(service, accounts, jwxtSource)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenAccount(service) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ServiceBadge(service = service, size = 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = service.name,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = account?.displayName?.takeIf { it.isNotBlank() } ?: "未登录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StatusDot(loggedIn = account?.loggedIn == true)
                }
            }
            if (size == WidgetSize.Large) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "登录状态跟随「教务系统 → 数据来源」的选择。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun accountOf(
    service: SchoolService,
    accounts: Map<String, Account>,
    jwxtSource: JwxtSource,
): Account? = accounts[
    if (service.id == "jwxt" && jwxtSource == JwxtSource.Grab) "grab" else service.id
]

// ── 小组件面板 ──────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetGallery(
    cached: CachedTimetable?,
    today: LocalDate,
    accounts: Map<String, Account>,
    jwxtSource: JwxtSource,
    onAdd: (WidgetType, WidgetSize) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "添加小组件",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "和首页同尺寸的真实预览，点一下添加到底部。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDismiss) { Text("完成") }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            WidgetSize.entries.forEach { size ->
                item(key = "size-${size.key}") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = size.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = sizeHint(size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            // 小号在首页是两块并排，预览也用同一条半宽。
                            val previewWidth = if (size == WidgetSize.Small) {
                                (maxWidth - 12.dp) / 2f
                            } else {
                                maxWidth
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                WidgetType.entries.forEach { type ->
                                    // 有的功能只支持部分尺寸（比如「下一节课」只做小号和中号）。
                                    if (size in type.sizes) {
                                        WidgetPreview(
                                            type = type,
                                            size = size,
                                            width = previewWidth,
                                            cached = cached,
                                            today = today,
                                            accounts = accounts,
                                            jwxtSource = jwxtSource,
                                            onAdd = { onAdd(type, size) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sizeHint(size: WidgetSize): String = when (size) {
    WidgetSize.Small -> "两块并排，只留一个重点数字"
    WidgetSize.Medium -> "占满一行，显示主要内容"
    WidgetSize.Large -> "占满一行，信息最全"
}

/**
 * 真实尺寸的小组件预览：内容、样式、高度都和首页一致。
 *
 * 整块盖一层透明点击区，点哪里都是「添加」，不会误触组件里的按钮。
 */
@Composable
private fun WidgetPreview(
    type: WidgetType,
    size: WidgetSize,
    width: Dp,
    cached: CachedTimetable?,
    today: LocalDate,
    accounts: Map<String, Account>,
    jwxtSource: JwxtSource,
    onAdd: () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Column(modifier = Modifier.width(width)) {
        Box {
            WidgetContent(
                item = DashboardItem("preview-${type.key}-${size.key}", type, size),
                cached = cached,
                today = today,
                accounts = accounts,
                jwxtSource = jwxtSource,
                onOpenFeature = {},
                onOpenTimetable = {},
                onOpenAccount = {},
                onOpenMine = {},
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .clickable(onClick = onAdd),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = type.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = "添加",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ── 拖动占位 ────────────────────────────────────────────────

@Composable
private fun DashedPlaceholder(modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val density = LocalDensity.current
    val radius = with(density) { 22.dp.toPx() }
    val stroke = with(density) { 1.5.dp.toPx() }
    val dash = with(density) { 10.dp.toPx() }
    Box(
        modifier = modifier.drawBehind {
            drawRoundRect(
                color = outline.copy(alpha = 0.6f),
                cornerRadius = CornerRadius(radius),
                style = Stroke(
                    width = stroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.7f)),
                ),
            )
        }
    )
}

// ── 工具 ────────────────────────────────────────────────────

private fun todayCourses(timetable: Timetable, today: LocalDate): List<Course> =
    timetable.coursesInWeek(timetable.currentWeek)
        .filter { it.dayOfWeek == today.dayOfWeek.value }
        .sortedBy { it.startPeriod }

private fun semesterLabel(timetable: Timetable): String {
    val year = timetable.xnm.toIntOrNull()
        ?.let { "$it–${it + 1} 学年" }
        ?: "当前学年"
    val term = termLabel(timetable)
    return if (term.isBlank()) year else "$year · $term"
}

private fun termLabel(timetable: Timetable): String =
    when (timetable.xqm) {
        "3" -> "第一学期"
        "12" -> "第二学期"
        "16" -> "第三学期"
        else -> ""
    }

@Composable
private fun accentOf(feature: ServiceFeature): Color =
    ServiceCatalog.byId(feature.targetServiceId)?.accent
        ?: MaterialTheme.colorScheme.primary

private fun formatUpdatedAt(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))

private fun greeting(): String =
    when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "早上好"
        in 12..17 -> "下午好"
        in 18..22 -> "晚上好"
        else -> "夜深了"
    }

private val DatePattern: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)