from collections.abc import Callable, Generator
from contextlib import contextmanager, nullcontext
import logging
from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, Path, Response, status
from sqlalchemy.orm import Session

from app.api.dependencies import TokenClaims, get_current_user_claims
from app.api.providers import get_provider_config_store
from app.core.config import Settings, get_settings
from app.db.session import get_optional_db_session
from app.schemas.interviews import (
    InterviewAnswerRequest,
    InterviewAnswerResponse,
    InterviewHistoryItem,
    InterviewReportResponse,
    InterviewStartRequest,
    InterviewStartResponse,
    InterviewType,
)
from app.services.credit_balances import CreditBalanceStore, get_credit_balance_store
from app.services.credit_balances import DatabaseCreditBalanceStore
from app.services.credit_ledger import CreditLedgerStore, get_credit_ledger_store
from app.services.credit_ledger import DatabaseCreditLedgerStore
from app.services.credits import CreditLedger, CreditLedgerEntry, InsufficientCreditsError
from app.services.ai_call_logs import AICallLogStore, get_ai_call_log_store
from app.services.content_safety import BLOCKED_MESSAGE_CODE, check_user_answer
from app.services.content_safety_logs import ContentSafetyLogStore, get_content_safety_log_store
from app.services.interview_runtime import (
    DatabaseInterviewRuntimeStore,
    InMemoryInterviewRuntimeStore,
    InterviewSessionConflictError,
    InterviewSessionNotFoundError,
    InterviewState,
    memory_interview_store,
)
from app.services.interview_materials import InterviewMaterialStore, get_interview_material_store
from app.services.interview_materials import DatabaseInterviewMaterialStore
from app.services.ai_router import AIServiceRouter
from app.services.capacity_gate import acquire_capacity
from app.services.interview_ai import assess_answer_quality, build_contextual_fallback_question, generate_next_interview_question
from app.services.interview_capability_retrieval import build_capability_prompt_context
from app.services.interview_products import get_interview_product
from app.services.interview_presets import build_preset_prompt_context
from app.services.interview_vouchers import DatabaseInterviewVoucherStore, InterviewVoucherStore, get_interview_voucher_store
from app.services.llm_gateway import OpenAICompatibleLLMClient
from app.services.provider_configs import DatabaseProviderConfigStore, InMemoryProviderConfigStore

router = APIRouter(prefix="/interviews", tags=["interviews"])
logger = logging.getLogger("mianba.interviews")

INTERVIEW_REQUIRED_TABLES = ("interview_sessions", "interview_turns", "interview_reports", "interview_materials")
INTERVIEW_START_REQUIRED_TABLES = (
    "users",
    "credit_ledger",
    "interview_vouchers",
    "interview_sessions",
    "interview_turns",
    "interview_reports",
    "interview_materials",
)


def get_optional_interview_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(INTERVIEW_REQUIRED_TABLES)


def get_optional_interview_start_db_session() -> Generator[Session | None, None, None]:
    yield from get_optional_db_session(INTERVIEW_START_REQUIRED_TABLES)


def get_interview_store(
    db_session: Session | None = Depends(get_optional_interview_db_session),
) -> DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore:
    if db_session is None:
        return memory_interview_store
    return DatabaseInterviewRuntimeStore(db_session)


def build_start_response(
    state: InterviewState,
    credit_change: int,
    balance_after: int,
    ledger_reason: str,
    voucher_id: str | None = None,
) -> InterviewStartResponse:
    return InterviewStartResponse(
        session_id=state.session_id,
        interview_type=state.interview_type,
        credit_change=credit_change,
        balance_after=balance_after,
        ledger_reason=ledger_reason,
        voucher_applied=voucher_id is not None,
        voucher_id=voucher_id,
        status=state.status,
        current_step_index=state.current_step_index,
        total_steps=state.total_steps,
        current_question=state.current_question,
        report=state.report,
    )


def build_answer_response(state: InterviewState) -> InterviewAnswerResponse:
    return InterviewAnswerResponse(
        session_id=state.session_id,
        interview_type=state.interview_type,
        status=state.status,
        current_step_index=state.current_step_index,
        total_steps=state.total_steps,
        current_question=state.current_question,
        report=state.report,
    )


@router.post("", response_model=InterviewStartResponse, status_code=status.HTTP_201_CREATED)
def start_interview(
    payload: InterviewStartRequest,
    claims: TokenClaims = Depends(get_current_user_claims),
    fallback_credit_store: CreditBalanceStore = Depends(get_credit_balance_store),
    fallback_credit_ledger_store: CreditLedgerStore = Depends(get_credit_ledger_store),
    fallback_voucher_store: InterviewVoucherStore = Depends(get_interview_voucher_store),
    fallback_interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore = Depends(get_interview_store),
    fallback_material_store: InterviewMaterialStore = Depends(get_interview_material_store),
    start_db_session: Session | None = Depends(get_optional_interview_start_db_session),
) -> InterviewStartResponse:
    if start_db_session is not None:
        try:
            response = start_interview_with_stores(
                payload=payload,
                claims=claims,
                credit_store=DatabaseCreditBalanceStore(start_db_session, commit_on_write=False),
                credit_ledger_store=DatabaseCreditLedgerStore(start_db_session, commit_on_write=False),
                voucher_store=DatabaseInterviewVoucherStore(start_db_session, commit_on_write=False),
                interview_store=DatabaseInterviewRuntimeStore(start_db_session, commit_on_write=False),
                material_store=DatabaseInterviewMaterialStore(start_db_session),
            )
            start_db_session.commit()
            return response
        except Exception:
            start_db_session.rollback()
            raise

    return start_interview_with_stores(
        payload=payload,
        claims=claims,
        credit_store=fallback_credit_store,
        credit_ledger_store=fallback_credit_ledger_store,
        voucher_store=fallback_voucher_store,
        interview_store=fallback_interview_store,
        material_store=fallback_material_store,
    )


def start_interview_with_stores(
    payload: InterviewStartRequest,
    claims: TokenClaims,
    credit_store: CreditBalanceStore,
    credit_ledger_store: CreditLedgerStore,
    voucher_store: InterviewVoucherStore,
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore,
    material_store: InterviewMaterialStore,
) -> InterviewStartResponse:
    interview_type = payload.interview_type or InterviewType.JOB
    product = get_interview_product(interview_type)
    is_admin = claims["role"] == "admin"
    existing_state = interview_store.get_session(claims["sub"], payload.session_id)
    if existing_state is not None and existing_state.status != "completed":
        return build_start_response(
            existing_state,
            credit_change=0,
            balance_after=credit_store.get_balance(claims["sub"]),
            ledger_reason="interview_resume",
        )
    if existing_state is not None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="interview_session_already_completed")

    material_context = material_store.get_owned(claims["sub"], payload.material_id, interview_type)
    if interview_type in {InterviewType.JOB, InterviewType.POSTGRADUATE} and material_context is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="interview_material_required")
    if payload.material_id and material_context is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="interview_material_not_found")
    if interview_store.session_id_exists(payload.session_id):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="interview_session_id_conflict")

    try:
        voucher_id = None
        if is_admin:
            ledger = CreditLedger(initial_balance=credit_store.get_balance(claims["sub"]), is_admin=True)
            entry = ledger.consume_for_interview(
                payload.session_id,
                credit_cost=product.credit_cost,
                interview_type=str(interview_type),
            )
        else:
            voucher = voucher_store.redeem_for_interview(claims["sub"], payload.session_id, interview_type)
            if voucher is not None:
                voucher_id = voucher.id
                entry = CreditLedgerEntry(
                    change_amount=0,
                    balance_after=credit_store.get_balance(claims["sub"]),
                    reason=f"voucher_redeemed:{interview_type}",
                    related_session_id=payload.session_id,
                )
            else:
                balance_after = credit_store.adjust(claims["sub"], -product.credit_cost)
                entry = CreditLedger(
                    initial_balance=balance_after + product.credit_cost,
                    is_admin=False,
                ).consume_for_interview(
                    payload.session_id,
                    credit_cost=product.credit_cost,
                    interview_type=str(interview_type),
                )
    except InsufficientCreditsError as exc:
        raise HTTPException(status_code=status.HTTP_402_PAYMENT_REQUIRED, detail="insufficient_credits") from exc

    try:
        state = interview_store.create_session(
            claims["sub"],
            payload.session_id,
            interview_type,
            material_context,
            admin_unlimited_usage=is_admin,
        )
    except InterviewSessionConflictError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="interview_session_id_conflict") from exc
    credit_ledger_store.record(
        user_email=claims["sub"],
        change_amount=entry.change_amount,
        balance_after=entry.balance_after,
        reason=entry.reason,
        related_session_id=entry.related_session_id,
        operator_admin_email=claims["sub"] if is_admin else None,
        note=f"voucher:{voucher_id}" if voucher_id else None,
    )
    return build_start_response(
        state,
        credit_change=entry.change_amount,
        balance_after=entry.balance_after,
        ledger_reason=entry.reason,
        voucher_id=voucher_id,
    )


@router.get("/active", response_model=InterviewAnswerResponse)
def read_active_interview(
    claims: TokenClaims = Depends(get_current_user_claims),
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore = Depends(get_interview_store),
) -> InterviewAnswerResponse:
    state = interview_store.get_active_session(claims["sub"])
    if state is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="active_interview_not_found")
    return build_answer_response(state)


@router.get("/history", response_model=list[InterviewHistoryItem])
def read_interview_history(
    claims: TokenClaims = Depends(get_current_user_claims),
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore = Depends(get_interview_store),
) -> list[InterviewHistoryItem]:
    return [
        InterviewHistoryItem(
            session_id=record.session_id,
            interview_type=record.interview_type,
            status=record.status,
            current_step_index=record.current_step_index,
            total_steps=record.total_steps,
            report_total_score=record.report_total_score,
            created_at=record.created_at,
        )
        for record in interview_store.list_user_sessions(claims["sub"])
    ]


@router.get("/{session_id}", response_model=InterviewAnswerResponse)
def read_interview(
    session_id: Annotated[str, Path(min_length=1, max_length=120)],
    claims: TokenClaims = Depends(get_current_user_claims),
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore = Depends(get_interview_store),
) -> InterviewAnswerResponse:
    state = interview_store.get_session(claims["sub"], session_id)
    if state is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="interview_session_not_found")
    return build_answer_response(state)


@router.delete("/{session_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_interview(
    session_id: Annotated[str, Path(min_length=1, max_length=120)],
    claims: TokenClaims = Depends(get_current_user_claims),
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore = Depends(get_interview_store),
) -> Response:
    if not interview_store.delete_session(claims["sub"], session_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="interview_session_not_found")
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/{session_id}/answers", response_model=InterviewAnswerResponse)
def answer_interview_question(
    session_id: Annotated[str, Path(min_length=1, max_length=120)],
    payload: InterviewAnswerRequest,
    claims: TokenClaims = Depends(get_current_user_claims),
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore = Depends(get_interview_store),
    provider_store: DatabaseProviderConfigStore | InMemoryProviderConfigStore = Depends(get_provider_config_store),
    ai_call_log_store: AICallLogStore = Depends(get_ai_call_log_store),
    content_safety_log_store: ContentSafetyLogStore = Depends(get_content_safety_log_store),
    settings: Settings = Depends(get_settings),
) -> InterviewAnswerResponse:
    current_state = interview_store.get_session(claims["sub"], session_id)
    if current_state is not None and current_state.status != "completed":
        safety_decision = check_user_answer(payload.answer_text, current_state.interview_type)
        if safety_decision.categories:
            content_safety_log_store.record_decision(
                user_email=claims["sub"],
                session_id=session_id,
                source="user_answer",
                decision=safety_decision,
                content=payload.answer_text,
            )
        if not safety_decision.allowed:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=safety_decision.message_code or BLOCKED_MESSAGE_CODE,
            )
    next_question_override = None
    if current_state is not None and current_state.current_question is not None:
        answer_quality = assess_answer_quality(
            current_state.interview_type,
            current_state.current_question.text,
            payload.answer_text,
            current_state.current_question.round_name,
        )
        if not answer_quality.acceptable:
            try:
                state = interview_store.answer_current_question(
                    claims["sub"],
                    session_id,
                    payload.answer_text,
                    retry_question_override=answer_quality.retry_question,
                )
            except InterviewSessionNotFoundError as exc:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="interview_session_not_found") from exc
            return build_answer_response(state)

        try:
            next_question_override = build_next_question_override(
                current_state=current_state,
                answer_text=payload.answer_text,
                provider_store=provider_store,
                ai_call_log_store=ai_call_log_store,
                content_safety_log_store=content_safety_log_store,
                settings=settings,
                user_email=claims["sub"],
                capability_db_connection_factory=lambda: capability_db_connection_for_store(interview_store),
            )
        except Exception:
            logger.warning("Failed to generate adaptive follow-up for session_id=%s", session_id, exc_info=True)

    try:
        state = interview_store.answer_current_question(
            claims["sub"],
            session_id,
            payload.answer_text,
            next_question_override=next_question_override,
        )
    except InterviewSessionNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="interview_session_not_found") from exc
    return build_answer_response(state)


@router.get("/{session_id}/report", response_model=InterviewReportResponse)
def read_interview_report(
    session_id: Annotated[str, Path(min_length=1, max_length=120)],
    claims: TokenClaims = Depends(get_current_user_claims),
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore = Depends(get_interview_store),
) -> InterviewReportResponse:
    report = interview_store.get_report(claims["sub"], session_id)
    if report is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="interview_report_not_found")
    return report


def build_next_question_override(
    current_state: InterviewState,
    answer_text: str,
    provider_store: DatabaseProviderConfigStore | InMemoryProviderConfigStore,
    ai_call_log_store: AICallLogStore,
    content_safety_log_store: ContentSafetyLogStore,
    settings: Settings,
    user_email: str,
    capability_db_connection: Any | None = None,
    capability_db_connection_factory: Callable[[], Any] | None = None,
) -> str | None:
    if current_state.current_step_index + 1 >= current_state.total_steps:
        return None
    with open_capability_db_connection_context(
        capability_db_connection_factory,
        capability_db_connection,
    ) as db_connection:
        if current_state.next_question is not None:
            next_round_name = current_state.next_question.round_name
            next_static_question = current_state.next_question.text
        else:
            steps = build_interview_steps_for_state(current_state, db_connection)
            next_step = steps[current_state.current_step_index + 1]
            next_round_name = next_step.round_name
            next_static_question = next_step.question_text
        preset_context = build_preset_prompt_context(
            current_state.interview_type,
            current_state.material_context,
            round_name=next_round_name,
        )
        capability_context = build_capability_prompt_context(
            current_state.interview_type,
            current_state.material_context,
            round_name=next_round_name,
            db_connection=db_connection,
        )
    trusted_context = "\n\n".join(context for context in (preset_context, capability_context) if context)
    with acquire_capacity(
        "llm:interview_followup",
        settings.llm_concurrency_limit,
        settings.llm_capacity_lease_seconds,
    ) as lease:
        if not lease.acquired:
            logger.warning(
                "LLM follow-up capacity exhausted active_count=%s retry_after=%s session_id=%s",
                lease.active_count,
                lease.retry_after_seconds,
                current_state.session_id,
            )
            return None
        generated_question = generate_next_interview_question(
            router=AIServiceRouter(provider_store.list_configs()),
            llm_client=OpenAICompatibleLLMClient(settings),
            interview_type=current_state.interview_type,
            current_question=current_state.current_question.text,
            answer_text=answer_text,
            next_round_name=next_round_name,
            next_static_question=next_static_question,
            preset_context=trusted_context,
            call_log_store=ai_call_log_store,
            content_safety_log_store=content_safety_log_store,
            session_id=current_state.session_id,
            user_email=user_email,
        )
        return generated_question or build_contextual_fallback_question(
            current_state.interview_type,
            answer_text,
            next_round_name,
            next_static_question,
        )


def build_interview_steps_for_state(current_state: InterviewState, capability_db_connection: Any | None = None):
    from app.services.interview_runtime import build_interview_steps

    return build_interview_steps(
        current_state.interview_type,
        current_state.material_context,
        capability_db_connection=capability_db_connection,
    )


def open_capability_db_connection_context(
    capability_db_connection_factory: Callable[[], Any] | None,
    capability_db_connection: Any | None,
):
    if capability_db_connection_factory is not None:
        return capability_db_connection_factory()
    return nullcontext(capability_db_connection)


@contextmanager
def capability_db_connection_for_store(
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore,
) -> Generator[Any | None, None, None]:
    if isinstance(interview_store, DatabaseInterviewRuntimeStore):
        with interview_store.open_capability_db_connection() as capability_db_connection:
            yield capability_db_connection
        return
    yield None
