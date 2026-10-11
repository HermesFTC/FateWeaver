# Installation

FateWeaver is published on Maven Central. You add it as one Gradle dependency.

### Finding the latest version

![Maven Central Version](https://img.shields.io/maven-central/v/gay.zharel.fateweaver/ftc?style=for-the-badge&label=Latest%20Version)

!!! tip "Version selection"
    Replace `VERSION` with the latest version shown in the badge above, **without** the `v` prefix. For example, if the badge shows `v0.3.2`, use `0.3.2` in your dependency declaration.

## FTC project integration

### Standard FTC project structure

If you use the standard FTC Robot Controller project structure, add the dependency to your `TeamCode/build.gradle` file:

=== "Kotlin DSL"

    ```kotlin
    // TeamCode/build.gradle.kts
    dependencies {
        implementation("gay.zharel.fateweaver:ftc:VERSION")

        // Your existing dependencies...
        implementation project(':FtcRobotController')
        // ... other dependencies
    }
    ```

=== "Groovy DSL"

    ```groovy
    // TeamCode/build.gradle
    dependencies {
        implementation 'gay.zharel.fateweaver:ftc:VERSION'

        // Your existing dependencies...
        implementation project(':FtcRobotController')
        // ... other dependencies
    }
    ```

### Coming from RoadRunner

FateWeaver is not a drop-in replacement for RoadRunner's `FlightRecorder`, and the `FlightRecorder`
API is gone. The log file format still shares its first schema tags with RoadRunner logs.

Add the dependency the same way:

```kotlin
dependencies {
    implementation("gay.zharel.fateweaver:ftc:VERSION")
}
```

Then change your logging calls. There is no `createChannel` step, so delete your channel fields and
log directly:

```kotlin
// Before, with RoadRunner's FlightRecorder
FlightRecorder.write("x", obj)

// After
Fate.log("x", obj)
```

In Kotlin, also import `gay.zharel.fateweaver.telemetry.Fate` and
`gay.zharel.fateweaver.telemetry.log`. You no longer log a `TIMESTAMP` channel either, because
FateWeaver stamps every value for you.

## Verification

Add this OpMode and run it. It logs one string each loop, so a log file with a `test` path means the
dependency resolved.

### Test installation

```java
@TeleOp(name = "FateWeaver Test")
public class FateWeaverTest extends OpMode {
    @Override
    public void init() {
        telemetry.addData("FateWeaver", "Ready");
    }

    @Override
    public void loop() {
        Fate.log("test", "Hello from FateWeaver!");
        telemetry.addData("Status", "Logging data...");
    }
}
```

Run the OpMode from the Driver Station, then stop it. FateWeaver writes the log to the
`FateWeaver/Logs` folder on the Robot Controller. The file name starts with a date and time and ends
in `.fate`.

### Import errors

If you see import errors, make sure your IDE has refreshed the Gradle project:

- **Android Studio**: Click "Sync Project with Gradle Files"
- **IntelliJ IDEA**: Click the Gradle refresh button or use `Ctrl+Shift+O` (Cmd+Shift+O on Mac)

### Getting help

If you still have issues:

1. Check the [GitHub Issues](https://github.com/hermesftc/fateweaver/issues) for similar problems.
2. Verify you are using a [supported version](#requirements).
3. Create a [new issue](https://github.com/hermesftc/fateweaver/issues/new) with your build configuration.

## Next steps

- [Getting started](getting-started.md) walks through your first logging OpMode.
- [Schema system](schemas.md) explains how types become bytes.
- [API JavaDocs](https://javadoc.io/doc/gay.zharel.fateweaver/ftc/latest/)
