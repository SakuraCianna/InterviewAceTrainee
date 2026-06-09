import argparse
import json
from pathlib import Path
import sys

from sqlalchemy.exc import SQLAlchemyError

from app.db.session import engine
from app.services.interview_capability_retrieval import (
    capability_card_inventory,
    configured_embedding_model_name,
    create_embedding_provider,
    import_capability_vector_store_from_export,
    seed_capability_vector_store,
    write_capability_vector_export,
)


def main() -> int:
    parser = argparse.ArgumentParser(description="Seed real embedding vectors for interview capability cards.")
    parser.add_argument("--provider", default=None, help="Embedding provider, defaults to CAPABILITY_EMBEDDING_PROVIDER.")
    parser.add_argument("--model", default=None, help="Hugging Face model name, defaults to CAPABILITY_EMBEDDING_MODEL.")
    parser.add_argument("--device", default=None, help="Optional sentence-transformers device, such as cpu or cuda.")
    parser.add_argument("--batch-size", type=int, default=None, help="Embedding batch size.")
    parser.add_argument("--export-json", type=Path, default=None, help="Write an offline vector export JSON and exit.")
    parser.add_argument("--import-json", type=Path, default=None, help="Import an offline vector export JSON and exit.")
    args = parser.parse_args()

    if args.export_json is not None and args.import_json is not None:
        print(json.dumps({"ok": False, "error": "--export-json and --import-json cannot be used together"}, ensure_ascii=False, indent=2))
        return 1

    try:
        if args.import_json is not None:
            expected_embedding_model = configured_embedding_model_name(args.provider, args.model)
            with engine.begin() as connection:
                count = import_capability_vector_store_from_export(
                    connection,
                    args.import_json,
                    required=True,
                    expected_embedding_model=expected_embedding_model,
                )
            print(
                json.dumps(
                    {
                        "ok": True,
                        "imported_count": count,
                        "import_json": str(args.import_json),
                        "expected_embedding_model": expected_embedding_model,
                        "inventory": capability_card_inventory(),
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return 0

        provider = create_embedding_provider(
            provider_name=args.provider,
            model_name=args.model,
            device=args.device,
            batch_size=args.batch_size,
        )
        if args.export_json is not None:
            payload = write_capability_vector_export(
                args.export_json,
                embedding_provider=provider,
                batch_size=args.batch_size,
            )
            print(
                json.dumps(
                    {
                        "ok": True,
                        "exported_count": payload["card_count"],
                        "embedding_model": payload["embedding_model"],
                        "export_json": str(args.export_json),
                        "inventory": capability_card_inventory(),
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return 0

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
