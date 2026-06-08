package com.datanote;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DataNote 数据开发平台启动类
 */
@SpringBootApplication
@MapperScan({"com.datanote.domain.consumption.mapper", "com.datanote.domain.datasource.mapper", "com.datanote.domain.develop.mapper", "com.datanote.domain.governance.mapper", "com.datanote.domain.integration.mapper", "com.datanote.domain.mdm.mapper", "com.datanote.domain.metadata.mapper", "com.datanote.domain.orchestration.mapper", "com.datanote.domain.project.mapper", "com.datanote.platform.alert.mapper", "com.datanote.platform.audit.mapper", "com.datanote.platform.config.mapper", "com.datanote.platform.iam.mapper", "com.datanote.platform.portal.mapper", "com.datanote.platform.ai.agent.mapper"})
@EnableScheduling
@EnableAsync
public class DataNoteApplication {

    /**
     * 应用启动入口
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(DataNoteApplication.class, args);
    }
}
