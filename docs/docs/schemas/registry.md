# Schema registry

The registry maps a class to the schema used for it, everywhere.

## Automatic registration

The first time you log a class, FateWeaver looks in the registry. On a miss, it builds a schema by
reflection and stores it. That schema is a `TypedClassSchema` if the class has an `AS_TYPE` constant
and a `ReflectedClassSchema` otherwise. Every later log of that class reuses the stored schema, so
you pay the reflection cost once.

## Manual registration

Register your own schema to replace the reflected default. After that, plain `log(name, value)`
picks it up.

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

    // Both paths use optimizedPoseSchema
    Fate.log("Robot/Pose", robotPose)
    Fate.log("Target/Pose", targetPose)
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

    // Both paths use optimizedPoseSchema
    Fate.log("Robot/Pose", robotPose);
    Fate.log("Target/Pose", targetPose);
    ```

A registered schema also applies to the class when it is a field of another class or an element of a
list or map. It wins for every parameterization of the class.

## Where to register

Register in your OpMode's `init()`, before anything logs that class. A path keeps the schema it had
on its first log. If you register later, only new paths use it.

Keep every registration in one place. Registering the same class twice overwrites the first
registration without a warning, and that is easier to spot when the calls sit next to each other.

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
