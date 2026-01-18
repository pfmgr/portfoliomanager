package my.portfoliomanager.app.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TestDatabaseCleaner {
	private final JdbcTemplate jdbcTemplate;

	public TestDatabaseCleaner(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void clean() {
		jdbcTemplate.update("update depots set active_snapshot_id = null");
		jdbcTemplate.update("delete from snapshot_positions");
		jdbcTemplate.update("delete from snapshots");
		jdbcTemplate.update("delete from sparplans_history");
		jdbcTemplate.update("delete from sparplans");
		jdbcTemplate.update("delete from instrument_dossier_extractions");
		jdbcTemplate.update("delete from instrument_dossiers");
		jdbcTemplate.update("delete from knowledge_base_extractions");
		jdbcTemplate.update("delete from kb_runs");
		jdbcTemplate.update("delete from kb_alternatives");
		jdbcTemplate.update("delete from kb_config");
		jdbcTemplate.update("delete from instrument_facts");
		jdbcTemplate.update("delete from instrument_overrides");
		jdbcTemplate.update("delete from instrument_edits");
		jdbcTemplate.update("delete from rulesets");
		jdbcTemplate.update("delete from advisor_runs");
		jdbcTemplate.update("delete from import_files");
		jdbcTemplate.update("delete from layer_target_config");
		jdbcTemplate.update("delete from instruments");
		jdbcTemplate.update("delete from depots");
	}
}
