package my.portfoliomanager.app.dto;

import my.portfoliomanager.app.domain.InstrumentBlacklistScope;

public record KnowledgeBaseBlacklistStateDto(
		InstrumentBlacklistScope requestedScope,
		InstrumentBlacklistScope effectiveScope,
		boolean pendingChange
) {
}
