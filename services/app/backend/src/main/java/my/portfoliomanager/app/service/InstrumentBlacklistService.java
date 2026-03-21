package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.InstrumentBlacklist;
import my.portfoliomanager.app.domain.InstrumentBlacklistScope;
import my.portfoliomanager.app.dto.KnowledgeBaseBlacklistStateDto;
import my.portfoliomanager.app.repository.InstrumentBlacklistRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class InstrumentBlacklistService {
	private final InstrumentBlacklistRepository repository;

	public InstrumentBlacklistService(InstrumentBlacklistRepository repository) {
		this.repository = repository;
	}

	public KnowledgeBaseBlacklistStateDto getState(String isin) {
		if (isin == null || isin.isBlank()) {
			return new KnowledgeBaseBlacklistStateDto(InstrumentBlacklistScope.NONE, InstrumentBlacklistScope.NONE, false);
		}
		return toDto(repository.findByIsin(normalizeIsin(isin)).orElse(null));
	}

	public Map<String, KnowledgeBaseBlacklistStateDto> getStates(Collection<String> isins) {
		if (isins == null || isins.isEmpty()) {
			return Map.of();
		}
		Set<String> normalized = normalizeIsins(isins);
		if (normalized.isEmpty()) {
			return Map.of();
		}
		Map<String, KnowledgeBaseBlacklistStateDto> states = new LinkedHashMap<>();
		for (String isin : normalized) {
			states.put(isin, new KnowledgeBaseBlacklistStateDto(InstrumentBlacklistScope.NONE, InstrumentBlacklistScope.NONE, false));
		}
		for (InstrumentBlacklist blacklist : repository.findByIsinIn(normalized)) {
			states.put(blacklist.getIsin(), toDto(blacklist));
		}
		return Map.copyOf(states);
	}

	public Set<String> findSavingPlanExcludedIsins(Collection<String> isins) {
		return filterEffectiveIsins(isins, true, false);
	}

	public Set<String> findAllProposalExcludedIsins(Collection<String> isins) {
		return filterEffectiveIsins(isins, false, true);
	}

	@Transactional
	public void carryForwardRequestedScope(String isin, Long newDossierId, Long previousDossierId) {
		if (isin == null || isin.isBlank() || newDossierId == null) {
			return;
		}
		Optional<InstrumentBlacklist> optional = repository.findByIsin(normalizeIsin(isin));
		if (optional.isEmpty()) {
			return;
		}
		InstrumentBlacklist blacklist = optional.get();
		Long requestedDossierId = blacklist.getRequestedDossierId();
		Long effectiveDossierId = blacklist.getEffectiveDossierId();
		if ((requestedDossierId != null && requestedDossierId.equals(previousDossierId))
				|| requestedDossierId == null
				|| (effectiveDossierId != null && effectiveDossierId.equals(requestedDossierId))) {
			blacklist.setRequestedDossierId(newDossierId);
			blacklist.setRequestedUpdatedAt(LocalDateTime.now());
			repository.save(blacklist);
		}
	}

	@Transactional
	public void updateRequestedScope(String isin, Long dossierId, InstrumentBlacklistScope scope) {
		if (isin == null || isin.isBlank() || dossierId == null || scope == null) {
			return;
		}
		String normalizedIsin = normalizeIsin(isin);
		LocalDateTime now = LocalDateTime.now();
		InstrumentBlacklist blacklist = repository.findByIsin(normalizedIsin).orElseGet(() -> createDefault(normalizedIsin, now));
		blacklist.setRequestedScope(normalizeScope(scope));
		blacklist.setRequestedDossierId(dossierId);
		blacklist.setRequestedUpdatedAt(now);
		repository.save(blacklist);
	}

	@Transactional
	public void activateApprovedScope(String isin, Long dossierId) {
		if (isin == null || isin.isBlank() || dossierId == null) {
			return;
		}
		Optional<InstrumentBlacklist> optional = repository.findByIsin(normalizeIsin(isin));
		if (optional.isEmpty()) {
			return;
		}
		InstrumentBlacklist blacklist = optional.get();
		LocalDateTime now = LocalDateTime.now();
		InstrumentBlacklistScope scope = normalizeScope(blacklist.getRequestedScope());
		blacklist.setRequestedScope(scope);
		blacklist.setEffectiveScope(scope);
		blacklist.setRequestedDossierId(dossierId);
		blacklist.setEffectiveDossierId(dossierId);
		blacklist.setRequestedUpdatedAt(now);
		blacklist.setEffectiveUpdatedAt(now);
		repository.save(blacklist);
	}

	@Transactional
	public void discardPendingScope(String isin, Long dossierId) {
		if (isin == null || isin.isBlank() || dossierId == null) {
			return;
		}
		Optional<InstrumentBlacklist> optional = repository.findByIsin(normalizeIsin(isin));
		if (optional.isEmpty()) {
			return;
		}
		InstrumentBlacklist blacklist = optional.get();
		if (blacklist.getRequestedDossierId() == null || !blacklist.getRequestedDossierId().equals(dossierId)) {
			return;
		}
		if (normalizeScope(blacklist.getRequestedScope()) == normalizeScope(blacklist.getEffectiveScope())) {
			return;
		}
		blacklist.setRequestedScope(normalizeScope(blacklist.getEffectiveScope()));
		blacklist.setRequestedDossierId(blacklist.getEffectiveDossierId());
		blacklist.setRequestedUpdatedAt(LocalDateTime.now());
		repository.save(blacklist);
	}

	@Transactional
	public int deleteByIsins(Collection<String> isins) {
		Set<String> normalized = normalizeIsins(isins);
		if (normalized.isEmpty()) {
			return 0;
		}
		return repository.deleteByIsinIn(normalized);
	}

	private Set<String> filterEffectiveIsins(Collection<String> isins,
											boolean excludeSavingPlans,
											boolean excludeOneTimeInvests) {
		if (isins == null || isins.isEmpty()) {
			return Set.of();
		}
		Set<String> normalized = normalizeIsins(isins);
		if (normalized.isEmpty()) {
			return Set.of();
		}
		Set<String> matches = new LinkedHashSet<>();
		List<InstrumentBlacklist> rows = repository.findByIsinIn(normalized);
		for (InstrumentBlacklist blacklist : rows) {
			InstrumentBlacklistScope scope = normalizeScope(blacklist.getEffectiveScope());
			if ((excludeSavingPlans && scope.excludesSavingPlans())
					|| (excludeOneTimeInvests && scope.excludesOneTimeInvests())) {
				matches.add(blacklist.getIsin());
			}
		}
		return Set.copyOf(matches);
	}

	private InstrumentBlacklist createDefault(String isin, LocalDateTime now) {
		InstrumentBlacklist blacklist = new InstrumentBlacklist();
		blacklist.setIsin(isin);
		blacklist.setRequestedScope(InstrumentBlacklistScope.NONE);
		blacklist.setEffectiveScope(InstrumentBlacklistScope.NONE);
		blacklist.setRequestedUpdatedAt(now);
		blacklist.setEffectiveUpdatedAt(now);
		return blacklist;
	}

	private KnowledgeBaseBlacklistStateDto toDto(InstrumentBlacklist blacklist) {
		InstrumentBlacklistScope requested = blacklist == null ? InstrumentBlacklistScope.NONE : normalizeScope(blacklist.getRequestedScope());
		InstrumentBlacklistScope effective = blacklist == null ? InstrumentBlacklistScope.NONE : normalizeScope(blacklist.getEffectiveScope());
		return new KnowledgeBaseBlacklistStateDto(requested, effective, requested != effective);
	}

	private Set<String> normalizeIsins(Collection<String> isins) {
		Set<String> normalized = new LinkedHashSet<>();
		for (String isin : isins) {
			String value = normalizeIsin(isin);
			if (value != null) {
				normalized.add(value);
			}
		}
		return normalized;
	}

	private String normalizeIsin(String isin) {
		if (isin == null) {
			return null;
		}
		String trimmed = isin.trim();
		return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
	}

	private InstrumentBlacklistScope normalizeScope(InstrumentBlacklistScope scope) {
		return scope == null ? InstrumentBlacklistScope.NONE : scope;
	}
}
