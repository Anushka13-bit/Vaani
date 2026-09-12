# torgo_base_adapter_english_v1 — Static Seed Adapter

This directory holds the **TORGO cluster English v1** LoRA adapter,
which is the warm-start seed for all English-language user adapters.

---

## How to activate this adapter

1. **Download your trained LoRA adapter zip** from your training environment.
2. **Extract `adapter_model.bin`** (or rename your weights file to `adapter_model.bin`).
3. **Drop `adapter_model.bin` into this directory.**
4. **Restart the server** (`uvicorn app.main:app --reload`).

The `adapter_registry.py` startup hook will detect the file, compute its SHA-256 checksum,
and upsert the adapter record in the database automatically.

---

## Verification

After restarting, confirm registration:

```bash
curl http://localhost:8000/v1/adapters/clusters?language=en
# → { "adapter_id": "torgo_base_adapter_english_v1", "version": 1, "download_url": "..." }

curl http://localhost:8000/v1/adapters/torgo_base_adapter_english_v1
# → Full adapter metadata including checksum
```

---

## Expected file format

| Field | Value |
|---|---|
| `adapter_id` | `torgo_base_adapter_english_v1` |
| `type` | `CLUSTER` |
| `language_code` | `en` |
| `base_model` | `openai/whisper-small` |
| `version` | `1` |
| `weights_file` | `adapter_model.bin` |

The adapter must be a PEFT/LoRA adapter trained on top of `openai/whisper-small`.
Accepted formats: `.bin` (PyTorch) or `.safetensors` — if using safetensors,
update `weights_file` in `adapter_manifest.json` accordingly and re-drop.

---

## Directory contents

```
torgo_base_adapter_english_v1/
├── adapter_manifest.json   ← registration metadata (pre-filled, do not edit unless changing model)
├── adapter_model.bin       ← ← ← DROP YOUR WEIGHTS HERE
└── README.md               ← this file
```

The `.gitkeep` file (if present) keeps this directory tracked by git before the weights arrive.
**Do not commit `adapter_model.bin` to git** — add it to `.gitignore`.
