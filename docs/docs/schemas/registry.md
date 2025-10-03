# Schema Registry

The schema registry provides global schema management and reuse.

## Automatic Registration

When you create a channel for a class, FateWeaver automatically:

1. Checks if a schema is already registered for that class
2. If not, creates an appropriate schema (Reflected or Typed based on AS_TYPE)
3. Registers the schema for future use
4. Returns a channel using the registered schema

## Manual Registration

You can register custom schemas to override the defaults:

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
    // Both use optimizedPoseSchema automatically!
    ```

=== "Java"

    ```java
    // Define once
    CustomStructSchema<Pose2d> optimizedPoseSchema = new CustomStructSchema<>(
        "Pose2d",
        List.of("x", "y", "theta"),
        List.of(DoubleSchema.INSTANCE, DoubleSchema.INSTANCE, DoubleSchema.INSTANCE),
        pose -> List.of(pose.position.x, pose.position.y, pose.heading.toDouble())
    );

    // Register globally
    FateSchema.registerSchema(Pose2d.class, optimizedPoseSchema);

    // All future channels automatically use your custom schema
    FlightLogChannel<Pose2d> poseChannel1 =
        FlightRecorder.createChannel("Robot/Pose", Pose2d.class);
    FlightLogChannel<Pose2d> poseChannel2 =
        FlightRecorder.createChannel("Target/Pose", Pose2d.class);
    // Both use optimizedPoseSchema automatically!
    ```

## Best Practices for Registration

1. **Register Early**: Set up schemas in your OpMode's `init()` method
2. **One Place**: Keep all schema registrations in a single location
3. **Document Choices**: Comment why you chose custom schemas over defaults

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

