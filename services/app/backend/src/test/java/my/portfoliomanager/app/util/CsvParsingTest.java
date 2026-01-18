package my.portfoliomanager.app.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsvParsingTest {
	@Test
	void stripBomRemovesLeadingMarker() {
		String value = "\uFEFFa,b,c";
		assertThat(CsvParsing.stripBom(value)).isEqualTo("a,b,c");
	}

	@Test
	void sniffDelimiterPrefersSemicolon() {
		String sample = "a;b;c\n1;2;3";
		assertThat(CsvParsing.sniffDelimiter(sample)).isEqualTo(';');
	}

	@Test
	void stripBomHandlesNullAndEmpty() {
		assertThat(CsvParsing.stripBom(null)).isNull();
		assertThat(CsvParsing.stripBom("")).isEqualTo("");
	}

	@Test
	void sniffDelimiterDefaultsToCommaWhenBothOrNeitherPresent() {
		String both = "a,b;c\n1,2;3";
		String neither = "abc";
		assertThat(CsvParsing.sniffDelimiter(both)).isEqualTo(';');
		assertThat(CsvParsing.sniffDelimiter(neither)).isEqualTo(',');
	}

	@Test
	void sniffDelimiterHandlesNullOrEmpty() {
		assertThat(CsvParsing.sniffDelimiter(null)).isEqualTo(',');
		assertThat(CsvParsing.sniffDelimiter("")).isEqualTo(',');
	}

	@Test
	void decodeUtf8RemovesBom() {
		byte[] payload = "\uFEFFa,b".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(CsvParsing.decodeUtf8(payload)).isEqualTo("a,b");
	}
}
