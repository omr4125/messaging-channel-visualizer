package messaging.channel.visualizer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.messages.Topic
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel

private val COLUMN_NAMES = arrayOf("Framework", "Direction", "Profile", "Channel", "Broker", "File")
private val COLUMN_WIDTHS = intArrayOf(100, 96, 70, 190, 130, 160)

class MessagingToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = BrokerViewPanel(project)

        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(panel.root, "Overview", false)
        content.setDisposer(panel.disposable)
        toolWindow.contentManager.addContent(content)
    }
}

private class BrokerViewPanel(private val project: Project) {

    val disposable: Disposable = Disposer.newDisposable("MessagingChannelVisualizerToolWindow")
    val root = JPanel(BorderLayout())

    private val parser = MessagingChannelParser()
    private val updater = ChannelConfigUpdater(project)
    private val channels = mutableListOf<ChannelConfig>()
    private val statusLabel = JLabel("Loading channels from application.properties...", SwingConstants.LEFT)
    private val detailLabel = JLabel("Select a channel to see its details.", SwingConstants.LEFT)
    private var isRefreshing = false
    private val tableModel = createTableModel()
    private val table = createTable()

    init {
        styleLabels()
        assembleLayout()
        refreshChannels()
        registerVfsListener()
        registerDocumentListener()
    }

    private fun createTableModel(): DefaultTableModel {
        return object : DefaultTableModel(COLUMN_NAMES, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean {
                return resolveEditability(row, column)
            }

            override fun setValueAt(aValue: Any?, row: Int, column: Int) {
                if (isRefreshing) {
                    super.setValueAt(aValue, row, column)
                    return
                }
                applyTableEdit(row, column, aValue)
            }
        }
    }

    private fun createTable(): JTable {
        val jbTable = JBTable(tableModel)
        jbTable.autoCreateRowSorter = true
        jbTable.rowHeight = JBUI.scale(26)
        jbTable.intercellSpacing = JBUI.size(0, 0)
        jbTable.setShowVerticalLines(false)
        jbTable.setShowHorizontalLines(false)
        jbTable.fillsViewportHeight = true

        COLUMN_WIDTHS.forEachIndexed { i, width ->
            jbTable.columnModel.getColumn(i).preferredWidth = JBUI.scale(width)
        }

        jbTable.setDefaultRenderer(Any::class.java, ChannelTableCellRenderer { channels })

        jbTable.selectionModel.addListSelectionListener {
            val index = jbTable.selectedRow
            if (index in channels.indices) {
                val channel = channels[jbTable.convertRowIndexToModel(index)]
                detailLabel.text = ChannelUi.buildChannelDetailsHtml(channel)
            }
        }

        return jbTable
    }

    private fun styleLabels() {
        statusLabel.border = JBUI.Borders.empty(6, 10)
        statusLabel.foreground = JBColor(0x5F6368, 0xA0A4AA)
        detailLabel.border = JBUI.Borders.empty(8, 10)
    }

    private fun assembleLayout() {
        val splitter = JBSplitter(true, 0.62f)
        splitter.firstComponent = JBScrollPane(table)
        splitter.secondComponent = JBScrollPane(detailLabel)
        splitter.dividerWidth = JBUI.scale(4)

        root.add(statusLabel, BorderLayout.NORTH)
        root.add(splitter, BorderLayout.CENTER)
    }

    private fun refreshChannels() {
        val loaded = ApplicationManager.getApplication().runReadAction<List<ChannelConfig>> {
            parser.loadChannelConfigs(project)
        }

        isRefreshing = true
        channels.clear()
        channels.addAll(loaded)

        tableModel.setRowCount(0)
        channels.forEach { ch ->
            tableModel.addRow(channelToRow(ch))
        }
        isRefreshing = false

        updateStatusAfterRefresh()
    }

    private fun channelToRow(ch: ChannelConfig): Array<String> {
        return arrayOf(
            ch.framework,
            if (ch.direction == Direction.INCOMING) "Incoming" else "Outgoing",
            ch.profile ?: "",
            ch.name,
            ch.connector ?: "",
            ch.sourceFile.name
        )
    }

    private fun updateStatusAfterRefresh() {
        if (channels.isEmpty()) {
            statusLabel.text = "No incoming/outgoing channels found in application.properties."
            detailLabel.text =
                "<html><body style='padding:8px;'>Example: mp.messaging.incoming.orders.connector=kafka</body></html>"
        } else {
            statusLabel.text = "Found ${channels.size} channel(s). Updates are reflected live."
            detailLabel.text = "Select a channel to see its details."
        }
    }

    private fun resolveEditability(row: Int, column: Int): Boolean {
        val channel = channels.getOrNull(row.coerceIn(0, tableModel.rowCount - 1)) ?: return false
        val isEditableSource = channel.sourceType == ChannelSourceType.QUARKUS ||
            channel.sourceType == ChannelSourceType.SPRING_CLOUD_STREAM

        return when (column) {
            0, 5 -> false
            1, 3, 4 -> isEditableSource
            2 -> channel.sourceType == ChannelSourceType.QUARKUS
            else -> false
        }
    }

    private fun applyTableEdit(row: Int, column: Int, aValue: Any?) {
        val channel = channels.getOrNull(row.coerceIn(0, tableModel.rowCount - 1)) ?: return
        val newValue = aValue?.toString()?.trim().orEmpty()

        val edit = ChannelConfigUpdater.ChannelEdit(
            direction = if (column == 1) parseDirection(newValue) ?: channel.direction else channel.direction,
            profile = if (column == 2) newValue.ifBlank { null } else channel.profile,
            name = if (column == 3) newValue.ifBlank { channel.name } else channel.name,
            broker = if (column == 4) newValue.ifBlank { channel.connector } else channel.connector
        )

        if (!updater.applyEdit(channel, edit)) {
            statusLabel.text = "Read-only row. Editable sync is available for Quarkus and Spring Cloud Stream bindings."
            return
        }
        statusLabel.text = "Saved changes to ${channel.sourceFile.name}."
    }

    private fun registerVfsListener() {
        val connection = project.messageBus.connect(disposable)
        val listener = object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                if (events.any { isPropertiesFile(it.path) }) {
                    scheduleRefresh()
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        val topic = VirtualFileManager.VFS_CHANGES as Topic<BulkFileListener>
        connection.subscribe(topic, listener)
    }

    private fun registerDocumentListener() {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val changedFile = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (changedFile.name != "application.properties") return
                if (!ProjectFileIndex.getInstance(project).isInContent(changedFile)) return
                if (!project.isDisposed) refreshChannels()
            }
        }, disposable)
    }

    private fun scheduleRefresh() {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) refreshChannels()
        }
    }

    private fun isPropertiesFile(path: String): Boolean {
        return path.endsWith("/application.properties") || path.endsWith("\\application.properties")
    }
}

private fun parseDirection(value: String): Direction? {
    return when (value.trim().lowercase()) {
        "incoming", "in" -> Direction.INCOMING
        "outgoing", "out" -> Direction.OUTGOING
        else -> null
    }
}
