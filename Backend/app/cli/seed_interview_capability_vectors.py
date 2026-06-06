from __future__ import annotations

import json
import sys

from sqlalchemy import create_engine

from app.core.config import get_settings
from app.services.interview_capability_retrieval import (
    capability_card_inventory,
    seed_capability_vector_store,
)


def main() -> int:
    settings = get_settings()
    engine = create_engine(settings.database_url, pool_pre_ping=True)
    try:
        with engine.begin() as connection:
            seeded_count = seed_capability_vector_store(connection)
    except RuntimeError as exc:
        print(f"capability vector seed failed: {exc}", file=sys.stderr)
        return 1
    finally:
        engine.dispose()

    print(
        json.dumps(
            {
                "seeded_count": seeded_count,
                "inventory": capability_card_inventory(),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
