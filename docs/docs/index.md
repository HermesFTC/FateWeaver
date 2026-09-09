# FateWeaver

![Maven Central Version](https://img.shields.io/maven-central/v/gay.zharel.fateweaver/ftc?style=for-the-badge&label=Latest%20Version)

FateWeaver is a binary data logger for FTC robots. It writes RoadRunner-format logs that
AdvantageScope opens directly.

Channels are typed, so writing a `Double` to an `Int` channel is a compile error rather than a
garbled plot three matches later. Your own classes are logged by reflection, or by a schema you
write when you want control over the bytes. It drops in where RoadRunner's `FlightRecorder` already
is, and runs alongside the logging you already have.

## Quick start

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

## The schema system

Most of the time you never write a schema. FateWeaver infers one from the type you hand it:

- Int, Long, Double, String, Boolean
- Arrays, lists, sets and maps
- Enums, encoded as a 4-byte ordinal
- Your own classes, by reflection over their public properties
- Classes with an `AS_TYPE` constant, which get that name recorded in the log

### Writing a schema by hand

When you care about the exact bytes, say so:

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

### The schema registry

Register a schema once and every later channel for that class picks it up:

```kotlin
// Register once during initialization
FateSchema.registerSchema<Pose2d>(customPoseSchema)

// Every channel built from Pose2d now uses it
val poseChannel = FlightRecorder.createChannel("Robot/Pose", Pose2d::class.java)
```

## Where to next

1. [Installation](installation.md) - add FateWeaver to your FTC project
2. [Getting started](getting-started.md) - your first logging OpMode
3. [Schema system](schemas.md) - how types become bytes
4. [Log management](log-management.md) - downloading and viewing your data

## Links

- [API JavaDocs](https://javadoc.io/doc/gay.zharel.fateweaver/ftc/latest/)
- [GitHub](https://github.com/hermesftc/fateweaver/)
- [Issues](https://github.com/hermesftc/fateweaver/issues)
- [Discord](https://discord.gg/49C5epU22h)

---

*FateWeaver is developed and maintained by the FTC community for the FTC community. Happy logging!* 🤖