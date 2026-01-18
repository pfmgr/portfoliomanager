package my.portfoliomanager.app.api;

import my.portfoliomanager.app.dto.ImportResultDto;
import my.portfoliomanager.app.service.ImportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;

@RestController
@RequestMapping("/api/imports")
public class ImportController {
	private final ImportService importService;

	public ImportController(ImportService importService) {
		this.importService = importService;
	}

	@PostMapping(path = "/depot-statement", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ImportResultDto importDepotStatement(@RequestParam("depotCode") String depotCode,
												@RequestParam("file") MultipartFile file,
												@RequestParam(value = "forceReimport", defaultValue = "false") boolean forceReimport,
												@RequestParam(value = "pruneMissing", defaultValue = "true") boolean pruneMissing,
												@RequestParam(value = "applyRules", defaultValue = "true") boolean applyRules,
												Principal principal) {
		String editedBy = principal == null ? "system" : principal.getName();
		return importService.importDepotStatement(file, depotCode, forceReimport, pruneMissing, applyRules, editedBy);
	}
}
