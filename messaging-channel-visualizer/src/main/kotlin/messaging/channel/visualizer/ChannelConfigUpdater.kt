package messaging.channel.visualizer

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

class ChannelConfigUpdater(private val project: Project) {

    data class ChannelEdit(
        val direction: Direction,
        val profile: String?,
        val name: String,
        val broker: String?
    )

    fun applyEdit(channel: ChannelConfig, edit: ChannelEdit): Boolean {
        return when (channel.sourceType) {
            ChannelSourceType.QUARKUS -> applyQuarkusEdit(channel, edit)
            ChannelSourceType.SPRING_CLOUD_STREAM -> applySpringCloudStreamEdit(channel, edit)
            ChannelSourceType.SPRING_KAFKA, ChannelSourceType.SPRING_RABBIT -> false
        }
    }

    private fun applyQuarkusEdit(channel: ChannelConfig, edit: ChannelEdit): Boolean {
        val oldPrefix = quarkusPrefix(channel.direction, channel.profile, channel.name)
        val newPrefix = quarkusPrefix(edit.direction, edit.profile, edit.name)
        return applyPrefixRenameAndBrokerUpdate(channel, oldPrefix, newPrefix, edit.broker)
    }

    private fun applySpringCloudStreamEdit(channel: ChannelConfig, edit: ChannelEdit): Boolean {
        val oldPrefix = "spring.cloud.stream.bindings.${channel.name}."
        val newPrefix = "spring.cloud.stream.bindings.${edit.name}."
        return applyPrefixRenameAndBrokerUpdate(channel, oldPrefix, newPrefix, edit.broker)
    }

    private fun applyPrefixRenameAndBrokerUpdate(
        channel: ChannelConfig,
        oldPrefix: String,
        newPrefix: String,
        newBroker: String?
    ): Boolean {
        val document = FileDocumentManager.getInstance().getDocument(channel.sourceFile) ?: return false
        val transform = transformText(document.text, oldPrefix, newPrefix, newBroker ?: channel.connector)
        if (!transform.changed) return true

        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(transform.text)
            FileDocumentManager.getInstance().saveDocument(document)
        }
        return true
    }

    internal fun quarkusPrefix(direction: Direction, profile: String?, name: String): String {
        val profilePrefix = if (profile.isNullOrBlank()) "" else "%${profile.trim()}."
        val dir = if (direction == Direction.INCOMING) "incoming" else "outgoing"
        return "${profilePrefix}mp.messaging.$dir.${name.trim()}."
    }

    internal data class TransformResult(
        val text: String,
        val changed: Boolean
    )

    internal fun transformText(
        oldText: String,
        oldPrefix: String,
        newPrefix: String,
        newBroker: String?
    ): TransformResult {
        val newLines = mutableListOf<String>()
        var changed = false
        var connectorFound = false

        oldText.lines().forEach { line ->
            val trimmed = line.trimStart()

            if (trimmed.startsWith(oldPrefix)) {
                val leadingWhitespaceLength = line.length - trimmed.length
                val leading = line.substring(0, leadingWhitespaceLength)
                val renamed = leading + trimmed.replaceFirst(oldPrefix, newPrefix)

                val connectorKeyEq = "${newPrefix}connector="
                val connectorKeyColon = "${newPrefix}connector:"
                val renamedTrimmed = renamed.trimStart()
                val isConnectorLine = renamedTrimmed.startsWith(connectorKeyEq) ||
                    renamedTrimmed.startsWith(connectorKeyColon)

                val updatedLine = if (isConnectorLine) {
                    connectorFound = true
                    changed = true
                    "$leading$connectorKeyEq${newBroker.orEmpty()}"
                } else {
                    if (renamed != line) changed = true
                    renamed
                }
                newLines += updatedLine
            } else {
                newLines += line
            }
        }

        val effectiveBroker = newBroker?.trim().orEmpty()
        if (!connectorFound && effectiveBroker.isNotEmpty()) {
            newLines += "${newPrefix}connector=$effectiveBroker"
            changed = true
        }

        return TransformResult(newLines.joinToString("\n"), changed)
    }
}

