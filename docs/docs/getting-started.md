# Getting Started

This guide will walk you through your first FateWeaver implementation, from basic logging to more advanced features. By the end, you'll have a solid understanding of how to integrate FateWeaver into your FTC robot code.

## Your First Log

Let's start with the simplest possible example - logging a timestamp:

=== "Kotlin"

    ```kotlin
    @TeleOp(name = "Basic Logging")
    class BasicLoggingOpMode : OpMode() {
        private lateinit var timestampChannel: FlightLogChannel<Long>

        override fun init() {
            timestampChannel = FlightRecorder.createChannel("timestamp", LongSchema.INSTANCE)
            telemetry.addData("Status", "Ready to log!")
        }

        override fun loop() {
            timestampChannel.put(System.nanoTime())
            telemetry.addData("Logging", "Current time: ${System.nanoTime()}")
        }
    }
    ```

=== "Java"

    ```java
    @TeleOp(name = "Basic Logging")
    public class BasicLoggingOpMode extends OpMode {
        private FlightLogChannel<Long> timestampChannel;

        @Override
        public void init() {
            timestampChannel = FlightRecorder.createChannel("timestamp", LongSchema.INSTANCE);
            telemetry.addData("Status", "Ready to log!");
        }

        @Override
        public void loop() {
            timestampChannel.put(System.nanoTime());
            telemetry.addData("Logging", "Current time: " + System.nanoTime());
        }
    }
    ```

!!! success "That's it!"
    You're now logging data with FateWeaver! Every call to `timestampChannel.put()` writes a timestamped entry to your log file.

## Logging Robot Data

Let's expand to log some actual robot data. Here's how to log motor positions and sensor values:

=== "Kotlin"

    ```kotlin
    @TeleOp(name = "Robot Data Logging")
    class RobotLoggingOpMode : OpMode() {
        // Hardware
        private lateinit var leftMotor: DcMotor
        private lateinit var rightMotor: DcMotor
        private lateinit var gyro: IMU

        // Log channels
        private lateinit var leftPosChannel: FlightLogChannel<Int>
        private lateinit var rightPosChannel: FlightLogChannel<Int>
        private lateinit var gyroChannel: FlightLogChannel<Double>

        override fun init() {
            // Initialize hardware
            leftMotor = hardwareMap.get(DcMotor::class.java, "leftMotor")
            rightMotor = hardwareMap.get(DcMotor::class.java, "rightMotor")
            gyro = hardwareMap.get(IMU::class.java, "imu")

            // Create log channels
            leftPosChannel = FlightRecorder.createChannel("Motors/Left/Position", IntSchema.INSTANCE)
            rightPosChannel = FlightRecorder.createChannel("Motors/Right/Position", IntSchema.INSTANCE)
            gyroChannel = FlightRecorder.createChannel("Sensors/Gyro/Heading", DoubleSchema.INSTANCE)

            telemetry.addData("Status", "Initialized with logging")
        }

        override fun loop() {
            // Your regular robot code
            val drive = -gamepad1.left_stick_y.toDouble()
            val turn = gamepad1.right_stick_x.toDouble()

            leftMotor.power = drive + turn
            rightMotor.power = drive - turn

            // Log the data
            leftPosChannel.put(leftMotor.currentPosition)
            rightPosChannel.put(rightMotor.currentPosition)
            gyroChannel.put(gyro.robotYawPitchRollAngles.getYaw(AngleUnit.DEGREES))

            // Display current values
            telemetry.addData("Left Position", leftMotor.currentPosition)
            telemetry.addData("Right Position", rightMotor.currentPosition)
            telemetry.addData("Heading", gyro.robotYawPitchRollAngles.getYaw(AngleUnit.DEGREES))
        }
    }
    ```

=== "Java"

    ```java
    @TeleOp(name = "Robot Data Logging")
    public class RobotLoggingOpMode extends OpMode {
        // Hardware
        private DcMotor leftMotor, rightMotor;
        private IMU gyro;

        // Log channels
        private FlightLogChannel<Integer> leftPosChannel;
        private FlightLogChannel<Integer> rightPosChannel;
        private FlightLogChannel<Double> gyroChannel;

        @Override
        public void init() {
            // Initialize hardware
            leftMotor = hardwareMap.get(DcMotor.class, "leftMotor");
            rightMotor = hardwareMap.get(DcMotor.class, "rightMotor");
            gyro = hardwareMap.get(IMU.class, "imu");

            // Create log channels
            leftPosChannel = FlightRecorder.createChannel("Motors/Left/Position", IntSchema.INSTANCE);
            rightPosChannel = FlightRecorder.createChannel("Motors/Right/Position", IntSchema.INSTANCE);
            gyroChannel = FlightRecorder.createChannel("Sensors/Gyro/Heading", DoubleSchema.INSTANCE);

            telemetry.addData("Status", "Initialized with logging");
        }

        @Override
        public void loop() {
            // Your regular robot code
            double drive = -gamepad1.left_stick_y;
            double turn = gamepad1.right_stick_x;

            leftMotor.setPower(drive + turn);
            rightMotor.setPower(drive - turn);

            // Log the data
            leftPosChannel.put(leftMotor.getCurrentPosition());
            rightPosChannel.put(rightMotor.getCurrentPosition());
            gyroChannel.put(gyro.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES));

            // Display current values
            telemetry.addData("Left Position", leftMotor.getCurrentPosition());
            telemetry.addData("Right Position", rightMotor.getCurrentPosition());
            telemetry.addData("Heading", gyro.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES));
        }
    }
    ```

## Channel Naming Convention

Notice the hierarchical naming in the example above (`Motors/Left/Position`, `Sensors/Gyro/Heading`). This creates a organized structure in AdvantageScope:

```
📁 Motors
  📁 Left
    📊 Position
  📁 Right
    📊 Position
📁 Sensors
  📁 Gyro
    📊 Heading
```

!!! tip "Naming Best Practices"
    - Use forward slashes (`/`) to create folders
    - Start with broad categories (`Motors`, `Sensors`, `Robot`)
    - Be specific but concise (`Position` not `CurrentEncoderPosition`)
    - Use consistent casing (PascalCase recommended)

## Logging Complex Objects

FateWeaver shines when logging complex data structures. Let's log a `Pose2d` object:

=== "Kotlin"

    ```kotlin
    class DriveLoggingOpMode : OpMode() {
        private lateinit var drive: MecanumDrive
        private lateinit var poseChannel: FlightLogChannel<Pose2d>
        private lateinit var velocityChannel: FlightLogChannel<PoseVelocity2d>

        override fun init() {
            drive = MecanumDrive(hardwareMap, Pose2d(0.0, 0.0, 0.0))

            // FateWeaver automatically creates schemas for RoadRunner classes
            poseChannel = FlightRecorder.createChannel("Robot/Pose", Pose2d::class.java)
            velocityChannel = FlightRecorder.createChannel("Robot/Velocity", PoseVelocity2d::class.java)
        }

        override fun loop() {
            // Update drivetrain
            drive.setDrivePowers(PoseVelocity2d(
                Vector2d(-gamepad1.left_stick_y.toDouble(), -gamepad1.left_stick_x.toDouble()),
                -gamepad1.right_stick_x.toDouble()
            ))

            val velocity = drive.updatePoseEstimate()

            // Log complex objects directly
            poseChannel.put(drive.pose)
            velocityChannel.put(velocity)

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
        private FlightLogChannel<Pose2d> poseChannel;
        private FlightLogChannel<PoseVelocity2d> velocityChannel;

        @Override
        public void init() {
            drive = new MecanumDrive(hardwareMap, new Pose2d(0.0, 0.0, 0.0));

            // FateWeaver automatically creates schemas for RoadRunner classes
            poseChannel = FlightRecorder.createChannel("Robot/Pose", Pose2d.class);
            velocityChannel = FlightRecorder.createChannel("Robot/Velocity", PoseVelocity2d.class);
        }

        @Override
        public void loop() {
            // Update drivetrain
            drive.setDrivePowers(new PoseVelocity2d(
                new Vector2d(-gamepad1.left_stick_y, -gamepad1.left_stick_x),
                -gamepad1.right_stick_x
            ));

            PoseVelocity2d velocity = drive.updatePoseEstimate();

            // Log complex objects directly
            poseChannel.put(drive.getPose());
            velocityChannel.put(velocity);

            telemetry.addData("X", drive.getPose().position.x);
            telemetry.addData("Y", drive.getPose().position.y);
            telemetry.addData("Heading", Math.toDegrees(drive.getPose().heading.toDouble()));
        }
    }
    ```

!!! info "Automatic Schema Generation"
    FateWeaver uses reflection to automatically create schemas for complex objects. No manual configuration needed!

## Custom Data Classes

You can also log your own custom classes:

=== "Kotlin"

    ```kotlin
    data class RobotState(
        val timestamp: Long,
        val batteryVoltage: Double,
        val temperature: Double,
        val isAutonomous: Boolean,
        val alliance: String
    )

    class StateLoggingOpMode : OpMode() {
        private lateinit var stateChannel: FlightLogChannel<RobotState>
        private lateinit var voltageSensor: VoltageSensor

        override fun init() {
            voltageSensor = hardwareMap.voltageSensor.iterator().next()
            stateChannel = FlightRecorder.createChannel("Robot/State", RobotState::class.java)
        }

        override fun loop() {
            val currentState = RobotState(
                timestamp = System.nanoTime(),
                batteryVoltage = voltageSensor.voltage,
                temperature = 25.0, // Mock temperature
                isAutonomous = false,
                alliance = "Red"
            )

            stateChannel.put(currentState)

            telemetry.addData("Battery", "%.1f V", currentState.batteryVoltage)
            telemetry.addData("Temperature", "%.1f°C", currentState.temperature)
        }
    }
    ```

=== "Java"

    ```java
    public class RobotState {
        public final long timestamp;
        public final double batteryVoltage;
        public final double temperature;
        public final boolean isAutonomous;
        public final String alliance;

        public RobotState(long timestamp, double batteryVoltage, double temperature,
                         boolean isAutonomous, String alliance) {
            this.timestamp = timestamp;
            this.batteryVoltage = batteryVoltage;
            this.temperature = temperature;
            this.isAutonomous = isAutonomous;
            this.alliance = alliance;
        }
    }

    public class StateLoggingOpMode extends OpMode {
        private FlightLogChannel<RobotState> stateChannel;
        private VoltageSensor voltageSensor;

        @Override
        public void init() {
            voltageSensor = hardwareMap.voltageSensor.iterator().next();
            stateChannel = FlightRecorder.createChannel("Robot/State", RobotState.class);
        }

        @Override
        public void loop() {
            RobotState currentState = new RobotState(
                System.nanoTime(),
                voltageSensor.getVoltage(),
                25.0, // Mock temperature
                false,
                "Red"
            );

            stateChannel.put(currentState);

            telemetry.addData("Battery", "%.1f V", currentState.batteryVoltage);
            telemetry.addData("Temperature", "%.1f°C", currentState.temperature);
        }
    }
    ```

## Performance Considerations

### Downsampling

For high-frequency data that doesn't need to be logged every loop, use downsampling:

```kotlin
class DownsampledLoggingOpMode : OpMode() {
    private lateinit var fastChannel: FlightLogChannel<Double>
    private lateinit var slowChannel: DownsampledWriter<Pose2d>

    override fun init() {
        fastChannel = FlightRecorder.createChannel("FastData", DoubleSchema.INSTANCE)

        val poseChannel = FlightRecorder.createChannel("Robot/Pose", Pose2d::class.java)
        slowChannel = poseChannel.downsample(100_000_000) // Log every 100ms
    }

    override fun loop() {
        // This logs every loop cycle
        fastChannel.put(System.nanoTime() / 1e9)

        // This logs at most every 100ms
        slowChannel.write(getCurrentPose())
    }
}
```

### Batch Operations

For multiple related values, consider using a single custom class instead of multiple channels:

!!! success "Efficient"
    ```kotlin
    data class SensorData(val gyro: Double, val accel: Double, val voltage: Double)
    val sensorChannel = FlightRecorder.createChannel("Sensors", SensorData::class.java)
    ```

!!! warning "Less Efficient"
    ```kotlin
    val gyroChannel = FlightRecorder.createChannel("Gyro", DoubleSchema.INSTANCE)
    val accelChannel = FlightRecorder.createChannel("Accel", DoubleSchema.INSTANCE)
    val voltageChannel = FlightRecorder.createChannel("Voltage", DoubleSchema.INSTANCE)
    ```

!!! tip "Start Simple"
    Begin with basic logging and gradually add more complex features as your needs grow. FateWeaver is designed to scale with your team's expertise!