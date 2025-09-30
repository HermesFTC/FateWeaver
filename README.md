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

## Downloading Logs

To download logs to your computer, simply connect your computer to the robot's WiFi network,
and go to `192.168.43.1:8080/fate/logs` in your browser.