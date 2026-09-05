"""
Shim to call adapter_registry from the app package.
This avoids a circular import between app.main and ml.adapter_registry.
"""
from __future__ import annotations


async def run_adapter_registry() -> None:
    from ml.adapter_registry import scan_and_register_adapters
    await scan_and_register_adapters()
