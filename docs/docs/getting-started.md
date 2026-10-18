# Getting started

This page goes from one logged number to your own classes.

## Your first log

Log the battery voltage every loop:

=== "Kotlin"

    ```kotlin
    import gay.zharel.fateweaver.telemetry.Fate
    import gay.zharel.fateweaver.telemetry.log

    @TeleOp(name = "Basic Logging")
    class BasicLoggingOpMode : OpMode() {
        private lateinit var battery: VoltageSensor

        override fun init() {
            battery = hardwareMap.voltageSensor.iterator().next()
        }

        override fun loop() {
            Fate.log("Battery/Voltage", battery.voltage)
            telemetry.addData("Voltage", battery.voltage)
        }
    }
    ```

=== "Java"

    ```java
    import gay.zharel.fateweaver.telemetry.Fate;

    @TeleOp(name = "Basic Logging")
    public class BasicLoggingOpMode extends OpMode {
        private VoltageSensor battery;

        @Override
        public void init() {
            battery = hardwareMap.voltageSensor.iterator().next();
        }

        @Override
        public void loop() {
            Fate.log("Battery/Voltage", battery.getVoltage());
            telemetry.addData("Voltage", battery.getVoltage());
        }
    }
    ```

Each `Fate.log` call appends one value to the log file for the running OpMode, stamped with the time
you logged it. In Kotlin, import the `log` extension next to `Fate`, or the call will not compile.

You do not create channels or declare anything up front. The first `Fate.log("Battery/Voltage", ...)`
creates the path, and later calls add values to it.

A path keeps the type of its first value. If you log a `String` to a path that holds `Double`s,
FateWeaver warns once and skips that value. Logging never throws into your robot code.

## Where the log goes

On a robot, `FateLogManager` runs without any setup from you. It creates a log file for each OpMode
and routes everything you log through `Fate` into it.

- Files go to the `FateWeaver/Logs` folder on the Robot Controller.
- Each name starts with a date and time, followed by the OpMode name, and ends in `.fate`.
- Once the folder passes 250 MB, FateWeaver deletes the oldest logs first.
- Values you log while no OpMode is running are not recorded.
- Every log gets an `OpMode/Event` channel with `PRE_INIT`, `PRE_START` and `POST_STOP`.

FateWeaver also streams the log to AdvantageScope over port 8110 while the OpMode runs.

!!! warning "Live streaming is banned at competition"
    The 2026-2027 FTC manual bans live streaming at competition. Set
    `FateLogManager.mode = LogMode.FILE` before you compete. Both classes are in
    `gay.zharel.fateweaver.flight`. The log file is still written.

## Logging robot data

For a group of related values, ask `Fate` for a table once and log into it. Tables are cheap, so
hold one in a field.

=== "Kotlin"

    ```kotlin
    @TeleOp(name = "Robot Data Logging")
    class RobotLoggingOpMode : OpMode() {
        private lateinit var leftMotor: DcMotor
        private lateinit var rightMotor: DcMotor
        private lateinit var gyro: IMU

        private val motors = Fate.table("Motors")
        private val sensors = Fate.table("Sensors")

        override fun init() {
            leftMotor = hardwareMap.get(DcMotor::class.java, "leftMotor")
            rightMotor = hardwareMap.get(DcMotor::class.java, "rightMotor")
            gyro = hardwareMap.get(IMU::class.java, "imu")

            telemetry.addData("Status", "Initialized")
        }

        override fun loop() {
            val drive = -gamepad1.left_stick_y.toDouble()
            val turn = gamepad1.right_stick_x.toDouble()

            leftMotor.power = drive + turn
            rightMotor.power = drive - turn

            motors.log("Left/Position", leftMotor.currentPosition)
            motors.log("Right/Position", rightMotor.currentPosition)
            sensors.log("Gyro/Heading", gyro.robotYawPitchRollAngles.getYaw(AngleUnit.DEGREES))

            telemetry.addData("Left Position", leftMotor.currentPosition)
            telemetry.addData("Right Position", rightMotor.currentPosition)
        }
    }
    ```

=== "Java"

    ```java
    @TeleOp(name = "Robot Data Logging")
    public class RobotLoggingOpMode extends OpMode {
        private DcMotor leftMotor, rightMotor;
        private IMU gyro;

        private final FateTable motors = Fate.table("Motors");
        private final FateTable sensors = Fate.table("Sensors");

        @Override
        public void init() {
            leftMotor = hardwareMap.get(DcMotor.class, "leftMotor");
            rightMotor = hardwareMap.get(DcMotor.class, "rightMotor");
            gyro = hardwareMap.get(IMU.class, "imu");

            telemetry.addData("Status", "Initialized");
        }

        @Override
        public void loop() {
            double drive = -gamepad1.left_stick_y;
            double turn = gamepad1.right_stick_x;

            leftMotor.setPower(drive + turn);
            rightMotor.setPower(drive - turn);

            motors.log("Left/Position", leftMotor.getCurrentPosition());
            motors.log("Right/Position", rightMotor.getCurrentPosition());
            sensors.log("Gyro/Heading", gyro.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES));

            telemetry.addData("Left Position", leftMotor.getCurrentPosition());
            telemetry.addData("Right Position", rightMotor.getCurrentPosition());
        }
    }
    ```

`FateTable` lives in `gay.zharel.fateweaver.telemetry`, next to `Fate`. Java boxes the primitives for
you.

## Logging through FTC telemetry

If your code already calls `telemetry.addData`, you can send those items to the log without
rewriting them. `FateFtcTelemetry` implements the FTC SDK `Telemetry` interface, so you can hand it
to anything that takes one, such as FTC Dashboard's `MultipleTelemetry`:

=== "Kotlin"

    ```kotlin
    telemetry = MultipleTelemetry(telemetry, FateFtcTelemetry)
    ```

=== "Java"

    ```java
    telemetry = new MultipleTelemetry(telemetry, FateFtcTelemetry.INSTANCE);
    ```

It behaves like the SDK's telemetry. `addData` only queues an item, and nothing is logged until
`update()`, which evaluates every value producer and logs each item under its caption. A caption
with slashes, like `"Drive/leftPower"`, goes into the matching table.

- An item with a format is logged as the formatted string. Otherwise the raw value is logged and
  its schema is inferred. `null` values are skipped.
- `telemetry.log()` messages go to the string channel `Telemetry/Log`.
- Display settings (separators, display format, transmission interval) are stored but do nothing,
  and `speak` does nothing.
- A value producer that throws is skipped with a warning instead of stopping your loop.

`FateFtcTelemetry` is in `gay.zharel.fateweaver.flight`.

### Showing logged values on the Driver Station

`FtcTelemetryBackend` goes the other way. It is a backend that sends everything you log through
`Fate` to an FTC `Telemetry`, so values you log once also show up on the Driver Station:

=== "Kotlin"

    ```kotlin
    Fate.registry.register(FtcTelemetryBackend(telemetry))
    ```

=== "Java"

    ```java
    Fate.getRegistry().register(new FtcTelemetryBackend(telemetry));
    ```

Each logged value calls `telemetry.addData(path, value)` with its full path as the caption. You
still call `telemetry.update()` yourself to push them to the screen. The value is passed as is, so
there is no schema or number formatting. Remove it with `Fate.registry.unregister(backend)`. It is
in `gay.zharel.fateweaver.flight`.

!!! warning "Don't use both on one telemetry"
    Registering `FtcTelemetryBackend` on a telemetry that also contains `FateFtcTelemetry`, for
    example through `MultipleTelemetry`, logs each value into itself. Use one direction or the other.

## Path naming convention

Slashes in a path become folders in AdvantageScope. A table named `Motors` and a name of
`Left/Position` give the path `Motors/Left/Position`. Tables nest too, so
`Fate.table("Motors/Left")` is the same as `Fate.table("Motors").table("Left")`. The example above
produces this tree:

```
Motors
  Left
    Position
  Right
    Position
Sensors
  Gyro
    Heading
```

Start with a broad category such as `Motors`, `Sensors` or `Robot`, then get specific. Keep the last
part short, since the folders already carry the context. `Position` reads better than
`CurrentEncoderPosition` when it already sits under `Motors/Left`. Pick one casing and keep it,
because the tree sorts as text.

## Logging complex objects

You can log a whole object the same way you log a double. Here is a `Pose2d`:

=== "Kotlin"

    ```kotlin
    class DriveLoggingOpMode : OpMode() {
        private lateinit var drive: MecanumDrive
        private val robot = Fate.table("Robot")

        override fun init() {
            drive = MecanumDrive(hardwareMap, Pose2d(0.0, 0.0, 0.0))
        }

        override fun loop() {
            drive.setDrivePowers(PoseVelocity2d(
                Vector2d(-gamepad1.left_stick_y.toDouble(), -gamepad1.left_stick_x.toDouble()),
                -gamepad1.right_stick_x.toDouble()
            ))

            val velocity = drive.updatePoseEstimate()

            robot.log("Pose", drive.pose)
            robot.log("Velocity", velocity)

            telemetry.addData("X", drive.pose.position.x)
            telemetry.addData("Y", drive.pose.position.y)
            telemetry.addData("Heading", Math.toDegrees(drive.pose.heading.toDouble()))
        }
    }
    ```

=== "Java"

    ```java
    public class DriveLoggingOpMode extends OpMode {
        private MecanumDrive drive;
        private final FateTable robot = Fate.table("Robot");

        @Override
        public void init() {
            drive = new MecanumDrive(hardwareMap, new Pose2d(0.0, 0.0, 0.0));
        }

        @Override
        public void loop() {
            drive.setDrivePowers(new PoseVelocity2d(
                new Vector2d(-gamepad1.left_stick_y, -gamepad1.left_stick_x),
                -gamepad1.right_stick_x
            ));

            PoseVelocity2d velocity = drive.updatePoseEstimate();

            robot.log("Pose", drive.getPose());
            robot.log("Velocity", velocity);

            telemetry.addData("X", drive.getPose().position.x);
            telemetry.addData("Y", drive.getPose().position.y);
            telemetry.addData("Heading", Math.toDegrees(drive.getPose().heading.toDouble()));
        }
    }
    ```

!!! info "No schema needed"
    FateWeaver reflects over the public properties of the class you log and builds the schema from
    them. The first log to a path fixes that schema. To use your own, pass it as a third argument,
    as in `robot.log("Pose", drive.pose, mySchema)`.

Kotlin keeps type arguments, so `table.log("powers", mapOf("fl" to 0.5))` logs a
`Map<String, Double>`. Java's two-argument `log` only sees the runtime class and cannot log a `Map`
or a `List`. In Java, pass a schema:

```java
table.log("powers", powers, new MapSchema<>(StringSchema.INSTANCE, DoubleSchema.INSTANCE));
```

## Custom data classes

Your own classes work the same way. Group values you always want at the same moment:

=== "Kotlin"

    ```kotlin
    data class RobotState(
        val batteryVoltage: Double,
        val temperature: Double,
        val isAutonomous: Boolean,
        val alliance: String
    )

    class StateLoggingOpMode : OpMode() {
        private lateinit var voltageSensor: VoltageSensor
        private val robot = Fate.table("Robot")

        override fun init() {
            voltageSensor = hardwareMap.voltageSensor.iterator().next()
        }

        override fun loop() {
            val currentState = RobotState(
                batteryVoltage = voltageSensor.voltage,
                temperature = 25.0, // placeholder value
                isAutonomous = false,
                alliance = "Red"
            )

            robot.log("State", currentState)

            telemetry.addData("Battery", "%.1f V", currentState.batteryVoltage)
            telemetry.addData("Temperature", "%.1f°C", currentState.temperature)
        }
    }
    ```

=== "Java"

    ```java
    public class RobotState {
        public final double batteryVoltage;
        public final double temperature;
        public final boolean isAutonomous;
        public final String alliance;

        public RobotState(double batteryVoltage, double temperature,
                         boolean isAutonomous, String alliance) {
            this.batteryVoltage = batteryVoltage;
            this.temperature = temperature;
            this.isAutonomous = isAutonomous;
            this.alliance = alliance;
        }
    }

    public class StateLoggingOpMode extends OpMode {
        private VoltageSensor voltageSensor;
        private final FateTable robot = Fate.table("Robot");

        @Override
        public void init() {
            voltageSensor = hardwareMap.voltageSensor.iterator().next();
        }

        @Override
        public void loop() {
            RobotState currentState = new RobotState(
                voltageSensor.getVoltage(),
                25.0, // placeholder value
                false,
                "Red"
            );

            robot.log("State", currentState);

            telemetry.addData("Battery", "%.1f V", currentState.batteryVoltage);
            telemetry.addData("Temperature", "%.1f°C", currentState.temperature);
        }
    }
    ```

The class has no timestamp field. FateWeaver stamps every logged value itself, so you never log a
time by hand.

### Spreading an object across several paths

If you want each field on its own path, implement `FateLoggable` and log the object with
`Fate.log`. Its values land under the name you pass.

=== "Kotlin"

    ```kotlin
    class DriveState(val left: Double, val right: Double, val pose: Pose2d) : FateLoggable {
        override fun logTo(table: FateTable) {
            table.log("left", left)
            table.log("right", right)
            table.log("pose", pose)
        }
    }

    // Lands at drive/left, drive/right and drive/pose
    Fate.log("drive", DriveState(leftPower, rightPower, drive.pose))
    ```

=== "Java"

    ```java
    public class DriveState implements FateLoggable {
        private final double left, right;
        private final Pose2d pose;

        public DriveState(double left, double right, Pose2d pose) {
            this.left = left;
            this.right = right;
            this.pose = pose;
        }

        @Override
        public void logTo(FateTable table) {
            table.log("left", left);
            table.log("right", right);
            table.log("pose", pose);
        }
    }

    // Lands at drive/left, drive/right and drive/pose
    Fate.log("drive", new DriveState(leftPower, rightPower, drive.getPose()));
    ```

The fields are separate writes. A reader can see some from this loop and some from the last one.

## Performance considerations

### Log slow values less often

A loop running at 50 Hz does not need every path written 50 times a second. There is no built-in
downsampling, so gate the call yourself:

```kotlin
private val timer = ElapsedTime()
private val robot = Fate.table("Robot")

override fun loop() {
    Fate.log("FastData", System.nanoTime() / 1e9)  // every loop

    if (timer.milliseconds() >= 100) {              // ten times a second
        robot.log("Pose", drive.pose)
        timer.reset()
    }
}
```

### Group values you always log together

Every message entry carries 16 bytes of overhead before your data. That is a 4-byte entry type, a
4-byte channel index and an 8-byte timestamp. Three separate double paths logged each loop cost
3 × (16 + 8) = 72 bytes. The same three doubles in one class cost 16 + 24 = 40 bytes.

```kotlin
// One path, one entry per loop
data class SensorData(val gyro: Double, val accel: Double, val voltage: Double)
Fate.log("Sensors", SensorData(gyro, accel, voltage))

// Three paths, three entries per loop
Fate.log("Gyro", gyro)
Fate.log("Accel", accel)
Fate.log("Voltage", voltage)
```

Separate paths cost more space, but you can plot and throttle each one on its own. Group values only
when you always want them at the same timestamp.
