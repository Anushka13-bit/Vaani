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
    "Help me please.",
    "Open the door.",
    "Send a message to Ravi.",
    "Set an alarm for seven.",
    "I want to go home.",
    "Play some music.",
    "Can you repeat that?",
    "Yes, that is correct.",
    "No thank you.",
    "I am hungry.",
    "Call the doctor.",
    "Turn up the volume.",
    "Search for nearby restaurants.",
    "Read my messages.",
    "Set a reminder.",
    "Good morning.",
    "Good night.",
    "I love you.",
    "Thank you very much.",
    "I need help.",
    "Please slow down.",
    "Where is the bathroom?",
    "I am in pain.",
    "Can I have some food?",
    "I want to sleep.",
    "Please be quiet.",
    "I do not understand.",
    "Say that again.",
    "Call an ambulance.",
    "I am cold.",
    "I am hot.",
    "Open a new tab.",
    "Take a photo.",
    "Pause the video.",
    "Stop playing music.",
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
    """Insert English calibration prompts if they don't exist yet."""
    from sqlalchemy import select
    result = await session.execute(
        select(PromptRecord).where(PromptRecord.prompt_set_id == ENGLISH_PROMPT_SET_ID).limit(1)
    )
    if result.scalar_one_or_none() is not None:
        return  # already seeded

    for i, text in enumerate(ENGLISH_PROMPTS, start=1):
        session.add(PromptRecord(
            prompt_id=f"torgo_en_{i:03d}",
            prompt_set_id=ENGLISH_PROMPT_SET_ID,
            text=text,
            language="en",
        ))
