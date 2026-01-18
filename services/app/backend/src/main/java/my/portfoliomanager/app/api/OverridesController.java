package my.portfoliomanager.app.api;

import jakarta.validation.Valid;
import my.portfoliomanager.app.dto.InstrumentOverrideRequest;
import my.portfoliomanager.app.dto.OverridesImportResultDto;
import my.portfoliomanager.app.service.OverridesService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;

@RestController
@RequestMapping("/api/overrides")
public class OverridesController {
	private final OverridesService overridesService;

	public OverridesController(OverridesService overridesService) {
		this.overridesService = overridesService;
	}

	@GetMapping("/export")
	public ResponseEntity<byte[]> exportCsv() {
		String csv = overridesService.exportCsv();
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=overrides.csv")
				.contentType(MediaType.parseMediaType("text/csv"))
				.body(csv.getBytes());
	}

	@PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public OverridesImportResultDto importCsv(@RequestParam("file") MultipartFile file, Principal principal) {
		String editedBy = principal == null ? "system" : principal.getName();
		return overridesService.importCsv(file, editedBy);
	}

	@PutMapping("/{isin}")
	public void upsertOverride(@PathVariable String isin,
							   @Valid @RequestBody InstrumentOverrideRequest request,
							   Principal principal) {
		String editedBy = principal == null ? "system" : principal.getName();
		overridesService.upsertOverride(isin, request, editedBy);
	}

	@DeleteMapping("/{isin}")
	public void deleteOverride(@PathVariable String isin, Principal principal) {
		String editedBy = principal == null ? "system" : principal.getName();
		overridesService.deleteOverride(isin, editedBy);
	}
}
