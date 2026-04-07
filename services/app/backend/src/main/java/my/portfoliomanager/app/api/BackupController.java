package my.portfoliomanager.app.api;

import jakarta.validation.Valid;
import my.portfoliomanager.app.dto.BackupExportRequestDto;
import my.portfoliomanager.app.dto.BackupImportResultDto;
import my.portfoliomanager.app.service.BackupService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/backups")
public class BackupController {
	private final BackupService backupService;

	public BackupController(BackupService backupService) {
		this.backupService = backupService;
	}

	@GetMapping(path = "/export")
	public ResponseEntity<byte[]> exportLegacyBackup() {
		return backupResponse(backupService.exportBackup(), "backup.zip");
	}

	@PostMapping(path = "/export", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<byte[]> exportBackup(@Valid @RequestBody BackupExportRequestDto request) {
		return backupResponse(backupService.exportBackup(request.password()), "backup.pmbk");
	}

	private ResponseEntity<byte[]> backupResponse(byte[] payload, String filename) {
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
				.header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, private")
				.header(HttpHeaders.PRAGMA, "no-cache")
				.header(HttpHeaders.EXPIRES, "0")
				.header("X-Content-Type-Options", "nosniff")
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.body(payload);
	}

	@PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public BackupImportResultDto importBackup(@RequestParam("file") MultipartFile file,
										@RequestParam(value = "password", required = false) String password) {
		return backupService.importBackup(file, password);
	}
}
