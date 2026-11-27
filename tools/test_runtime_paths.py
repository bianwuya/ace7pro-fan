"""Source-contract checks for runtime paths that pure policy tests cannot cover."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class RuntimePathTests(unittest.TestCase):
    def test_fg_debug_parameter_is_module_parameter(self):
        text = (ROOT / "src/dev/eta/acefan/Telemetry.java").read_text()
        self.assertIn('FAN_DEBUG_RPM = "/sys/module/oplus_fan/parameters/dbg_rpm"', text)
        # Legacy Hook, independent root report and root FG callback use the actual parameter.
        self.assertEqual(text.count("readInt(FAN_DEBUG_RPM)"), 3)
        self.assertIn('Integer debug = root ? readInt(FAN_DEBUG_RPM) : readDebugRpm()', text)
        root_report = text.split('static void addKernelFanRpm(', 1)[1].split('private static void addFanRpm(', 1)[0]
        self.assertEqual(root_report.count("readInt(FAN_DEBUG_RPM)"), 1)
        self.assertNotIn('readDebugRpm()', root_report)
        self.assertEqual(text.count("readDebugRpm()"), 3)
        self.assertIn("FanDebugReport.parse(report, getBootId(), SystemClock.elapsedRealtime())", text)
        self.assertNotIn('FAN_ROOT + "dbg_rpm"', text)

    def test_fg_read_does_not_poll_legacy_measurement_nodes(self):
        text = (ROOT / "src/dev/eta/acefan/Telemetry.java").read_text()
        self.assertIsNone(re.search(r'read(?:Int|Long|Text)\(FAN_ROOT\s*\+\s*"(?:rpm|speed|duty)"', text))
        self.assertIn('FAN_RPM.accept(token, path, state, raw', text)


if __name__ == "__main__":
    unittest.main()
