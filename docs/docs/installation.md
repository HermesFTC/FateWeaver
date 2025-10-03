# Installation

FateWeaver is distributed through Maven Central, making it easy to add to any FTC project.

### Finding the Latest Version

![Maven Central Version](https://img.shields.io/maven-central/v/gay.zharel.fateweaver/ftc?style=for-the-badge&label=Latest%20Version)

!!! tip "Version Selection"
    Replace `VERSION` with the latest version shown in the badge above, **without** the `v` prefix. For example, if the badge shows `v0.3.2`, use `0.3.2` in your dependency declaration.

## FTC Project Integration

### Standard FTC Project Structure

If you're using the standard FTC Robot Controller project structure, add the dependency to your `TeamCode/build.gradle` file:

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

### RoadRunner Compatibility

FateWeaver is backwards compatible with RoadRunner's FlightRecorder API. 
If you're using RoadRunner, add the dependency to your `build.gradle` file:

```kotlin
dependencies {
    implementation("gay.zharel.fateweaver:ftc:VERSION")
}
```

Simply replace any imports for `com.acmerobotics.roadrunner.ftc.FlightRecorder` and replace them with 
`gay.zharel.fateweaver.FlightRecorder`.

## Verification

After adding the dependency, verify your installation by creating a simple test:

### Test Installation

Create a simple OpMode to verify FateWeaver is working:

```java
@TeleOp(name = "FateWeaver Test")
public class FateWeaverTest extends OpMode {
    private FlightLogChannel<String> testChannel;

    @Override
    public void init() {
        // This will succeed if FateWeaver is properly installed
        testChannel = FlightRecorder.createChannel("test", String.class);
        telemetry.addData("FateWeaver", "Successfully initialized!");
    }

    @Override
    public void loop() {
        testChannel.put("Hello from FateWeaver!");
        telemetry.addData("Status", "Logging data...");
    }
}
```

### Import Errors

If you see import errors, ensure your IDE has refreshed the Gradle project:

- **Android Studio**: Click "Sync Project with Gradle Files"
- **IntelliJ IDEA**: Click the Gradle refresh button or use `Ctrl+Shift+O` (Cmd+Shift+O on Mac)

### Getting Help

If you're still having issues:

1. Check the [GitHub Issues](https://github.com/hermesftc/fateweaver/issues) for similar problems
2. Verify you're using a [supported version](#requirements)
3. Create a [new issue](https://github.com/hermesftc/fateweaver/issues/new) with your build configuration

## Next Steps

Now that FateWeaver is installed, you're ready to start logging! Continue to:

- [**Getting Started**](getting-started.md) - Learn the basics of FateWeaver logging
- [**API Reference**](api-reference.md) - Explore the complete API