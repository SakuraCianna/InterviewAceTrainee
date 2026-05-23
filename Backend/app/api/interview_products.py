from fastapi import APIRouter

from app.schemas.interviews import InterviewProduct, InterviewType
from app.services.interview_products import get_interview_product, list_interview_products

router = APIRouter(prefix="/interview-products", tags=["interview-products"])


@router.get("", response_model=list[InterviewProduct])
def read_interview_products() -> list[InterviewProduct]:
    return list_interview_products()


@router.get("/{product_id}", response_model=InterviewProduct)
def read_interview_product(product_id: InterviewType) -> InterviewProduct:
    return get_interview_product(product_id)
