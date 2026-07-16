package icu.sakuracianna.mianba.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 将校验、业务和未知异常统一转换为不泄露内部实现的错误信封。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorEnvelope> handleApiException(ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status())
                .body(ErrorEnvelope.of(exception.detail(), exception.getMessage(), requestId(request), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorEnvelope> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<ErrorEnvelope.FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(ErrorEnvelope.of(
                "validation_failed", "请求参数不正确", requestId(request), errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorEnvelope> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        List<ErrorEnvelope.FieldError> errors = exception.getConstraintViolations().stream()
                .map(violation -> new ErrorEnvelope.FieldError(
                        violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(ErrorEnvelope.of(
                "validation_failed", "请求参数不正确", requestId(request), errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorEnvelope> handleMalformedJson(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ErrorEnvelope.of(
                "malformed_json", "请求 JSON 无法解析", requestId(request), List.of()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorEnvelope> handleUploadLimit(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(ErrorEnvelope.of(
                "resume_file_too_large", "上传文件不能超过 5 MiB", requestId(request), List.of()));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, ServletRequestBindingException.class})
    ResponseEntity<ErrorEnvelope> handleBadRequest(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ErrorEnvelope.of(
                "request_validation_failed", "请求参数或请求头不正确", requestId(request), List.of()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorEnvelope> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (exception.getSupportedHttpMethods() != null) {
            response.allow(exception.getSupportedHttpMethods().toArray(HttpMethod[]::new));
        }
        return response.body(ErrorEnvelope.of(
                "method_not_allowed", "请求方法不受支持", requestId(request), List.of()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorEnvelope> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ErrorEnvelope.of(
                "unsupported_media_type", "请求 Content-Type 不受支持", requestId(request), List.of()));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ErrorEnvelope> handleNotAcceptable(
            HttpMediaTypeNotAcceptableException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body(ErrorEnvelope.of(
                "response_type_not_acceptable", "无法生成客户端要求的响应格式", requestId(request), List.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorEnvelope> handleNotFound(
            NoResourceFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorEnvelope.of(
                "resource_not_found", "请求资源不存在", requestId(request), List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorEnvelope> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unhandled request failure request_id={}", requestId(request), exception);
        return ResponseEntity.internalServerError().body(ErrorEnvelope.of(
                "internal_error", "服务暂时不可用，请稍后重试", requestId(request), List.of()));
    }

    private static ErrorEnvelope.FieldError toFieldError(FieldError fieldError) {
        String message = fieldError.getDefaultMessage() == null ? "参数不正确" : fieldError.getDefaultMessage();
        return new ErrorEnvelope.FieldError(fieldError.getField(), message);
    }

    private static String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId instanceof String value ? value : "req_unknown";
    }
}
