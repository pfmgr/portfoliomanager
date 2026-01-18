package my.portfoliomanager.app.repository;

import my.portfoliomanager.app.domain.InstrumentDossier;
import my.portfoliomanager.app.repository.projection.InstrumentDossierSearchProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InstrumentDossierRepository extends JpaRepository<InstrumentDossier, Long> {
	List<InstrumentDossier> findByIsinOrderByUpdatedAtDesc(String isin);

	List<InstrumentDossier> findByIsinOrderByVersionDesc(String isin);

	java.util.Optional<InstrumentDossier> findFirstByIsinOrderByVersionDesc(String isin);

	@Query(value = """
			WITH latest_dossiers AS (
			  SELECT dossier_id, isin, status, updated_at, display_name, version,
			         ROW_NUMBER() OVER (PARTITION BY isin ORDER BY version DESC, updated_at DESC, dossier_id DESC) AS rn
			  FROM instrument_dossiers
			),
			latest_extractions AS (
			  SELECT d.isin, MAX(e.created_at) AS latest_extraction_at
			  FROM instrument_dossiers d
			  JOIN instrument_dossier_extractions e ON e.dossier_id = d.dossier_id
			  GROUP BY d.isin
			),
			latest_approved_dossiers AS (
			  SELECT isin, MAX(approved_at) AS approved_at
			  FROM instrument_dossiers
			  WHERE status = 'APPROVED'
			  GROUP BY isin
			),
			approved_extractions AS (
			  SELECT d.isin, MAX(COALESCE(e.approved_at, e.applied_at, e.created_at)) AS approved_at
			  FROM instrument_dossiers d
			  JOIN instrument_dossier_extractions e ON e.dossier_id = d.dossier_id
			  WHERE e.status IN ('APPROVED', 'APPLIED')
			  GROUP BY d.isin
			),
			combined AS (
			  SELECT
			    ie.isin,
			    ie.name,
			    ie.layer AS effective_layer,
			    d.dossier_id,
			    d.status AS dossier_status,
			    d.updated_at AS dossier_updated_at,
			    d.version AS dossier_version,
			    lad.approved_at AS dossier_approved_at,
			    ae.approved_at AS extraction_approved_at
			  FROM instruments_effective ie
			  LEFT JOIN latest_dossiers d ON d.isin = ie.isin AND d.rn = 1
			  LEFT JOIN latest_approved_dossiers lad ON lad.isin = ie.isin
			  LEFT JOIN approved_extractions ae ON ae.isin = ie.isin
			  UNION ALL
			  SELECT
			    d.isin,
			    COALESCE(o.name, i.name, d.display_name) AS name,
			    COALESCE(o.layer, c.layer, i.layer) AS effective_layer,
			    d.dossier_id,
			    d.status AS dossier_status,
			    d.updated_at AS dossier_updated_at,
			    d.version AS dossier_version,
			    lad.approved_at AS dossier_approved_at,
			    ae.approved_at AS extraction_approved_at
			  FROM latest_dossiers d
			  LEFT JOIN instruments_effective ie ON ie.isin = d.isin
			  LEFT JOIN instruments i ON i.isin = d.isin
			  LEFT JOIN instrument_overrides o ON o.isin = d.isin
			  LEFT JOIN instrument_classifications c ON c.isin = d.isin
			  LEFT JOIN latest_approved_dossiers lad ON lad.isin = d.isin
			  LEFT JOIN approved_extractions ae ON ae.isin = d.isin
			  WHERE d.rn = 1 AND ie.isin IS NULL
			)
			SELECT
			  c.isin AS isin,
			  c.name AS name,
			  c.effective_layer AS effectiveLayer,
			  c.dossier_id AS dossierId,
			  c.dossier_status AS dossierStatus,
			  c.dossier_updated_at AS dossierUpdatedAt,
			  c.dossier_version AS dossierVersion,
			  c.dossier_approved_at AS dossierApprovedAt,
			  CASE
			    WHEN c.dossier_approved_at IS NULL THEN FALSE
			    ELSE TRUE
			  END AS hasApprovedDossier,
			  CASE
			    WHEN c.extraction_approved_at IS NULL THEN FALSE
			    ELSE TRUE
			  END AS hasApprovedExtraction,
			  CASE
			    WHEN c.dossier_approved_at IS NULL THEN FALSE
			    WHEN c.dossier_approved_at < :staleBefore THEN TRUE
			    ELSE FALSE
			  END AS stale,
			  CASE
			    WHEN c.dossier_id IS NULL THEN 'NONE'
			    WHEN le.latest_extraction_at IS NULL THEN 'NONE'
			    WHEN le.latest_extraction_at >= c.dossier_updated_at THEN 'CURRENT'
			    ELSE 'OUTDATED'
			  END AS extractionFreshness
			FROM combined c
			LEFT JOIN latest_extractions le ON le.isin = c.isin
			WHERE (:query IS NULL OR :query = '' OR LOWER(c.isin) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(c.name) LIKE LOWER(CONCAT('%', :query, '%')))
			  AND (:status IS NULL OR c.dossier_status = :status)
			  AND (:stale IS NULL OR (
			    CASE
			      WHEN c.dossier_approved_at IS NULL THEN FALSE
			      WHEN c.dossier_approved_at < :staleBefore THEN TRUE
			      ELSE FALSE
			    END
			  ) = :stale)
			ORDER BY c.isin
			LIMIT :limit OFFSET :offset
			""", nativeQuery = true)
	List<InstrumentDossierSearchProjection> searchDossiers(
			@Param("query") String query,
			@Param("status") String status,
			@Param("stale") Boolean stale,
			@Param("staleBefore") java.time.LocalDateTime staleBefore,
			@Param("limit") int limit,
			@Param("offset") int offset
	);

	@Query(value = """
			WITH latest_dossiers AS (
			  SELECT dossier_id, isin, status, updated_at, display_name, version,
			         ROW_NUMBER() OVER (PARTITION BY isin ORDER BY version DESC, updated_at DESC, dossier_id DESC) AS rn
			  FROM instrument_dossiers
			),
			latest_approved_dossiers AS (
			  SELECT isin, MAX(approved_at) AS approved_at
			  FROM instrument_dossiers
			  WHERE status = 'APPROVED'
			  GROUP BY isin
			),
			combined AS (
			  SELECT
			    ie.isin,
			    ie.name,
			    ie.layer AS effective_layer,
			    d.dossier_id,
			    d.status AS dossier_status,
			    d.updated_at AS dossier_updated_at,
			    lad.approved_at AS dossier_approved_at
			  FROM instruments_effective ie
			  LEFT JOIN latest_dossiers d ON d.isin = ie.isin AND d.rn = 1
			  LEFT JOIN latest_approved_dossiers lad ON lad.isin = ie.isin
			  UNION ALL
			  SELECT
			    d.isin,
			    COALESCE(o.name, i.name, d.display_name) AS name,
			    COALESCE(o.layer, c.layer, i.layer) AS effective_layer,
			    d.dossier_id,
			    d.status AS dossier_status,
			    d.updated_at AS dossier_updated_at,
			    lad.approved_at AS dossier_approved_at
			  FROM latest_dossiers d
			  LEFT JOIN instruments_effective ie ON ie.isin = d.isin
			  LEFT JOIN instruments i ON i.isin = d.isin
			  LEFT JOIN instrument_overrides o ON o.isin = d.isin
			  LEFT JOIN instrument_classifications c ON c.isin = d.isin
			  LEFT JOIN latest_approved_dossiers lad ON lad.isin = d.isin
			  WHERE d.rn = 1 AND ie.isin IS NULL
			),
			filtered AS (
			  SELECT
			    c.isin,
			    c.name,
			    c.dossier_status,
			    CASE
			      WHEN c.dossier_approved_at IS NULL THEN FALSE
			      WHEN c.dossier_approved_at < :staleBefore THEN TRUE
			      ELSE FALSE
			    END AS stale
			  FROM combined c
			)
			SELECT COUNT(*)
			FROM filtered
			WHERE (:query IS NULL OR :query = '' OR LOWER(isin) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(name) LIKE LOWER(CONCAT('%', :query, '%')))
			  AND (:status IS NULL OR dossier_status = :status)
			  AND (:stale IS NULL OR stale = :stale)
			""", nativeQuery = true)
	long countSearch(@Param("query") String query,
					 @Param("status") String status,
					 @Param("stale") Boolean stale,
					 @Param("staleBefore") java.time.LocalDateTime staleBefore);

	@Query("select d.dossierId from InstrumentDossier d where d.isin in :isins")
	List<Long> findIdsByIsinIn(@Param("isins") List<String> isins);

	@Modifying
	@Query("update InstrumentDossier d set d.supersedesId = null where d.isin in :isins")
	int clearSupersedesByIsinIn(@Param("isins") List<String> isins);

	@Modifying
	@Query("delete from InstrumentDossier d where d.isin in :isins")
	int deleteByIsinIn(@Param("isins") List<String> isins);
}
