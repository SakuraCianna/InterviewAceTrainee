from fastapi import HTTPException, status

from app.api.dependencies import TokenClaims
from app.services.interview_runtime import DatabaseInterviewRuntimeStore, InMemoryInterviewRuntimeStore, InterviewState


def require_owned_interview_session(
    claims: TokenClaims,
    session_id: str,
    interview_store: DatabaseInterviewRuntimeStore | InMemoryInterviewRuntimeStore,
) -> InterviewState:
    normalized_session_id = session_id.strip()
    if not normalized_session_id:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="interview_session_required")

    state = interview_store.get_session(claims["sub"], normalized_session_id)
    if state is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="interview_session_not_found")
    return state
