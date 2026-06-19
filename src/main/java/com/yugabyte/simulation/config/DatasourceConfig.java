package com.yugabyte.simulation.config;

import java.util.Properties;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Configuration
public class DatasourceConfig {
//	@Bean
//	@ConfigurationProperties("app.datasource")
//	public HikariDataSource dataSource() {
//		DataSourceProperties props  = new DataSourceProperties();
//	    return (HikariDataSource) DataSourceBuilder.create()
//	            .type(HikariDataSource.class)
//	            .build();
//	}
	
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public HikariConfig hikariConfig() {
    	HikariConfig config = new HikariConfig();
    	config.setInitializationFailTimeout(-1);
    	return config;
    }

    @Bean
    @Primary // may not be required
    public DynamicDataSource dataSource() {
    	HikariDataSource ds = new HikariDataSource(hikariConfig());
    	return new DynamicDataSource(ds);
    }
}