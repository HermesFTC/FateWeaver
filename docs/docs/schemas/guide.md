# Selection guide

## Picking a schema

Start with inference. `Fate.log("pose", myObject)` builds a `ReflectedClassSchema` over the public
properties, and for most robot data that is all you need.

Use `CustomStructSchema` when you want fewer bytes or different ones. You can drop fields you won't
plot, round a double, or convert units as you log. You give it the component names, their schemas,
and a function that pulls the values out. Pass it to `log(name, value, schema)`, or register it so
plain `log` finds it.

`TranslatedSchema` is the smaller version of the same idea. It converts one value to another type and
encodes that, so a `Rotation2d` can go into the log as a plain double.

## What costs what

Primitive schemas are the cheap case. Their sizes are fixed, so nothing has to be measured before
writing. An Int takes 4 bytes, a Long or Double 8, a Boolean 1, and a String a 4-byte length prefix
plus its UTF-8 bytes.

`ReflectedClassSchema` looks up its property accessors once per class and caches them. It still reads
each property through reflection on every log. A `CustomStructSchema` reads them directly.

`objSize` and `encodeObject` both run on every log, so a `CustomStructSchema` encoder is called twice
per logged value. Keep it to field reads and arithmetic. At 50 Hz, anything slower adds up.

Register custom schemas in `init()` rather than mid-match, so the reflection happens before the match
starts.

## Registering schemas

`FateSchema.registerSchema` overwrites any previous entry for the same class and gives no error. A
second registration changes the schema for every path first logged after it. Register each schema
once, early in initialization.

## Use AS_TYPE

A class with an `AS_TYPE` constant gets that name recorded in the log next to its fields. FateWeaver
doesn't require it. It gives viewers and your own analysis scripts something to key on besides the
path.
