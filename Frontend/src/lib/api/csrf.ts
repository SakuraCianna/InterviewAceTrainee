export const CSRF_COOKIE_NAME = "csrf_token";
export const CSRF_HEADER_NAME = "X-CSRF-Token";

/**
 * 读取双提交 CSRF Cookie。Cookie 本身不作为登录凭据，服务端仍会同时校验会话与请求头。
 */
export function getCookie(name: string) {
  const prefix = `${encodeURIComponent(name)}=`;
  const cookie = document.cookie
    .split(";")
    .map((item) => item.trim())
    .find((item) => item.startsWith(prefix));
  if (!cookie) {
    return "";
  }
  return decodeURIComponent(cookie.slice(prefix.length));
}

export function csrfHeaders(headers: HeadersInit = {}): HeadersInit {
  const token = getCookie(CSRF_COOKIE_NAME);
  if (!token) {
    return headers;
  }
  return {
    ...headers,
    [CSRF_HEADER_NAME]: token,
  };
}
