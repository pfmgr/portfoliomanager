package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.Ruleset;
import my.portfoliomanager.app.repository.RulesetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RulesetServiceTest {
	@Mock
	private RulesetRepository rulesetRepository;

	@InjectMocks
	private RulesetService rulesetService;

	@Test
	void createNewVersionDeactivatesPrevious() {
		Ruleset existing = new Ruleset();
		existing.setId(1L);
		existing.setName("default");
		existing.setVersion(1);
		existing.setActive(true);

		when(rulesetRepository.findByNameOrderByVersionDesc("default"))
				.thenReturn(List.of(existing));
		when(rulesetRepository.save(any(Ruleset.class))).thenAnswer(invocation -> invocation.getArgument(0));

		rulesetService.createNewVersion("default", "{\"schema_version\":1,\"name\":\"default\",\"rules\":[]}", true);

		ArgumentCaptor<Ruleset> captor = ArgumentCaptor.forClass(Ruleset.class);
		verify(rulesetRepository, times(2)).save(captor.capture());
		List<Ruleset> saved = captor.getAllValues();
		assertThat(saved).anyMatch(r -> r.getId() != null && !r.isActive());
		assertThat(saved).anyMatch(r -> r.getId() == null && r.isActive());
	}

	@Test
	void validateYamlReturnsErrors() {
		var response = rulesetService.validateRuleset("{\"schema_version\":\"bad\"}");
		assertThat(response.valid()).isFalse();
		assertThat(response.errors()).isNotEmpty();
	}

	@Test
	void parseYamlThrowsOnInvalid() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> rulesetService.parseRuleset("{\"schema_version\":\"bad\"}"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void getLatestByNameReturnsEmptyWhenMissing() {
		when(rulesetRepository.findByNameOrderByVersionDesc("missing")).thenReturn(List.of());

		Optional<?> result = rulesetService.getLatestByName("missing");

		assertThat(result).isEmpty();
	}

	@Test
	void validateYamlReturnsValidWhenNoErrors() {
		var response = rulesetService.validateRuleset("{\"schema_version\":1,\"name\":\"default\",\"rules\":[]}");

		assertThat(response.valid()).isTrue();
		assertThat(response.errors()).isEmpty();
	}
}
