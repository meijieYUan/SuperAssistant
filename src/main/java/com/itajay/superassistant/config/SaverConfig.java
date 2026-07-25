package com.itajay.superassistant.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class SaverConfig {
    @Bean
    public MysqlSaver mysqlSaver(DataSource dataSource) {
        return MysqlSaver
                .builder()
                .dataSource(dataSource)
                .build();
    }

    @Bean
    public DataSource dataSource() {
        return DataSourceBuilder.create()
                .url("jdbc:mysql://localhost:3306/superassistant")
                .username("root")
                .password("123456")
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .build();
    }

}
