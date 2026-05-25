from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import uuid4

from fastapi import Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.session import get_optional_db_session
from app.models.entities import InterviewMaterial
from app.schemas.interviews import InterviewType
from app.services.interview_material_context import InterviewMaterialContext


def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def normalize_email(email: str) -> str:
    return email.strip().lower()


@dataclass(frozen=True)
class InterviewMaterialDraft:
    user_email: str
    interview_type: InterviewType
    resume_filename: str | None
    resume_content_type: str | None
    resume_text: str | None
    job_title: str | None
    job_requirements: str | None
    target_school: str | None
    major: str | None
    research_direction: str | None
    profile_summary: str
    keywords: list[str]


class InterviewMaterialStore:
    def create(self, draft: InterviewMaterialDraft) -> InterviewMaterialContext:
        raise NotImplementedError

    def get_owned(
        self,
        user_email: str,
        material_id: str | None,
        interview_type: InterviewType | None = None,
    ) -> InterviewMaterialContext | None:
        raise NotImplementedError


class InMemoryInterviewMaterialStore(InterviewMaterialStore):
    def __init__(self) -> None:
        self._records: dict[str, InterviewMaterialContext] = {}

    def create(self, draft: InterviewMaterialDraft) -> InterviewMaterialContext:
        record = InterviewMaterialContext(
            id=str(uuid4()),
            user_email=normalize_email(draft.user_email),
            interview_type=draft.interview_type,
            resume_filename=draft.resume_filename,
            resume_content_type=draft.resume_content_type,
            resume_text=draft.resume_text,
            job_title=draft.job_title,
            job_requirements=draft.job_requirements,
            target_school=draft.target_school,
            major=draft.major,
            research_direction=draft.research_direction,
            profile_summary=draft.profile_summary,
            keywords=draft.keywords,
            created_at=utc_now(),
        )
        self._records[record.id] = record
        return record

    def get_owned(
        self,
        user_email: str,
        material_id: str | None,
        interview_type: InterviewType | None = None,
    ) -> InterviewMaterialContext | None:
        if not material_id:
            return None
        record = self._records.get(material_id)
        if record is None or record.user_email != normalize_email(user_email):
            return None
        if interview_type is not None and record.interview_type != interview_type:
            return None
        return record


class DatabaseInterviewMaterialStore(InterviewMaterialStore):
    def __init__(self, session: Session) -> None:
        self._session = session

    def create(self, draft: InterviewMaterialDraft) -> InterviewMaterialContext:
        model = InterviewMaterial(
            user_email=normalize_email(draft.user_email),
            interview_type=str(draft.interview_type),
            resume_filename=draft.resume_filename,
            resume_content_type=draft.resume_content_type,
            resume_text=draft.resume_text,
            job_title=draft.job_title,
            job_requirements=draft.job_requirements,
            target_school=draft.target_school,
            major=draft.major,
            research_direction=draft.research_direction,
            profile_summary=draft.profile_summary,
            keywords_json=draft.keywords,
        )
        self._session.add(model)
        self._session.commit()
        return self._to_context(model)

    def get_owned(
        self,
        user_email: str,
        material_id: str | None,
        interview_type: InterviewType | None = None,
    ) -> InterviewMaterialContext | None:
        if not material_id:
            return None
        query = select(InterviewMaterial).where(
            InterviewMaterial.id == material_id,
            InterviewMaterial.user_email == normalize_email(user_email),
        )
        if interview_type is not None:
            query = query.where(InterviewMaterial.interview_type == str(interview_type))
        model = self._session.execute(query).scalar_one_or_none()
        return None if model is None else self._to_context(model)

    def _to_context(self, model: InterviewMaterial) -> InterviewMaterialContext:
        return InterviewMaterialContext(
            id=model.id,
            user_email=model.user_email,
            interview_type=InterviewType(model.interview_type),
            resume_filename=model.resume_filename,
            resume_content_type=model.resume_content_type,
            resume_text=model.resume_text,
            job_title=model.job_title,
            job_requirements=model.job_requirements,
            target_school=model.target_school,
            major=model.major,
            research_direction=model.research_direction,
            profile_summary=model.profile_summary,
            keywords=list(model.keywords_json or []),
            created_at=model.created_at,
        )


def get_optional_material_db_session():
    yield from get_optional_db_session(("interview_materials",))


memory_interview_material_store = InMemoryInterviewMaterialStore()


def get_interview_material_store(
    db_session: Session | None = Depends(get_optional_material_db_session),
) -> InterviewMaterialStore:
    if db_session is None:
        return memory_interview_material_store
    return DatabaseInterviewMaterialStore(db_session)
