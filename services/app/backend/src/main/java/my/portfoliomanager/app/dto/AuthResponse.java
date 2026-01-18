package my.portfoliomanager.app.dto;

public record AuthResponse(String token, String tokenType, long expiresInSeconds) {
}
