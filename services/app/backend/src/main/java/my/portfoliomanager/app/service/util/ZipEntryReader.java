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
	private static final int MAX_ENTRIES = 200;
	private static final int MAX_ENTRY_BYTES = 10 * 1024 * 1024;
	private static final int MAX_TOTAL_BYTES = 50 * 1024 * 1024;

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
		int totalBytes = 0;
		int entryCount = 0;
		while ((entry = zip.getNextEntry()) != null) {
			if (entry.isDirectory()) {
				continue;
			}
			entryCount++;
			if (entryCount > MAX_ENTRIES) {
				throw new IOException("Zip archive contains too many entries.");
			}
			if (entries.containsKey(entry.getName())) {
				throw new IOException("Zip archive contains duplicate entries.");
			}
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			int entryBytes = 0;
			int read;
			while ((read = zip.read(chunk)) != -1) {
				entryBytes += read;
				totalBytes += read;
				if (entryBytes > MAX_ENTRY_BYTES) {
					throw new IOException("Zip entry exceeds maximum allowed size.");
				}
				if (totalBytes > MAX_TOTAL_BYTES) {
					throw new IOException("Zip archive exceeds maximum allowed size.");
				}
				buffer.write(chunk, 0, read);
			}
			entries.put(entry.getName(), buffer.toByteArray());
		}
		return entries;
	}
}
