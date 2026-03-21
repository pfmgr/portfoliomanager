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
			  SELECT dossier_id, isin, status, updated_at, approved_at, display_name, version,
			         ROW_NUMBER() OVER (PARTITION BY isin ORDER BY version DESC, updated_at DESC, dossier_id DESC) AS rn
			  FROM instrument_dossiers
			),
			latest_extractions AS (
			  SELECT d.isin,
			         e.status AS extraction_status,
			         e.created_at AS latest_extraction_at,
			         ROW_NUMBER() OVER (PARTITION BY d.isin ORDER BY e.created_at DESC, e.extraction_id DESC) AS rn
			  FROM instrument_dossiers d
			  JOIN instrument_dossier_extractions e ON e.dossier_id = d.dossier_id
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
			    d.display_name AS dossier_display_name,
			    ie.layer AS effective_layer,
			    d.dossier_id,
			    d.status AS dossier_status,
			    d.updated_at AS dossier_updated_at,
			    d.version AS dossier_version,
			    d.approved_at AS dossier_approved_at,
			    ae.approved_at AS extraction_approved_at,
			    le.extraction_status AS latest_extraction_status,
			    le.latest_extraction_at AS latest_extraction_at,
			    ib.effective_scope AS blacklist_scope,
			    CASE
			      WHEN ib.requested_scope IS NULL THEN FALSE
			      WHEN ib.requested_scope <> ib.effective_scope THEN TRUE
			      ELSE FALSE
			    END AS blacklist_pending_change
			  FROM instruments_effective ie
			  LEFT JOIN latest_dossiers d ON d.isin = ie.isin AND d.rn = 1
			  LEFT JOIN latest_approved_dossiers lad ON lad.isin = ie.isin
			  LEFT JOIN approved_extractions ae ON ae.isin = ie.isin
			  LEFT JOIN latest_extractions le ON le.isin = ie.isin AND le.rn = 1
			  LEFT JOIN instrument_blacklists ib ON ib.isin = ie.isin
			  UNION ALL
			  SELECT
			    d.isin,
			    COALESCE(o.name, i.name, d.display_name) AS name,
			    d.display_name AS dossier_display_name,
			    COALESCE(o.layer, c.layer, i.layer) AS effective_layer,
			    d.dossier_id,
			    d.status AS dossier_status,
			    d.updated_at AS dossier_updated_at,
			    d.version AS dossier_version,
			    d.approved_at AS dossier_approved_at,
			    ae.approved_at AS extraction_approved_at,
			    le.extraction_status AS latest_extraction_status,
			    le.latest_extraction_at AS latest_extraction_at,
			    ib.effective_scope AS blacklist_scope,
			    CASE
			      WHEN ib.requested_scope IS NULL THEN FALSE
			      WHEN ib.requested_scope <> ib.effective_scope THEN TRUE
			      ELSE FALSE
			    END AS blacklist_pending_change
			  FROM latest_dossiers d
			  LEFT JOIN instruments_effective ie ON ie.isin = d.isin
			  LEFT JOIN instruments i ON i.isin = d.isin
			  LEFT JOIN instrument_overrides o ON o.isin = d.isin
			  LEFT JOIN instrument_classifications c ON c.isin = d.isin
			  LEFT JOIN latest_approved_dossiers lad ON lad.isin = d.isin
			  LEFT JOIN approved_extractions ae ON ae.isin = d.isin
			  LEFT JOIN latest_extractions le ON le.isin = d.isin AND le.rn = 1
			  LEFT JOIN instrument_blacklists ib ON ib.isin = d.isin
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
			    WHEN c.dossier_approved_at IS NULL THEN 'NOT_APPROVED'
			    ELSE 'APPROVED'
			  END AS approvalStatus,
			  COALESCE(c.latest_extraction_status, 'NONE') AS latestExtractionStatus,
			  COALESCE(c.blacklist_scope, 'NONE') AS blacklistScope,
			  COALESCE(c.blacklist_pending_change, FALSE) AS blacklistPendingChange,
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
			    WHEN c.latest_extraction_at IS NULL THEN 'NONE'
			    WHEN c.latest_extraction_at >= c.dossier_updated_at THEN 'CURRENT'
			    ELSE 'OUTDATED'
			  END AS extractionFreshness
			FROM combined c
			WHERE (
			  :queryContainsPattern IS NULL
			  OR LOWER(c.isin) LIKE :queryPrefixPattern ESCAPE '\\'
			  OR LOWER(c.name) LIKE :queryPrefixPattern ESCAPE '\\'
			  OR LOWER(c.dossier_display_name) LIKE :queryPrefixPattern ESCAPE '\\'
			  OR LOWER(c.isin) LIKE :queryContainsPattern ESCAPE '\\'
			  OR LOWER(c.name) LIKE :queryContainsPattern ESCAPE '\\'
			  OR LOWER(c.dossier_display_name) LIKE :queryContainsPattern ESCAPE '\\'
			)
			  AND (:status IS NULL OR c.dossier_status = :status)
			  AND (:approvalStatus IS NULL OR (
			    CASE WHEN c.dossier_approved_at IS NULL THEN 'NOT_APPROVED' ELSE 'APPROVED' END
			  ) = :approvalStatus)
			  AND (:extractionStatus IS NULL OR COALESCE(c.latest_extraction_status, 'NONE') = :extractionStatus)
			  AND (:freshnessStatus IS NULL OR (
			    CASE
			      WHEN c.dossier_id IS NULL THEN 'NONE'
			      WHEN c.latest_extraction_at IS NULL THEN 'NONE'
			      WHEN c.latest_extraction_at >= c.dossier_updated_at THEN 'CURRENT'
			      ELSE 'OUTDATED'
			    END
			  ) = :freshnessStatus)
			  AND (:blacklistStatus IS NULL OR COALESCE(c.blacklist_scope, 'NONE') = :blacklistStatus)
			  AND (:stale IS NULL OR (
			    CASE
			      WHEN c.dossier_approved_at IS NULL THEN FALSE
			      WHEN c.dossier_approved_at < :staleBefore THEN TRUE
			      ELSE FALSE
			    END
			  ) = :stale)
			ORDER BY
			  CASE
			    WHEN :sortBy = 'updatedAt' THEN CASE WHEN c.dossier_updated_at IS NULL THEN 1 ELSE 0 END
			    ELSE 0
			  END ASC,
			  CASE WHEN :sortBy = 'isin' AND :sortDirection = 'asc' THEN LOWER(c.isin) END ASC,
			  CASE WHEN :sortBy = 'isin' AND :sortDirection = 'desc' THEN LOWER(c.isin) END DESC,
			  CASE WHEN :sortBy = 'status' AND :sortDirection = 'asc' THEN CASE c.dossier_status
			    WHEN 'DRAFT' THEN 0
			    WHEN 'PENDING_REVIEW' THEN 1
			    WHEN 'APPROVED' THEN 2
			    WHEN 'SUPERSEDED' THEN 3
			    WHEN 'REJECTED' THEN 4
			    WHEN 'FAILED' THEN 5
			    ELSE 6
			  END END ASC,
			  CASE WHEN :sortBy = 'status' AND :sortDirection = 'desc' THEN CASE c.dossier_status
			    WHEN 'DRAFT' THEN 0
			    WHEN 'PENDING_REVIEW' THEN 1
			    WHEN 'APPROVED' THEN 2
			    WHEN 'SUPERSEDED' THEN 3
			    WHEN 'REJECTED' THEN 4
			    WHEN 'FAILED' THEN 5
			    ELSE 6
			  END END DESC,
			  CASE WHEN :sortBy = 'approvalStatus' AND :sortDirection = 'asc' THEN CASE
			    WHEN c.dossier_approved_at IS NULL THEN 0
			    ELSE 1
			  END END ASC,
			  CASE WHEN :sortBy = 'approvalStatus' AND :sortDirection = 'desc' THEN CASE
			    WHEN c.dossier_approved_at IS NULL THEN 0
			    ELSE 1
			  END END DESC,
			  CASE WHEN :sortBy = 'extractionStatus' AND :sortDirection = 'asc' THEN CASE COALESCE(c.latest_extraction_status, 'NONE')
			    WHEN 'NONE' THEN 0
			    WHEN 'CREATED' THEN 1
			    WHEN 'PENDING_REVIEW' THEN 2
			    WHEN 'APPROVED' THEN 3
			    WHEN 'APPLIED' THEN 4
			    WHEN 'REJECTED' THEN 5
			    WHEN 'FAILED' THEN 6
			    ELSE 7
			  END END ASC,
			  CASE WHEN :sortBy = 'extractionStatus' AND :sortDirection = 'desc' THEN CASE COALESCE(c.latest_extraction_status, 'NONE')
			    WHEN 'NONE' THEN 0
			    WHEN 'CREATED' THEN 1
			    WHEN 'PENDING_REVIEW' THEN 2
			    WHEN 'APPROVED' THEN 3
			    WHEN 'APPLIED' THEN 4
			    WHEN 'REJECTED' THEN 5
			    WHEN 'FAILED' THEN 6
			    ELSE 7
			  END END DESC,
			  CASE WHEN :sortBy = 'freshnessStatus' AND :sortDirection = 'asc' THEN CASE
			    WHEN c.dossier_id IS NULL THEN 0
			    WHEN c.latest_extraction_at IS NULL THEN 0
			    WHEN c.latest_extraction_at >= c.dossier_updated_at THEN 2
			    ELSE 1
			  END END ASC,
			  CASE WHEN :sortBy = 'freshnessStatus' AND :sortDirection = 'desc' THEN CASE
			    WHEN c.dossier_id IS NULL THEN 0
			    WHEN c.latest_extraction_at IS NULL THEN 0
			    WHEN c.latest_extraction_at >= c.dossier_updated_at THEN 2
			    ELSE 1
			  END END DESC,
			  CASE WHEN :sortBy = 'blacklistStatus' AND :sortDirection = 'asc' THEN CASE COALESCE(c.blacklist_scope, 'NONE')
			    WHEN 'NONE' THEN 0
			    WHEN 'SAVING_PLAN_ONLY' THEN 1
			    WHEN 'ALL_PROPOSALS' THEN 2
			    ELSE 3
			  END END ASC,
			  CASE WHEN :sortBy = 'blacklistStatus' AND :sortDirection = 'desc' THEN CASE COALESCE(c.blacklist_scope, 'NONE')
			    WHEN 'NONE' THEN 0
			    WHEN 'SAVING_PLAN_ONLY' THEN 1
			    WHEN 'ALL_PROPOSALS' THEN 2
			    ELSE 3
			  END END DESC,
			  CASE WHEN :sortBy = 'updatedAt' AND :sortDirection = 'asc' THEN c.dossier_updated_at END ASC,
			  CASE WHEN :sortBy = 'updatedAt' AND :sortDirection = 'desc' THEN c.dossier_updated_at END DESC,
			  LOWER(c.isin) ASC
			LIMIT :limit OFFSET :offset
			""", nativeQuery = true)
	List<InstrumentDossierSearchProjection> searchDossiers(
			@Param("queryContainsPattern") String queryContainsPattern,
			@Param("queryPrefixPattern") String queryPrefixPattern,
			@Param("status") String status,
			@Param("approvalStatus") String approvalStatus,
			@Param("extractionStatus") String extractionStatus,
			@Param("freshnessStatus") String freshnessStatus,
			@Param("blacklistStatus") String blacklistStatus,
			@Param("stale") Boolean stale,
			@Param("staleBefore") java.time.LocalDateTime staleBefore,
			@Param("sortBy") String sortBy,
			@Param("sortDirection") String sortDirection,
			@Param("limit") int limit,
			@Param("offset") int offset
	);

	@Query(value = """
			WITH latest_dossiers AS (
			  SELECT dossier_id, isin, status, updated_at, approved_at, display_name, version,
			         ROW_NUMBER() OVER (PARTITION BY isin ORDER BY version DESC, updated_at DESC, dossier_id DESC) AS rn
			  FROM instrument_dossiers
			),
			latest_approved_dossiers AS (
			  SELECT isin, MAX(approved_at) AS approved_at
			  FROM instrument_dossiers
			  WHERE status = 'APPROVED'
			  GROUP BY isin
			),
			latest_extractions AS (
			  SELECT d.isin,
			         e.status AS extraction_status,
			         e.created_at AS latest_extraction_at,
			         ROW_NUMBER() OVER (PARTITION BY d.isin ORDER BY e.created_at DESC, e.extraction_id DESC) AS rn
			  FROM instrument_dossiers d
			  JOIN instrument_dossier_extractions e ON e.dossier_id = d.dossier_id
			),
			combined AS (
			  SELECT
			    ie.isin,
			    ie.name,
			    d.display_name AS dossier_display_name,
			    ie.layer AS effective_layer,
			    d.dossier_id,
			    d.status AS dossier_status,
			    d.updated_at AS dossier_updated_at,
			    d.approved_at AS dossier_approved_at,
			    le.extraction_status AS latest_extraction_status,
			    le.latest_extraction_at AS latest_extraction_at,
			    ib.effective_scope AS blacklist_scope
			  FROM instruments_effective ie
			  LEFT JOIN latest_dossiers d ON d.isin = ie.isin AND d.rn = 1
			  LEFT JOIN latest_approved_dossiers lad ON lad.isin = ie.isin
			  LEFT JOIN latest_extractions le ON le.isin = ie.isin AND le.rn = 1
			  LEFT JOIN instrument_blacklists ib ON ib.isin = ie.isin
			  UNION ALL
			  SELECT
			    d.isin,
			    COALESCE(o.name, i.name, d.display_name) AS name,
			    d.display_name AS dossier_display_name,
			    COALESCE(o.layer, c.layer, i.layer) AS effective_layer,
			    d.dossier_id,
			    d.status AS dossier_status,
			    d.updated_at AS dossier_updated_at,
			    d.approved_at AS dossier_approved_at,
			    le.extraction_status AS latest_extraction_status,
			    le.latest_extraction_at AS latest_extraction_at,
			    ib.effective_scope AS blacklist_scope
			  FROM latest_dossiers d
			  LEFT JOIN instruments_effective ie ON ie.isin = d.isin
			  LEFT JOIN instruments i ON i.isin = d.isin
			  LEFT JOIN instrument_overrides o ON o.isin = d.isin
			  LEFT JOIN instrument_classifications c ON c.isin = d.isin
			  LEFT JOIN latest_approved_dossiers lad ON lad.isin = d.isin
			  LEFT JOIN latest_extractions le ON le.isin = d.isin AND le.rn = 1
			  LEFT JOIN instrument_blacklists ib ON ib.isin = d.isin
			  WHERE d.rn = 1 AND ie.isin IS NULL
			),
			filtered AS (
			  SELECT
			    c.isin,
			    c.name,
			    c.dossier_display_name,
			    c.dossier_status,
			    CASE
			      WHEN c.dossier_approved_at IS NULL THEN 'NOT_APPROVED'
			      ELSE 'APPROVED'
			    END AS approval_status,
			    COALESCE(c.latest_extraction_status, 'NONE') AS latest_extraction_status,
			    CASE
			      WHEN c.dossier_id IS NULL THEN 'NONE'
			      WHEN c.latest_extraction_at IS NULL THEN 'NONE'
			      WHEN c.latest_extraction_at >= c.dossier_updated_at THEN 'CURRENT'
			      ELSE 'OUTDATED'
			    END AS extraction_freshness,
			    COALESCE(c.blacklist_scope, 'NONE') AS blacklist_scope,
			    CASE
			      WHEN c.dossier_approved_at IS NULL THEN FALSE
			      WHEN c.dossier_approved_at < :staleBefore THEN TRUE
			      ELSE FALSE
			    END AS stale
			  FROM combined c
			)
			SELECT COUNT(*)
			FROM filtered
			WHERE (
			  :queryContainsPattern IS NULL
			  OR LOWER(isin) LIKE :queryPrefixPattern ESCAPE '\\'
			  OR LOWER(name) LIKE :queryPrefixPattern ESCAPE '\\'
			  OR LOWER(dossier_display_name) LIKE :queryPrefixPattern ESCAPE '\\'
			  OR LOWER(isin) LIKE :queryContainsPattern ESCAPE '\\'
			  OR LOWER(name) LIKE :queryContainsPattern ESCAPE '\\'
			  OR LOWER(dossier_display_name) LIKE :queryContainsPattern ESCAPE '\\'
			)
			  AND (:status IS NULL OR dossier_status = :status)
			  AND (:approvalStatus IS NULL OR approval_status = :approvalStatus)
			  AND (:extractionStatus IS NULL OR latest_extraction_status = :extractionStatus)
			  AND (:freshnessStatus IS NULL OR extraction_freshness = :freshnessStatus)
			  AND (:blacklistStatus IS NULL OR blacklist_scope = :blacklistStatus)
			  AND (:stale IS NULL OR stale = :stale)
			""", nativeQuery = true)
	long countSearch(@Param("queryContainsPattern") String queryContainsPattern,
					 @Param("queryPrefixPattern") String queryPrefixPattern,
					 @Param("status") String status,
					 @Param("approvalStatus") String approvalStatus,
					 @Param("extractionStatus") String extractionStatus,
					 @Param("freshnessStatus") String freshnessStatus,
					 @Param("blacklistStatus") String blacklistStatus,
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
