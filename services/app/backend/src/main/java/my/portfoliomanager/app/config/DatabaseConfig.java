package my.portfoliomanager.app.config;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.support.DatabaseStartupValidator;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(DatabaseConfig.LiquibaseSettings.class)
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
	public SpringLiquibase liquibase(DataSource dataSource, LiquibaseSettings properties) {
		SpringLiquibase liquibase = new SpringLiquibase();
		liquibase.setDataSource(dataSource);
		String changeLog = properties.getChangeLog();
		if (changeLog == null || changeLog.isBlank()) {
			changeLog = "classpath:db/changelog/db.changelog-master.yaml";
		}
		liquibase.setChangeLog(changeLog);
		liquibase.setShouldRun(properties.isEnabled());
		return liquibase;
	}

	@Bean
	public static BeanFactoryPostProcessor liquibaseDependsOnPostProcessor() {
		return beanFactory -> {
			ensureDependsOn(beanFactory, "entityManagerFactory", "liquibase");
			ensureDependsOn(beanFactory, "jpaSharedEM_entityManagerFactory", "liquibase");
		};
	}

	private static void ensureDependsOn(org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory,
										String beanName,
										String dependency) {
		if (!beanFactory.containsBeanDefinition(beanName)) {
			return;
		}
		BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
		String[] existing = definition.getDependsOn();
		Set<String> merged = new LinkedHashSet<>();
		if (existing != null) {
			merged.addAll(Arrays.asList(existing));
		}
		merged.add(dependency);
		definition.setDependsOn(merged.toArray(new String[0]));
	}

	@ConfigurationProperties(prefix = "spring.liquibase")
	public static class LiquibaseSettings {
		private String changeLog;
		private boolean enabled = true;

		public String getChangeLog() {
			return changeLog;
		}

		public void setChangeLog(String changeLog) {
			this.changeLog = changeLog;
		}

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}
	}
}
