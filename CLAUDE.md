# SmsForwarder – workflow rules

## After every code change

1. **TDD tests first**: write or update a failing unit test that describes the change, then make it pass
   (`./gradlew.bat testDebugUnitTest --console=plain`). Bug fixes get a regression test.
   Logic that is hard to unit-test (Android receivers/workers) should be pulled into small pure functions so it can be tested.
2. **Then build Release**: `./gradlew.bat assembleRelease --console=plain`.
   Do not report the change as done until the tests pass and the Release build is green.
   If either fails, report the exact error instead of claiming success.

## Build environment

```
export JAVA_HOME="C:/Users/M-Vakilzadeh/.jdks/jbr-17.0.14"   # Android Studio's bundled JBR 17
```

- Never run a CLI build while Android Studio is building (Windows locks `R.jar`); `gradlew --stop` releases it.
- Do not install a JDK; use the JBR above.
- Release signing needs `keystore/keystore.properties`; if it is missing, say so rather than changing the signing config.
