from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.api import admin, auth, health, interview_materials, interview_products, interviews, providers, speech, websocket
from app.core.config import get_settings
from app.core.exceptions import http_exception_handler, unhandled_exception_handler, validation_exception_handler
from app.core.logging import configure_logging


def create_app() -> FastAPI:
    configure_logging()
    settings = get_settings()
    application = FastAPI(title=settings.app_name, version="0.1.0")
    application.add_exception_handler(StarletteHTTPException, http_exception_handler)
    application.add_exception_handler(RequestValidationError, validation_exception_handler)
    application.add_exception_handler(Exception, unhandled_exception_handler)

    application.add_middleware(
        CORSMiddleware,
        allow_origins=[origin.strip() for origin in settings.cors_origins.split(",") if origin.strip()],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    application.include_router(health.router, prefix=settings.api_prefix)
    application.include_router(auth.router, prefix=settings.api_prefix)
    application.include_router(interview_products.router, prefix=settings.api_prefix)
    application.include_router(interview_materials.router, prefix=settings.api_prefix)
    application.include_router(interviews.router, prefix=settings.api_prefix)
    application.include_router(speech.router, prefix=settings.api_prefix)
    application.include_router(providers.router, prefix=settings.api_prefix)
    application.include_router(admin.router, prefix=settings.api_prefix)
    application.include_router(websocket.router, prefix=settings.api_prefix)
    return application


app = create_app()
