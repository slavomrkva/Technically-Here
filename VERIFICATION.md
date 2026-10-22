# Verification

Current working source version: 0.4.6. This publication contains no installable APK.

Private templates and reference documents are excluded. The form under `test-fixtures/` is synthetic: a white background, an example Wi-Fi name, and generic fields. It is used only by automated tests.

The original English publication passed 76 unit tests: 46 core tests, 23 native UI/graphics tests, and 7 recovery tests. They include English UI under a Slovak device locale, English default report labels and decimal formatting, and importing custom JSON after removing the sample-import button.

## Departure reliability fix (2026-10-22)

Before the change, all 46 existing core tests passed, while all 7 initial departure regression tests failed against the original core. The regressions reproduce a lunch return followed by a departure with unavailable GPS and lost service memory.

After the change, **all 87 tests pass**: 46 existing core tests, 9 new departure tests, 23 native UI/graphics tests, and 9 recovery tests. The service test runs the actual `TrackingService` evaluation, destroys it, discards the repository singleton, reopens its AtomicFile, and confirms the original departure time after the service is recreated. Other cases cover Wi-Fi-only tracking, unknown sensors, GPS-only tracking, a current GPS fix inside the area, reconnection, rule changes, manual entries, explicit Stop, backup restore, and a single report row with one daily break.

This environment could download dependencies through Python/curl, but Gradle's Java dependency resolution could not reach its repositories. The full suite therefore ran directly with JUnit 4.13.2 and Robolectric 4.14.1, using the declared dependencies, API 28/35 instrumented Android jars, compiled API 35 app resources, and the app directory as the test working directory. All production Java sources also compiled successfully against Android SDK 35. No installable APK was assembled and the version remains 0.4.6.

No physical phone or emulator is connected. Robolectric checks the shared native Canvas rendering path, not actual PDF bytes from Android PdfDocument. The instrumented PDF test is under `app/src/androidTest/`. Long-running tracking, real GPS/Wi-Fi events, restarts, and manufacturer power-saving policies still require device testing.
