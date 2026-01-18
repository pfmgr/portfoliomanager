package my.portfoliomanager.app.api;

import my.portfoliomanager.app.dto.KnowledgeBaseImportResultDto;
import my.portfoliomanager.app.service.KnowledgeBaseBackupService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/kb/backup")
public class KnowledgeBaseBackupController {
	private final KnowledgeBaseBackupService knowledgeBaseBackupService;

	public KnowledgeBaseBackupController(KnowledgeBaseBackupService knowledgeBaseBackupService) {
		this.knowledgeBaseBackupService = knowledgeBaseBackupService;
	}

	@GetMapping("/export")
	public ResponseEntity<byte[]> exportKnowledgeBase() {
		byte[] payload = knowledgeBaseBackupService.exportKnowledgeBase();
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=knowledge-base.zip")
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.body(payload);
	}

	@PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public KnowledgeBaseImportResultDto importKnowledgeBase(@RequestParam("file") MultipartFile file) {
		return knowledgeBaseBackupService.importKnowledgeBase(file);
	}
}
