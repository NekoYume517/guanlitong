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
    var editorFocusId by mutableStateOf<String?>(null)
        private set
    var editorFocusIndex by mutableStateOf<Int?>(null)
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
        editorFocusId = menu?.target?.feature?.id
        editorFocusIndex = menu?.target?.index
        menuVisible = false
    }

    fun openPendingEditor() {
        if (editorFocusId != null) editorOpen = true
    }

    fun editAll() {
        menu = null
        menuVisible = false
        openEditor(null)
    }

    fun dismissMenu() { menuVisible = false }
    fun dismissEditor() {
        editorOpen = false
        editorFocusId = null
        editorFocusIndex = null
    }
    fun openEditor(id: String?) {
        editorFocusId = id
        editorFocusIndex = null
        editorOpen = true
    }
}

@Composable
internal fun rememberQuickActionOverlayState() = remember { QuickActionOverlayState() }
