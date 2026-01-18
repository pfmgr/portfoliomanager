package my.portfoliomanager.app.util;

import java.nio.charset.StandardCharsets;

public final class CsvParsing {
	private CsvParsing() {
	}

	public static String stripBom(String value) {
		if (value == null || value.isEmpty()) {
			return value;
		}
		if (value.charAt(0) == '\uFEFF') {
			return value.substring(1);
		}
		return value;
	}

	public static char sniffDelimiter(String sample) {
		if (sample == null || sample.isEmpty()) {
			return ',';
		}
		boolean hasComma = sample.indexOf(',') >= 0;
		boolean hasSemicolon = sample.indexOf(';') >= 0;
		if (hasSemicolon && !hasComma) {
			return ';';
		}
		if (hasSemicolon && hasComma) {
			return ';';
		}
		return ',';
	}

	public static String decodeUtf8(byte[] payload) {
		String raw = new String(payload, StandardCharsets.UTF_8);
		return stripBom(raw);
	}
}
