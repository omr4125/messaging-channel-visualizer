# Messaging Channel Visualizer

An IntelliJ IDEA plugin that visualizes messaging broker channel configurations defined in `application.properties` files.

Instead of scrolling through dozens of raw property lines, see all your incoming and outgoing channels in a clean, color-coded table — and edit them directly.

## Supported Frameworks

| Framework | Configuration Pattern |
|---|---|
| **Quarkus** | `mp.messaging.incoming.*` / `mp.messaging.outgoing.*` |
| **Spring Cloud Stream** | `spring.cloud.stream.bindings.*` |
| **Spring Kafka** | `spring.kafka.consumer.*` / `spring.kafka.producer.*` / `spring.kafka.listener.*` |
| **Spring RabbitMQ** | `spring.rabbitmq.listener.*` |

## Features

- **Channel overview table** — All incoming and outgoing channels in a single view, sorted by direction
- **Color-coded rows** — Green tint for incoming, orange tint for outgoing channels
- **Profile-aware parsing** — Recognizes Quarkus profile prefixes (`%dev.`, `%prod.`, etc.)
- **In-table editing** — Edit Direction, Profile, Channel name, and Broker directly in the table; changes are written back to the `application.properties` file automatically
- **Live refresh** — Table updates in real-time on file save and while typing
- **Detail panel** — Click any row to see all raw properties for that channel in the bottom panel
- **Theme support** — Works with both light and dark IDE themes

## Installation

### From JetBrains Marketplace

1. Open IntelliJ IDEA
2. Go to **Settings** > **Plugins** > **Marketplace**
3. Search for **"Messaging Channel Visualizer"**
4. Click **Install** and restart the IDE

### From ZIP

1. Download the latest release `.zip` file
2. Go to **Settings** > **Plugins** > **Gear icon** > **Install Plugin from Disk...**
3. Select the `.zip` file and restart the IDE

## Usage

1. Open a project that contains `application.properties` with messaging channel configurations
2. Open the **Messaging Channel Visualizer** tool window from the right sidebar
3. The table displays all detected channels with their framework, direction, profile, name, and broker
4. Click a row to view all raw properties in the detail panel below
5. Double-click an editable cell (Direction, Profile, Channel, Broker) to modify it — the change is saved to the file automatically

## Building from Source

### Prerequisites

- JDK 17+
- Gradle 9.0+

### Build

```bash
./gradlew buildPlugin
```

The plugin ZIP will be generated at `build/distributions/`.

### Run in Sandbox IDE

```bash
./gradlew runIde
```

### Run Tests

```bash
./gradlew test
```

## Project Structure

```
src/main/kotlin/messaging/channel/visualizer/
├── ChannelConfig.kt              # Data models (Direction, ChannelSourceType, ChannelConfig)
├── MessagingChannelParser.kt     # Parses application.properties into ChannelConfig objects
├── ChannelUi.kt                  # Table cell renderer and detail panel HTML builder
├── ChannelConfigUpdater.kt       # Writes table edits back to properties files
└── MessagingToolWindowFactory.kt # Tool window factory and main UI panel

src/test/kotlin/messaging/channel/visualizer/
├── MessagingChannelParserTest.kt # Parser unit tests
└── ChannelConfigUpdaterTest.kt   # Updater unit tests
```

## Compatibility

- IntelliJ IDEA 2020.1+ (build 201) through 2025.3.x (build 253)
- Works with IntelliJ IDEA Community and Ultimate editions
- Compatible with all JetBrains IDEs based on the IntelliJ Platform

## License

Apache License 2.0
