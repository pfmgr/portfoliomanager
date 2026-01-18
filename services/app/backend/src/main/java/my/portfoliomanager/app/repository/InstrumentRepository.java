package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.Instrument;
import my.portfoliomanager.app.repository.projection.InstrumentEffectiveProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InstrumentRepository extends JpaRepository<Instrument, String> {
	List<Instrument> findByIsinIn(List<String> isins);

	@Modifying
	@Query("update Instrument i set i.deleted = true where i.depotCode = :depotCode and i.isin not in :isins")
	int markDeletedForDepot(@Param("depotCode") String depotCode, @Param("isins") List<String> isins);

	@Query(value = """
			SELECT
			  i.isin AS isin,
			  i.name AS baseName,
			  i.instrument_type AS baseInstrumentType,
			  i.asset_class AS baseAssetClass,
			  i.sub_class AS baseSubClass,
			  i.layer AS baseLayer,
			  i.layer_notes AS baseLayerNotes,
			  o.name AS overrideName,
			  o.instrument_type AS overrideInstrumentType,
			  o.asset_class AS overrideAssetClass,
			  o.sub_class AS overrideSubClass,
			  o.layer AS overrideLayer,
			  o.layer_notes AS overrideLayerNotes,
			  ie.name AS effectiveName,
			  ie.instrument_type AS effectiveInstrumentType,
			  ie.asset_class AS effectiveAssetClass,
			  ie.sub_class AS effectiveSubClass,
			  ie.layer AS effectiveLayer,
			  ie.layer_notes AS effectiveLayerNotes,
			  ie.classified_by_rule AS classifiedByRule,
			  ie.applied_rule_id AS appliedRuleId,
			  ie.has_override AS hasOverride,
			  ie.effective_updated_at AS effectiveUpdatedAt
			FROM instruments i
			LEFT JOIN instrument_overrides o USING (isin)
			LEFT JOIN instruments_effective ie USING (isin)
			WHERE NOT i.is_deleted
			  AND (:query IS NULL OR :query = '' OR LOWER(i.isin) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(i.name) LIKE LOWER(CONCAT('%', :query, '%')))
			  AND (:onlyOverrides = false OR o.isin IS NOT NULL)
			ORDER BY i.isin
			LIMIT :limit OFFSET :offset
			""", nativeQuery = true)
	List<InstrumentEffectiveProjection> findEffective(@Param("query") String query,
													  @Param("onlyOverrides") boolean onlyOverrides,
													  @Param("limit") int limit,
													  @Param("offset") int offset);

	@Query(value = """
			SELECT COUNT(*)
			FROM instruments i
			LEFT JOIN instrument_overrides o USING (isin)
			WHERE NOT i.is_deleted
			  AND (:query IS NULL OR :query = '' OR LOWER(i.isin) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(i.name) LIKE LOWER(CONCAT('%', :query, '%')))
			  AND (:onlyOverrides = false OR o.isin IS NOT NULL)
			""", nativeQuery = true)
	long countEffective(@Param("query") String query, @Param("onlyOverrides") boolean onlyOverrides);

	@Query(value = "select layer from instruments_effective where isin = :isin", nativeQuery = true)
	Integer findEffectiveLayer(@Param("isin") String isin);
}
