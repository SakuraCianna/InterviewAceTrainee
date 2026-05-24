from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status

from app.api.dependencies import TokenClaims, get_current_user_claims
from app.core.config import Settings, get_settings
from app.schemas.interviews import InterviewMaterialResponse, InterviewType
from app.services.document_intake import (
    UnsupportedResumeFormatError,
    build_material_summary,
    extract_keywords,
    extract_resume_text,
    normalize_extracted_text,
)
from app.services.interview_material_context import InterviewMaterialContext
from app.services.interview_materials import (
    InterviewMaterialDraft,
    InterviewMaterialStore,
    get_interview_material_store,
)

router = APIRouter(prefix="/interview-materials", tags=["interview-materials"])


@router.post("", response_model=InterviewMaterialResponse, status_code=status.HTTP_201_CREATED)
async def create_interview_material(
    interview_type: Annotated[InterviewType, Form()],
    resume_file: Annotated[UploadFile | None, File()] = None,
    job_title: Annotated[str | None, Form(max_length=160)] = None,
    job_requirements: Annotated[str | None, Form(max_length=8000)] = None,
    major: Annotated[str | None, Form(max_length=160)] = None,
    research_direction: Annotated[str | None, Form(max_length=240)] = None,
    claims: TokenClaims = Depends(get_current_user_claims),
    store: InterviewMaterialStore = Depends(get_interview_material_store),
    settings: Settings = Depends(get_settings),
) -> InterviewMaterialResponse:
    normalized_job_title = _clean_optional(job_title)
    normalized_job_requirements = _clean_optional(job_requirements)
    normalized_major = _clean_optional(major)
    normalized_research_direction = _clean_optional(research_direction)

    if interview_type == InterviewType.JOB:
        if resume_file is None or not normalized_job_title or not normalized_job_requirements:
            raise HTTPException(status_code=422, detail="job_material_required_fields")
    if interview_type == InterviewType.POSTGRADUATE and not normalized_major:
        raise HTTPException(status_code=422, detail="postgraduate_major_required")

    resume_text = await _extract_uploaded_resume_text(resume_file, settings)
    keywords = extract_keywords(
        resume_text,
        normalized_job_title,
        normalized_job_requirements,
        normalized_major,
        normalized_research_direction,
    )
    summary = build_material_summary(
        interview_type=str(interview_type),
        resume_text=resume_text,
        job_title=normalized_job_title,
        job_requirements=normalized_job_requirements,
        major=normalized_major,
        research_direction=normalized_research_direction,
    )
    record = store.create(
        InterviewMaterialDraft(
            user_email=claims["sub"],
            interview_type=interview_type,
            resume_filename=resume_file.filename if resume_file is not None else None,
            resume_content_type=resume_file.content_type if resume_file is not None else None,
            resume_text=resume_text,
            job_title=normalized_job_title,
            job_requirements=normalized_job_requirements,
            major=normalized_major,
            research_direction=normalized_research_direction,
            profile_summary=summary,
            keywords=keywords,
        )
    )
    return _to_response(record)


async def _extract_uploaded_resume_text(resume_file: UploadFile | None, settings: Settings) -> str | None:
    if resume_file is None:
        return None
    payload = await resume_file.read()
    if len(payload) > settings.interview_material_max_upload_bytes:
        raise HTTPException(status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, detail="resume_file_too_large")
    try:
        extracted_text = extract_resume_text(
            resume_file.filename,
            resume_file.content_type,
            payload,
            ocr_provider=settings.resume_ocr_provider,
        )
    except UnsupportedResumeFormatError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    normalized_text = normalize_extracted_text(extracted_text)
    if not normalized_text:
        raise HTTPException(status_code=422, detail="resume_text_empty")
    return normalized_text


def _to_response(record: InterviewMaterialContext) -> InterviewMaterialResponse:
    resume_text = record.resume_text or ""
    return InterviewMaterialResponse(
        id=record.id,
        interview_type=record.interview_type,
        job_title=record.job_title,
        job_requirements=record.job_requirements,
        major=record.major,
        research_direction=record.research_direction,
        resume_filename=record.resume_filename,
        resume_content_type=record.resume_content_type,
        resume_text_preview=resume_text[:220] if resume_text else None,
        extracted_text_chars=len(resume_text),
        profile_summary=record.profile_summary,
        keywords=record.keywords,
        created_at=record.created_at,
    )


def _clean_optional(value: str | None) -> str | None:
    if value is None:
        return None
    cleaned = value.strip()
    return cleaned or None
