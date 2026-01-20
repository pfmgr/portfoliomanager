package my.portfoliomanager.app.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class TestDatabaseCleaner {
	private final JdbcTemplate jdbcTemplate;

	public TestDatabaseCleaner(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void clean() {
		List<String> tables = jdbcTemplate.queryForList("""
				select tablename
				from pg_tables
				where schemaname = 'public'
				  and tablename not in ('databasechangelog', 'databasechangeloglock')
				""", String.class);
		if (tables.isEmpty()) {
			return;
		}
		String joined = tables.stream()
				.map(name -> "\"" + name + "\"")
				.collect(Collectors.joining(", "));
		jdbcTemplate.execute("truncate table " + joined + " restart identity cascade");
	}
}
