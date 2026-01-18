package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.SnapshotPosition;
import my.portfoliomanager.app.domain.SnapshotPositionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface SnapshotPositionRepository extends JpaRepository<SnapshotPosition, SnapshotPositionId> {
	@Query(value = "select sp.isin, sum(sp.value_eur) as value_eur "
			+ "from snapshot_positions sp "
			+ "join depots d on d.active_snapshot_id = sp.snapshot_id "
			+ "group by sp.isin", nativeQuery = true)
	List<Object[]> sumValueEurByIsinActiveSnapshots();

	@Query(value = "select sum(sp.value_eur) "
			+ "from snapshot_positions sp "
			+ "join depots d on d.active_snapshot_id = sp.snapshot_id", nativeQuery = true)
	Double sumValueEurActiveSnapshots();

	@Query(value = "select sp.isin, sum(sp.value_eur) as value_eur "
			+ "from snapshot_positions sp "
			+ "join snapshots s on s.snapshot_id = sp.snapshot_id "
			+ "where s.snapshot_id in ("
			+ "select s2.snapshot_id from snapshots s2 "
			+ "join (select depot_id, max(as_of_date) as max_date from snapshots where as_of_date <= ?1 group by depot_id) md "
			+ "on s2.depot_id = md.depot_id and s2.as_of_date = md.max_date"
			+ ") "
			+ "group by sp.isin", nativeQuery = true)
	List<Object[]> sumValueEurByIsinAsOf(LocalDate asOfDate);

	@Query(value = "select sum(sp.value_eur) "
			+ "from snapshot_positions sp "
			+ "join snapshots s on s.snapshot_id = sp.snapshot_id "
			+ "where s.snapshot_id in ("
			+ "select s2.snapshot_id from snapshots s2 "
			+ "join (select depot_id, max(as_of_date) as max_date from snapshots where as_of_date <= ?1 group by depot_id) md "
			+ "on s2.depot_id = md.depot_id and s2.as_of_date = md.max_date"
			+ ")", nativeQuery = true)
	Double sumValueEurAsOf(LocalDate asOfDate);
}
