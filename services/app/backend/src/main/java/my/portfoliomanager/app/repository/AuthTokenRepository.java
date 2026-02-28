package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {
	boolean existsByJtiHashAndRevokedAtIsNullAndExpiresAtAfter(String jtiHash, Instant now);

	@Modifying
	@Query("update AuthToken t set t.revokedAt = :revokedAt where t.jtiHash = :jtiHash and t.revokedAt is null")
	int revokeByJtiHash(@Param("jtiHash") String jtiHash, @Param("revokedAt") Instant revokedAt);

	@Modifying
	@Query(value = """
		delete from auth_tokens
		where id in (
			select id from auth_tokens
			where expires_at <= :cutoff
			order by expires_at
			limit :limit
		)
		""", nativeQuery = true)
	int deleteExpiredBatch(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
