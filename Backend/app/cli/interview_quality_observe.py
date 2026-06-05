import argparse
import json
import sys

from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.db.session import SessionLocal
from app.models.entities import InterviewMaterial, InterviewSession, InterviewTurn
from app.schemas.interviews import InterviewType
from app.services.interview_material_context import InterviewMaterialContext
from app.services.interview_quality_observer import (
    ObservedInterviewSession,
    ObservedInterviewTurn,
    evaluate_observed_interviews,
)


def main() -> int:
    parser = argparse.ArgumentParser(description="Observe real interview records and calculate quality risk metrics.")
    parser.add_argument("--limit", type=int, default=200, help="Number of recent sessions to inspect.")
    parser.add_argument("--min-samples", type=int, default=30, help="Minimum real sessions required to claim pass.")
    args = parser.parse_args()

    try:
        with SessionLocal() as session:
            observed_sessions = _load_observed_sessions(session, limit=max(1, args.limit))
    except SQLAlchemyError as exc:
        print(json.dumps(database_unavailable_payload(str(exc)), ensure_ascii=False, indent=2))
        return 1
    report = evaluate_observed_interviews(observed_sessions, min_samples=max(1, args.min_samples))
    print(json.dumps(report.to_dict(), ensure_ascii=False, indent=2))
    return 0 if report.passed else 1


def database_unavailable_payload(error_message: str) -> dict[str, object]:
    return {
        "passed": False,
        "sample_status": "database_unavailable",
        "session_count": 0,
        "current_session_count": 0,
        "legacy_session_count": 0,
        "turn_count": 0,
        "wrong_question_risk_rate": 0.0,
        "flow_error_risk_rate": 0.0,
        "wrong_question_failures": [],
        "flow_failures": [],
        "scenario_metrics": {},
        "failure_summary": f"database unavailable: {error_message[:240]}",
    }


def _load_observed_sessions(session: Session, limit: int) -> list[ObservedInterviewSession]:
    session_models = list(
        session.execute(
            select(InterviewSession)
            .order_by(InterviewSession.created_at.desc())
            .limit(limit)
        ).scalars()
    )
    observed_sessions: list[ObservedInterviewSession] = []
    for session_model in session_models:
        try:
            interview_type = InterviewType(session_model.interview_type)
        except ValueError:
            continue
        turns = list(
            session.execute(
                select(InterviewTurn)
                .where(InterviewTurn.session_id == session_model.id)
                .order_by(InterviewTurn.turn_index)
            ).scalars()
        )
        observed_sessions.append(
            ObservedInterviewSession(
                session_id=session_model.id,
                interview_type=interview_type,
                material_context=_material_context(session, session_model.material_id),
                turns=[
                    ObservedInterviewTurn(
                        turn_index=turn.turn_index,
                        round_name=turn.round_name,
                        question_text=turn.question_text,
                        status=turn.status,
                    )
                    for turn in turns
                ],
            )
        )
    return observed_sessions


def _material_context(session: Session, material_id: str | None) -> InterviewMaterialContext | None:
    if not material_id:
        return None
    material = session.execute(
        select(InterviewMaterial).where(InterviewMaterial.id == material_id)
    ).scalar_one_or_none()
    if material is None:
        return None
    return InterviewMaterialContext(
        id=material.id,
        user_email=material.user_email,
        interview_type=InterviewType(material.interview_type),
        resume_filename=material.resume_filename,
        resume_content_type=material.resume_content_type,
        resume_text=material.resume_text,
        job_title=material.job_title,
        job_requirements=material.job_requirements,
        target_school=material.target_school,
        major=material.major,
        research_direction=material.research_direction,
        profile_summary=material.profile_summary,
        keywords=list(material.keywords_json or []),
        created_at=material.created_at,
    )


if __name__ == "__main__":
    sys.exit(main())
