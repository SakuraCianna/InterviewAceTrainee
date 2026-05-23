from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api import admin, auth, health, interview_products, interviews, providers
from app.core.config import get_settings


def create_app() -> FastAPI:
    settings = get_settings()
    application = FastAPI(title=settings.app_name, version="0.1.0")

    application.add_middleware(
        CORSMiddleware,
        allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    application.include_router(health.router, prefix=settings.api_prefix)
    application.include_router(auth.router, prefix=settings.api_prefix)
    application.include_router(interview_products.router, prefix=settings.api_prefix)
    application.include_router(interviews.router, prefix=settings.api_prefix)
    application.include_router(providers.router, prefix=settings.api_prefix)
    application.include_router(admin.router, prefix=settings.api_prefix)
    return application


app = create_app()
