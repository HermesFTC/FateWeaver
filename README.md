# FateWeaver

![Maven Central Version](https://img.shields.io/maven-central/v/gay.zharel.fateweaver/ftc?style=for-the-badge&label=Latest%20Version)

A RoadRunner-log based data logging library for FTC robotics.
Check out the [API docs](https://javadoc.io/doc/gay.zharel.fateweaver/ftc/latest/ftc/gay.zharel.hermes.fateweaver/index.html)!

## Installation

FateWeaver is available on Maven Central. Add the following dependencies to your project:

### Gradle (Kotlin DSL)
```kotlin
dependencies {
    implementation("gay.zharel.fateweaver:ftc:VERSION")
}
```

### Gradle (Groovy DSL)
```gradle
dependencies {
    implementation 'gay.zharel.fateweaver:ftc:VERSION'
}
```

> **Note:** Replace `VERSION` with the latest version displayed above
> (without the `v` prefix).

## Features

- Easy-to-use logging API with `FlightRecorder` and `FlightLogChannel`!
- Type-safe channels, including support for custom data types using reflection!
- Downloading logs to your computer for viewing with AdvantageScope!
- Backwards-compatibility with existing RoadRunner FlightRecorder API.
- Support for typed classes with `AS_TYPE` and fully customizable serialization with `CustomStructSchema`!

## Usage

This OpMode uses FateWeaver to log the robot's pose and velocity;
while it uses classes in the RoadRunner library/quickstart,
neither of them are required.

```java
public class MyOpMode extends OpMode {
    MecanumDrive drive;
    FlightLogChannel<Long> timestamps;
    FlightLogChannel<Pose2d> poses;

    @Override
    public void init() {
        drive = new MecanumDrive(hardwareMap, new Pose2d(0.0, 0.0, 0.0));
        timestamps = FlightRecorder.createChannel("TIMESTAMP", LongSchema.INSTANCE);
        poses = FlightRecorder.createChannel("Robot/Pose", Pose2d.class);
    }

    @Override
    public void loop() {
        drive.setDrivePowers(/* stuff */);
        PoseVelocity2d velocity = drive.updatePoseEstimate();
        
        timestamps.put(System.nanoTime());
        FlightRecorder.write('Robot/Velocity', velocity);
        poses.put(drive.localizer.getPose());
    }
}
```

## Advanced Schema Features

### Typed Classes with AS_TYPE

FateWeaver automatically detects classes with a companion object containing an `AS_TYPE` property
(or a static `AS_TYPE` field for Java classes)
and creates enhanced schemas that include type information in the serialized data.
This is useful for polymorphic logging and schema evolution.

AdvantageScope will automatically use this information to display the correct type in the UI.

```kotlin
data class RobotCommand(
    val type: String,
    val timestamp: Long,
    val parameters: Map<String, String>
) {
    companion object {
        val AS_TYPE = "RobotCommand"  // This triggers TypedClassSchema
    }
}

// Usage
val commandChannel = FlightRecorder.createChannel("commands", RobotCommand::class.java)
commandChannel.put(RobotCommand("MOVE_FORWARD", System.nanoTime(), mapOf("speed" to "0.8")))
```

### Custom Serialization with CustomStructSchema

For maximum flexibility, you can define exactly how your objects are serialized using `CustomStructSchema`.
This is particularly useful for:
- Legacy classes that can't be modified
- Optimized encoding to reduce log file size
- Data transformation during serialization
- Including only specific fields

```kotlin
// Transform and optimize data during serialization
data class SensorReading(val timestamp: Long, val value: Double, val sensorId: String, val metadata: Map<String, Any>)

val optimizedSensorSchema = CustomStructSchema<SensorReading>(
    type = "OptimizedSensor",
    componentNames = listOf("time", "reading", "sensor"),
    componentSchemas = listOf(LongSchema, DoubleSchema, StringSchema),
    encoder = { reading ->
        listOf(
            reading.timestamp / 1000000,  // Convert nanoseconds to milliseconds
            String.format("%.3f", reading.value).toDouble(),  // Round to 3 decimal places
            reading.sensorId.take(8)  // Truncate sensor ID to save space
        )
    }
)

// Usage with custom schema
val sensorChannel = writer.createChannel("sensors", optimizedSensorSchema)
sensorChannel.put(SensorReading(System.nanoTime(), 3.14159265, "GYRO_SENSOR_001", mapOf()))
```

#### Coordinate System Transformations

```kotlin
data class RobotPose(val x: Double, val y: Double, val heading: Double)

val fieldCentricSchema = CustomStructSchema<RobotPose>(
    type = "FieldCentricPose",
    componentNames = listOf("fieldX", "fieldY", "fieldHeading"),
    componentSchemas = listOf(DoubleSchema, DoubleSchema, DoubleSchema),
    encoder = { pose ->
        // Transform robot-centric coordinates to field-centric
        val fieldX = pose.x + FIELD_OFFSET_X
        val fieldY = pose.y + FIELD_OFFSET_Y
        val fieldHeading = normalizeAngle(pose.heading)
        listOf(fieldX, fieldY, fieldHeading)
    }
)
```

#### Selective Field Logging

```java
class Pose2d {
    public Vector2d position;
    public Rotation2d heading;
    
    public static final CustomStructSchema<Pose2d> SCHEMA = new CustomStructSchema<>(
            "Pose2d",
            List.of("x", "y", "heading"),
            List.of(DoubleSchema.INSTANCE, DoubleSchema.INSTANCE, DoubleSchema.INSTANCE),
            pose -> List.of(pose.position.x, pose.position.y, pose.heading.toDouble())
    );
}
```

## Schema Registry

FateWeaver maintains a global schema registry that automatically caches and reuses schemas for classes.
When you call `FateSchema.schemaOfClass()` or use a class directly with `FlightRecorder.createChannel()`,
the library automatically creates an appropriate schema and stores it in the registry.

### Registering Custom Schemas

You can register custom schemas globally using `FateSchema.registerSchema()`.
Once registered,
your custom schema will be used automatically whenever that class type is logged,
without needing to explicitly pass the schema to each channel creation.

This is particularly useful for:
- Defining optimized serialization for frequently-used classes
- Overriding the default reflection-based schema
- Ensuring consistent serialization across your entire codebase

#### Kotlin Example

```kotlin
// Define your custom schema once
val optimizedPoseSchema = CustomStructSchema<Pose2d>(
    type = "Pose2d",
    componentNames = listOf("x", "y", "heading"),
    componentSchemas = listOf(DoubleSchema, DoubleSchema, DoubleSchema),
    encoder = { pose -> 
        listOf(pose.position.x, pose.position.y, pose.heading.toDouble())
    }
)

// Register it globally (typically in your initialization code)
FateSchema.registerSchema<Pose2d>(optimizedPoseSchema)

// Now all channels using Pose2d will automatically use your custom schema
val poseChannel = FlightRecorder.createChannel("Robot/Pose", Pose2d::class.java)
// Uses optimizedPoseSchema automatically!
```

#### Java Example

```java
public class MyOpMode extends LinearOpMode {
    @Override
    public void runOpMode() {
        // Register custom schema at initialization
        CustomStructSchema<Pose2d> poseSchema = new CustomStructSchema<>(
            "Pose2d",
            List.of("x", "y", "heading"),
            List.of(DoubleSchema.INSTANCE, DoubleSchema.INSTANCE, DoubleSchema.INSTANCE),
            pose -> List.of(pose.position.x, pose.position.y, pose.heading.toDouble())
        );
        
        FateSchema.registerSchema(Pose2d.class, poseSchema);
        
        // All channels created after registration use the custom schema
        FlightLogChannel<Pose2d> poseChannel = FlightRecorder.createChannel("Robot/Pose", Pose2d.class);
        // Automatically uses poseSchema!
    }
}
```

## Downloading Logs

To download logs to your computer, simply connect your computer to the robot's WiFi network,
and go to `192.168.43.1:8080/fate/logs` in your browser.