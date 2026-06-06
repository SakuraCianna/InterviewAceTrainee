import argparse
import json
import sys

from sqlalchemy.exc import SQLAlchemyError

from app.db.session import engine
from app.services.interview_capability_retrieval import (
    capability_card_inventory,
    create_embedding_provider,
    seed_capability_vector_store,
)


def main() -> int:
    parser = argparse.ArgumentParser(description="Seed real embedding vectors for interview capability cards.")
    parser.add_argument("--provider", default=None, help="Embedding provider, defaults to CAPABILITY_EMBEDDING_PROVIDER.")
    parser.add_argument("--model", default=None, help="Hugging Face model name, defaults to CAPABILITY_EMBEDDING_MODEL.")
    parser.add_argument("--device", default=None, help="Optional sentence-transformers device, such as cpu or cuda.")
    parser.add_argument("--batch-size", type=int, default=None, help="Embedding batch size.")
    args = parser.parse_args()

    try:
        provider = create_embedding_provider(
            provider_name=args.provider,
            model_name=args.model,
            device=args.device,
            batch_size=args.batch_size,
        )
        with engine.begin() as connection:
            count = seed_capability_vector_store(
                connection,
                embedding_provider=provider,
                batch_size=args.batch_size,
            )
    except (RuntimeError, ValueError, SQLAlchemyError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False, indent=2))
        return 1

    print(
        json.dumps(
            {
                "ok": True,
                "seeded_count": count,
                "embedding_model": provider.model_name,
                "inventory": capability_card_inventory(),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
