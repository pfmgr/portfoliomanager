package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.KnowledgeBaseRun;
import my.portfoliomanager.app.domain.KnowledgeBaseRunAction;
import my.portfoliomanager.app.domain.KnowledgeBaseRunStatus;
import my.portfoliomanager.app.dto.KnowledgeBaseAlternativesResponseDto;
import my.portfoliomanager.app.dto.KnowledgeBaseBulkResearchItemDto;
import my.portfoliomanager.app.dto.KnowledgeBaseBulkResearchItemStatus;
import my.portfoliomanager.app.dto.KnowledgeBaseBulkResearchResponseDto;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionCreateRequestDto;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionDto;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionTrigger;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionType;
import my.portfoliomanager.app.repository.InstrumentDossierRepository;
import my.portfoliomanager.app.repository.KnowledgeBaseRunRepository;
import my.portfoliomanager.app.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = my.portfoliomanager.app.AppApplication.class)
@ActiveProfiles("test")
@Import(KnowledgeBaseLlmActionMigrationIntegrationTest.TestConfig.class)
class KnowledgeBaseLlmActionMigrationIntegrationTest {

	private static final String JWT_SECRET = UUID.randomUUID().toString();

	@Autowired
	private KnowledgeBaseLlmActionService actionService;

	@Autowired
	private KnowledgeBaseWebsearchJobService websearchJobService;

	@Autowired
	private KnowledgeBaseRunService runService;

	@Autowired
	private KnowledgeBaseRunRepository runRepository;

	@Autowired
	private KnowledgeBaseMaintenanceService maintenanceService;

	@Autowired
	private KnowledgeBaseService knowledgeBaseService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TestDatabaseCleaner databaseCleaner;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from kb_runs");
		reset(maintenanceService, knowledgeBaseService);
		when(knowledgeBaseService.resolveManualApprovals(anyList())).thenReturn(List.of());
		when(maintenanceService.bulkResearch(anyList(), anyBoolean(), anyBoolean(), anyString())).thenAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			List<String> isins = new ArrayList<>((List<String>) invocation.getArgument(0));
			return bulkResearchResponse(isins);
		});
		when(maintenanceService.findAlternatives(anyString(), anyBoolean(), anyString(), anySet())).thenAnswer(invocation ->
			alternativesResponse(invocation.getArgument(0, String.class)));
	}

	@AfterEach
	void tearDown() {
		databaseCleaner.clean();
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.security.admin-user", () -> "admin");
		registry.add("app.security.admin-pass", () -> "admin");
		registry.add("app.jwt.secret", () -> JWT_SECRET);
		registry.add("app.jwt.issuer", () -> "test-issuer");
	}

	@Test
	void sameActorSameIntentReplaysTheExistingDurableAction() {
		KnowledgeBaseLlmActionCreateRequestDto request = researchRequest("alice", "idem-key", List.of("DE0000000001"));

		KnowledgeBaseLlmActionDto first = actionService.create(request, "idem-key");
		KnowledgeBaseLlmActionDto replay = actionService.create(request, "idem-key");

		assertThat(replay.actionId()).isEqualTo(first.actionId());
		awaitTerminal(first.actionId());
	}

	@Test
	void sameActorSameKeyWithDifferentIntentConflicts() {
		KnowledgeBaseLlmActionDto first = actionService.create(researchRequest("alice", "idem-key", List.of("DE0000000001")), "idem-key");

		assertThatThrownBy(() -> actionService.create(researchRequest("alice", "idem-key", List.of("DE0000000002")), "idem-key"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("different workflow intent");

		awaitTerminal(first.actionId());
	}

	@Test
	void differentActorsUseIndependentIdempotencyScopes() {
		KnowledgeBaseLlmActionDto first = actionService.create(researchRequest("alice", "idem-key", List.of("DE0000000001")), "idem-key");
		KnowledgeBaseLlmActionDto second = actionService.create(researchRequest("bob", "idem-key", List.of("DE0000000001")), "idem-key");

		assertThat(second.actionId()).isNotEqualTo(first.actionId());
		awaitTerminal(first.actionId());
		awaitTerminal(second.actionId());
	}

	@Test
	void actionDetailAndListSurviveBookkeepingResetAndReconstruction() throws Exception {
		KnowledgeBaseLlmActionDto created = actionService.create(researchRequest("alice", "restart-key", List.of("DE0000000003")), "restart-key");
		awaitTerminal(created.actionId());

		clearSubmittedSet();
		actionService.recoverAndDispatch();

		List<KnowledgeBaseLlmActionDto> actions = actionService.listActions();
		assertThat(actions).extracting(KnowledgeBaseLlmActionDto::actionId).contains(created.actionId());

		KnowledgeBaseLlmActionDto detail = actionService.getAction(created.actionId());
		assertThat(detail.actionId()).isEqualTo(created.actionId());
		assertThat(detail.childTotal()).isEqualTo(1);
		assertThat(detail.childCompleted()).isEqualTo(1);
	}

	@Test
	void queuedActionCancellationPreventsDispatchAndResultWrite() throws Exception {
		String payload = actionPayload(KnowledgeBaseLlmActionType.ALTERNATIVES, List.of("DE0000000004"), "alice", "Queued alternatives search");
		KnowledgeBaseRun queued = runService.enqueueActionRun("DE0000000004", KnowledgeBaseRunAction.ALTERNATIVES, payload, null, "cancel-key");

		actionService.cancel(String.valueOf(queued.getRunId()));
		invokeExecute(queued.getRunId());

		verifyNoInteractions(maintenanceService);

		KnowledgeBaseRun canceled = runService.findById(queued.getRunId()).orElseThrow();
		assertThat(canceled.getStatus()).isEqualTo(KnowledgeBaseRunStatus.CANCELED);
		assertThat(canceled.getFinishedAt()).isNotNull();
		assertThat(canceled.getActionPayload()).doesNotContain("\"result\"");
	}

	@Test
	void heartbeatOwnershipLossPreventsPostBoundaryWorkAndResultPersistence() throws Exception {
		doAnswer(invocation -> {
			String isin = invocation.getArgument(0);
			Long runId = jdbcTemplate.queryForObject(
					"select run_id from kb_runs where isin = ? and status = 'RUNNING' order by started_at desc limit 1",
					Long.class,
					isin
			);
			jdbcTemplate.update("update kb_runs set lease_until = ? where run_id = ?", LocalDateTime.now().minusMinutes(1), runId);
			return alternativesResponse(isin);
		}).when(maintenanceService).findAlternatives(anyString(), anyBoolean(), anyString(), anySet());

		String payload = actionPayload(KnowledgeBaseLlmActionType.ALTERNATIVES, List.of("DE0000000005"), "alice", "Queued alternatives search");
		KnowledgeBaseRun queued = runService.enqueueActionRun("DE0000000005", KnowledgeBaseRunAction.ALTERNATIVES, payload, null, "ownership-key");

		invokeExecute(queued.getRunId());

		KnowledgeBaseRun persisted = runService.findById(queued.getRunId()).orElseThrow();
		assertThat(persisted.getStatus()).isEqualTo(KnowledgeBaseRunStatus.RUNNING);
		assertThat(persisted.getCurrentStep()).isEqualTo("PROVIDER_OR_DOMAIN_CALL");
		assertThat(persisted.getFinishedAt()).isNull();
		assertThat(persisted.getActionPayload()).doesNotContain("\"output\"");
	}

	@Test
	void restartRecoveryDispatchesDurableQueuedAndDueRetryBulkChildrenAndDerivesParentProgress() throws Exception {
		Map<String, String> childPayloads = new LinkedHashMap<>();
		childPayloads.put("DE0000000010", actionPayload(KnowledgeBaseLlmActionType.RESEARCH, List.of("DE0000000010"), "alice", "Queued bulk research item"));
		childPayloads.put("DE0000000011", actionPayload(KnowledgeBaseLlmActionType.RESEARCH, List.of("DE0000000011"), "alice", "Queued bulk research item"));
		KnowledgeBaseRun parent = runService.enqueueBulkAction(
				actionPayload(KnowledgeBaseLlmActionType.RESEARCH, List.of("DE0000000010", "DE0000000011"), "alice", "Queued bulk research"),
				"restart-bulk-key",
				childPayloads,
				KnowledgeBaseRunAction.BULK_CREATE
		);

		List<KnowledgeBaseRun> children = runService.childrenOf(parent.getRunId());
		KnowledgeBaseRun dueRetryChild = children.get(0);
		dueRetryChild.setStatus(KnowledgeBaseRunStatus.WAITING_RETRY);
		dueRetryChild.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
		runRepository.save(dueRetryChild);

		clearSubmittedSet();
		actionService.recoverAndDispatch();
		awaitTerminal(String.valueOf(parent.getRunId()));

		KnowledgeBaseLlmActionDto detail = actionService.getAction(String.valueOf(parent.getRunId()));
		assertThat(detail.status()).isEqualTo(my.portfoliomanager.app.dto.KnowledgeBaseLlmActionStatus.COMPLETED);
		assertThat(detail.childTotal()).isEqualTo(2);
		assertThat(detail.childCompleted()).isEqualTo(2);
		assertThat(detail.childFailed()).isEqualTo(0);
	}

	@Test
	void retryExhaustionPersistsAttemptsAndTypedTerminalFailure() throws Exception {
		when(maintenanceService.findAlternatives(anyString(), anyBoolean(), anyString(), anySet()))
				.thenThrow(new IllegalStateException("provider temporarily unavailable"));
		KnowledgeBaseRun queued = runService.enqueueActionRun("DE0000000020", KnowledgeBaseRunAction.ALTERNATIVES,
				actionPayload(KnowledgeBaseLlmActionType.ALTERNATIVES, List.of("DE0000000020"), "alice", "retry"), null, "retry-exhaustion");

		// The configured default permits three retries after the initial call.
		for (int attempt = 0; attempt < 4; attempt++) {
			invokeExecute(queued.getRunId());
			KnowledgeBaseRun current = runService.findById(queued.getRunId()).orElseThrow();
			if (attempt < 3) {
				assertThat(current.getStatus()).isEqualTo(KnowledgeBaseRunStatus.WAITING_RETRY);
				current.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
				runRepository.saveAndFlush(current);
			}
		}

		KnowledgeBaseRun exhausted = runService.findById(queued.getRunId()).orElseThrow();
		assertThat(exhausted.getAttempts()).isEqualTo(4);
		assertThat(exhausted.getStatus()).isEqualTo(KnowledgeBaseRunStatus.FAILED);
		assertThat(exhausted.getErrorCode()).isEqualTo("RETRY_EXHAUSTED");
	}

	@Test
	void dueRetryDispatcherClaimsAndExecutesPersistedWorkWithoutListOrStartupRecovery() {
		KnowledgeBaseRun due = runService.enqueueActionRun("DE0000000021", KnowledgeBaseRunAction.ALTERNATIVES,
				actionPayload(KnowledgeBaseLlmActionType.ALTERNATIVES, List.of("DE0000000021"), "alice", "due retry"), null, "due-dispatch");
		due.setStatus(KnowledgeBaseRunStatus.WAITING_RETRY);
		due.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
		runRepository.saveAndFlush(due);

		actionService.dispatchDueRetries(LocalDateTime.now());
		awaitTerminal(String.valueOf(due.getRunId()));

		assertThat(runService.findById(due.getRunId()).orElseThrow().getStatus()).isEqualTo(KnowledgeBaseRunStatus.COMPLETED);
	}

	@Test
	void legacySingleWebsearchPersistsCanonicalRunAcrossTheAdapterBoundary() throws Exception {
		when(knowledgeBaseService.createDossierDraftViaWebsearch("DE0000000022"))
				.thenReturn(new my.portfoliomanager.app.dto.InstrumentDossierWebsearchResponseDto("draft", "Instrument", null, "test"));

		var started = websearchJobService.start("DE0000000022", "alice");
		awaitTerminal(started.jobId());

		assertThat(runRepository.findById(Long.valueOf(started.jobId()))).isPresent();
		assertThat(websearchJobService.get(started.jobId()).status()).isEqualTo(my.portfoliomanager.app.dto.InstrumentDossierWebsearchJobStatus.DONE);
	}

	@Test
	void completedLegacyWebsearchDoesNotPersistRawMarkdownCitationsOrModelOutput() throws Exception {
		tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
		when(knowledgeBaseService.createDossierDraftViaWebsearch("DE0000000024"))
				.thenReturn(new my.portfoliomanager.app.dto.InstrumentDossierWebsearchResponseDto(
						"# raw markdown should never persist",
						"Raw Instrument",
						mapper.createArrayNode().add(mapper.createObjectNode().put("url", "https://example.com")),
						"raw-model"
				));

		var started = websearchJobService.start("DE0000000024", "alice");
		awaitTerminal(started.jobId());

		KnowledgeBaseRun run = runRepository.findById(Long.valueOf(started.jobId())).orElseThrow();
		assertThat(run.getActionPayload())
				.doesNotContain("raw markdown should never persist")
				.doesNotContain("raw-model")
				.doesNotContain("https://example.com")
				.doesNotContain("citations");
	}

	@Test
	void legacySingleWebsearchScopesImplicitIdempotencyByAuthenticatedActor() {
		KnowledgeBaseLlmActionDto alice = actionService.startLegacyWebsearch("DE0000000023", "alice");
		KnowledgeBaseLlmActionDto bob = actionService.startLegacyWebsearch("DE0000000023", "bob");

		assertThat(bob.actionId()).isNotEqualTo(alice.actionId());
	}

	private KnowledgeBaseLlmActionCreateRequestDto researchRequest(String actor, String key, List<String> isins) {
		return new KnowledgeBaseLlmActionCreateRequestDto(
				KnowledgeBaseLlmActionType.RESEARCH,
				isins,
				null,
				Boolean.FALSE,
				Boolean.FALSE,
				Boolean.FALSE,
				null,
				actor,
				KnowledgeBaseLlmActionTrigger.USER,
				key
		);
	}

	private String actionPayload(KnowledgeBaseLlmActionType type, List<String> isins, String actor, String message) {
		try {
			com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
			com.fasterxml.jackson.databind.node.ObjectNode input = mapper.createObjectNode();
			input.put("type", type.name());
			input.put("trigger", KnowledgeBaseLlmActionTrigger.USER.name());
			input.set("isins", mapper.valueToTree(isins));
			input.put("actor", actor);
			input.put("message", message);
			com.fasterxml.jackson.databind.node.ObjectNode envelope = mapper.createObjectNode();
			envelope.set("input", input);
			return mapper.writeValueAsString(envelope);
		} catch (Exception ex) {
			throw new IllegalStateException("Unable to build durable action payload", ex);
		}
	}

	private KnowledgeBaseBulkResearchResponseDto bulkResearchResponse(List<String> isins) {
		List<KnowledgeBaseBulkResearchItemDto> items = isins.stream()
				.map(isin -> new KnowledgeBaseBulkResearchItemDto(isin, KnowledgeBaseBulkResearchItemStatus.SUCCEEDED, null, null, null, null))
				.toList();
		return new KnowledgeBaseBulkResearchResponseDto(isins.size(), isins.size(), 0, 0, items);
	}

	private KnowledgeBaseAlternativesResponseDto alternativesResponse(String isin) {
		return new KnowledgeBaseAlternativesResponseDto(isin, List.of());
	}

	@SuppressWarnings("unchecked")
	private void clearSubmittedSet() throws Exception {
		Field field = KnowledgeBaseLlmActionService.class.getDeclaredField("submitted");
		field.setAccessible(true);
		((Set<Long>) field.get(actionService)).clear();
	}

	private void invokeExecute(Long runId) throws Exception {
		Method method = KnowledgeBaseLlmActionService.class.getDeclaredMethod("execute", Long.class);
		method.setAccessible(true);
		method.invoke(actionService, runId);
	}

	private void awaitTerminal(String actionId) {
		for (int attempt = 0; attempt < 200; attempt++) {
			KnowledgeBaseRun run = runService.findById(Long.valueOf(actionId)).orElseThrow();
			if (run.getStatus() != null && run.getStatus().isTerminal()) {
				return;
			}
			try {
				Thread.sleep(25L);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for durable action " + actionId, ex);
			}
		}
		throw new AssertionError("Timed out waiting for durable action " + actionId);
	}

	@Configuration
	static class TestConfig {
		@Bean
		@Primary
		KnowledgeBaseMaintenanceService testKnowledgeBaseMaintenanceService() {
			return mock(KnowledgeBaseMaintenanceService.class);
		}

		@Bean
		@Primary
		KnowledgeBaseRefreshService testKnowledgeBaseRefreshService() {
			return mock(KnowledgeBaseRefreshService.class);
		}

		@Bean
		@Primary
		KnowledgeBaseService testKnowledgeBaseService() {
			return mock(KnowledgeBaseService.class);
		}

		@Bean
		@Primary
		InstrumentDossierRepository testInstrumentDossierRepository() {
			return mock(InstrumentDossierRepository.class);
		}
	}
}
