"""
Comprehensive automated tests for VaaniMitra calibration storage/transfer/train/return pipeline.
"""
from __future__ import annotations

import io
import json
import os
import sys
import unittest
import uuid
import wave
from pathlib import Path

# Add project root to sys.path
_BACKEND_ROOT = Path(__file__).resolve().parent
if str(_BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(_BACKEND_ROOT))

from fastapi.testclient import TestClient
from app.main import app
from app.config import settings
from app.services.session_status import write_status, read_status


def generate_pcm_wav(num_samples: int = 16000) -> bytes:
    """Generate a valid 16kHz mono 16-bit PCM WAV in memory."""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        # 16-bit PCM silence/test tone
        w.writeframes(b"\x00\x00" * num_samples)
    return buf.getvalue()


class TestCalibrationPipeline(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)

    def test_01_health(self):
        resp = self.client.get("/health")
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json().get("status"), "ok")

    def test_02_get_40_prompts(self):
        resp = self.client.get("/v1/calibration/prompts?language=en&count=40")
        self.assertEqual(resp.status_code, 200)
        data = resp.json()
        prompts = data.get("prompts", [])
        self.assertEqual(len(prompts), 40, f"Expected 40 prompts, got {len(prompts)}")
        self.assertEqual(prompts[0]["text"], "Please call my sister.")
        self.assertEqual(prompts[-1]["text"], "Everything is going well.")

    def test_03_calibrate_batch_multipart_immediate_202(self):
        session_id = f"test_session_{uuid.uuid4().hex[:8]}"

        # Create 40 clips and a manifest mapping
        manifest_map = {}
        files_payload = []

        wav_bytes = generate_pcm_wav(16000)  # 1s 16kHz mono 16-bit PCM (32044 bytes)

        for i in range(1, 41):
            filename = f"phrase_{i:02d}.wav"
            prompt_text = f"Spoken phrase prompt number {i}"
            manifest_map[filename] = prompt_text
            files_payload.append(
                ("files", (filename, wav_bytes, "audio/wav"))
            )

        manifest_json_str = json.dumps({
            "session_id": session_id,
            "mapping": manifest_map
        })

        # Test POST /calibrate (both root mount and /v1 mount)
        data = {
            "session_id": session_id,
            "manifest_json": manifest_json_str
        }

        resp = self.client.post("/calibrate", data=data, files=files_payload)
        self.assertEqual(resp.status_code, 202, f"Expected 202, got {resp.status_code}: {resp.text}")
        body = resp.json()
        self.assertEqual(body.get("status"), "queued")
        self.assertEqual(body.get("session_id"), session_id)
        self.assertTrue("job_id" in body)

        # Check files were written to app/sessions/{session_id}/
        session_dir = settings.SESSIONS_DIR / session_id
        self.assertTrue(session_dir.is_dir(), f"Session dir does not exist: {session_dir}")

        manifest_file = session_dir / "manifest.json"
        self.assertTrue(manifest_file.is_file(), f"manifest.json missing in {session_dir}")

        saved_manifest = json.loads(manifest_file.read_text())
        self.assertTrue("phrase_01.wav" in saved_manifest.get("mapping", saved_manifest))

        # Check first and last audio clips
        clip_1 = session_dir / "phrase_01.wav"
        clip_40 = session_dir / "phrase_40.wav"
        self.assertTrue(clip_1.is_file(), "phrase_01.wav not saved")
        self.assertTrue(clip_40.is_file(), "phrase_40.wav not saved")
        self.assertGreater(clip_1.stat().st_size, 1000)

        status_data = read_status(session_id)
        self.assertIsNotNone(status_data)
        self.assertIn(status_data.get("status"), ["queued", "training", "failed"])

        # Confirm DB recorded the ground-truth prompt text from the manifest
        from app.models.db_models import CalibrationSampleRecord
        from app.workers.train_worker import _sync_db_session
        db = _sync_db_session()
        try:
            samples = db.query(CalibrationSampleRecord).filter(
                CalibrationSampleRecord.session_id == session_id
            ).all()
            self.assertEqual(len(samples), 40)
            sample_1 = next(s for s in samples if s.prompt_text == "Spoken phrase prompt number 1")
            self.assertIsNotNone(sample_1)
            # Ensure it is NOT the raw filename
            self.assertNotEqual(sample_1.prompt_text, "phrase_01")
        finally:
            db.close()

    def test_04_session_adapter_status_and_not_ready_404(self):
        session_id = f"test_poll_{uuid.uuid4().hex[:8]}"

        # Polling non-existent session gives 404
        resp_missing = self.client.get(f"/adapter/{session_id}/status")
        self.assertEqual(resp_missing.status_code, 404)

        # Write training status
        write_status(session_id, status="training", message="Fine-tuning LoRA on M3")
        resp_training = self.client.get(f"/adapter/{session_id}/status")
        self.assertEqual(resp_training.status_code, 200)
        self.assertEqual(resp_training.json().get("status"), "training")
        self.assertEqual(resp_training.json().get("progress_pct"), 40)

        # Downloading while in training gives 404 "not ready"
        resp_dl_early = self.client.get(f"/adapter/{session_id}")
        self.assertEqual(resp_dl_early.status_code, 404)
        self.assertIn("not ready", resp_dl_early.json().get("detail", "").lower())

        # When status is ready and mobile_bundle.zip exists
        adapter_id = f"user_{session_id}"
        export_dir = settings.MOBILE_EXPORT_DIR / adapter_id
        export_dir.mkdir(parents=True, exist_ok=True)
        bundle_zip = export_dir / "mobile_bundle.zip"
        bundle_zip.write_bytes(b"PK\x05\x06" + b"\x00" * 18)  # Empty zip

        write_status(session_id, status="ready", adapter_id=adapter_id, message="Ready")
        resp_ready = self.client.get(f"/adapter/{session_id}/status")
        self.assertEqual(resp_ready.status_code, 200)
        self.assertEqual(resp_ready.json().get("status"), "ready")
        self.assertEqual(resp_ready.json().get("progress_pct"), 100)

        resp_dl = self.client.get(f"/adapter/{session_id}")
        self.assertEqual(resp_dl.status_code, 200)
        self.assertEqual(resp_dl.headers.get("content-type"), "application/zip")


if __name__ == "__main__":
    unittest.main()
