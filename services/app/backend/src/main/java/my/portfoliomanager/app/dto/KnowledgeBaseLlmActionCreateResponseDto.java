package my.portfoliomanager.app.dto;

/** Deliberately small acknowledgement; use GET /{actionId} for action details. */
public record KnowledgeBaseLlmActionCreateResponseDto(String actionId, KnowledgeBaseLlmActionStatus status) { }
