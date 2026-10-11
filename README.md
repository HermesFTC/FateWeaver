# FateWeaver

![Maven Central Version](https://img.shields.io/maven-central/v/gay.zharel.fateweaver/ftc?style=for-the-badge&label=Latest%20Version)

FateWeaver records data from your FTC robot to a log file and shows it in AdvantageScope.
You call `Fate.log` with a name and a value, and FateWeaver writes one log file for each OpMode.

The guides are at [fate.zharel.gay](https://fate.zharel.gay/) and the API reference is at [fate.zharel.gay/api](https://fate.zharel.gay/api/).

## Installation

FateWeaver is on Maven Central. Add it to your `TeamCode` dependencies.

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

> **Note:** Replace `VERSION` with the latest version shown in the badge above, without the `v` prefix.

## Features

- Log numbers, strings, booleans, arrays, maps and your own classes with one call.
- Group values into tables like `Drive/leftVelocity`, which show up as folders in AdvantageScope.
- A log file for every OpMode, with old logs deleted once the folder passes 250 MB.
- Live streaming to AdvantageScope while the file is still being recorded.
- A web dashboard on the Robot Controller for downloading logs.
- Custom serialization with `CustomStructSchema`.

## Usage

There is nothing to set up. FateWeaver opens a log when an OpMode initializes and closes it when the OpMode stops.

### Kotlin

```kotlin
import gay.zharel.fateweaver.telemetry.Fate
import gay.zharel.fateweaver.telemetry.log

class MyOpMode : OpMode() {
    private val drive = Fate.table("Drive")

    override fun init() {}

    override fun loop() {
        Fate.log("Battery/Voltage", hardwareMap.voltageSensor.iterator().next().voltage)
        drive.log("leftVelocity", leftMotor.velocity)
    }
}
```

### Java

```java
import gay.zharel.fateweaver.telemetry.Fate;
import gay.zharel.fateweaver.telemetry.FateTable;

public class MyOpMode extends OpMode {
    private final FateTable drive = Fate.table("Drive");

    @Override
    public void init() {}

    @Override
    public void loop() {
        Fate.log("Battery/Voltage", hardwareMap.voltageSensor.iterator().next().getVoltage());
        drive.log("leftVelocity", leftMotor.getVelocity());
    }
}
```

A path is created by its first log and keeps the type of that first value. If you log a different type to the same path later, FateWeaver prints one warning and skips the value. Logging never throws an exception into your robot code.

Tables are cheap, so keep one in a field. A name like `"Drive/Odometry"` makes a table inside a table.

Kotlin's `log` keeps type arguments, so `drive.log("powers", mapOf("fl" to 0.5))` logs a `Map<String, Double>`. Java can only see the runtime class, so it cannot infer a map or a list. Pass a schema for those:

```java
drive.log("powers", powers, new MapSchema<>(StringSchema.INSTANCE, DoubleSchema.INSTANCE));
```

The Kotlin `log` is an extension function, so you must import `gay.zharel.fateweaver.telemetry.log`.

A class that is really several values can implement `FateLoggable` and write each value itself. `Fate.log("drive", driveState)` then logs everything under `drive/`.

For custom schemas, `AS_TYPE` and `CustomStructSchema`, see the schemas page in the docs.

## Where logs go

Logs are saved on the Robot Controller in `FateWeaver/Logs`. Each file is named `yyyy_MM_dd__HH_mm_ss_SSS__<OpModeName>.fate`. Once the folder passes 250 MB, FateWeaver deletes the oldest logs first.

Values logged while no OpMode is running are not recorded. Each log also has an `OpMode/Event` channel with `PRE_INIT`, `PRE_START` and `POST_STOP`.

## Viewing logs

1. Connect your computer to the robot's WiFi network.
2. Open `http://192.168.43.1:8080/fate` in a browser. On a phone Robot Controller, use `192.168.49.1`.
3. Download a log.
4. Open it in AdvantageScope with `File` → `Open Log(s)...`.

The log format is version 3, so you need an AdvantageScope build with FateWeaver v3 support.

## Live streaming

`FlightProjector` streams the log to AdvantageScope over a WebSocket on port 8110 while the OpMode runs. It sends the same bytes as the `.fate` file, and clients that connect late get the schemas replayed. In AdvantageScope, set the robot address and choose `File` → `Connect to Robot` → `FateWeaver`.

> [!WARNING]
> The FTC 2026-2027 competition manual prohibits live streaming from third-party libraries during
> competition. FlightProjector is for testing and tuning only. Turn it off with the dashboard toggle
> or the "Enable/Disable FateWeaver Live" OpMode before you compete. Both close the server and
> are remembered across restarts.

To stop FateWeaver from starting the projector in code, set `FateLogManager.mode = LogMode.FILE`. This is a runtime variable, not a compile-time switch. Logs are still recorded to files. Only the dashboard toggle and the OpMode close the server for sure, because `FlightProjector.setEnabled(true)` can start it again.

See [Log management](docs/docs/log-management.md) for the details.

## Custom web routes

`FateRouter` wraps the FTC SDK's NanoHTTPD web server, so you can add your own routes to the robot.

```kotlin
manager.route {
    get("/api/status") { req, res ->
        res.json("{\"status\": \"ok\", \"battery\": 12.4}")
    }

    get("/api/download") { req, res ->
        val fileName = req.query("file") ?: return@get res.badRequest("Missing 'file' parameter")
        res.download(File(LOG_ROOT, fileName))
    }
}
```

## Documentation

- [Installation](docs/docs/installation.md)
- [Getting started](docs/docs/getting-started.md)
- [Schemas](docs/docs/schemas/index.md)
- [Log management](docs/docs/log-management.md)

## Development

To build FateWeaver from source, install [Bun](https://bun.sh/). The Gradle build runs `bun install` and `bun run build` in `frontend/`, and the Vue dashboard it produces is bundled into the Android library. The build fails if Bun is missing. See `frontend/README.md` for working on the dashboard.
