# FateWeaver

![Maven Central Version](https://img.shields.io/maven-central/v/gay.zharel.fateweaver/ftc?style=for-the-badge&label=Latest%20Version)

**FateWeaver** is a powerful RoadRunner-log based data logging library designed specifically for FTC robotics teams. It provides type-safe, efficient logging capabilities with seamless integration into existing FTC projects and full compatibility with AdvantageScope for data visualization.

## Key Features

- **Type-Safe Logging**: Strongly typed channels prevent runtime errors and ensure data integrity
- **Easy Integration**: Drop-in replacement for RoadRunner FlightRecorder with enhanced capabilities
- **Custom Data Types**: Full support for complex objects using reflection or custom serialization
- **Schema Registry**: Automatic schema management and reuse across your entire codebase
- **AdvantageScope Compatible**: Direct integration with industry-standard log visualization tools
- **Backwards Compatible**: Works alongside existing RoadRunner logging infrastructure

## Quick Start

Get up and running with FateWeaver in just a few lines of code:

=== "Kotlin"

    ```kotlin
    class MyOpMode : OpMode() {
        private lateinit var drive: MecanumDrive
        private lateinit var poseChannel: FlightLogChannel<Pose2d>
        private lateinit var timestampChannel: FlightLogChannel<Long>

        override fun init() {
            drive = MecanumDrive(hardwareMap, Pose2d(0.0, 0.0, 0.0))

            // Create type-safe channels
            poseChannel = FlightRecorder.createChannel("Robot/Pose", Pose2d::class.java)
            timestampChannel = FlightRecorder.createChannel("TIMESTAMP", LongSchema.INSTANCE)
        }

        override fun loop() {
            drive.updatePoseEstimate()

            // Log data with full type safety
            poseChannel.put(drive.pose)
            timestampChannel.put(System.nanoTime())
        }
    }
    ```

=== "Java"

    ```java
    public class MyOpMode extends OpMode {
        private MecanumDrive drive;
        private FlightLogChannel<Pose2d> poseChannel;
        private FlightLogChannel<Long> timestampChannel;

        @Override
        public void init() {
            drive = new MecanumDrive(hardwareMap, new Pose2d(0.0, 0.0, 0.0));

            // Create type-safe channels
            poseChannel = FlightRecorder.createChannel("Robot/Pose", Pose2d.class);
            timestampChannel = FlightRecorder.createChannel("TIMESTAMP", LongSchema.INSTANCE);
        }

        @Override
        public void loop() {
            drive.updatePoseEstimate();

            // Log data with full type safety
            poseChannel.put(drive.getPose());
            timestampChannel.put(System.nanoTime());
        }
    }
    ```

## What Makes FateWeaver Special?

### Intelligent Schema System
FateWeaver automatically generates optimized schemas for your data types, with support for:

- **Primitive types** (Int, Long, Double, String, Boolean)
- **Collections** (Arrays, Lists)
- **Enums** with efficient ordinal encoding
- **Custom classes** via reflection or manual definition
- **Polymorphic types** with AS_TYPE annotation support

### Advanced Serialization Control
Take full control of how your data is serialized:

```kotlin
val optimizedSchema = CustomStructSchema<SensorReading>(
    type = "OptimizedSensor",
    componentNames = listOf("time", "value", "sensor"),
    componentSchemas = listOf(LongSchema, DoubleSchema, StringSchema),
    encoder = { reading ->
        listOf(
            reading.timestamp / 1000000,  // Convert to milliseconds
            String.format("%.3f", reading.value).toDouble(),  // Round precision
            reading.sensorId.take(8)  // Truncate ID
        )
    }
)
```

### Global Schema Registry
Register schemas once, use them everywhere:

```kotlin
// Register once during initialization
FateSchema.registerSchema<Pose2d>(customPoseSchema)

// Use automatically across your entire codebase
val poseChannel = FlightRecorder.createChannel("Robot/Pose", Pose2d::class.java)
// Automatically uses your custom schema!
```

## Getting Started

Ready to enhance your robot's data logging capabilities?

1. [**Installation**](installation.md) - Add FateWeaver to your FTC project
2. [**Getting Started**](getting-started.md) - Your first logging implementation
3. [**API Reference**](api-reference.md) - Complete API documentation
4. [**Schema System**](schemas.md) - Understanding FateWeaver's type system
5. [**Log Management**](log-management.md) - Downloading and viewing your data

## Community & Support

- **Documentation**: [API JavaDocs](https://javadoc.io/doc/gay.zharel.fateweaver/ftc/latest/)
- **Source Code**: [GitHub Repository](https://github.com/hermesftc/fateweaver/)
- **Issues & Features**: [GitHub Issues](https://github.com/hermesftc/fateweaver/issues)
- **Community**: [Discord](https://discord.gg/49C5epU22h)

---

*FateWeaver is developed and maintained by the FTC community for the FTC community. Happy logging!* 🤖