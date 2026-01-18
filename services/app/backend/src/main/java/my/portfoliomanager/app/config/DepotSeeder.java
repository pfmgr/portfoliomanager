package my.portfoliomanager.app.config;

import my.portfoliomanager.app.domain.Depot;
import my.portfoliomanager.app.repository.DepotRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DepotSeeder implements ApplicationRunner {
	private final DepotRepository depotRepository;

	public DepotSeeder(DepotRepository depotRepository) {
		this.depotRepository = depotRepository;
	}

	@Override
	public void run(ApplicationArguments args) {
		List<DepotSeed> seeds = List.of(
				new DepotSeed("tr", "Trade Republic", "Trade Republic"),
				new DepotSeed("deka", "Deka Depot", "Deka")
		);
		for (DepotSeed seed : seeds) {
			depotRepository.findByDepotCode(seed.code).orElseGet(() -> {
				Depot depot = new Depot();
				depot.setDepotCode(seed.code);
				depot.setName(seed.name);
				depot.setProvider(seed.provider);
				return depotRepository.save(depot);
			});
		}
	}

	private record DepotSeed(String code, String name, String provider) {
	}
}
