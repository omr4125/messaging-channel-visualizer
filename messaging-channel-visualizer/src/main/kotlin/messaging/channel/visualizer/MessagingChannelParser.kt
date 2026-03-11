package messaging.channel.visualizer

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

class MessagingChannelParser {

    fun loadChannelConfigs(project: Project): List<ChannelConfig> {
        val scope = GlobalSearchScope.projectScope(project)
        val files = FilenameIndex.getVirtualFilesByName("application.properties", scope).toList()
        val result = mutableListOf<ChannelConfig>()

        files.forEach { file ->
            val text = readPropertiesContent(file)
            val props = parseProperties(text)
            result += parseQuarkusChannels(file, props)
            result += parseSpringCloudStreamChannels(file, props)
            result += parseSpringKafkaChannels(file, props)
            result += parseSpringRabbitMqChannels(file, props)
        }

        return sortChannels(result)
    }

    internal fun sortChannels(channels: List<ChannelConfig>): List<ChannelConfig> {
        return channels.sortedWith(
            compareBy<ChannelConfig> { it.direction.ordinal }
                .thenBy { it.framework }
                .thenBy { it.profile ?: "" }
                .thenBy { it.name }
                .thenBy { it.sourceFile.name }
        )
    }

    private fun readPropertiesContent(file: VirtualFile): String {
        val document = FileDocumentManager.getInstance().getDocument(file)
        if (document != null) {
            return document.text
        }
        return file.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private data class ChannelKey(
        val framework: String,
        val direction: Direction,
        val profile: String?,
        val name: String,
        val file: VirtualFile
    )

    private fun parseQuarkusChannels(file: VirtualFile, props: Map<String, String>): List<ChannelConfig> {
        val grouped = mutableMapOf<ChannelKey, MutableMap<String, String>>()

        props.forEach { (key, value) ->
            val (profile, normalizedKey) = extractProfileAndNormalizedKey(key)
            val incomingPrefix = "mp.messaging.incoming."
            val outgoingPrefix = "mp.messaging.outgoing."

            when {
                normalizedKey.startsWith(incomingPrefix) -> {
                    val rest = normalizedKey.removePrefix(incomingPrefix)
                    val channelName = rest.substringBefore(".")
                    val property = rest.substringAfter(".", missingDelimiterValue = "")
                    val channelKey = ChannelKey("Quarkus", Direction.INCOMING, profile, channelName, file)
                    grouped.computeIfAbsent(channelKey) { mutableMapOf() }[property] = value
                }

                normalizedKey.startsWith(outgoingPrefix) -> {
                    val rest = normalizedKey.removePrefix(outgoingPrefix)
                    val channelName = rest.substringBefore(".")
                    val property = rest.substringAfter(".", missingDelimiterValue = "")
                    val channelKey = ChannelKey("Quarkus", Direction.OUTGOING, profile, channelName, file)
                    grouped.computeIfAbsent(channelKey) { mutableMapOf() }[property] = value
                }
            }
        }

        return grouped.map { (key, mutableProps) ->
            val properties = mutableProps.toMap()
            ChannelConfig(
                framework = key.framework,
                sourceType = ChannelSourceType.QUARKUS,
                direction = key.direction,
                profile = key.profile,
                name = key.name,
                connector = properties["connector"] ?: guessBrokerFromProperties(properties),
                rawProperties = properties,
                sourceFile = key.file
            )
        }
    }

    private fun parseSpringCloudStreamChannels(file: VirtualFile, props: Map<String, String>): List<ChannelConfig> {
        val grouped = mutableMapOf<ChannelKey, MutableMap<String, String>>()
        val prefix = "spring.cloud.stream.bindings."

        props.forEach { (key, value) ->
            if (!key.startsWith(prefix)) return@forEach

            val rest = key.removePrefix(prefix)
            val bindingName = rest.substringBefore(".")
            val property = rest.substringAfter(".", missingDelimiterValue = "")
            val direction = inferSpringBindingDirection(bindingName, property) ?: return@forEach
            val broker = resolveSpringCloudStreamBroker(bindingName, props)
            val channelKey = ChannelKey("Spring", direction, null, bindingName, file)
            grouped.computeIfAbsent(channelKey) { mutableMapOf() }[property] = value
            grouped[channelKey]?.putIfAbsent("connector", broker)
        }

        return grouped.map { (key, mutableProps) ->
            val properties = mutableProps.toMap()
            ChannelConfig(
                framework = key.framework,
                sourceType = ChannelSourceType.SPRING_CLOUD_STREAM,
                direction = key.direction,
                profile = key.profile,
                name = key.name,
                connector = properties["connector"] ?: guessBrokerFromProperties(properties),
                rawProperties = properties,
                sourceFile = key.file
            )
        }
    }

    private fun parseSpringKafkaChannels(file: VirtualFile, props: Map<String, String>): List<ChannelConfig> {
        val incoming = mutableMapOf<String, String>()
        val outgoing = mutableMapOf<String, String>()
        val global = mutableMapOf<String, String>()

        props.forEach { (key, value) ->
            when {
                key.startsWith("spring.kafka.consumer.") -> incoming[key.removePrefix("spring.kafka.consumer.")] = value
                key.startsWith("spring.kafka.listener.") -> incoming["listener.${key.removePrefix("spring.kafka.listener.")}"] = value
                key.startsWith("spring.kafka.producer.") -> outgoing[key.removePrefix("spring.kafka.producer.")] = value
                key.startsWith("spring.kafka.template.") -> outgoing["template.${key.removePrefix("spring.kafka.template.")}"] = value
                key.startsWith("spring.kafka.") -> global[key.removePrefix("spring.kafka.")] = value
            }
        }

        val channels = mutableListOf<ChannelConfig>()
        if (incoming.isNotEmpty()) {
            channels += ChannelConfig(
                framework = "Spring",
                sourceType = ChannelSourceType.SPRING_KAFKA,
                direction = Direction.INCOMING,
                profile = null,
                name = "kafka-consumer",
                connector = "kafka",
                rawProperties = (global + incoming).toSortedMap(),
                sourceFile = file
            )
        }
        if (outgoing.isNotEmpty()) {
            channels += ChannelConfig(
                framework = "Spring",
                sourceType = ChannelSourceType.SPRING_KAFKA,
                direction = Direction.OUTGOING,
                profile = null,
                name = "kafka-producer",
                connector = "kafka",
                rawProperties = (global + outgoing).toSortedMap(),
                sourceFile = file
            )
        }
        return channels
    }

    private fun parseSpringRabbitMqChannels(file: VirtualFile, props: Map<String, String>): List<ChannelConfig> {
        val incoming = mutableMapOf<String, String>()
        val outgoing = mutableMapOf<String, String>()
        val global = mutableMapOf<String, String>()

        props.forEach { (key, value) ->
            when {
                key.startsWith("spring.rabbitmq.listener.") -> incoming[key.removePrefix("spring.rabbitmq.listener.")] = value
                key.startsWith("spring.rabbitmq.template.") -> outgoing[key.removePrefix("spring.rabbitmq.template.")] = value
                key.startsWith("spring.rabbitmq.") -> global[key.removePrefix("spring.rabbitmq.")] = value
            }
        }

        val channels = mutableListOf<ChannelConfig>()
        if (incoming.isNotEmpty()) {
            channels += ChannelConfig(
                framework = "Spring",
                sourceType = ChannelSourceType.SPRING_RABBIT,
                direction = Direction.INCOMING,
                profile = null,
                name = "rabbit-listener",
                connector = "rabbitmq",
                rawProperties = (global + incoming).toSortedMap(),
                sourceFile = file
            )
        }
        if (outgoing.isNotEmpty()) {
            channels += ChannelConfig(
                framework = "Spring",
                sourceType = ChannelSourceType.SPRING_RABBIT,
                direction = Direction.OUTGOING,
                profile = null,
                name = "rabbit-template",
                connector = "rabbitmq",
                rawProperties = (global + outgoing).toSortedMap(),
                sourceFile = file
            )
        }
        return channels
    }

    internal fun inferSpringBindingDirection(bindingName: String, property: String): Direction? {
        if (property.startsWith("consumer.")) return Direction.INCOMING
        if (property.startsWith("producer.")) return Direction.OUTGOING
        if (bindingName.endsWith("-in-0") || bindingName.endsWith(".in") || bindingName.startsWith("input")) {
            return Direction.INCOMING
        }
        if (bindingName.endsWith("-out-0") || bindingName.endsWith(".out") || bindingName.startsWith("output")) {
            return Direction.OUTGOING
        }
        return null
    }

    internal fun resolveSpringCloudStreamBroker(bindingName: String, props: Map<String, String>): String {
        val bindingBinder = props["spring.cloud.stream.bindings.$bindingName.binder"]
        val defaultBinder = props["spring.cloud.stream.default-binder"]
        val binderName = bindingBinder ?: defaultBinder
        if (!binderName.isNullOrBlank()) {
            val binderType = props["spring.cloud.stream.binders.$binderName.type"]
            if (!binderType.isNullOrBlank()) {
                return binderType
            }
        }

        if (props.keys.any { it.startsWith("spring.cloud.stream.kafka.bindings.$bindingName.") }) return "kafka"
        if (props.keys.any { it.startsWith("spring.cloud.stream.rabbit.bindings.$bindingName.") }) return "rabbitmq"
        if (props.keys.any { it.startsWith("spring.cloud.stream.amqp.bindings.$bindingName.") }) return "amqp"
        return "spring-cloud-stream"
    }

    internal fun extractProfileAndNormalizedKey(key: String): Pair<String?, String> {
        if (!key.startsWith("%")) return null to key
        val dotIndex = key.indexOf('.')
        if (dotIndex <= 1) return null to key
        val profile = key.substring(1, dotIndex)
        val normalized = key.substring(dotIndex + 1)
        return profile to normalized
    }

    internal fun guessBrokerFromProperties(properties: Map<String, String>): String? {
        val connector = properties["connector"] ?: return null
        return when {
            connector.contains("kafka", ignoreCase = true) -> "kafka"
            connector.contains("rabbit", ignoreCase = true) -> "rabbitmq"
            connector.contains("amqp", ignoreCase = true) -> "amqp"
            else -> connector
        }
    }

    internal fun parseProperties(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .forEach { line ->
                val eq = line.indexOf('=')
                val colon = line.indexOf(':')
                val idx = when {
                    eq > 0 && colon > 0 -> minOf(eq, colon)
                    eq > 0 -> eq
                    colon > 0 -> colon
                    else -> -1
                }
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (key.isNotEmpty()) {
                        map[key] = value
                    }
                }
            }
        return map
    }
}

