package com.demo.demo_app;

import liquibase.integration.spring.SpringLiquibase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class LiquibaseConfig {

    private static final Logger log = LoggerFactory.getLogger(LiquibaseConfig.class);

    @Bean
    SpringLiquibase liquibase() {
        log.info(">>> LiquibaseConfig : initialisation du DataSource sur localhost:5555");

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://localhost:5555/demodb");
        dataSource.setUsername("demo");
        dataSource.setPassword("demo123");

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        return liquibase;
    }
}
