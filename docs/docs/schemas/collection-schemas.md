# Collection schemas

FateWeaver encodes three shapes of collection: arrays, maps, and enums.

## Arrays, lists and sets

`ArraySchema<T>` writes a 4-byte element count followed by each element encoded with the element
schema. `List<T>`, `Set<T>` and `Collection<T>` fields use the same wire format, so a `List<Double>`
field is byte-identical to a `Double[]` field.

=== "Kotlin"

    ```kotlin
    class Odometry(val wheelPositions: List<Double>)

    // wheelPositions is inferred as an array of DOUBLE
    Fate.log("Odometry", Odometry(listOf(0.0, 1.5, 3.0)))
    ```

=== "Java"

    ```java
    ArraySchema<Double> schema = new ArraySchema<>(DoubleSchema.INSTANCE);
    Fate.log("Wheels", new Double[]{0.0, 1.5, 3.0}, schema);
    ```

Sets are written in their own iteration order. That order is stable for a `LinkedHashSet` and
arbitrary for a `HashSet`. Either way, it means nothing in the log.

## Maps

`MapSchema<K, V>` writes a 4-byte entry count followed by each key/value pair. Keys and values are
encoded by their own nested schemas, and keys don't have to be strings.

=== "Kotlin"

    ```kotlin
    val motors = Fate.table("Motors")
    motors.log(
        "Powers",
        mapOf("fl" to 0.8, "fr" to 0.6),
        MapSchema(StringSchema.INSTANCE, DoubleSchema.INSTANCE),
    )

    // Or inferred from a declared field:
    class Counters(val counts: Map<String, Int>)
    Fate.log("Counters", Counters(mapOf("cycles" to 3)))
    ```

=== "Java"

    ```java
    MapSchema<String, Double> schema =
        new MapSchema<>(StringSchema.INSTANCE, DoubleSchema.INSTANCE);
    FateTable motors = Fate.table("Motors");

    Map<String, Double> sample = new LinkedHashMap<>();
    sample.put("fl", 0.8);
    sample.put("fr", 0.6);
    motors.log("Powers", sample, schema);
    ```

## Logging a collection without a schema

In Kotlin you can skip the schema. `log` is a reified extension, so it reads the static type at the
call site, type arguments included. A `Map<String, Double>` is just a `LinkedHashMap` at runtime, so
without the static type the key and value types would be gone.

=== "Kotlin"

    ```kotlin
    import gay.zharel.fateweaver.telemetry.Fate
    import gay.zharel.fateweaver.telemetry.log   // the reified overload is an extension

    Fate.log("Motors/Powers", mapOf("fl" to 0.8, "fr" to 0.6))
    Fate.log("Path/Points", listOf(a, b, c))
    ```

=== "Java"

    ```java
    // Java's two-argument log only sees the runtime class, so it can't log a Map or List.
    // Pass the schema instead:
    Fate.log("Motors/Powers", sample,
        new MapSchema<>(StringSchema.INSTANCE, DoubleSchema.INSTANCE));
    ```

Two cases still fail.

If the static type is `Any`, or anything else without type arguments, inference falls back to the
runtime class. So `val v: Any = mapOf(...)` is rejected. The key and value types are already gone,
and guessing them from the first entry would lock one sample's shape into the path.

A path's schema is also fixed by its first log. Log a `Map<String, Double>` and then a
`Map<String, Int>` to the same name, and the second call prints a warning and is skipped. The
warning names both types.

`FateLogWriter` has the same API outside FTC. Import its extension from `gay.zharel.fateweaver.log`.

## Nulls

A nullable type infers an `OptionalSchema`, encoded as a presence byte followed by the value when
there is one. There is nothing to turn on. Declare the property nullable and you are done.

=== "Kotlin"

    ```kotlin
    class Target(val name: String, val pose: Pose?)          // pose is optional
    class Readings(val values: Map<String, Double?>)         // values may be absent
    class Labels(val items: List<String?>)                   // elements may be absent
    ```

=== "Java"

    ```java
    // Optional<T> gets the same tag and the same bytes as Kotlin's T?
    public class Target {
        public final String name;
        public final Optional<Pose> pose;
    }

    // OptionalInt, OptionalLong and OptionalDouble work too
    public class Counters {
        public final OptionalDouble voltage;
    }
    ```

An absent value costs one byte. A present one costs one byte more than it would unwrapped. Non-null
fields are unchanged, so your existing logs don't get any bigger.

A null in a field that isn't declared nullable is still an error that names the field. You will see
this with Java fields and Kotlin platform types, where the declaration says non-null and the value
disagrees.

!!! warning "Decoder compatibility"
    Optional values use schema tag 9. A decoder that predates the tag cannot skip past it. It stops
    reading at the first channel that uses one.

## Requirements

The format has a fixed shape, so collections must be homogeneous and fully typed:

- `Map<String, Any>`, `List<*>` and raw types are rejected when the schema is built. The error names
  the field and type.
- Nullable element, key or value types such as `List<String?>` and `Map<String, Double?>` are fine.
  They infer an `OptionalSchema`, which costs one byte per element.
- Type arguments must be concrete. A generic holder like `class Box<T>(val items: List<T>)` can't be
  inferred. Register a schema for the concrete type instead.

For a heterogeneous map, or a type you don't control, inference won't work. Build the schema by hand
with `CustomStructSchema`, or register one for the class with `FateSchema.registerSchema`. A
registered schema always beats inference.

!!! warning "Decoder compatibility"
    `MapSchema` uses schema tag 8, which was added after the first release of the format. Log entries
    are not length-prefixed, so a decoder that predates the tag cannot skip it and stops reading
    there. Update external tooling before you produce logs that contain maps.
