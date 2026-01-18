package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.SavingPlan;
import my.portfoliomanager.app.repository.projection.SavingPlanListProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SavingPlanRepository extends JpaRepository<SavingPlan, Long> {
	Optional<SavingPlan> findByDepotIdAndIsin(Long depotId, String isin);

	@Query(value = "select isin, sum(amount_eur) as amount_eur from sparplans where active = true group by isin", nativeQuery = true)
	List<Object[]> sumActiveAmountsByIsin();

	@Query("select count(s) > 0 from SavingPlan s where s.isin = :isin and s.active = true")
	boolean existsActiveByIsin(String isin);

	@Query(value = """
			select sp.sparplan_id as savingPlanId,
			       sp.depot_id as depotId,
			       d.depot_code as depotCode,
			       d.name as depotName,
			       sp.isin as isin,
			       coalesce(sp.name, i.name) as name,
			       sp.amount_eur as amountEur,
			       sp.frequency as frequency,
			       sp.day_of_month as dayOfMonth,
			       sp.active as active,
			       sp.last_changed as lastChanged,
			       ie.layer as layer
			from sparplans sp
			join depots d on d.depot_id = sp.depot_id
			left join instruments i on i.isin = sp.isin
			left join instruments_effective ie on ie.isin = sp.isin
			order by d.depot_code, sp.isin
			""", nativeQuery = true)
	List<SavingPlanListProjection> findAllWithDetails();
}
