from __future__ import annotations

import logging
from uuid import uuid4

from fastapi import Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.error_messages import ERROR_MESSAGES_ZH, normalize_error_detail

logger = logging.getLogger("mianba.errors")


async def http_exception_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
    request_id = str(uuid4())
    code, message = normalize_error_detail(exc.detail)
    level = logging.WARNING if exc.status_code < 500 else logging.ERROR
    logger.log(level, "HTTP error %s %s code=%s request_id=%s", request.method, request.url.path, code, request_id)
    return JSONResponse(
        status_code=exc.status_code,
        content={"detail": code, "message": message, "request_id": request_id},
        headers=getattr(exc, "headers", None),
    )


async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    request_id = str(uuid4())
    logger.warning("Validation error %s %s request_id=%s errors=%s", request.method, request.url.path, request_id, exc.errors())
    return JSONResponse(
        status_code=422,
        content=jsonable_encoder({
            "detail": "request_validation_failed",
            "message": ERROR_MESSAGES_ZH["request_validation_failed"],
            "request_id": request_id,
            "errors": exc.errors(),
        }),
    )


async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    request_id = str(uuid4())
    logger.exception("Unhandled error %s %s request_id=%s", request.method, request.url.path, request_id)
    return JSONResponse(
        status_code=500,
        content={
            "detail": "internal_server_error",
            "message": ERROR_MESSAGES_ZH["internal_server_error"],
            "request_id": request_id,
        },
    )
