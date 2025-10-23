# FateWeaver Log File Format Specification, Version 1

A compact binary logging format designed for high‑speed, low‑overhead recording and offline analysis of structured telemetry produced by FateWeaver. This document describes the format implemented by the core module reader/writer APIs.

## Motivation
Robotics and embedded applications generate diverse telemetry at high rates. Text formats (CSV, JSON) have high CPU and I/O overhead and are difficult to decode unambiguously. FateWeaver defines a binary format with explicitly declared per‑channel schemas, enabling:

- Efficient writes and reads with fixed/known sizes
- Self‑describing streams via schema announcements
- Support for primitives, arrays, enums, and structured objects

## Definitions

- Entry
  A single unit in the log stream. There are two entry kinds: Schema and Message.

- Channel
  A named data stream (topic). Each channel is associated with exactly one schema definition that describes the layout of its messages.

- Schema
  A binary descriptor that defines how objects of a given type are encoded, including nested structures, arrays, and enums. Schemas are tagged by a TypeRegistry value.

- Message
  A data record for a specific channel that encodes one object according to the channel schema.

## Design Overview

A FateWeaver log is a sequence of entries with no padding between them. This design prioritizes compactness and streaming efficiency while maintaining self-describing capability.

### File Structure

The binary format follows a simple linear structure:

```
[Header: 4 bytes] [Entry 1] [Entry 2] ... [Entry N]
```

Each entry is variable-length and immediately follows the previous entry without alignment padding. This approach maximizes storage efficiency and enables straightforward sequential reading/writing.

### Core Design Principles

1. **Self-Describing**: Every log file contains all necessary schema information to decode its contents without external metadata files or configuration.

2. **Streaming-Friendly**: The format can be written and read incrementally. Schema entries can appear anywhere in the stream, allowing for dynamic schema evolution during logging.

3. **Compact Representation**: Binary encoding with explicit length prefixes eliminates the overhead of delimiters and escape sequences found in text formats.

4. **Type Safety**: Strong schema enforcement prevents data corruption and enables efficient parsing with known data layouts.

5. **Language Agnostic**: The wire format uses standard binary primitives that can be implemented in any programming language.

### Entry Types

- **Header (4 bytes)**: magic + version
- **Zero or more entries**. Each entry starts with a 4‑byte type tag:
  - 0 = Schema entry (registers a channel and its schema)
  - 1 = Message entry (payload for an existing channel)

The entry type system is designed for extensibility. Future versions may introduce additional entry types (e.g., metadata entries, compression markers) while maintaining backward compatibility through version negotiation.

### Channel Model

Channels represent independent data streams within a single log file. Each channel:
- Has a unique name within the log file
- Is associated with exactly one schema definition
- Receives an auto-assigned index based on declaration order
- Can be referenced by multiple message entries

This model supports heterogeneous logging scenarios where different subsystems log different data types to the same file.

## Header

The file begins with exactly 4 bytes:

- 2‑byte ASCII magic string: "RR"
- 2‑byte unsigned version number (as a 16‑bit short). Current version: 1

If either the magic or the version does not match, the file must be rejected.

Example header bytes (hex): 52 52 00 01

## Entries

Each entry starts with a 4‑byte signed integer specifying the entry kind:

- 0 → Schema entry
- 1 → Message entry

### Schema Entry (entry kind = 0)

Defines a channel and its schema. Format:

- 4 bytes: name length N (int)
- N bytes: UTF‑8 channel name
- Schema: a self‑contained binary schema blob, starting with a 4‑byte type tag followed by type‑specific data (see Schema Encoding below)

Upon reading a Schema entry, the channel is appended to an internal channel list; its index is the zero‑based position in this list. Messages reference the channel by this index.

Multiple channels are allowed; schemas are independent and can be nested.

### Message Entry (entry kind = 1)

Carries one payload value for a previously announced channel. Format:

- 4 bytes: channel index (int) — must be a valid index referencing a prior Schema entry
- Payload bytes: object encoded according to the channel’s schema

## Schema Encoding

All schemas begin with a 4‑byte type tag (int) defined by the TypeRegistry:

- 0 = CUSTOM (structured object)
- 1 = INT (32‑bit signed)
- 2 = LONG (64‑bit signed)
- 3 = DOUBLE (64‑bit IEEE‑754)
- 4 = STRING (UTF‑8 with 32‑bit length prefix)
- 5 = BOOLEAN (single byte, 0x00 = false, 0x01 = true)
- 6 = ENUM (ordinal‑encoded with declared constant names)
- 7 = ARRAY (homogeneous array)

The schema payload that follows the tag depends on the tag value:

- INT, LONG, DOUBLE, STRING, BOOLEAN
  No additional schema bytes (beyond the tag). Objects are encoded directly (see Data Encoding).

- ENUM
  - 4 bytes: constant count C (int)
  - Repeat C times:
    - 4 bytes: name length Li (int)
    - Li bytes: UTF‑8 constant name
  Objects are encoded as a 4‑byte ordinal (int). When decoding via DynamicEnumSchema, the ordinal is mapped back to the declared name.

- ARRAY
  - Nested schema bytes for the element type: one complete schema blob (tag + payload) for the element
  Objects are encoded as:
  - 4 bytes: element count K (int)
  - K elements: each element encoded with the element schema

- CUSTOM (structured object)
  Represents a structured object with named fields. The schema describes the field layout, and objects are encoded as the concatenation of field values in declaration order.

  Schema payload format:
  - 4 bytes: field count F (int)
  - Repeat F times:
    - 4 bytes: field name length Mi (int)
    - Mi bytes: UTF‑8 field name
    - Field schema: one complete schema blob for the field type

  Object encoding:
  - Field values are concatenated in the order declared in the schema
  - Each field is encoded according to its individual schema
  - No padding or alignment between fields

  Implementation variants:
  Two variants exist in the implementation that share the same wire format but differ in runtime object construction:
  
  - ReflectedClassSchema: Maps to arbitrary object fields
  - TypedClassSchema: Same as ReflectedClassSchema, but injects a synthetic type discriminator field
  
  For TypedClassSchema, the field count F includes the additional synthetic field, which appears first:
  - First field: discriminator (default name "as_type", schema STRING)
  - Remaining fields: user-defined fields as declared
  
  The discriminator enables polymorphic deserialization by embedding type information in the data stream.

All numbers are big‑endian.

## Channel Indexing

- Channels are assigned indices in the order their Schema entries appear (0‑based).
- A Message entry must reference a valid channel index that has already been declared.
- The format does not currently support channel deletion or re‑use; readers should treat out‑of‑range indices as an error.

## Versioning

- Header version is a 16‑bit integer. Current version is 1.
- Readers must reject files with unsupported versions.
- Schema tags (TypeRegistry values) are stable and part of the wire contract.

## Examples

Below are illustrative, not exhaustive, and omit some lengths for brevity. All ints are big‑endian.

1) Header
   - Magic "RR" + version 1 → 52 52 00 01

2) Declare a channel named "poses" with schema: ARRAY of CUSTOM with fields { x: DOUBLE, y: DOUBLE }
   - Entry kind: 00 00 00 00
   - Name length: 00 00 00 05; name: 70 6F 73 65 73
   - Schema: ARRAY tag 00 00 00 07
     - Element schema: CUSTOM tag 00 00 00 00
       - Field count: 00 00 00 02
         - Field "x": length 00 00 00 01; 78; schema DOUBLE 00 00 00 03
         - Field "y": length 00 00 00 01; 79; schema DOUBLE 00 00 00 03

3) Message with two poses for channel index 0
   - Entry kind: 00 00 00 01
   - Channel index: 00 00 00 00
   - Array length: 00 00 00 02
   - Pose[0]: x(double) 40 00 00 00 00 00 00 00; y(double) 40 08 00 00 00 00 00 00
   - Pose[1]: x(double) 40 10 00 00 00 00 00 00; y(double) 40 14 00 00 00 00 00 00

## Notes

- Endianness is big‑endian for all multi‑byte primitives.
- There is no global timestamp; include time fields in your schemas if needed.
- Strings are UTF‑8 with a 32‑bit length prefix.
- Readers must validate channel indices and schema tags and should fail fast on malformed data.

## Additional Resources

- Core implementation: core/src/main/kotlin/gay/zharel/fateweaver/log/FateLogWriter.kt
- Schema definitions and tags: core/src/main/kotlin/gay/zharel/fateweaver/schemas/*.kt