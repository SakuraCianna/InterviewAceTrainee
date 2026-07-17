package icu.sakuracianna.mianba.identity.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;

/** 验证身份配置 Repository 能使用生产环境默认的 CGLIB 类代理。 */
class JdbcIdentitySettingsProviderProxyTest {
    @Test
    void repositorySupportsClassBasedPersistenceExceptionTranslationProxy() {
        ProxyFactory proxyFactory = new ProxyFactory(
                new JdbcIdentitySettingsProvider(mock(JdbcTemplate.class)));
        proxyFactory.setProxyTargetClass(true);

        Object proxy = proxyFactory.getProxy();

        assertThat(AopUtils.isCglibProxy(proxy)).isTrue();
    }
}
