from fastapi.testclient import TestClient

from app.main import app


def test_interview_products_include_four_business_modules():
    client = TestClient(app)
    response = client.get("/api/interview-products")

    assert response.status_code == 200
    products = response.json()
    product_ids = {product["id"] for product in products}

    assert product_ids == {"job", "postgraduate", "civil_service", "ielts"}


def test_job_interview_product_has_two_professional_rounds_and_hr_round():
    client = TestClient(app)
    response = client.get("/api/interview-products/job")

    assert response.status_code == 200
    product = response.json()

    assert product["rounds"] == ["专业一面", "专业二面", "HR 面"]
    assert product["credit_cost"] == 3
    assert product["pricing_unit"] == "complete_experience"


def test_postgraduate_reexam_product_is_single_session():
    client = TestClient(app)
    response = client.get("/api/interview-products/postgraduate")

    assert response.status_code == 200
    product = response.json()

    assert product["rounds"] == ["复试模拟"]
    assert product["credit_cost"] == 1

