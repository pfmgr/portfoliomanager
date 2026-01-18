package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.Depot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DepotRepository extends JpaRepository<Depot, Long> {
	Optional<Depot> findByDepotCode(String depotCode);
}
