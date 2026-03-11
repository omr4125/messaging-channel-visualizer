package messaging.channel.visualizer

import com.intellij.openapi.vfs.VirtualFile

enum class Direction {
    INCOMING,
    OUTGOING
}

enum class ChannelSourceType {
    QUARKUS,
    SPRING_CLOUD_STREAM,
    SPRING_KAFKA,
    SPRING_RABBIT
}

data class ChannelConfig(
    val framework: String,
    val sourceType: ChannelSourceType,
    val direction: Direction,
    val profile: String?,
    val name: String,
    val connector: String?,
    val rawProperties: Map<String, String>,
    val sourceFile: VirtualFile
)

