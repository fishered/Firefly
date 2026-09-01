# scheduler-core compatibility module

`scheduler-core` is retained as the stable Maven/Gradle entry point used by
Firefly 1.x consumers. Its public packages are supplied by the split bounded
context modules and re-exported here through `api` dependencies. New code can
depend directly on the smallest module it needs; existing imports and the
`com.firefly.engine` API remain compatible during the 1.1.x migration.
