# Schema registry

The registry maps a class to the schema used for it, everywhere.

## Automatic registration

Creating a channel for a class looks in the registry first. On a miss, FateWeaver builds a schema by
reflection (a `TypedClassSchema` if the class has an `AS_TYPE` constant, otherwise a
`ReflectedClassSchema`), stores it, and hands back a channel using it. Every later channel for that
class reuses the same schema, so the reflection cost is paid once.

## Manual registration

Register your own schema to override the reflected default:

=== "Kotlin"

    ```kotlin
    // Define once
    val optimizedPoseSchema = CustomStructSchema<Pose2d>(
        type = "Pose2d",
        componentNames = listOf("x", "y", "theta"),
        componentSchemas = listOf(DoubleSchema.INSTANCE, DoubleSchema.INSTANCE, DoubleSchema.INSTANCE),
        encoder = { pose -> listOf(pose.position.x, pose.position.y, pose.heading.toDouble()) }
    )

    // Register globally
    FateSchema.registerSchema(Pose2d::class.java, optimizedPoseSchema)

    // All future channels automatically use your custom schema
    val poseChannel1 = FlightRecorder.createChannel("Robot/Pose", Pose2d::class.java)
    val poseChannel2 = FlightRecorder.createChannel("Target/Pose", Pose2d::class.java)
    // Both use optimizedPoseSchema
    ```

=== "Java"

    ```java
    // Define once
    CustomStructSchema<Pose2d> optimizedPoseSchema = new CustomStructSchema<>(
        "Pose2d",
        Arrays.asList("x", "y", "theta"),
        Arrays.asList(DoubleSchema.INSTANCE, DoubleSchema.INSTANCE, DoubleSchema.INSTANCE),
        pose -> Arrays.asList(pose.position.x, pose.position.y, pose.heading.toDouble())
    );

    // Register globally
    FateSchema.registerSchema(Pose2d.class, optimizedPoseSchema);

    // All future channels automatically use your custom schema
    FlightLogChannel<Pose2d> poseChannel1 =
        FlightRecorder.createChannel("Robot/Pose", Pose2d.class);
    FlightLogChannel<Pose2d> poseChannel2 =
        FlightRecorder.createChannel("Target/Pose", Pose2d.class);
    // Both use optimizedPoseSchema
    ```

## Where to register

Register in your OpMode's `init()`, before any channel exists. A schema registered after a channel
was created does nothing for that channel, since the channel already has one.

Keep every registration in one place. Registering the same class twice overwrites silently, and
that is much easier to spot when the calls sit next to each other.

=== "Kotlin"

    ```kotlin
    abstract class BaseOpMode : OpMode() {
        override fun init() {
            setupSchemas()
            initHardware()
        }

        private fun setupSchemas() {
            // Register all custom schemas here
            FateSchema.registerSchema(Pose2d::class.java, OPTIMIZED_POSE_SCHEMA)
            FateSchema.registerSchema(PoseVelocity2d::class.java, VELOCITY_SCHEMA)
            FateSchema.registerSchema(RobotState::class.java, STATE_SCHEMA)
        }

        companion object {
            private val OPTIMIZED_POSE_SCHEMA = CustomStructSchema<Pose2d>(/* ... */)
            private val VELOCITY_SCHEMA = CustomStructSchema<PoseVelocity2d>(/* ... */)
            private val STATE_SCHEMA = CustomStructSchema<RobotState>(/* ... */)
        }
    }
    ```

=== "Java"

    ```java
    public abstract class BaseOpMode extends OpMode {
        @Override
        public final void init() {
            setupSchemas();
            initHardware();
        }

        private void setupSchemas() {
            // Register all custom schemas here
            FateSchema.registerSchema(Pose2d.class, OPTIMIZED_POSE_SCHEMA);
            FateSchema.registerSchema(PoseVelocity2d.class, VELOCITY_SCHEMA);
            FateSchema.registerSchema(RobotState.class, STATE_SCHEMA);
        }

        protected abstract void initHardware();

        private static final CustomStructSchema<Pose2d> OPTIMIZED_POSE_SCHEMA =
            new CustomStructSchema<>(/* ... */);
        private static final CustomStructSchema<PoseVelocity2d> VELOCITY_SCHEMA =
            new CustomStructSchema<>(/* ... */);
        private static final CustomStructSchema<RobotState> STATE_SCHEMA =
            new CustomStructSchema<>(/* ... */);
    }
    ```

