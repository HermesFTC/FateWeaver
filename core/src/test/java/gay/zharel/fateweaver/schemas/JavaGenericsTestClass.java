package gay.zharel.fateweaver.schemas;

import java.util.List;
import java.util.Map;

/**
 * Fixture for generic inference over Java-declared fields.
 *
 * <p>Java generics are platform types on the Kotlin side, which report
 * {@code isMarkedNullable == false}, so these must infer cleanly rather than being rejected as
 * nullable type arguments.
 */
public class JavaGenericsTestClass {
    public Map<String, Integer> javaMap;
    public List<String> javaList;

    public JavaGenericsTestClass(Map<String, Integer> javaMap, List<String> javaList) {
        this.javaMap = javaMap;
        this.javaList = javaList;
    }
}
