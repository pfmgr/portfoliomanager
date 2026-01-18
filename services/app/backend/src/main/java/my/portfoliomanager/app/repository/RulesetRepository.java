package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.Ruleset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RulesetRepository extends JpaRepository<Ruleset, Long> {
	@Query("select r from Ruleset r where r.name = :name order by r.version desc")
	List<Ruleset> findByNameOrderByVersionDesc(String name);

	@Query("select r from Ruleset r where r.name = :name and r.active = true")
	Optional<Ruleset> findActiveByName(String name);

	@Query("select r from Ruleset r order by r.name asc, r.version desc")
	List<Ruleset> findAllOrderByName();
}
