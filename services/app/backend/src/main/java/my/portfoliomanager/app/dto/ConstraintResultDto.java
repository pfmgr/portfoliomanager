package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class ConstraintResultDto {
	private final String name;
	private final boolean ok;
	private final String details;

	@JsonCreator
	public ConstraintResultDto(@JsonProperty("name") String name,
							   @JsonProperty("ok") boolean ok,
							   @JsonProperty("details") String details) {
		this.name = name;
		this.ok = ok;
		this.details = details;
	}

}
