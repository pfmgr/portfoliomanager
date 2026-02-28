package my.portfoliomanager.app.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import my.portfoliomanager.app.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "app.jwt.cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class AuthTokenCleanupScheduler {
	private static final Logger logger = LoggerFactory.getLogger(AuthTokenCleanupScheduler.class);
	private final AuthTokenService tokenService;
	private final AppProperties properties;
	private final Clock clock;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

	public AuthTokenCleanupScheduler(AuthTokenService tokenService, AppProperties properties, Clock clock) {
		this.tokenService = tokenService;
		this.properties = properties;
		this.clock = clock;
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
			long interval = resolveCleanupIntervalSeconds();
			int batchSize = resolveCleanupBatchSize();
			if (batchSize > 0) {
				tokenService.cleanupExpired(clock.instant(), batchSize);
			}
			scheduleNext(interval);
		} catch (Exception ex) {
			logger.warn("Auth token cleanup failed: {}", ex.getMessage());
			scheduleNext(60);
		}
	}

	private long resolveCleanupIntervalSeconds() {
		Long configured = properties.jwt().cleanupIntervalSeconds();
		if (configured == null || configured <= 0) {
			return AuthTokenService.DEFAULT_CLEANUP_INTERVAL_SECONDS;
		}
		return configured;
	}

	private int resolveCleanupBatchSize() {
		Integer configured = properties.jwt().cleanupBatchSize();
		if (configured == null || configured <= 0) {
			return AuthTokenService.DEFAULT_CLEANUP_BATCH_SIZE;
		}
		return configured;
	}
}
