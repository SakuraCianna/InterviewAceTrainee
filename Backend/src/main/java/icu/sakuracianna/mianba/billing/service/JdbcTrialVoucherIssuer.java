package icu.sakuracianna.mianba.billing.service;

import icu.sakuracianna.mianba.identity.service.TrialVoucherIssuer;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 通过固定注册幂等键保证同一用户至多获得一张初始试用券。 */
@Component
public class JdbcTrialVoucherIssuer implements TrialVoucherIssuer {
    private final JdbcTemplate jdbc;

    public JdbcTrialVoucherIssuer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void issueRegistrationVouchers(UUID userId, int quantity) {
        if (quantity < 1) {
            return;
        }
        jdbc.update("""
                INSERT INTO vouchers(
                    user_id, issue_idempotency_key, voucher_type,
                    issue_reason, remaining_uses, status, note)
                VALUES (?, ?, 'new_user_trial', 'registration_bonus', ?, 'available', 'new_user_trial')
                """, userId, "registration:" + userId, quantity);
    }
}
