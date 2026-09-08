"""
Comprehensive test runner for all VaaniMitra backend endpoints.
"""
import io
import json
import urllib.request
import urllib.error
import wave

BASE_URL = "http://127.0.0.1:8000"

def request(method, path, data=None, headers=None, files=None):
    url = f"{BASE_URL}{path}"
    headers = headers or {}
    
    if files:
        # Multipart form-data
        boundary = "----WebKitFormBoundaryTest123456"
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        body = bytearray()
        
        # Add normal form fields
        if data:
            for k, v in data.items():
                body.extend(f"--{boundary}\r\n".encode())
                body.extend(f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode())
                body.extend(f"{v}\r\n".encode())
                
        # Add files
        for field_name, (filename, file_bytes, mime_type) in files.items():
            body.extend(f"--{boundary}\r\n".encode())
            body.extend(f'Content-Disposition: form-data; name="{field_name}"; filename="{filename}"\r\n'.encode())
            body.extend(f"Content-Type: {mime_type}\r\n\r\n".encode())
            body.extend(file_bytes)
            body.extend(b"\r\n")
            
        body.extend(f"--{boundary}--\r\n".encode())
        req_data = bytes(body)
    elif data is not None:
        headers["Content-Type"] = "application/json"
        req_data = json.dumps(data).encode("utf-8")
    else:
        req_data = None
        
    req = urllib.request.Request(url, data=req_data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            status_code = resp.status
            content_type = resp.headers.get("Content-Type", "")
            raw = resp.read()
            if "application/json" in content_type:
                return status_code, json.loads(raw.decode("utf-8"))
            elif "octet-stream" in content_type:
                return status_code, f"<binary data: {len(raw)} bytes>"
            else:
                return status_code, raw.decode("utf-8")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw

def make_dummy_wav():
    buf = io.BytesIO()
    with wave.open(buf, 'wb') as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(16000)
        wav.writeframes(b'\x00\x00' * 16000)  # 1 second of silence
    return buf.getvalue()

def run_tests():
    results = []
    
    print("=" * 70)
    print("🚀 RUNNING VAANIMITRA BACKEND ENDPOINT TESTS")
    print("=" * 70)

    import uuid
    run_id = str(uuid.uuid4())[:8]

    # 1. Health
    st, res = request("GET", "/health")
    results.append(("GET /health", st, res))
    
    # 2. Auth - Register Device (User 1 - Patient)
    st, res = request("POST", "/v1/auth/device", {
        "device_id": f"phone-pixel-user-{run_id}",
        "preferred_language": "en",
        "dysarthria_severity_hint": "moderate"
    })
    results.append(("POST /v1/auth/device (Patient)", st, res))
    user_token = res["access_token"]
    user_id = res["user_id"]
    user_auth = {"Authorization": f"Bearer {user_token}"}
    
    # 3. Auth - Register Device (User 2 - Caregiver)
    st, res = request("POST", "/v1/auth/device", {
        "device_id": f"caregiver-laptop-{run_id}",
        "preferred_language": "en"
    })
    results.append(("POST /v1/auth/device (Caregiver)", st, res))
    caregiver_token = res["access_token"]
    caregiver_id = res["user_id"]
    caregiver_auth = {"Authorization": f"Bearer {caregiver_token}"}

    # 4. Calibration Prompts
    st, res = request("GET", "/v1/calibration/prompts?language=en&count=5", headers=user_auth)
    results.append(("GET /v1/calibration/prompts", st, res))
    prompt_set_id = res["prompt_set_id"]
    first_prompt_id = res["prompts"][0]["prompt_id"]

    # 5. Create Calibration Session
    st, res = request("POST", "/v1/calibration/sessions", {
        "user_id": user_id,
        "prompt_set_id": prompt_set_id
    }, headers=user_auth)
    results.append(("POST /v1/calibration/sessions", st, res))
    session_id = res["session_id"]

    # 6. Upload Calibration Audio Sample
    wav_data = make_dummy_wav()
    st, res = request("POST", f"/v1/calibration/sessions/{session_id}/samples",
                      data={"prompt_id": first_prompt_id},
                      files={"audio": ("sample1.wav", wav_data, "audio/wav")},
                      headers=user_auth)
    results.append((f"POST /v1/calibration/sessions/{session_id}/samples", st, res))

    # 7. Get Calibration Session Status
    st, res = request("GET", f"/v1/calibration/sessions/{session_id}/status", headers=user_auth)
    results.append((f"GET /v1/calibration/sessions/{session_id}/status", st, res))

    # 8. Trigger Training (Stubbed - expect 501)
    st, res = request("POST", f"/v1/calibration/sessions/{session_id}/train", headers=user_auth)
    results.append((f"POST /v1/calibration/sessions/{session_id}/train (STUBBED)", st, res))

    # 9. Get Cluster Adapters
    st, res = request("GET", "/v1/adapters/clusters?language=en", headers=user_auth)
    results.append(("GET /v1/adapters/clusters?language=en", st, res))
    adapter_id = res["adapter_id"]

    # 10. Get Adapter Metadata
    st, res = request("GET", f"/v1/adapters/{adapter_id}", headers=user_auth)
    results.append((f"GET /v1/adapters/{adapter_id}", st, res))

    # 11. Download Adapter Weights
    st, res = request("GET", f"/v1/adapters/{adapter_id}/download", headers=user_auth)
    results.append((f"GET /v1/adapters/{adapter_id}/download", st, res))

    # 12. Get User Adapter (expected 404 since user hasn't trained custom adapter)
    st, res = request("GET", f"/v1/users/{user_id}/adapter", headers=user_auth)
    results.append((f"GET /v1/users/{user_id}/adapter", st, res))

    # 13. Upload Corrections
    st, res = request("POST", "/v1/corrections", {
        "user_id": user_id,
        "corrections": [
            {
                "original_transcript": "I need otter",
                "corrected_transcript": "I need water",
                "confidence_at_time": 0.42,
                "audio_included": False
            }
        ]
    }, headers=user_auth)
    results.append(("POST /v1/corrections", st, res))

    # 14. Request Caregiver Link
    st, res = request("POST", "/v1/caregiver/link", {
        "user_id": user_id,
        "caregiver_email": "caregiver@vaanimitra.org",
        "permissions": ["VIEW_TRANSCRIPTS", "EDIT_PHRASEBOOK"]
    }, headers=user_auth)
    results.append(("POST /v1/caregiver/link", st, res))
    created_link_caregiver_id = res["caregiver_id"]

    # 15. Put Caregiver Phrasebook Entry (User editing own phrasebook or approved caregiver)
    st, res = request("PUT", f"/v1/caregiver/{user_id}/phrasebook/phrase_water_01", {
        "trigger_phrase": "need water",
        "action_type": "SPEAK_ALERT",
        "action_payload": {"text": "Patient requested drinking water", "urgency": "medium"}
    }, headers=user_auth)
    results.append((f"PUT /v1/caregiver/{user_id}/phrasebook/phrase_water_01", st, res))

    # 16. Get Caregiver Transcripts (as User viewing their own transcripts)
    st, res = request("GET", f"/v1/caregiver/{user_id}/transcripts?confidence_lt=0.8&limit=10", headers=user_auth)
    results.append((f"GET /v1/caregiver/{user_id}/transcripts (as Patient)", st, res))

    print("\n--- TEST SUMMARY REPORT ---")
    for name, code, body in results:
        print(f"\n[{code}] {name}")
        print("Response:", json.dumps(body, indent=2) if isinstance(body, dict) else body)

if __name__ == "__main__":
    run_tests()
