package my.portfoliomanager.app.api;

import my.portfoliomanager.app.dto.BackupImportResultDto;
import my.portfoliomanager.app.service.BackupService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/backups")
public class BackupController {
	private final BackupService backupService;

	public BackupController(BackupService backupService) {
		this.backupService = backupService;
	}

	@GetMapping("/export")
	public ResponseEntity<byte[]> exportBackup() {
		byte[] payload = backupService.exportBackup();
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=backup.zip")
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.body(payload);
	}

	@PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public BackupImportResultDto importBackup(@RequestParam("file") MultipartFile file) {
		return backupService.importBackup(file);
	}
}
