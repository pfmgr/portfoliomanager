package my.portfoliomanager.app.config;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.support.DatabaseStartupValidator;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(LiquibaseProperties.class)
public class DatabaseConfig {
	@Bean
	public DatabaseStartupValidator databaseStartupValidator(DataSource dataSource) {
		DatabaseStartupValidator validator = new DatabaseStartupValidator();
		validator.setDataSource(dataSource);
		validator.setTimeout(60);
		validator.setInterval(5);
		return validator;
	}

	@Bean
	@DependsOn("databaseStartupValidator")
	public SpringLiquibase liquibase(DataSource dataSource, LiquibaseProperties properties) {
		SpringLiquibase liquibase = new SpringLiquibase();
		liquibase.setDataSource(dataSource);
		liquibase.setChangeLog(properties.getChangeLog());
		liquibase.setShouldRun(properties.isEnabled());
		return liquibase;
	}
}
