package gay.zharel.fateweaver.telemetry;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java's view of the telemetry API. The Kotlin member is named {@code logErased} so the reified
 * {@code log} extension can exist, and {@code @JvmName} is what keeps it {@code log} from here.
 */
class JavaTelemetryApiTest {

    @Test
    void javaLogsBoxedPrimitivesAndStrings() {
        FateRegistry registry = new FateRegistry();
        MockFateBackend mock = new MockFateBackend();
        registry.register(mock);

        FateTable drive = registry.table("Drive");
        drive.log("left", 1.5);
        drive.log("count", 3);
        drive.log("enabled", true);
        drive.log("state", "RUNNING");

        assertEquals(Collections.singletonList(1.5), mock.valuesAt("Drive/left"));
        assertEquals(Collections.singletonList(3), mock.valuesAt("Drive/count"));
        assertEquals(Collections.singletonList(true), mock.valuesAt("Drive/enabled"));
        assertEquals(Collections.singletonList("RUNNING"), mock.valuesAt("Drive/state"));
    }

    @Test
    void javaLogsALoggableLambda() {
        FateRegistry registry = new FateRegistry();
        MockFateBackend mock = new MockFateBackend();
        registry.register(mock);

        registry.getRoot().log("drive", (FateLoggable) table -> {
            table.log("left", 1.0);
            table.log("right", 2.0);
        });

        assertEquals(Arrays.asList("drive/left", "drive/right"), mock.getPaths());
    }

    @Test
    void javaLogsThroughTheStaticFacade() {
        MockFateBackend mock = new MockFateBackend();
        Fate.getRegistry().register(mock);
        try {
            Fate.log("battery", 12.4);
            Fate.table("Drive").log("left", 1.5);

            assertEquals(Collections.singletonList(12.4), mock.valuesAt("battery"));
            assertEquals(Collections.singletonList(1.5), mock.valuesAt("Drive/left"));
        } finally {
            Fate.getRegistry().unregister(mock);
        }
    }

    @Test
    void javaRejectsAParameterizedValueWithAWarningNotAnException() {
        FateRegistry registry = new FateRegistry();
        MockFateBackend mock = new MockFateBackend();
        registry.register(mock);
        StringBuilder warnings = new StringBuilder();
        registry.setWarningHandler(warnings::append);

        registry.getRoot().log("powers", Collections.singletonMap("fl", 0.5));

        assertTrue(warnings.length() > 0);
        assertTrue(mock.getRecords().isEmpty());
    }
}
