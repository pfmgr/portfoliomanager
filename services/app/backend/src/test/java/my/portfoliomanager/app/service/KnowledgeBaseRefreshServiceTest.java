package my.portfoliomanager.app.service;

import my.portfoliomanager.app.dto.KnowledgeBaseBulkResearchItemStatus;
import my.portfoliomanager.app.dto.KnowledgeBaseRefreshBatchRequestDto;
import my.portfoliomanager.app.dto.KnowledgeBaseRefreshBatchResponseDto;
import my.portfoliomanager.app.dto.KnowledgeBaseRefreshItemDto;
import my.portfoliomanager.app.dto.KnowledgeBaseRefreshScopeDto;
import my.portfoliomanager.app.domain.KnowledgeBaseRun;
import my.portfoliomanager.app.domain.KnowledgeBaseRunAction;
import my.portfoliomanager.app.domain.KnowledgeBaseRunStatus;
import my.portfoliomanager.app.llm.KnowledgeBaseLlmClient;
import my.portfoliomanager.app.repository.InstrumentDossierRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseRefreshServiceTest {
	@Mock
	private KnowledgeBaseConfigService configService;

	@Mock
	private KnowledgeBaseLlmClient llmClient;

	@Mock
	private KnowledgeBaseService knowledgeBaseService;

	@Mock
	private KnowledgeBaseRunService runService;

	@Mock
	private InstrumentDossierRepository dossierRepository;

	@Test
	void refreshBatch_respectsMaxBatchesPerRun() {
		KnowledgeBaseRefreshService service = new KnowledgeBaseRefreshService(
				configService,
				llmClient,
				knowledgeBaseService,
				runService,
				dossierRepository
		);
		when(configService.getSnapshot()).thenReturn(snapshot(2, 100));
		List<String> isins = sampleIsins(10);
		KnowledgeBaseRefreshBatchRequestDto request = new KnowledgeBaseRefreshBatchRequestDto(
				100,
				3,
				true,
				new KnowledgeBaseRefreshScopeDto(isins)
		);

		KnowledgeBaseRefreshBatchResponseDto result = service.refreshBatch(request, "tester");

		assertThat(result.processed()).isEqualTo(6);
		assertThat(result.skipped()).isEqualTo(6);
	}

	@Test
	void refreshBatch_respectsMaxInstrumentsPerRun() {
		KnowledgeBaseRefreshService service = new KnowledgeBaseRefreshService(
				configService,
				llmClient,
				knowledgeBaseService,
				runService,
				dossierRepository
		);
		when(configService.getSnapshot()).thenReturn(snapshot(5, 4));
		List<String> isins = sampleIsins(10);
		KnowledgeBaseRefreshBatchRequestDto request = new KnowledgeBaseRefreshBatchRequestDto(
				100,
				3,
				true,
				new KnowledgeBaseRefreshScopeDto(isins)
		);

		KnowledgeBaseRefreshBatchResponseDto result = service.refreshBatch(request, "tester");

		assertThat(result.processed()).isEqualTo(4);
		assertThat(result.skipped()).isEqualTo(4);
	}

	@Test
	void refreshSingle_skipsWhenRecentRunExists() {
		KnowledgeBaseRefreshService service = new KnowledgeBaseRefreshService(
				configService,
				llmClient,
				knowledgeBaseService,
				runService,
				dossierRepository
		);
		when(configService.getSnapshot()).thenReturn(snapshot(5, 100));
		KnowledgeBaseRun recent = new KnowledgeBaseRun();
		recent.setStatus(KnowledgeBaseRunStatus.SUCCEEDED);
		recent.setStartedAt(LocalDateTime.now().minusDays(1));
		when(runService.findLatest("DE0000000001", KnowledgeBaseRunAction.REFRESH))
				.thenReturn(Optional.of(recent));

		KnowledgeBaseRefreshItemDto result = service.refreshSingle("DE0000000001", null, "tester");

		assertThat(result.status()).isEqualTo(KnowledgeBaseBulkResearchItemStatus.SKIPPED);
	}

	private KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot snapshot(int maxBatches, int maxInstruments) {
		return new KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot(
				true,
				30,
				false,
				false,
				false,
				10,
				120000,
				2,
				maxBatches,
				300,
				maxInstruments,
				3,
				2,
				30,
				15000,
				7,
				30,
				"low",
				List.of("example.com"),
				2,
				true,
				0.6,
				true,
				null
		);
	}

	private List<String> sampleIsins(int count) {
		return java.util.stream.IntStream.rangeClosed(1, count)
				.mapToObj(i -> String.format("DE00000000%02d", i))
				.toList();
	}
}
