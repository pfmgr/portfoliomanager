package my.portfoliomanager.app.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseBatchPlannerTest {
	@Test
	void buildBatches_respectsBatchSizeAndCharLimit() {
		KnowledgeBaseBatchPlanner planner = new KnowledgeBaseBatchPlanner();
		List<String> items = List.of("AAAA", "BBB", "CC", "DDDD");

		List<List<String>> batches = planner.buildBatches(items, 2, 6, String::length);

		assertThat(batches).hasSize(3);
		assertThat(batches.get(0)).containsExactly("AAAA");
		assertThat(batches.get(1)).containsExactly("BBB", "CC");
		assertThat(batches.get(2)).containsExactly("DDDD");
		assertThat(batches).allSatisfy(batch -> {
			int totalChars = batch.stream().mapToInt(String::length).sum();
			assertThat(batch.size()).isLessThanOrEqualTo(2);
			assertThat(totalChars).isLessThanOrEqualTo(6);
		});
	}
}
