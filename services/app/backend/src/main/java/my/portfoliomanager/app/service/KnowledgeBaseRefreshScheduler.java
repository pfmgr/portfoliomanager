package my.portfoliomanager.app.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionTrigger;
import my.portfoliomanager.app.dto.KnowledgeBaseLlmActionType;
import my.portfoliomanager.app.dto.KnowledgeBaseRefreshBatchRequestDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "app.kb.refresh-scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeBaseRefreshScheduler {
	private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseRefreshScheduler.class);
	private final KnowledgeBaseConfigService configService;
	private final KnowledgeBaseLlmActionService actionService;
	private final KnowledgeBaseAvailabilityService availabilityService;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

	public KnowledgeBaseRefreshScheduler(KnowledgeBaseConfigService configService,
										 KnowledgeBaseLlmActionService actionService,
										 KnowledgeBaseAvailabilityService availabilityService) {
		this.configService = configService;
		this.actionService = actionService;
		this.availabilityService = availabilityService;
	}

	@PostConstruct
	public void schedule() {
		scheduleNext(5);
	}

	@PreDestroy
	public void shutdown() {
		executor.shutdownNow();
	}

	private void scheduleNext(long delaySeconds) {
		long delay = Math.max(1, delaySeconds);
		executor.schedule(this::runOnce, delay, TimeUnit.SECONDS);
	}

	private void runOnce() {
		try {
			KnowledgeBaseConfigService.KnowledgeBaseConfigSnapshot config = configService.getSnapshot();
			if (config.enabled() && availabilityService.isAvailable()) {
				if (!actionService.hasRunningType(KnowledgeBaseLlmActionType.REFRESH)) {
					actionService.startRefreshBatch(
							new KnowledgeBaseRefreshBatchRequestDto(null, null, false, null),
							"system",
							KnowledgeBaseLlmActionTrigger.AUTO
					);
				}
			}
			scheduleNext(config.pollIntervalSeconds());
		} catch (Exception ex) {
			logger.warn("KB refresh poll failed: {}", ex.getMessage());
			scheduleNext(60);
		}
	}
}
