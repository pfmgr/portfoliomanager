package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.InstrumentBlacklist;
import my.portfoliomanager.app.domain.InstrumentBlacklistScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InstrumentBlacklistRepository extends JpaRepository<InstrumentBlacklist, Long> {
	Optional<InstrumentBlacklist> findByIsin(String isin);

	List<InstrumentBlacklist> findByIsinIn(Collection<String> isins);

	List<InstrumentBlacklist> findByEffectiveScopeIn(Collection<InstrumentBlacklistScope> scopes);

	@Modifying
	int deleteByIsinIn(Collection<String> isins);
}
