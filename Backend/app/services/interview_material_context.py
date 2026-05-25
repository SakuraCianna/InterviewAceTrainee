from dataclasses import dataclass
from datetime import datetime

from app.schemas.interviews import InterviewType


@dataclass(frozen=True)
class InterviewMaterialContext:
    id: str
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
    created_at: datetime
