package my.portfoliomanager.app.service;

import my.portfoliomanager.app.domain.InstrumentDossier;
import my.portfoliomanager.app.dto.InstrumentDossierExtractionPayload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DossierPreParserTest {
	private final DossierPreParser parser = new DossierPreParser();

	@Test
	void parse_keepsPriceUnscaledWhenContextContainsBWord() {
		InstrumentDossier dossier = dossierWithContent("price: 227.50 EUR (as of 2026-02-13, Bourse Hamburg listing)");

		InstrumentDossierExtractionPayload payload = parser.parse(dossier);

		assertThat(payload.valuation()).isNotNull();
		assertThat(payload.valuation().price()).isEqualByComparingTo(new BigDecimal("227.50"));
	}

	@Test
	void parse_supportsGermanDecimalCommaForPrice() {
		InstrumentDossier dossier = dossierWithContent("price: 227,50 EUR");

		InstrumentDossierExtractionPayload payload = parser.parse(dossier);

		assertThat(payload.valuation()).isNotNull();
		assertThat(payload.valuation().price()).isEqualByComparingTo(new BigDecimal("227.50"));
	}

	@Test
	void parse_supportsGermanAndEnglishGroupedDecimals() {
		InstrumentDossier dossier = dossierWithContent("price: 1.234,56 EUR\npe_current: 1,234.56");

		InstrumentDossierExtractionPayload payload = parser.parse(dossier);

		assertThat(payload.valuation()).isNotNull();
		assertThat(payload.valuation().price()).isEqualByComparingTo(new BigDecimal("1234.56"));
		assertThat(payload.valuation().peCurrent()).isEqualByComparingTo(new BigDecimal("1234.56"));
	}

	@Test
	void parse_appliesScaleWhenSuffixBelongsToSameNumber() {
		InstrumentDossier dossier = dossierWithContent("market_cap: 2.3 B EUR");

		InstrumentDossierExtractionPayload payload = parser.parse(dossier);

		assertThat(payload.valuation()).isNotNull();
		assertThat(payload.valuation().marketCap()).isEqualByComparingTo(new BigDecimal("2300000000.0"));
	}

	@Test
	void parse_appliesScaleWhenCurrencySitsBetweenNumberAndSuffix() {
		InstrumentDossier dossier = dossierWithContent("revenue: 78,914.0 EUR million (FY)");

		InstrumentDossierExtractionPayload payload = parser.parse(dossier);

		assertThat(payload.financials()).isNotNull();
		assertThat(payload.financials().revenue()).isEqualByComparingTo(new BigDecimal("78914000000.0"));
	}

	private InstrumentDossier dossierWithContent(String content) {
		InstrumentDossier dossier = new InstrumentDossier();
		dossier.setIsin("DE000DK2CDS0");
		dossier.setContentMd(content);
		return dossier;
	}
}
