package my.portfoliomanager.app.service.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ZipEntryReader {
	private ZipEntryReader() {
	}

	public static Map<String, byte[]> readZipEntries(MultipartFile file) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
			return readZipEntries(zip);
		}
	}

	public static Map<String, byte[]> readZipEntries(ZipInputStream zip) throws IOException {
		Map<String, byte[]> entries = new HashMap<>();
		ZipEntry entry;
		while ((entry = zip.getNextEntry()) != null) {
			if (entry.isDirectory()) {
				continue;
			}
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			zip.transferTo(buffer);
			entries.put(entry.getName(), buffer.toByteArray());
		}
		return entries;
	}
}
