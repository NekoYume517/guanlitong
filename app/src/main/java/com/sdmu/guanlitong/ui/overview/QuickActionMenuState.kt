package com.sdmu.guanlitong.ui.overview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.sdmu.guanlitong.data.ServiceFeature

internal class QuickActionPressTracker {
    private var nextToken = 0L
    var downToken = 0L
        private set
    var downPosition = Offset.Zero
        private set
    var menuToken = -1L
        private set
    var menuStartedDragging = false

    fun down(position: Offset): Long {
        nextToken += 1
        downToken = nextToken
        downPosition = position
        return nextToken
    }

    fun longPress(token: Long) {
        if (token == downToken) {
            menuToken = token
            menuStartedDragging = false
        }
    }

    fun isCurrent(token: Long) = token == downToken
}

internal data class QuickActionTarget(
    val widgetId: String,
    val index: Int,
    val feature: ServiceFeature,
    val bounds: Rect,
)

internal data class QuickActionMenuTarget(
    val target: QuickActionTarget,
    /** Root coordinates relative to the overview overlay's origin. */
    val anchor: Rect,
)

/** The only owner of quick-action overlays; drag and gallery previews have no state. */
internal class QuickActionOverlayState {
    var menu by mutableStateOf<QuickActionMenuTarget?>(null)
        private set
    var menuVisible by mutableStateOf(false)
        private set
    var editorWidgetId by mutableStateOf<String?>(null)
        private set
    var editorFocusId by mutableStateOf<String?>(null)
        private set
    var editorOpen by mutableStateOf(false)
        private set

    fun open(target: QuickActionTarget, containerOrigin: Offset) {
        if (menu != null) return
        menu = QuickActionMenuTarget(target, target.bounds.translate(-containerOrigin))
        menuVisible = true
    }

    fun beginDismissMenu() { menuVisible = false }
    fun finishDismissMenu() { menu = null }

    fun close() {
        menuVisible = false
        menu = null
    }

    fun edit() {
        editorWidgetId = menu?.target?.widgetId
        editorFocusId = menu?.target?.feature?.id
        menuVisible = false
    }

    fun openPendingEditor() {
        if (editorWidgetId != null && editorFocusId != null) editorOpen = true
    }

    fun editAll(widgetId: String) {
        menu = null
        menuVisible = false
        openEditor(widgetId, null)
    }

    fun dismissMenu() { menuVisible = false }
    fun dismissEditor() {
        editorOpen = false
        editorWidgetId = null
        editorFocusId = null
    }
    fun openEditor(widgetId: String, id: String?) {
        editorWidgetId = widgetId
        editorFocusId = id
        editorOpen = true
    }
}

@Composable
internal fun rememberQuickActionOverlayState() = remember { QuickActionOverlayState() }
