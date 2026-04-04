package my.portfoliomanager.app.service.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipEntryReaderTest {
	@Test
	void rejectsOversizedEntry() throws Exception {
		byte[] zipBytes = zipWithOversizedEntry();

		try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
			assertThatThrownBy(() -> ZipEntryReader.readZipEntries(zipInputStream))
					.isInstanceOf(IOException.class)
					.hasMessageContaining("maximum allowed size");
		}
	}

	private byte[] zipWithOversizedEntry() throws Exception {
		byte[] payload = new byte[11 * 1024 * 1024];
		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			 ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
			zipOutputStream.putNextEntry(new ZipEntry("a.json"));
			zipOutputStream.write(payload);
			zipOutputStream.closeEntry();
			zipOutputStream.finish();
			return outputStream.toByteArray();
		}
	}
}
