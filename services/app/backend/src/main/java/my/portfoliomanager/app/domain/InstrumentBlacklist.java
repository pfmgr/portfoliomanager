package my.portfoliomanager.app.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "instrument_blacklists")
public class InstrumentBlacklist {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "blacklist_id")
	private Long blacklistId;

	@Column(name = "isin", nullable = false, length = 12, unique = true)
	private String isin;

	@Enumerated(EnumType.STRING)
	@Column(name = "requested_scope", nullable = false)
	private InstrumentBlacklistScope requestedScope;

	@Enumerated(EnumType.STRING)
	@Column(name = "effective_scope", nullable = false)
	private InstrumentBlacklistScope effectiveScope;

	@Column(name = "requested_dossier_id")
	private Long requestedDossierId;

	@Column(name = "effective_dossier_id")
	private Long effectiveDossierId;

	@Column(name = "requested_updated_at", nullable = false)
	private LocalDateTime requestedUpdatedAt;

	@Column(name = "effective_updated_at")
	private LocalDateTime effectiveUpdatedAt;

	public Long getBlacklistId() {
		return blacklistId;
	}

	public void setBlacklistId(Long blacklistId) {
		this.blacklistId = blacklistId;
	}

	public String getIsin() {
		return isin;
	}

	public void setIsin(String isin) {
		this.isin = isin;
	}

	public InstrumentBlacklistScope getRequestedScope() {
		return requestedScope;
	}

	public void setRequestedScope(InstrumentBlacklistScope requestedScope) {
		this.requestedScope = requestedScope;
	}

	public InstrumentBlacklistScope getEffectiveScope() {
		return effectiveScope;
	}

	public void setEffectiveScope(InstrumentBlacklistScope effectiveScope) {
		this.effectiveScope = effectiveScope;
	}

	public Long getRequestedDossierId() {
		return requestedDossierId;
	}

	public void setRequestedDossierId(Long requestedDossierId) {
		this.requestedDossierId = requestedDossierId;
	}

	public Long getEffectiveDossierId() {
		return effectiveDossierId;
	}

	public void setEffectiveDossierId(Long effectiveDossierId) {
		this.effectiveDossierId = effectiveDossierId;
	}

	public LocalDateTime getRequestedUpdatedAt() {
		return requestedUpdatedAt;
	}

	public void setRequestedUpdatedAt(LocalDateTime requestedUpdatedAt) {
		this.requestedUpdatedAt = requestedUpdatedAt;
	}

	public LocalDateTime getEffectiveUpdatedAt() {
		return effectiveUpdatedAt;
	}

	public void setEffectiveUpdatedAt(LocalDateTime effectiveUpdatedAt) {
		this.effectiveUpdatedAt = effectiveUpdatedAt;
	}
}
