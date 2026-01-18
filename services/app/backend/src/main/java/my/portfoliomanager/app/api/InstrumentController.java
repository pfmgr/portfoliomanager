package my.portfoliomanager.app.api;

import my.portfoliomanager.app.dto.InstrumentEffectivePageDto;
import my.portfoliomanager.app.service.InstrumentEffectiveService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/instruments")
public class InstrumentController {
	private final InstrumentEffectiveService instrumentEffectiveService;

	public InstrumentController(InstrumentEffectiveService instrumentEffectiveService) {
		this.instrumentEffectiveService = instrumentEffectiveService;
	}

	@GetMapping("/effective")
	public InstrumentEffectivePageDto listEffective(@RequestParam(required = false) String q,
													@RequestParam(defaultValue = "false") boolean onlyOverrides,
													@RequestParam(defaultValue = "50") int limit,
													@RequestParam(defaultValue = "0") int offset) {
		return instrumentEffectiveService.listEffective(q, onlyOverrides, limit, offset);
	}
}
