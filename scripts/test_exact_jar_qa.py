import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).with_name("exact_jar_qa.py")
SPEC = importlib.util.spec_from_file_location("exact_jar_qa", SCRIPT)
QA = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(QA)


class ExactJarQaValidatorTest(unittest.TestCase):
    def test_stage9_lifecycle_accepts_complete_evidence(self):
        evidence = {
            "schemaVersion": 1,
            "status": "PASS",
            "resizePassed": True,
            "fullscreenPassed": True,
            "windowedRestorePassed": True,
            "surfaceSuspendRestorePassed": True,
            "originalWindowWidth": 1280,
            "originalWindowHeight": 720,
            "originalFramebufferWidth": 1280,
            "originalFramebufferHeight": 720,
            "resizedWindowWidth": 960,
            "resizedWindowHeight": 540,
            "ownershipPresentationDelta": 80,
            "openGlSuppressionDelta": 1,
            "ownershipInvalidationDelta": 0,
            "ownershipFailureDelta": 0,
        }

        self.assertIs(QA.stage9_lifecycle_is_valid(evidence), True)

    def test_stage9_lifecycle_rejects_incomplete_evidence(self):
        self.assertIs(QA.stage9_lifecycle_is_valid({}), False)

    def test_hardware_display_accepts_software_paced_200_hz(self):
        evidence = {
            "schemaVersion": 1,
            "status": "PASS",
            "connectedDisplays": 1,
            "displayMigrationRequired": False,
            "displayMigrationPassed": False,
            "retinaRequired": False,
            "retinaPassed": False,
            "retinaMonitor": "not-required",
            "retinaFramebufferScaleX": 0.0,
            "retinaFramebufferScaleY": 0.0,
            "minimumRefreshHz": 200,
            "reportedRefreshHz": 200,
            "refreshMonitor": "test-200-hz",
            "presentationMode": "software-paced-vsync-off",
            "presentationSamples": 600,
            "presentationIntervalP50Nanos": 5_000_000,
            "presentationIntervalP95Nanos": 7_000_000,
            "presentationIntervalP99Nanos": 9_000_000,
            "presentCallP50Nanos": 500_000,
            "measuredPresentationHz": 190.0,
            "presentationStutters": 0,
            "ownershipPresentationDelta": 600,
            "displayTransitionDelta": 1,
            "displayResetDelta": 1,
            "ownershipFailureDelta": 0,
        }

        self.assertIs(
            QA.hardware_display_is_valid(evidence, False, False, 200, 600),
            True,
        )

        evidence["ownershipFailureDelta"] = 1
        self.assertIs(
            QA.hardware_display_is_valid(evidence, False, False, 200, 600),
            False,
        )


if __name__ == "__main__":
    unittest.main()
