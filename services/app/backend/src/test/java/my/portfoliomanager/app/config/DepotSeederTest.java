package my.portfoliomanager.app.config;

import my.portfoliomanager.app.domain.Depot;
import my.portfoliomanager.app.repository.DepotRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DepotSeederTest {
	@Test
	void seedsMissingDepots() {
		DepotRepository repository = mock(DepotRepository.class);
		when(repository.findByDepotCode("tr")).thenReturn(Optional.empty());
		when(repository.findByDepotCode("deka")).thenReturn(Optional.empty());
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		DepotSeeder seeder = new DepotSeeder(repository);

		seeder.run(null);

		ArgumentCaptor<Depot> captor = ArgumentCaptor.forClass(Depot.class);
		verify(repository, times(2)).save(captor.capture());
		assertThat(captor.getAllValues())
				.extracting(Depot::getDepotCode)
				.containsExactlyInAnyOrder("tr", "deka");
	}

	@Test
	void skipsExistingDepots() {
		Depot existing = new Depot();
		existing.setDepotCode("tr");
		existing.setName("Trade Republic");
		existing.setProvider("Trade Republic");

		DepotRepository repository = mock(DepotRepository.class);
		when(repository.findByDepotCode("tr")).thenReturn(Optional.of(existing));
		when(repository.findByDepotCode("deka")).thenReturn(Optional.empty());
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		DepotSeeder seeder = new DepotSeeder(repository);
		seeder.run(null);

		verify(repository, times(1)).save(any());
		verify(repository, never()).save(argThat(depot -> "tr".equals(depot.getDepotCode())));
	}
}
