# Selection Guide

## Performance Tips

1. **Use Primitive Schemas** for simple types - they're the fastest
2. **Cache Schema References** if creating channels repeatedly
3. **Register Schemas Early** to avoid reflection overhead during logging
4. **Prefer CustomStructSchema** over reflection for performance-critical paths
5. **Keep Translation Functions Simple** when using TranslatedSchema
6. **Avoid Nested Collections** when possible (e.g., `Array<Array<Double>>`)

## Best Practices

1. **Start Simple**: Use automatic schemas (ReflectedClassSchema) until you need optimization
2. **Profile First**: Measure before optimizing with custom schemas
3. **Document Custom Schemas**: Explain why you chose a custom approach
4. **Test Serialization**: Verify your custom encoders work correctly
5. **Version Your Schemas**: Consider future compatibility when designing schemas
6. **Use AS_TYPE**: Add type information for better debugging and tooling support

## Registering Schemas

`FateSchema.registerSchema` overwrites any previous entry for the same class — it does not error.
A second registration silently changes the schema for every channel created after it, so register
each schema once, early in initialization.
