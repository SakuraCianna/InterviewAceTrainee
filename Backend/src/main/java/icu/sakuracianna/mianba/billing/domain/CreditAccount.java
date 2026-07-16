package icu.sakuracianna.mianba.billing.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 使用乐观版本控制并禁止透支的余额领域聚合。 */
public final class CreditAccount {
    private final UUID userId;
    private final Map<String, CreditMutation> mutations = new LinkedHashMap<>();
    private int balance;
    private long version;

    /**
     * 从持久化快照恢复余额聚合。
     *
     * @param userId 余额所属用户
     * @param balance 当前可用余额
     * @param version 当前乐观锁版本
     */
    public CreditAccount(UUID userId, int balance, long version) {
        this.userId = Objects.requireNonNull(userId, "userId");
        if (balance < 0 || version < 0) {
            throw new IllegalArgumentException("balance and version must be non-negative");
        }
        this.balance = balance;
        this.version = version;
    }

    /**
     * 使用当前内存版本扣减余额，适用于聚合未跨线程或跨进程传递的场景。
     *
     * @return 已应用或幂等重放的余额流水
     */
    public synchronized CreditMutation debit(String idempotencyKey, int amount, String reason) {
        return debit(idempotencyKey, amount, reason, version);
    }

    /**
     * 使用幂等键和期望版本扣减余额。
     * 幂等判断必须先于版本判断，使相同请求在首次提交成功后仍可安全重放。
     *
     * @param idempotencyKey 业务操作唯一键
     * @param amount 正数扣减数量
     * @param reason 可审计的业务原因
     * @param expectedVersion 调用方读取到的余额版本
     * @return 已应用或幂等重放的余额流水
     * @throws StaleCreditVersionException 余额已被其他操作修改时抛出
     * @throws InsufficientCreditException 可用余额不足时抛出
     */
    public synchronized CreditMutation debit(String idempotencyKey, int amount, String reason, long expectedVersion) {
        CreditMutation duplicate = mutations.get(idempotencyKey);
        if (duplicate != null) {
            return duplicate;
        }
        if (expectedVersion != version) {
            throw new StaleCreditVersionException();
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (balance < amount) {
            throw new InsufficientCreditException();
        }
        balance -= amount;
        version++;
        CreditMutation mutation = new CreditMutation(
                Objects.requireNonNull(idempotencyKey, "idempotencyKey"),
                -amount,
                balance,
                Objects.requireNonNull(reason, "reason"));
        mutations.put(idempotencyKey, mutation);
        return mutation;
    }

    public UUID userId() {
        return userId;
    }

    public synchronized int balance() {
        return balance;
    }

    public synchronized long version() {
        return version;
    }

    public synchronized List<CreditMutation> mutations() {
        return List.copyOf(mutations.values());
    }
}
