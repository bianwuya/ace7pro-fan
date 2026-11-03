#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/test-classes
javac --release 8 -Xlint:-options -encoding UTF-8 -d build/test-classes \
    src/dev/eta/acefan/ChargeCurve.java src/dev/eta/acefan/WakeHandoff.java \
    src/dev/eta/acefan/Sampling.java src/dev/eta/acefan/CompatibilityCatalog.java \
    src/dev/eta/acefan/ReportFreshness.java src/dev/eta/acefan/FanServiceCompat.java \
    src/dev/eta/acefan/ControlCycle.java src/dev/eta/acefan/RuntimeEvidence.java src/dev/eta/acefan/DiagnosticReport.java tests/*.java
for name in ChargeCurveTest WakeHandoffTest SamplingTest CompatibilityTest DiagnosticReportTest RuntimeEvidenceTest ControlCycleTest; do
    java -cp build/test-classes "dev.eta.acefan.$name"
done
