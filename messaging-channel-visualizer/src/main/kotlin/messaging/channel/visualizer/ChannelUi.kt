package messaging.channel.visualizer

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer

object ChannelUi {

    fun buildChannelDetailsHtml(channel: ChannelConfig): String {
        val sb = StringBuilder()
        sb.append("<html><body style='padding:8px;'>")
        sb.append("<h3 style='margin-top:0;'>")
        sb.append(if (channel.direction == Direction.INCOMING) "Incoming" else "Outgoing")
        sb.append(" - ")
        if (!channel.profile.isNullOrBlank()) {
            sb.append("[")
            sb.append(channel.profile)
            sb.append("] ")
        }
        sb.append(channel.name)
        sb.append("</h3>")

        sb.append("<p><b>Framework:</b> ")
        sb.append(channel.framework)
        sb.append("</p>")

        if (!channel.profile.isNullOrBlank()) {
            sb.append("<p><b>Profile:</b> ")
            sb.append(channel.profile)
            sb.append("</p>")
        }

        if (!channel.connector.isNullOrBlank()) {
            sb.append("<p><b>Broker / Connector:</b> ")
            sb.append(channel.connector)
            sb.append("</p>")
        }

        sb.append("<p><b>Source file:</b> ")
        sb.append(channel.sourceFile.path)
        sb.append("</p>")

        if (channel.rawProperties.isNotEmpty()) {
            sb.append("<h4>Properties</h4>")
            sb.append("<table cellpadding='2' cellspacing='0'>")
            channel.rawProperties.toSortedMap().forEach { (key, value) ->
                sb.append("<tr>")
                sb.append("<td><code>")
                sb.append(key.ifBlank { "(general)" })
                sb.append("</code></td>")
                sb.append("<td style='padding-left:8px;'>")
                sb.append(value)
                sb.append("</td>")
                sb.append("</tr>")
            }
            sb.append("</table>")
        }

        sb.append("</body></html>")
        return sb.toString()
    }
}

class ChannelTableCellRenderer(
    private val channelsProvider: () -> List<ChannelConfig>
) : DefaultTableCellRenderer() {

    private val zebraOdd = JBColor(Color(0xFA, 0xFB, 0xFC), Color(0x2F, 0x32, 0x36))
    private val zebraEven = JBColor(Color(0xFF, 0xFF, 0xFF), Color(0x3A, 0x3D, 0x42))
    private val incomingTint = JBColor(Color(0xD9, 0xF5, 0xE3), Color(0x2A, 0x45, 0x35))
    private val outgoingTint = JBColor(Color(0xFF, 0xE9, 0xD2), Color(0x4A, 0x3A, 0x2A))
    private val incomingFg = JBColor(Color(0x1E, 0x74, 0x39), Color(0x8E, 0xD9, 0xA6))
    private val outgoingFg = JBColor(Color(0xB6, 0x53, 0x00), Color(0xFF, 0xCF, 0x99))
    private val frameworkFg = JBColor(Color(0x1F, 0x5D, 0xB8), Color(0x8A, 0xBA, 0xFF))

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        val direction = channelsProvider().getOrNull(table.convertRowIndexToModel(row))?.direction

        if (isSelected) {
            applySelectedStyle(column)
        } else {
            applyUnselectedStyle(table, row, column, direction)
        }

        return component
    }

    private fun applySelectedStyle(column: Int) {
        horizontalAlignment = if (column == 1) SwingConstants.CENTER else SwingConstants.LEFT
        border = JBUI.Borders.empty(0, 6)
    }

    private fun applyUnselectedStyle(table: JTable, row: Int, column: Int, direction: Direction?) {
        val zebraBase = if (row % 2 == 0) zebraEven else zebraOdd
        val tint = directionTint(direction, zebraBase)
        background = blend(zebraBase, tint, 0.28f)
        foreground = table.foreground

        applyColumnStyle(column, direction)
    }

    private fun directionTint(direction: Direction?, fallback: Color): Color {
        return when (direction) {
            Direction.INCOMING -> incomingTint
            Direction.OUTGOING -> outgoingTint
            null -> fallback
        }
    }

    private fun applyColumnStyle(column: Int, direction: Direction?) {
        when {
            column == 1 && direction != null -> applyDirectionBadge(direction)
            column == 0 -> {
                horizontalAlignment = SwingConstants.LEFT
                border = JBUI.Borders.empty(0, 6)
                foreground = frameworkFg
            }
            else -> {
                horizontalAlignment = SwingConstants.LEFT
                border = JBUI.Borders.empty(0, 6)
            }
        }
    }

    private fun applyDirectionBadge(direction: Direction) {
        val color = if (direction == Direction.INCOMING) incomingFg else outgoingFg
        horizontalAlignment = SwingConstants.CENTER
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(3, 6, 3, 6),
            BorderFactory.createLineBorder(color, 1, true)
        )
        foreground = color
    }

    private fun blend(a: Color, b: Color, ratio: Float): Color {
        val clamped = ratio.coerceIn(0f, 1f)
        val inverse = 1f - clamped
        val r = (a.red * inverse + b.red * clamped).toInt().coerceIn(0, 255)
        val g = (a.green * inverse + b.green * clamped).toInt().coerceIn(0, 255)
        val bl = (a.blue * inverse + b.blue * clamped).toInt().coerceIn(0, 255)
        return Color(r, g, bl)
    }
}
