package my.portfoliomanager.app.api;

import jakarta.validation.Valid;
import my.portfoliomanager.app.dto.SavingPlanDto;
import my.portfoliomanager.app.dto.SavingPlanImportResultDto;
import my.portfoliomanager.app.dto.SavingPlanUpsertRequest;
import my.portfoliomanager.app.service.SavingPlanService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/sparplans")
public class SavingPlanController {
	private final SavingPlanService savingPlanService;

	public SavingPlanController(SavingPlanService savingPlanService) {
		this.savingPlanService = savingPlanService;
	}

	@GetMapping
	public List<SavingPlanDto> list() {
		return savingPlanService.list();
	}

	@PostMapping
	public SavingPlanDto create(@Valid @RequestBody SavingPlanUpsertRequest request) {
		return savingPlanService.create(request);
	}

	@PutMapping("/{id}")
	public SavingPlanDto update(@PathVariable Long id, @Valid @RequestBody SavingPlanUpsertRequest request) {
		return savingPlanService.update(id, request);
	}

	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		savingPlanService.delete(id);
	}

	@PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public SavingPlanImportResultDto importCsv(@RequestParam("file") MultipartFile file) {
		return savingPlanService.importCsv(file);
	}

	@GetMapping("/export")
	public ResponseEntity<byte[]> exportCsv() {
		String csv = savingPlanService.exportCsv();
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=savingPlans.csv")
				.contentType(MediaType.parseMediaType("text/csv"))
				.body(csv.getBytes());
	}
}
