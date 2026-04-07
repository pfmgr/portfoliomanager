package my.portfoliomanager.app.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BackupExportRequestDto(@NotBlank @Size(min = 12) String password) {
}
