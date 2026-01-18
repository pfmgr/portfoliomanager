package my.portfoliomanager.app.api;

import my.portfoliomanager.app.dto.DepotDto;
import my.portfoliomanager.app.repository.DepotRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/depots")
public class DepotController {
	private final DepotRepository depotRepository;

	public DepotController(DepotRepository depotRepository) {
		this.depotRepository = depotRepository;
	}

	@GetMapping
	public List<DepotDto> listDepots() {
		return depotRepository.findAll().stream()
				.map(depot -> new DepotDto(depot.getDepotId(), depot.getDepotCode(), depot.getName(), depot.getProvider()))
				.toList();
	}
}
