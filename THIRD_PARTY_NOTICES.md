# Third-party notices

The Android application source is provided under the GNU Affero General Public License, version 3, consistent with the existing PC project. The complete terms are in `LICENSE`, copied without alteration from the PC project's `LICENSE.txt`. This project license does not replace the licenses and notices of its dependencies.

## Runtime dependencies

| Component | Version | License | Purpose |
| --- | --- | --- | --- |
| PdfBox-Android, `com.tom-roush:pdfbox-android` | 2.0.27.0 | Apache License 2.0 | PDF parsing and internal outline writing |
| Gson, `com.google.code.gson:gson` | 2.14.0 | Apache License 2.0 | Strict JSON input handling |
| Bouncy Castle `bcprov-jdk15to18`, `bcpkix-jdk15to18`, `bcutil-jdk15to18` | 1.72 | Bouncy Castle license, based on MIT | Transitive PdfBox-Android dependencies |

PdfBox-Android is derived from Apache PDFBox. Preserve the Apache License and applicable PDFBox/PdfBox-Android NOTICE material when redistributing the dependency or the application. Upstream sources are [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) and [Apache PDFBox](https://pdfbox.apache.org/); the license is [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

Gson is maintained by Google. Its upstream source is [Gson](https://github.com/google/gson), and its license is Apache License 2.0. The 2.14.0 source archive is obtained from [Maven Central](https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.14.0/); its verified SHA-256 is pinned by the collection script and recorded in both source manifests. The copyright header remains unchanged from 2.11.0.

Bouncy Castle's published license is available at [Bouncy Castle license](https://www.bouncycastle.org/licence.html). Including its cryptographic libraries does not mean this application decrypts, modifies signatures or accepts protected PDFs; this first version rejects such inputs.

The versions above match this build's resolved Gradle dependency report. Full license and notice texts are retained in `licenses/` and copied byte-for-byte into `app/src/main/assets/licenses/` for the standalone APK; the project's full AGPL v3 text is also included there. `licenses/SOURCES.txt` records the official Maven/Unicode origins and SHA-256 values. Because the Android AAR and source JAR omit separate LICENSE/NOTICE entries, the corresponding Apache PDFBox 2.0.27 full license and notice are preserved together with actual PdfBox-Android source and resource copyright headers. PDFBox's full license also includes the Adobe resources, Liberation Fonts SIL OFL 1.1 and TwelveMonkeys terms. The Unicode data license is included separately. Rebuild this collection with `scripts/collect-third-party-assets.ps1`; this summary does not replace those full texts.

## Build and test components

JUnit 4.13.2 is used for tests under the Eclipse Public License 1.0. Its Hamcrest Core 1.3 dependency uses a BSD license. These test libraries are not application runtime features. Gradle Wrapper, Android Gradle Plugin, Android SDK and JDK are build tooling and are not distributed as part of the APK or application source archive, except for the project Gradle Wrapper files.

This APK does not bundle the PC application's Python, PyMuPDF/MuPDF, Tcl/Tk, TkDnD, RapidOCR, ONNX Runtime or OCR model files. Those libraries' existing PC notices remain with the unchanged PC distribution.
