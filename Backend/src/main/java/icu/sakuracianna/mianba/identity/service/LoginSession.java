package icu.sakuracianna.mianba.identity.service;

import java.util.UUID;

/** 登录成功后签发 JWT 所需的账号与服务端会话 ID。 */
public record LoginSession(UserAccount user, UUID sessionId) {
}
