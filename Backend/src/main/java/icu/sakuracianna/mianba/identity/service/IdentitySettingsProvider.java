package icu.sakuracianna.mianba.identity.service;

/** 在认证事务内读取当前强类型业务开关的端口。 */
public interface IdentitySettingsProvider {
    /** 返回当前事务可见的完整身份配置快照；缺项或值损坏时应拒绝静默降级。 */
    IdentitySettings current();
}
