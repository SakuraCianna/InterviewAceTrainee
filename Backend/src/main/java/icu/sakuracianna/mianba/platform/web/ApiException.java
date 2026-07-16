package icu.sakuracianna.mianba.platform.web;

import org.springframework.http.HttpStatus;

/** 携带稳定 HTTP 状态和业务错误码的可预期 API 异常。 */
public final class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String detail;

    public ApiException(HttpStatus status, String detail, String message) {
        super(message);
        this.status = status;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }

    public String detail() {
        return detail;
    }
}
