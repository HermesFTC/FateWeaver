package gay.zharel.fateweaver.log;

import gay.zharel.fateweaver.schemas.ArraySchema;
import gay.zharel.fateweaver.schemas.CustomStructSchema;
import gay.zharel.fateweaver.schemas.DoubleSchema;
import gay.zharel.fateweaver.schemas.EnumSchema;
import gay.zharel.fateweaver.schemas.FateSchema;
import gay.zharel.fateweaver.schemas.IntSchema;
import gay.zharel.fateweaver.schemas.LongSchema;
import gay.zharel.fateweaver.schemas.MapSchema;
import gay.zharel.fateweaver.schemas.StringSchema;
import gay.zharel.fateweaver.schemas.TranslatedSchema;
import gay.zharel.fateweaver.schemas.TypedClassSchema;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Transcription of the Java samples in docs/, adapted to FateLogWriter (FlightRecorder is a thin
 * {@code @JvmStatic} passthrough to the same createChannel/put signatures).
 *
 * <p>Most of these are compile-time assertions: the declared channel types are the ones the docs
 * tell a Java author to write, and Kotlin's declaration-site variance makes that a real constraint
 * (see {@code JavaWriteApiTest} for the {@code Map} case).
 */
class JavaApiSurfaceTest {

    // schemas.md:276 -- reflected class
    public static class RobotConfig {
        public final double maxSpeed;
        public RobotConfig(double maxSpeed) { this.maxSpeed = maxSpeed; }
    }

    // schemas.md:341 -- AS_TYPE triggers TypedClassSchema
    public static class SensorReading {
        public static final String AS_TYPE = "SensorReading";
        public final long timestamp;
        public SensorReading(long timestamp) { this.timestamp = timestamp; }
    }

    // schemas.md:389
    public static class Pose2d {
        public final double x, y, heading;
        public Pose2d(double x, double y, double heading) {
            this.x = x; this.y = y; this.heading = heading;
        }
    }

    // schemas.md:500
    public static class Voltage {
        public final double value;
        public Voltage(double value) { this.value = value; }
    }

    enum AllianceColor { RED, BLUE }

    @Test
    void primitives() {
        FateLogWriter w = new FateLogWriter(new ByteArrayOutputStream());
        LogChannel<Integer> a = w.createChannel("a", IntSchema.INSTANCE);
        LogChannel<Long> b = w.createChannel("b", LongSchema.INSTANCE);
        LogChannel<Double> c = w.createChannel("c", DoubleSchema.INSTANCE);
        LogChannel<String> d = w.createChannel("d", StringSchema.INSTANCE);
        a.put(1); b.put(1L); c.put(1.0); d.put("x");
    }

    @Test
    void arraysAndMaps() {
        FateLogWriter w = new FateLogWriter(new ByteArrayOutputStream());

        // schemas.md:183
        ArraySchema<Double> powersSchema = new ArraySchema<>(DoubleSchema.INSTANCE);
        LogChannel<Double[]> powers = w.createChannel("Motors/Powers", powersSchema);
        powers.put(new Double[]{0.8, 0.6});

        // schemas.md:209
        MapSchema<String, Double> mapSchema =
            new MapSchema<>(StringSchema.INSTANCE, DoubleSchema.INSTANCE);
        LogChannel<Map<String, Double>> byName = w.createChannel("ByName", mapSchema);
    }

    @Test
    void enums() {
        FateLogWriter w = new FateLogWriter(new ByteArrayOutputStream());

        // schemas.md:240. EnumSchema<T> used to implement FateSchema<Enum<T>>, which made this
        // declaration -- the one the docs give -- a compile error.
        EnumSchema<AllianceColor> allianceSchema = new EnumSchema<>(AllianceColor.class);
        LogChannel<AllianceColor> allianceChannel = w.createChannel("Match/Alliance", allianceSchema);
        allianceChannel.put(AllianceColor.RED);
    }

    @Test
    void translatedEnumAndRegistry() {
        FateLogWriter w = new FateLogWriter(new ByteArrayOutputStream());

        // schemas.md:651 -- enum via TranslatedSchema, which does give a LogChannel<DriveMode>
        TranslatedSchema<AllianceColor, String> modeSchema = new TranslatedSchema<>(
            String.class,
            AllianceColor::name
        );
        LogChannel<AllianceColor> modeChannel = w.createChannel("Drive/Mode", modeSchema);
        modeChannel.put(AllianceColor.RED);

        // schemas.md:740 / registry.md:50
        CustomStructSchema<Pose2d> poseSchema = new CustomStructSchema<>(
            "Pose2d",
            Arrays.asList("x", "y", "heading"),
            Arrays.asList(DoubleSchema.INSTANCE, DoubleSchema.INSTANCE, DoubleSchema.INSTANCE),
            pose -> Arrays.asList(pose.x, pose.y, pose.heading)
        );
        FateSchema.registerSchema(Pose2d.class, poseSchema);
        LogChannel<Pose2d> poseChannel = w.createChannel("Robot/Pose", Pose2d.class);
        poseChannel.put(new Pose2d(0, 0, 0));

        // guide.md:3 claims re-registration is an error. It is not -- it overwrites silently.
        FateSchema.registerSchema(Pose2d.class, poseSchema);
    }

    @Test
    void reflectedClasses() {
        FateLogWriter w = new FateLogWriter(new ByteArrayOutputStream());

        // schemas.md:276
        LogChannel<RobotConfig> config = w.createChannel("Robot/Config", RobotConfig.class);
        config.put(new RobotConfig(1.0));

        // schemas.md:341 -- a Java `public static final String AS_TYPE` must select
        // TypedClassSchema. Kotlin reflection exposes static properties as KProperty0, so reading
        // one as a KProperty1 threw ClassCastException here until ClassSchemas.kt:99 was fixed;
        // only the Kotlin companion-object form of AS_TYPE had coverage.
        LogChannel<SensorReading> sensor = w.createChannel("Sensors/Reading", SensorReading.class);
        sensor.put(new SensorReading(1L));

        TypedClassSchema<?> typed = assertInstanceOf(TypedClassSchema.class, sensor.getSchema());
        assertEquals("SensorReading", typed.getType());
    }

    @Test
    void customStructs() {
        FateLogWriter w = new FateLogWriter(new ByteArrayOutputStream());

        // schemas.md:389
        CustomStructSchema<Pose2d> poseSchema = new CustomStructSchema<>(
            "Pose2d",
            Arrays.asList("x", "y", "heading"),
            Arrays.asList(DoubleSchema.INSTANCE, DoubleSchema.INSTANCE, DoubleSchema.INSTANCE),
            pose -> Arrays.asList(pose.x, pose.y, pose.heading)
        );
        LogChannel<Pose2d> poseChannel = w.createChannel("Robot/Pose", poseSchema);
        poseChannel.put(new Pose2d(0, 0, 0));
    }

    @Test
    void translatedSchemas() {
        FateLogWriter w = new FateLogWriter(new ByteArrayOutputStream());

        // schemas.md:500
        TranslatedSchema<Voltage, Double> bySchema = new TranslatedSchema<>(
            DoubleSchema.INSTANCE,
            voltage -> voltage.value
        );
        LogChannel<Voltage> v1 = w.createChannel("Battery/Voltage", bySchema);
        v1.put(new Voltage(12.6));

        // schemas.md:533
        TranslatedSchema<Voltage, Double> byClass = new TranslatedSchema<>(
            Double.class,
            voltage -> voltage.value
        );
    }
}
