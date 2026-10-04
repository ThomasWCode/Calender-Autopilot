package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.outlook.Box
import com.thomaswcode.calendareventtimers.outlook.UiNode
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** A [UiNode] built from a uiautomator dump, as captured in `reference/` during planning. */
class XmlNode(
    override val className: String?,
    override val viewId: String?,
    override val text: String?,
    override val desc: String?,
    override val clickable: Boolean,
    override val scrollable: Boolean,
    override val selected: Boolean,
    override val bounds: Box,
    override val children: List<UiNode>,
    override val visible: Boolean = true,
) : UiNode

object Fixtures {
    private val boundsPattern = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    /** The whole dump under one synthetic root, like the driver's multi-window snapshot. */
    fun load(name: String): UiNode {
        val stream = requireNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")) { "missing fixture $name" }
        val doc = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val windows = doc.documentElement.childElements().map(::convert)
        return XmlNode(null, null, null, null, false, false, false, Box(0, 0, 1080, 2400), windows)
    }

    private fun convert(e: Element): UiNode {
        fun attr(name: String) = e.getAttribute(name).ifEmpty { null }
        val b = boundsPattern.find(e.getAttribute("bounds"))?.groupValues?.drop(1)?.map { it.toInt() } ?: listOf(0, 0, 0, 0)
        return XmlNode(
            className = attr("class"),
            viewId = attr("resource-id"),
            text = attr("text"),
            desc = attr("content-desc"),
            clickable = e.getAttribute("clickable") == "true",
            scrollable = e.getAttribute("scrollable") == "true",
            selected = e.getAttribute("selected") == "true",
            bounds = Box(b[0], b[1], b[2], b[3]),
            children = e.childElements().map(::convert),
        )
    }

    private fun Element.childElements(): List<Element> {
        val out = ArrayList<Element>()
        val list = childNodes
        for (i in 0 until list.length) (list.item(i) as? Element)?.takeIf { it.tagName == "node" }?.let(out::add)
        return out
    }
}
