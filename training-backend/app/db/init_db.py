"""
VaaniMitra — DB initialisation + seed data
Called once at startup to create tables and seed calibration prompt fixtures.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import engine
from app.models.db_models import Base, PromptRecord


# ── English calibration prompts (TORGO-appropriate) ───────────────────────────
# 40 short sentences covering a broad phoneme distribution, suitable for
# dysarthric speech calibration with TORGO-trained seed adapters.

ENGLISH_PROMPTS = [
    "Please call my sister.",
    "I need some water.",
    "Turn off the light.",
    "What time is it?",
    "I am feeling tired.",
    "Can you help me please?",
    "Where are my glasses?",
    "I want to sit down.",
    "Open the front door.",
    "Close the window now.",
    "Turn on the fan.",
    "Bring me my medicine.",
    "I would like some tea.",
    "Call the doctor soon.",
    "Thank you very much.",
    "Good morning everyone.",
    "I feel much better today.",
    "Could you repeat that?",
    "Please speak a little louder.",
    "I am ready to leave.",
    "Where is my phone?",
    "Please turn up the volume.",
    "I need to go outside.",
    "What is the weather today?",
    "Let us go for a walk.",
    "I would like to eat now.",
    "Please bring me a blanket.",
    "The television is too loud.",
    "Can you open this jar?",
    "I will see you tomorrow.",
    "Please write this down.",
    "I am very hungry today.",
    "Pass me the napkin please.",
    "The room is getting cold.",
    "Please wait for a moment.",
    "I cannot find my keys.",
    "Let me think about it.",
    "Have a wonderful afternoon.",
    "I need to rest for a while.",
    "Everything is going well.",
]

ENGLISH_PROMPT_SET_ID = "torgo_en_v1"


async def init_db() -> None:
    """Create all tables and seed prompt data."""
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async with AsyncSession(engine) as session:
        await _seed_prompts(session)
        await session.commit()


async def _seed_prompts(session: AsyncSession) -> None:
    """Insert or sync English calibration prompts."""
    from sqlalchemy import delete
    await session.execute(
        delete(PromptRecord).where(PromptRecord.prompt_set_id == ENGLISH_PROMPT_SET_ID)
    )
    for i, text in enumerate(ENGLISH_PROMPTS, start=1):
        session.add(PromptRecord(
            prompt_id=f"torgo_en_{i:03d}",
            prompt_set_id=ENGLISH_PROMPT_SET_ID,
            text=text,
            language="en",
        ))
