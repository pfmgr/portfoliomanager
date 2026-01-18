package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AssessorInstrumentBucketDto(
		@JsonProperty("isin") String isin,
		@JsonProperty("amount") Double amount,
		@JsonProperty("instrument_name") String instrumentName,
		@JsonProperty("layer") Integer layer
) {
}
