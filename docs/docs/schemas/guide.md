# Selection guide

## Picking a schema

Start with inference. `createChannel(name, MyClass::class.java)` builds a `ReflectedClassSchema` over
the public properties, and for most robot data that is the end of it.

Reach for `CustomStructSchema` when you want fewer bytes or different ones: dropping fields you don't
plan to plot, rounding a double, converting units at write time. You give it the component names,
their schemas, and a function that pulls the values out.

`TranslatedSchema` is the smaller version of the same idea. It converts one value to another type and
encodes that, so a `Rotation2d` can go into the log as a plain double.

## What costs what

Primitive schemas are the cheap case. Their sizes are fixed, so nothing has to be measured before
writing: 4 bytes for an Int, 8 for a Long or Double, 1 for a Boolean, and a 4-byte length prefix plus
UTF-8 for a String.

`ReflectedClassSchema` resolves its property accessors once per class and caches them, but it still
reads each property through reflection on every write. A `CustomStructSchema` reads them directly.

Both `objSize` and `encodeObject` run on every write, which means a `CustomStructSchema` encoder is
called twice per logged value. Keep it to field reads and arithmetic, not work you'd notice at 50 Hz.

Register custom schemas in `init()` rather than mid-match, so the reflection happens before the match
starts.

## Registering schemas

`FateSchema.registerSchema` overwrites any previous entry for the same class. It does not error, so
a second registration quietly changes the schema for every channel created after it. Register each
schema once, early in initialization.

## Use AS_TYPE

A class with an `AS_TYPE` constant gets that name recorded in the log alongside its fields. Nothing
in FateWeaver requires it, but it gives viewers and your own analysis scripts something to key on
besides the channel name.
