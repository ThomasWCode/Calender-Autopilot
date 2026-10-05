package com.thomaswcode.calendareventtimers.outlook

/** Screen rectangle, kept free of android.graphics so the readers run in plain JVM tests. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    override fun toString() = "[$left,$top][$right,$bottom]"
}

/**
 * Read-only view of one node of a window's accessibility tree. On the phone it wraps an
 * AccessibilityNodeInfo snapshot; in unit tests it is built from the uiautomator XML dumps in
 * `reference/`, so the readers are tested against real Outlook screens.
 */
interface UiNode {
    val className: String?
    val viewId: String?
    val text: String?
    val desc: String?
    val clickable: Boolean
    val scrollable: Boolean
    val selected: Boolean
    val bounds: Box
    val children: List<UiNode>

    /** False for nodes in the tree but outside the screen (uiautomator dumps leave those out). */
    val visible: Boolean get() = true

    val checkable: Boolean get() = false
    val checked: Boolean get() = false
    val focused: Boolean get() = false

    /** A text field; uiautomator dumps don't say, the service does (WebView editors included). */
    val editable: Boolean get() = false
}

fun UiNode.walk(): Sequence<UiNode> = sequence {
    yield(this@walk)
    for (child in children) yieldAll(child.walk())
}

fun UiNode.find(predicate: (UiNode) -> Boolean): UiNode? = walk().firstOrNull(predicate)

fun UiNode.findAll(predicate: (UiNode) -> Boolean): List<UiNode> = walk().filter(predicate).toList()

fun UiNode.byId(id: String): UiNode? = find { it.viewId == id }

val UiNode.shortClass: String? get() = className?.substringAfterLast('.')

val UiNode.isButton: Boolean get() = shortClass == "Button"

val UiNode.isTextView: Boolean get() = shortClass?.endsWith("TextView") == true

/**
 * One line per node, for logs: class, id, text, desc, flags and bounds. [redact] decides per node
 * whether its texts are replaced by their length (for anything that may be mail rather than the
 * calendar); it is told whether the node lies under one of [areaIds].
 */
fun UiNode.dump(
    maxText: Int = 80,
    areaIds: Set<String> = emptySet(),
    redact: (node: UiNode, inArea: Boolean) -> Boolean = { _, _ -> false },
): String = buildString {
    fun line(node: UiNode, depth: Int, parentInArea: Boolean) {
        val inArea = parentInArea || node.viewId in areaIds
        val hide = redact(node, inArea)
        fun shown(s: String) = if (hide) "<${s.length} chars>" else s.replace('\n', ' ').take(maxText)
        append("  ".repeat(depth)).append(node.shortClass ?: "?")
        node.viewId?.let { append(" #").append(it.substringAfter(":id/")) }
        node.text?.takeIf { it.isNotEmpty() }?.let { append(" t='").append(shown(it)).append('\'') }
        node.desc?.takeIf { it.isNotEmpty() }?.let { append(" d='").append(shown(it)).append('\'') }
        if (node.clickable) append(" [click]")
        if (node.scrollable) append(" [scroll]")
        if (node.selected) append(" [selected]")
        if (node.checked) append(" [checked]")
        if (node.editable) append(" [editable]")
        if (node.focused) append(" [focused]")
        if (!node.visible) append(" [offscreen]")
        append(' ').append(node.bounds).append('\n')
        for (child in node.children) line(child, depth + 1, inArea)
    }
    line(this@dump, 0, false)
}
