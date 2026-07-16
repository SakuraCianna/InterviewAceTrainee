package icu.sakuracianna.mianba.identity.persistence;

import icu.sakuracianna.mianba.identity.service.UserAccount;
import icu.sakuracianna.mianba.identity.service.UserAccountRepository;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 用户账号的 PostgreSQL 持久化实现，不向上层暴露密码哈希以外的凭据。 */
@Repository
public class JdbcUserAccountRepository implements UserAccountRepository {
    private static final RowMapper<UserAccount> ROW_MAPPER = JdbcUserAccountRepository::mapUser;
    private final JdbcTemplate jdbc;

    public JdbcUserAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findByEmail(String email) {
        List<UserAccount> rows = jdbc.query("""
                SELECT id, email::text, password_hash, role, credit_balance, is_active, auth_version
                FROM users WHERE email = ?
                """, ROW_MAPPER, email);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<UserAccount> findByEmailForUpdate(String email) {
        List<UserAccount> rows = jdbc.query("""
                SELECT id, email::text, password_hash, role, credit_balance, is_active, auth_version
                FROM users WHERE email = ?
                FOR UPDATE
                """, ROW_MAPPER, email);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<UserAccount> findById(UUID id) {
        List<UserAccount> rows = jdbc.query("""
                SELECT id, email::text, password_hash, role, credit_balance, is_active, auth_version
                FROM users WHERE id = ?
                """, ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<UserAccount> findByIdForUpdate(UUID id) {
        List<UserAccount> rows = jdbc.query("""
                SELECT id, email::text, password_hash, role, credit_balance, is_active, auth_version
                FROM users WHERE id = ?
                FOR UPDATE
                """, ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    @Override
    public int availableVoucherUses(UUID userId) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(sum(remaining_uses), 0)::integer
                FROM vouchers
                WHERE user_id = ? AND status = 'available' AND remaining_uses > 0
                  AND (expires_at IS NULL OR expires_at > now())
                """, Integer.class, userId);
        return value == null ? 0 : value;
    }

    @Override
    public UserAccount create(String email, String passwordHash, int initialCredits) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO users(id, email, password_hash, credit_balance)
                    VALUES (?, ?, ?, ?)
                    """, id, email, passwordHash, initialCredits);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "email_already_registered", "该邮箱已注册");
        }
        if (initialCredits > 0) {
            // 注册赠送也属于余额变化，必须和用户创建处于同一事务并留下可追溯台账。
            jdbc.update("""
                    INSERT INTO credit_ledger(
                        user_id, change_amount, balance_after, reason, idempotency_key, note
                    ) VALUES (?, ?, ?, 'registration_bonus', ?, 'new_user_default_credits')
                    """, id, initialCredits, initialCredits, "registration:" + id);
        }
        return findById(id).orElseThrow(() -> new IllegalStateException("Created user was not found"));
    }

    @Override
    public void updatePassword(UUID userId, String passwordHash) {
        int changed = jdbc.update("""
                UPDATE users
                SET password_hash = ?, auth_version = auth_version + 1,
                    version = version + 1, updated_at = now()
                WHERE id = ? AND is_active = true
                """, passwordHash, userId);
        if (changed != 1) {
            throw new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "用户不存在");
        }
    }

    private static UserAccount mapUser(ResultSet resultSet, int rowNumber) throws SQLException {
        return new UserAccount(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("email"),
                resultSet.getString("password_hash"),
                resultSet.getString("role"),
                resultSet.getInt("credit_balance"),
                resultSet.getBoolean("is_active"),
                resultSet.getLong("auth_version"));
    }
}
