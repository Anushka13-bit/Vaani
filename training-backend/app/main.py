"""
VaaniMitra Training Backend — FastAPI Entrypoint
Run with: uvicorn app.main:app --reload --port 8000
"""
from __future__ import annotations

import logging
import uuid
from contextlib import asynccontextmanager
from typing import AsyncGenerator

from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

import sys
from pathlib import Path

_PARENT_DIR = str(Path(__file__).resolve().parent.parent)
if _PARENT_DIR not in sys.path:
    sys.path.insert(0, _PARENT_DIR)

from app.config import settings
from app.db.init_db import init_db
from app.ml_registry_shim import run_adapter_registry
from app.models.pydantic_models import ErrorDetail, ErrorResponse
from app.routers import auth, calibration, adapters, corrections, caregiver, calibrate, session_adapter

logging.basicConfig(
    level=logging.DEBUG if settings.DEBUG else logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)


# ── Lifespan ──────────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    logger.info("Starting %s …", settings.APP_NAME)

    # 1. Create DB tables + seed calibration prompts
    await init_db()
    logger.info("Database initialised.")

    # 2. Scan ml/adapters/ and register static seed adapters
    await run_adapter_registry()
    logger.info("Adapter registry scan complete.")

    yield

    logger.info("Shutting down %s.", settings.APP_NAME)


# ── App ───────────────────────────────────────────────────────────────────────

app = FastAPI(
    title=settings.APP_NAME,
    version="1.0.0",
    description=(
        "Training backend for VaaniMitra — personalized dysarthric speech assistant. "
        "Manages calibration sessions, LoRA adapter storage/versioning, corrections, "
        "and caregiver endpoints. Local fine-tune + ONNX export when "
        "LIVE_TRAINING_ENABLED=true."
    ),
    docs_url="/docs",
    redoc_url="/redoc",
    lifespan=lifespan,
)

# ── CORS (open for local dev — restrict origins in production) ────────────────

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── Request ID middleware ─────────────────────────────────────────────────────

@app.middleware("http")
async def add_request_id(request: Request, call_next):  # type: ignore[no-untyped-def]
    request_id = str(uuid.uuid4())
    request.state.request_id = request_id
    response = await call_next(request)
    response.headers["X-Request-ID"] = request_id
    return response


# ── Routers ───────────────────────────────────────────────────────────────────

PREFIX = f"/{settings.API_VERSION}"

app.include_router(auth.router, prefix=PREFIX)
app.include_router(calibration.router, prefix=PREFIX)
app.include_router(calibrate.router, prefix=PREFIX)
app.include_router(session_adapter.router, prefix=PREFIX)
app.include_router(adapters.router, prefix=PREFIX)
app.include_router(corrections.router, prefix=PREFIX)
app.include_router(caregiver.router, prefix=PREFIX)


# ── Global exception handler (§5.6 error format) ─────────────────────────────

@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    request_id = getattr(request.state, "request_id", str(uuid.uuid4()))
    logger.exception("Unhandled exception [%s]: %s", request_id, exc)
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content=ErrorResponse(
            error=ErrorDetail(
                code="INTERNAL_SERVER_ERROR",
                message="An unexpected error occurred.",
                request_id=request_id,
            )
        ).model_dump(mode="json"),
    )


# ── Health check ──────────────────────────────────────────────────────────────

@app.get("/health", tags=["meta"])
async def health() -> dict:
    return {
        "status": "ok",
        "app": settings.APP_NAME,
        "live_training_enabled": settings.LIVE_TRAINING_ENABLED,
    }
