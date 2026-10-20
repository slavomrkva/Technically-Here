# Verification

Current working source version: 0.4.6. This publication contains no installable APK.

Private templates and reference documents are excluded. The form under `test-fixtures/` is synthetic: a white background, an example Wi-Fi name, and generic fields. It is used only by automated tests.

All 76 unit tests pass in the English source version: 46 core tests, 23 native UI/graphics tests, and 7 recovery tests. They include English UI under a Slovak device locale, English default report labels and decimal formatting, and importing custom JSON after removing the sample-import button. Only `:app:testDebugUnitTest` was run; no installable APK was assembled.

No physical phone or emulator is connected. Robolectric checks the shared native Canvas rendering path, not actual PDF bytes from Android PdfDocument. The instrumented PDF test is under `app/src/androidTest/`. Long-running tracking, real GPS/Wi-Fi events, restarts, and manufacturer power-saving policies still require device testing.
