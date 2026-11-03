#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/test-classes
javac --release 8 -Xlint:-options -encoding UTF-8 -d build/test-classes \
    src/dev/eta/acefan/ChargeCurve.java src/dev/eta/acefan/WakeHandoff.java \
    src/dev/eta/acefan/Sampling.java src/dev/eta/acefan/CompatibilityCatalog.java \
    src/dev/eta/acefan/ReportFreshness.java src/dev/eta/acefan/DexFingerprint.java \
    src/dev/eta/acefan/FanServiceCompat.java src/dev/eta/acefan/ReferenceFanProfile.java \
    src/dev/eta/acefan/DiagnosticReport.java tests/*.java
for name in ChargeCurveTest WakeHandoffTest SamplingTest CompatibilityTest DiagnosticReportTest; do
    java -cp build/test-classes "dev.eta.acefan.$name"
done
if [ -f build/compat-fixtures/reference.apk ]; then
    python3 tests/make_code_fixtures.py build/compat-fixtures
    java -cp build/test-classes dev.eta.acefan.CodeProfileTest \
        build/compat-fixtures/reference.apk build/compat-fixtures/repacked.apk \
        build/compat-fixtures/changed-candidate.apk build/compat-fixtures/changed-protection.apk \
        build/compat-fixtures/malformed.apk
else
    echo 'CodeProfile real-APK tests SKIPPED: private local reference.apk is not available.'
fi
