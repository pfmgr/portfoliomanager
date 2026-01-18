package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record SimulationRequest(@JsonAlias("contentYaml") String contentJson) {
}
