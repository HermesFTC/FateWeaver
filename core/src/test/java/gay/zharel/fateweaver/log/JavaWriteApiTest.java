package gay.zharel.fateweaver.log;

import gay.zharel.fateweaver.schemas.ArraySchema;
import gay.zharel.fateweaver.schemas.DoubleSchema;
import gay.zharel.fateweaver.schemas.FateSchema;
import gay.zharel.fateweaver.schemas.IntSchema;
import gay.zharel.fateweaver.schemas.MapSchema;
import gay.zharel.fateweaver.schemas.StringSchema;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Java's view of the write API. The Kotlin-side rename to {@code writeErased} is masked by
 * {@code @JvmName}, so this is also the regression test for that name staying {@code write}.
 */
class JavaWriteApiTest {

    private static List<FateLogEntry> roundTrip(ByteArrayOutputStream bytes) {
        try (FateLogReader reader = new FateLogReader(new ByteArrayInputStream(bytes.toByteArray()))) {
            return reader.readAll();
        }
    }

    @Test
    void javaCallsWriteByName() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FateLogWriter writer = new FateLogWriter(bytes)) {
            writer.write("n", 42);
        }

        FateLogEntry.Message<?> message = (FateLogEntry.Message<?>) roundTrip(bytes).get(1);
        assertEquals(42, message.getData());
    }

    @Test
    void javaUsesAnExplicitSchemaForParameterizedValues() {
        // Java erases at the call site too, so there is no inference path here: the schema is the
        // supported way to log a Map from Java. The channel type is the plain `Map<String, Double>`
        // a Java author would write — see javaMapChannelTypeHasNoWildcard.
        Map<String, Double> powers = new LinkedHashMap<>();
        powers.put("fl", 0.5);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FateLogWriter writer = new FateLogWriter(bytes)) {
            LogChannel<Map<String, Double>> channel = writer.createChannel(
                    "powers", new MapSchema<>(StringSchema.INSTANCE, DoubleSchema.INSTANCE));
            channel.put(powers);
        }

        FateLogEntry.Message<?> message = (FateLogEntry.Message<?>) roundTrip(bytes).get(1);
        assertEquals(powers, message.getData());
    }

    /**
     * Kotlin declares {@code Map<K, out V>}, which without {@code @JvmSuppressWildcards} would
     * surface here as {@code FateSchema<Map<String, ? extends Double>>} and make every one of these
     * declarations a compile error. This test is a compile-time assertion; the body barely matters.
     */
    @Test
    void javaMapChannelTypeHasNoWildcard() {
        FateSchema<Map<String, Double>> schema =
                new MapSchema<>(StringSchema.INSTANCE, DoubleSchema.INSTANCE);

        Map<String, Double> powers = new LinkedHashMap<>();
        powers.put("fl", 0.5);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FateLogWriter writer = new FateLogWriter(bytes)) {
            LogChannel<Map<String, Double>> channel = writer.createChannel("powers", schema);
            channel.put(powers);
        }

        assertEquals(2, roundTrip(bytes).size());
    }

    @Test
    void javaArrayChannel() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FateLogWriter writer = new FateLogWriter(bytes)) {
            LogChannel<Double[]> channel =
                    writer.createChannel("wheels", new ArraySchema<>(DoubleSchema.INSTANCE));
            channel.put(new Double[]{0.0, 1.5});
        }

        FateLogEntry.Message<?> message = (FateLogEntry.Message<?>) roundTrip(bytes).get(1);
        assertArrayEquals(new Object[]{0.0, 1.5}, (Object[]) message.getData());
    }

    @Test
    void javaNestedMapOfArrayChannel() {
        // Nesting is where a wildcard would resurface if only the outer type were suppressed.
        FateSchema<Map<String, Integer[]>> schema = new MapSchema<>(
                StringSchema.INSTANCE, new ArraySchema<>(IntSchema.INSTANCE));

        Map<String, Integer[]> buckets = new LinkedHashMap<>();
        buckets.put("evens", new Integer[]{2, 4});

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FateLogWriter writer = new FateLogWriter(bytes)) {
            LogChannel<Map<String, Integer[]>> channel = writer.createChannel("buckets", schema);
            channel.put(buckets);
        }

        FateLogEntry.Message<?> message = (FateLogEntry.Message<?>) roundTrip(bytes).get(1);
        Map<?, ?> decoded = (Map<?, ?>) message.getData();
        assertArrayEquals(new Object[]{2, 4}, (Object[]) decoded.get("evens"));
    }
}
