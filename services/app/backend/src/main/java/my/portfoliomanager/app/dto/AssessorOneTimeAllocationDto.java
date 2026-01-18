package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record AssessorOneTimeAllocationDto(
		@JsonProperty("layer_buckets") Map<Integer, Double> layerBuckets,
		@JsonProperty("instrument_buckets") Map<String, Double> instrumentBuckets,
		@JsonProperty("instrument_buckets_detailed") List<AssessorInstrumentBucketDto> instrumentBucketsDetailed,
		@JsonProperty("new_instruments") List<AssessorNewInstrumentSuggestionDto> newInstruments
) {
}
