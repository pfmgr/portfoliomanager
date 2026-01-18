package my.portfoliomanager.app.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

public record ApplyRequest(@JsonAlias("contentYaml") String contentJson, boolean dryRun, List<String> isins) {
}
