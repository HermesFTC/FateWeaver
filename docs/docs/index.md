# FateWeaver

![Maven Central Version](https://img.shields.io/maven-central/v/gay.zharel.fateweaver/ftc?style=for-the-badge&label=Latest%20Version)

FateWeaver is a binary data logger for FTC robots. It writes one log file per OpMode, and
AdvantageScope opens the file directly.

You log a value by name with `Fate.log`. There is nothing to create first, and every value is
stamped with the time you logged it. A path keeps the type of its first value. If you later log a
different type to it, FateWeaver warns once and skips that value instead of garbling the plot. Your
own classes are logged by reflection, or with a schema you write when you want control over the
bytes.

## Quick start

=== "Kotlin"

    ```kotlin
    import gay.zharel.fateweaver.telemetry.Fate
    import gay.zharel.fateweaver.telemetry.log

    class MyOpMode : OpMode() {
        private lateinit var drive: MecanumDrive

        override fun init() {
            drive = MecanumDrive(hardwareMap, Pose2d(0.0, 0.0, 0.0))
        }

        override fun loop() {
            drive.updatePoseEstimate()

            Fate.log("Robot/Pose", drive.pose)
        }
    }
    ```

=== "Java"

    ```java
    import gay.zharel.fateweaver.telemetry.Fate;

    public class MyOpMode extends OpMode {
        private MecanumDrive drive;

        @Override
        public void init() {
            drive = new MecanumDrive(hardwareMap, new Pose2d(0.0, 0.0, 0.0));
        }

        @Override
        public void loop() {
            drive.updatePoseEstimate();

            Fate.log("Robot/Pose", drive.getPose());
        }
    }
    ```

To group related values, ask for a table once and log into it. For example,
`val drive = Fate.table("Drive")` followed by `drive.log("leftVelocity", v)` logs to
`Drive/leftVelocity`. Slashes in a path become folders in AdvantageScope.

## The schema system

Most of the time you never write a schema. FateWeaver infers one from the value you log:

- Int, Long, Double, String, Boolean
- Arrays, lists, sets and maps
- Enums, encoded as a 4-byte ordinal
- Your own classes, by reflection over their public properties
- Classes with an `AS_TYPE` constant, which get that name recorded in the log

### Writing a schema by hand

When you care about the exact bytes, write the schema and pass it as the third argument:

```kotlin
val optimizedSchema = CustomStructSchema<SensorReading>(
    type = "OptimizedSensor",
    componentNames = listOf("time", "value", "sensor"),
    componentSchemas = listOf(LongSchema, DoubleSchema, StringSchema),
    encoder = { reading ->
        listOf(
            reading.timestamp / 1000000,  // convert to milliseconds
            String.format("%.3f", reading.value).toDouble(),  // round precision
            reading.sensorId.take(8)  // truncate ID
        )
    }
)

Fate.log("Sensors/Optimized", reading, optimizedSchema)
```

The first log to a path fixes its schema. Later calls reuse it.

### The schema registry

Register a schema once and every later path that logs that class picks it up:

```kotlin
// Register once during initialization
FateSchema.registerSchema<Pose2d>(customPoseSchema)

// Every Pose2d you log from now on uses it
Fate.log("Robot/Pose", drive.pose)
```

## Where to next

1. [Installation](installation.md) adds FateWeaver to your FTC project.
2. [Getting started](getting-started.md) walks through your first logging OpMode.
3. [Schema system](schemas/index.md) explains how types become bytes.
4. [Log management](log-management.md) covers downloading and viewing your data.

## Links

- [API JavaDocs](https://javadoc.io/doc/gay.zharel.fateweaver/ftc/latest/)
- [GitHub](https://github.com/hermesftc/fateweaver/)
- [Issues](https://github.com/hermesftc/fateweaver/issues)
- [Discord](https://discord.gg/49C5epU22h)

---

*FateWeaver is developed and maintained by the FTC community for the FTC community.*
