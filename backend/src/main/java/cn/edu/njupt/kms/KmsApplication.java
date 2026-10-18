package cn.edu.njupt.kms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 知识管理系统（Knowledge Management System）启动类。
 *
 * <p>《专业课程设计II》题目1 —— 南京邮电大学计算机学院。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan("cn.edu.njupt.kms.config")
@EnableJpaAuditing
@EnableAsync
@EnableScheduling
public class KmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(KmsApplication.class, args);
    }
}
