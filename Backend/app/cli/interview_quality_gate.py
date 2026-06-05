import json
import sys

from app.services.interview_quality_gate import evaluate_interview_quality


def main() -> int:
    report = evaluate_interview_quality()
    print(json.dumps(report.to_dict(), ensure_ascii=False, indent=2))
    return 0 if report.passed else 1


if __name__ == "__main__":
    sys.exit(main())
