package com.thomaswcode.calendareventtimers.outlook

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import com.thomaswcode.calendareventtimers.util.ScanLog
import kotlin.coroutines.resume
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

/** An immutable copy of one accessibility node, holding the live node for actions. */
class LiveNode(
    val info: AccessibilityNodeInfo?,
    override val className: String?,
    override val viewId: String?,
    override val text: String?,
    override val desc: String?,
    override val clickable: Boolean,
    override val scrollable: Boolean,
    override val selected: Boolean,
    override val bounds: Box,
    override val children: List<LiveNode>,
    override val visible: Boolean = true,
    override val checkable: Boolean = false,
    override val checked: Boolean = false,
    override val focused: Boolean = false,
    override val editable: Boolean = false,
) : UiNode {
    companion object {
        private const val MAX_DEPTH = 60

        fun capture(info: AccessibilityNodeInfo, depth: Int = 0): LiveNode {
            val kids = if (depth >= MAX_DEPTH) emptyList() else
                (0 until info.childCount).mapNotNull { i -> runCatching { info.getChild(i) }.getOrNull()?.let { capture(it, depth + 1) } }
            val r = Rect().also { info.getBoundsInScreen(it) }
            return LiveNode(
                info = info,
                className = info.className?.toString(),
                viewId = info.viewIdResourceName,
                text = info.text?.toString(),
                desc = info.contentDescription?.toString(),
                clickable = info.isClickable,
                scrollable = info.isScrollable,
                selected = info.isSelected,
                bounds = Box(r.left, r.top, r.right, r.bottom),
                children = kids,
                visible = info.isVisibleToUser,
                checkable = info.isCheckable,
                checked = isChecked(info),
                focused = info.isFocused,
                editable = info.isEditable,
            )
        }

        fun root(windows: List<LiveNode>) =
            LiveNode(null, null, null, null, null, false, false, false, Box(0, 0, 0, 0), windows)

        /** Android 16 made "checked" three-state; before it, a boolean. */
        private fun isChecked(info: AccessibilityNodeInfo): Boolean =
            if (Build.VERSION.SDK_INT >= 36) {
                info.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
            } else {
                @Suppress("DEPRECATION")
                info.isChecked
            }
    }
}

/**
 * Thin, polling wrapper over the accessibility APIs, limited to Outlook's windows. Every wait has
 * a timeout; the navigator dumps the tree when something isn't found.
 */
class UiDriver(private val service: AccessibilityService) {
    private val pollMs = 200L

    val screenWidth: Int get() = service.resources.displayMetrics.widthPixels

    /** All on-screen Outlook windows (the app and popups such as the view menu), topmost first, under one root. */
    fun snapshot(): LiveNode = LiveNode.root(outlookRoots().map { LiveNode.capture(it) })

    /**
     * A [snapshot] read afresh from Outlook, not from Android's cache of its screen: for values that
     * change without the events the cache relies on (the time wheels, seen on 2026-10-07). Android 14
     * and later; before that, the cache is all there is.
     */
    fun freshSnapshot(): LiveNode {
        if (Build.VERSION.SDK_INT >= 34) runCatching { service.clearCache() }
        return snapshot()
    }

    private fun outlookRoots(): List<AccessibilityNodeInfo> {
        // Application windows only: fetching a root is a call into the window's process, and for our
        // own scan overlay that would mean our main thread answering every poll.
        val fromWindows = runCatching { service.windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .mapNotNull { w -> runCatching { w.root }.getOrNull()?.takeIf { it.packageName == OutlookSelectors.PACKAGE } }
        if (fromWindows.isNotEmpty()) return fromWindows
        return listOfNotNull(service.rootInActiveWindow?.takeIf { it.packageName == OutlookSelectors.PACKAGE })
    }

    fun outlookInFront(): Boolean = service.rootInActiveWindow?.packageName == OutlookSelectors.PACKAGE

    /**
     * Whether a view with [id] is on screen (not just in the tree, where Outlook keeps covered
     * screens): one lookup per window and no tree copy, so cheap enough to poll.
     */
    fun hasId(id: String): Boolean = outlookRoots().any { root ->
        runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull().orEmpty().any { it.isVisibleToUser }
    }

    /** Polls [condition]; false on timeout. */
    suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            currentCoroutineContext().ensureActive()
            if (condition()) return true
            if (SystemClock.uptimeMillis() >= deadline) return false
            delay(pollMs)
        }
    }

    /** Polls until [probe] returns non-null; null on timeout. */
    suspend fun <T : Any> waitFor(timeoutMs: Long, probe: (UiNode) -> T?): T? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            currentCoroutineContext().ensureActive()
            probe(snapshot())?.let { return it }
            if (SystemClock.uptimeMillis() >= deadline) return null
            delay(pollMs)
        }
    }

    /** ACTION_CLICK on the node or its nearest clickable ancestor, else a tap on its centre. */
    suspend fun click(node: UiNode, what: String): Boolean {
        var current = (node as? LiveNode)?.info
        var hops = 0
        while (current != null && hops < 5) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                ScanLog.i("Clicked $what")
                delay(250)
                return true
            }
            current = current.parent
            hops++
        }
        ScanLog.i("Tapping $what at ${node.bounds}")
        return tap(node.bounds)
    }

    /** A real tap at the centre of [bounds]. */
    suspend fun tap(bounds: Box): Boolean {
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.centerX.toFloat(), bounds.centerY.toFloat()) }
        val done = dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build())
        delay(300)
        return done
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build())
    }

    /**
     * Scrolls [node] vertically by about a screen; false if it can't (e.g. at the end). Uses the
     * directional actions when offered. The generic forward/backward actions are only used on a
     * ScrollView, because on a horizontally paging list they would change the day instead.
     */
    fun scrollVertically(node: UiNode, down: Boolean): Boolean {
        val info = (node as? LiveNode)?.info ?: return false
        val offered = runCatching { info.actionList.map { it.id }.toSet() }.getOrDefault(emptySet())
        val directional = if (down) AccessibilityAction.ACTION_SCROLL_DOWN.id else AccessibilityAction.ACTION_SCROLL_UP.id
        if (directional in offered) return info.performAction(directional)
        val generic = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (node.shortClass == "ScrollView" && generic in offered) return info.performAction(generic)
        return false
    }

    suspend fun back() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        delay(600)
    }

    /** Replaces a text field's text (ACTION_SET_TEXT). */
    fun setText(node: UiNode, text: String): Boolean {
        val info = (node as? LiveNode)?.info ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return runCatching { info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
            .also { ScanLog.i("Set text of ${node.shortClass} (${text.length} chars): $it") }
    }

    /** Pastes the clipboard into a text field (ACTION_PASTE). */
    fun paste(node: UiNode): Boolean {
        val info = (node as? LiveNode)?.info ?: return false
        return runCatching { info.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
            .also { ScanLog.i("Paste into ${node.shortClass}: $it") }
    }

    /**
     * Types [text] into the focused text field the way a keyboard does (the service's input method,
     * Android 13+): unlike ACTION_SET_TEXT, the app sees each character typed, so a comma after an
     * address turns it into a chip in Outlook's Add People (seen on 2026-10-07). False when no field
     * is taking input.
     */
    fun type(text: String): Boolean {
        val connection = runCatching { service.inputMethod?.currentInputConnection }.getOrNull() ?: return false.also {
            ScanLog.w("No input connection to type into")
        }
        return runCatching {
            connection.commitText(text, 1, null)
            true
        }.getOrDefault(false).also { ScanLog.i("Typed ${text.length} chars: $it") }
    }

    /** Types [text] one character at a time through the service's input method ([type]). */
    suspend fun typeEach(text: String, gapMs: Long = 40): Boolean {
        for (ch in text) {
            if (!type(ch.toString())) return false
            delay(gapMs)
        }
        return true
    }

    /**
     * Pastes the clipboard into the focused field through the service's input method, as a
     * keyboard's Paste does (Android 13+): works on Outlook's description editor, a WebView that
     * refuses ACTION_PASTE (seen on 2026-10-07), and keeps an HTML clip's formatting.
     */
    fun imePaste(): Boolean {
        val connection = runCatching { service.inputMethod?.currentInputConnection }.getOrNull() ?: return false
        return runCatching {
            connection.performContextMenuAction(android.R.id.paste)
            true
        }.getOrDefault(false).also { ScanLog.i("Paste through the input method: $it") }
    }

    /** A key pressed and released through the service's input method (Android 13+). */
    fun key(keyCode: Int): Boolean {
        val connection = runCatching { service.inputMethod?.currentInputConnection }.getOrNull() ?: return false
        return runCatching {
            connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
            connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
            true
        }.getOrDefault(false).also { ScanLog.i("Key $keyCode: $it") }
    }

    /** The keyboard's Enter key on a focused text field (ACTION_IME_ENTER). */
    fun imeEnter(node: UiNode): Boolean {
        val info = (node as? LiveNode)?.info ?: return false
        return runCatching { info.performAction(AccessibilityAction.ACTION_IME_ENTER.id) }.getOrDefault(false)
            .also { ScanLog.i("IME enter on ${node.shortClass}: $it") }
    }

    /** Input focus on a node (a text field, or an editor inside a WebView). */
    fun focus(node: UiNode): Boolean {
        val info = (node as? LiveNode)?.info ?: return false
        return runCatching { info.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }.getOrDefault(false)
    }

    /**
     * The generic forward/backward scroll: one value on a NumberPicker wheel, a page of a
     * RecyclerView. (Not for the Day view, where it would change the day; see [scrollVertically].)
     */
    fun scroll(node: UiNode, forward: Boolean): Boolean {
        val info = (node as? LiveNode)?.info ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return runCatching { info.performAction(action) }.getOrDefault(false)
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null,
        )
        if (!accepted && cont.isActive) cont.resume(false)
    }
}
