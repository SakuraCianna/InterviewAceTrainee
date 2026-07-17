package icu.sakuracianna.mianba;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 面霸练习生的统一启动入口。
 *
 * 同一构建产物通过 {@code mianba.runtime.role} 分别运行 API、AI Worker 或一次性迁移任务。
 * 这种部署方式可避免在 4 核 4 GB 主机上维护多套重复依赖。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MianbaApplication {
    /**
     * 启动当前配置角色的 Spring Boot 进程。
     *
     * @param args Spring Boot 命令行参数
     */
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(MianbaApplication.class, args);
        if ("migrate".equals(context.getEnvironment().getProperty("mianba.runtime.role"))) {
            // Flyway 在 ApplicationContext 返回前已经完成；关闭上下文使迁移容器成为一次性任务。
            context.close();
        }
    }
}
