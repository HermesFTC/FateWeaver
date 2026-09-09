# Collection Schemas

FateWeaver encodes three shapes of collection: arrays, maps, and enums.

## Arrays, lists and sets

`ArraySchema<T>` writes a 4-byte element count followed by each element encoded with the element
schema. `List<T>`, `Set<T>` and `Collection<T>` fields use this same wire format, so a `List<Double>`
field is byte-identical to a `Double[]` field.

=== "Kotlin"

    ```kotlin
    class Odometry(val wheelPositions: List<Double>)

    // Inferred automatically: wheelPositions becomes an array of DOUBLE
    val channel = FlightRecorder.createChannel("Odometry", Odometry::class.java)
    channel.put(Odometry(listOf(0.0, 1.5, 3.0)))
    ```

=== "Java"

    ```java
    ArraySchema<Double> schema = new ArraySchema<>(DoubleSchema.INSTANCE);
    FlightLogChannel<Double[]> channel = FlightRecorder.createChannel("Wheels", schema);
    channel.put(new Double[]{0.0, 1.5, 3.0});
    ```

Sets are written in their own iteration order, which is stable for `LinkedHashSet` but arbitrary for
`HashSet`. Order is not meaningful in the log either way.

## Maps

`MapSchema<K, V>` writes a 4-byte entry count followed by each key/value pair, with keys and values
encoded by their own nested schemas. Keys are not restricted to strings.

=== "Kotlin"

    ```kotlin
    val powers = FlightRecorder.createChannel(
        "Motors/Powers",
        MapSchema(StringSchema.INSTANCE, DoubleSchema.INSTANCE),
    )
    powers.put(mapOf("fl" to 0.8, "fr" to 0.6))

    // Or inferred from a declared field:
    class Telemetry(val counters: Map<String, Int>)
    FlightRecorder.createChannel("Telemetry", Telemetry::class.java)
    ```

=== "Java"

    ```java
    MapSchema<String, Double> schema =
        new MapSchema<>(StringSchema.INSTANCE, DoubleSchema.INSTANCE);
    FlightLogChannel<Map<String, Double>> powers =
        FlightRecorder.createChannel("Motors/Powers", schema);

    Map<String, Double> sample = new LinkedHashMap<>();
    sample.put("fl", 0.8);
    sample.put("fr", 0.6);
    powers.put(sample);
    ```

## Logging a collection without declaring a channel

`write(channelName, value)` creates a channel on first use. A value's runtime class carries no type
arguments — a `Map<String, Double>` is just a `LinkedHashMap` at runtime — so from Kotlin this form
infers from the **static type at the call site**:

=== "Kotlin"

    ```kotlin
    import gay.zharel.fateweaver.flight.write   // the reified overload is an extension

    FlightRecorder.write("Motors/Powers", mapOf("fl" to 0.8, "fr" to 0.6))
    FlightRecorder.write("Path/Points", listOf(a, b, c))

    // Or name the type up front:
    val powers = FlightRecorder.createChannel<Map<String, Double>>("Motors/Powers")
    ```

=== "Java"

    ```java
    // Java erases at the call site too, so there is nothing to infer from.
    // Build the schema explicitly instead:
    FlightLogChannel<Map<String, Double>> powers = FlightRecorder.createChannel(
        "Motors/Powers", new MapSchema<>(StringSchema.INSTANCE, DoubleSchema.INSTANCE));
    powers.put(sample);
    ```

Two limits follow from this being *static* type information:

- If the static type is `Any` or another type with no arguments, inference falls back to the runtime
  class, exactly as it did before. `val v: Any = mapOf(...)` therefore still fails — the
  parameterization is genuinely gone by then.
- A channel's schema is fixed when the channel is created. Writing `Map<String, Double>` and then
  `Map<String, Int>` to the same channel name is rejected with an error naming both types.

The same API exists on `FateLogWriter` for non-FTC use, with the extension imported from
`gay.zharel.fateweaver.log`.

## Requirements

The format is fixed-shape, so collections must be homogeneous and fully typed:

- `Map<String, Any>`, `List<*>` and raw types are rejected when the schema is built, with an error
  naming the offending field and type.
- Nullable element, key or value types (`List<String?>`, `Map<String, Double?>`) are rejected — there
  is no encoding for null.
- Type arguments must be concrete. A generic holder such as `class Box<T>(val items: List<T>)` cannot
  be inferred; register a schema for the concrete type instead.

When inference can't help — a heterogeneous map, or a type you don't control — construct the schema
by hand with `CustomStructSchema`, or register one for the class with `FateSchema.registerSchema`.
An explicitly registered schema always wins over inference.

!!! warning "Decoder compatibility"
    `MapSchema` uses schema tag 8, added after the initial format release. Because log entries are
    not length-prefixed, a decoder that predates the tag cannot skip it and will stop reading at that
    point. Update external tooling before producing logs that contain map channels.
