package my.portfoliomanager.app.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import my.portfoliomanager.app.dto.LayerTargetConfigRequestDto;
import my.portfoliomanager.app.dto.LayerTargetConfigResponseDto;
import my.portfoliomanager.app.model.LayerTargetConfigModel;
import my.portfoliomanager.app.model.LayerTargetCustomOverrides;
import my.portfoliomanager.app.model.LayerTargetEffectiveConfig;
import my.portfoliomanager.app.model.LayerTargetProfile;
import my.portfoliomanager.app.model.LayerTargetRiskThresholds;
import my.portfoliomanager.app.dto.LayerTargetRiskThresholdsDto;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class LayerTargetConfigService {
	private static final String DEFAULT_PROFILE_KEY = "BALANCED";
	private static final BigDecimal DEFAULT_VARIANCE_PCT = new BigDecimal("3.0");
	private static final int DEFAULT_MINIMUM_SAVING_PLAN_SIZE = 15;
	private static final int DEFAULT_MINIMUM_REBALANCING_AMOUNT = 10;
	private static final int DEFAULT_MAX_SAVING_PLANS_PER_LAYER = 17;
	private static final int DEFAULT_RISK_LOW_MAX = 30;
	private static final int DEFAULT_RISK_HIGH_MIN = 51;
	private static final BigDecimal NORMALIZATION_THRESHOLD = new BigDecimal("1.5");
	private static final Map<Integer, String> DEFAULT_LAYER_NAMES = Map.of(
			1, "Global Core",
			2, "Core-Plus",
			3, "Themes",
			4, "Individual Stocks",
			5, "Unclassified"
	);
	private static final Map<Integer, Integer> DEFAULT_MAX_SAVING_PLANS = Map.of(
			1, DEFAULT_MAX_SAVING_PLANS_PER_LAYER,
			2, DEFAULT_MAX_SAVING_PLANS_PER_LAYER,
			3, DEFAULT_MAX_SAVING_PLANS_PER_LAYER,
			4, DEFAULT_MAX_SAVING_PLANS_PER_LAYER,
			5, DEFAULT_MAX_SAVING_PLANS_PER_LAYER
	);
	private static final Map<String, LayerTargetProfile> DEFAULT_PROFILES = createDefaultProfiles();

	private final JdbcTemplate jdbcTemplate;
	private final ResourceLoader resourceLoader;
	private final ObjectMapper jsonMapper;
	private volatile Boolean isPostgres;

	public LayerTargetConfigService(JdbcTemplate jdbcTemplate, ResourceLoader resourceLoader) {
		this.jdbcTemplate = jdbcTemplate;
		this.resourceLoader = resourceLoader;
		this.jsonMapper = JsonMapper.builder()
				.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
				.build();
	}

	public LayerTargetEffectiveConfig loadEffectiveConfig() {
		LayerTargetConfigModel config = loadStoredConfig().orElseGet(this::loadDefaultConfig);
		return buildEffectiveConfig(config);
	}

	public LayerTargetConfigResponseDto getConfigResponse() {
		LayerTargetConfigModel config = loadStoredConfig().orElseGet(this::loadDefaultConfig);
		return toResponse(config);
	}

	public LayerTargetConfigResponseDto saveConfig(LayerTargetConfigRequestDto request) {
		LayerTargetConfigModel current = loadStoredConfig().orElseGet(this::loadDefaultConfig);
		LayerTargetConfigModel updated = fromRequest(request, current);
		persistConfig(updated);
		return toResponse(updated);
	}

	public LayerTargetConfigResponseDto resetToDefault() {
		jdbcTemplate.update("delete from layer_target_config where id = 1");
		LayerTargetConfigModel config = loadDefaultConfig();
		return toResponse(config);
	}

	private LayerTargetEffectiveConfig buildEffectiveConfig(LayerTargetConfigModel config) {
		if (config == null) {
			return null;
		}
		String activeKey = config.getActiveProfile();
		LayerTargetProfile profile = config.getProfiles().get(activeKey);
		if (profile == null) {
			profile = DEFAULT_PROFILES.get(DEFAULT_PROFILE_KEY);
		}

		Map<Integer, BigDecimal> effectiveTargets = profile.getLayerTargets();
		BigDecimal variance = profile.getAcceptableVariancePct();
		Integer minimumSavingPlanSize = profile.getMinimumSavingPlanSize();
		Integer minimumRebalancingAmount = profile.getMinimumRebalancingAmount();
		LayerTargetCustomOverrides overrides = config.getCustomOverrides();
		boolean overridesActive = overrides != null && overrides.isEnabled();
		if (overridesActive && overrides.getLayerTargets() != null && !overrides.getLayerTargets().isEmpty()) {
			effectiveTargets = overrides.getLayerTargets();
		}
		if (overridesActive && overrides.getAcceptableVariancePct() != null) {
			variance = overrides.getAcceptableVariancePct();
		}
		if (overridesActive && overrides.getMinimumSavingPlanSize() != null) {
			minimumSavingPlanSize = overrides.getMinimumSavingPlanSize();
		}
		if (overridesActive && overrides.getMinimumRebalancingAmount() != null) {
			minimumRebalancingAmount = overrides.getMinimumRebalancingAmount();
		}

		return new LayerTargetEffectiveConfig(
				activeKey,
				profile,
				effectiveTargets,
				variance == null ? DEFAULT_VARIANCE_PCT : variance,
				minimumSavingPlanSizeOrDefault(minimumSavingPlanSize),
				minimumRebalancingAmountOrDefault(minimumRebalancingAmount),
				overridesActive,
				config.getLayerNames(),
				config.getUpdatedAt()
		);
	}

	private LayerTargetConfigModel fromRequest(LayerTargetConfigRequestDto request, LayerTargetConfigModel current) {
		String selectedProfile = determineProfileKey(request == null ? null : request.activeProfile());
		Map<String, LayerTargetProfile> profiles = current != null ? current.getProfiles() : DEFAULT_PROFILES;
		if (profiles == null || profiles.isEmpty()) {
			profiles = DEFAULT_PROFILES;
		}
		profiles = refreshProfiles(profiles);
		profiles = applyRiskThresholdUpdates(profiles, request == null ? null : request.profileRiskThresholds());
		Map<Integer, String> layerNames = current != null ? current.getLayerNames() : DEFAULT_LAYER_NAMES;
		if (layerNames == null || layerNames.isEmpty()) {
			layerNames = DEFAULT_LAYER_NAMES;
		}
		Map<Integer, Integer> maxSavingPlans = current != null ? current.getMaxSavingPlansPerLayer() : DEFAULT_MAX_SAVING_PLANS;
		if (maxSavingPlans == null || maxSavingPlans.isEmpty()) {
			maxSavingPlans = DEFAULT_MAX_SAVING_PLANS;
		}
		Map<Integer, Integer> requestedMaxSavingPlans = parseRequestMaxSavingPlans(request == null ? null : request.maxSavingPlansPerLayer());
		if (!requestedMaxSavingPlans.isEmpty()) {
			maxSavingPlans = requestedMaxSavingPlans;
		}
		LayerTargetCustomOverrides overrides = buildOverrides(request);
		return new LayerTargetConfigModel(selectedProfile, profiles, layerNames, maxSavingPlans, overrides, OffsetDateTime.now(ZoneOffset.UTC));
	}

	private LayerTargetCustomOverrides buildOverrides(LayerTargetConfigRequestDto request) {
		if (request == null) {
			return new LayerTargetCustomOverrides(false, Map.of(), null, null, null, null);
		}
		boolean enabled = Boolean.TRUE.equals(request.customOverridesEnabled());
		Map<Integer, BigDecimal> targets = parseRequestTargets(request.layerTargets());
		if (!enabled && targets != null && !targets.isEmpty()) {
			enabled = true;
		}
		BigDecimal variance = toBigDecimal(request.acceptableVariancePct());
		Integer minimumSavingPlanSize = normalizeMinimumSavingPlanSize(request.minimumSavingPlanSize());
		Integer minimumRebalancingAmount = normalizeMinimumRebalancingAmount(request.minimumRebalancingAmount());
		if (!enabled) {
			return new LayerTargetCustomOverrides(false, Map.of(), null, null, null, null);
		}
		Map<Integer, BigDecimal> normalized = normalizeTargets(targets);
		if (normalized.isEmpty()) {
			return new LayerTargetCustomOverrides(true, Map.of(), variancePctOrDefault(variance), minimumSavingPlanSize, minimumRebalancingAmount, null);
		}
		return new LayerTargetCustomOverrides(true, normalized, variancePctOrDefault(variance), minimumSavingPlanSize, minimumRebalancingAmount, null);
	}

	private void persistConfig(LayerTargetConfigModel config) {
		String json = writeConfigJson(config);
		jdbcTemplate.update("delete from layer_target_config where id = 1");
		String insertSql = isPostgres() ?
				"insert into layer_target_config (id, config_json, updated_at) values (1, cast(? as jsonb), ?)" :
				"insert into layer_target_config (id, config_json, updated_at) values (1, ?, ?)";
		Timestamp updatedAt = config.getUpdatedAt() == null ? null : Timestamp.from(config.getUpdatedAt().toInstant());
		jdbcTemplate.update(insertSql, json, updatedAt);
	}

	private Optional<LayerTargetConfigModel> loadStoredConfig() {
		List<LayerTargetConfigModel> rows = jdbcTemplate.query("""
				select config_json, updated_at
				from layer_target_config
				where id = 1
				""", (rs, rowNum) -> {
			String json = rs.getString("config_json");
			OffsetDateTime updatedAt = toOffsetDateTime(rs.getTimestamp("updated_at"));
			LayerTargetConfigModel parsed = parseConfig(json, updatedAt);
			return parsed;
		});
		return rows.stream().filter(item -> item != null).findFirst();
	}

	private LayerTargetConfigModel loadDefaultConfig() {
		Resource resource = resourceLoader.getResource("classpath:layer_targets.json");
		if (!resource.exists()) {
			return createDefaultModel();
		}
		try (InputStream inputStream = resource.getInputStream()) {
			String json = new String(inputStream.readAllBytes());
			LayerTargetConfigModel parsed = parseConfig(json, null);
			if (parsed == null) {
				return createDefaultModel();
			}
			return parsed;
		} catch (Exception ignored) {
			return createDefaultModel();
		}
	}

	private LayerTargetConfigModel parseConfig(String json, OffsetDateTime updatedAt) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			JsonNode root = jsonMapper.readTree(json);

			if (root.isObject() && !root.has("profiles")) {
				return parseLegacyConfig(root, updatedAt);
			}

			String activeProfile = extractText(root, "active_profile", DEFAULT_PROFILE_KEY);
			Map<String, LayerTargetProfile> profiles = parseProfiles(root.path("profiles"));
			if (profiles.isEmpty()) {
				profiles = DEFAULT_PROFILES;
			} else {
				profiles = mergeProfilesWithDefaults(profiles);
			}
			Map<Integer, String> layerNames = parseLayerNames(root.path("layer_names"));
			if (layerNames.isEmpty()) {
				layerNames = DEFAULT_LAYER_NAMES;
			}
			Map<Integer, Integer> maxSavingPlans = parseMaxSavingPlans(root.path("max_saving_plans_per_layer"));
			if (maxSavingPlans.isEmpty()) {
				maxSavingPlans = DEFAULT_MAX_SAVING_PLANS;
			}
			LayerTargetCustomOverrides overrides = parseCustomOverrides(root.path("custom_overrides"));
			if (overrides == null) {
				overrides = new LayerTargetCustomOverrides(false, Map.of(), null, null, null, null);
			}
			if (!profiles.containsKey(activeProfile)) {
				activeProfile = DEFAULT_PROFILE_KEY;
			}
			return new LayerTargetConfigModel(activeProfile, profiles, layerNames, maxSavingPlans, overrides, updatedAt);
		} catch (Exception ex) {
			return null;
		}
	}

	private LayerTargetConfigModel parseLegacyConfig(JsonNode root, OffsetDateTime updatedAt) {
		Map<Integer, BigDecimal> legacyTargets = parseLayerTargets(root);
		BigDecimal variance = parseVariance(root.path("acceptable_variance_pct"));
		if (legacyTargets.isEmpty()) {
			legacyTargets = DEFAULT_PROFILES.get(DEFAULT_PROFILE_KEY).getLayerTargets();
		}
		LayerTargetCustomOverrides overrides = new LayerTargetCustomOverrides(
				true,
				normalizeTargets(legacyTargets),
				variancePctOrDefault(variance),
				null,
				null,
				root.toString()
		);
		return new LayerTargetConfigModel(DEFAULT_PROFILE_KEY,
				DEFAULT_PROFILES,
				DEFAULT_LAYER_NAMES,
				DEFAULT_MAX_SAVING_PLANS,
				overrides,
				updatedAt);
	}

	private Map<String, LayerTargetProfile> parseProfiles(JsonNode node) {
		if (node == null || node.isMissingNode() || !node.isObject()) {
			return Map.of();
		}
		Map<String, LayerTargetProfile> profiles = new LinkedHashMap<>();
		node.properties().forEach(entry -> {
			String key = entry.getKey().toUpperCase(Locale.ROOT);
			JsonNode profileNode = entry.getValue();
			String displayName = extractText(profileNode, "display_name", key);
			String description = extractText(profileNode, "description", "");
			Map<Integer, BigDecimal> targetMap = parseLayerTargets(profileNode.path("layer_targets"));
			if (targetMap.isEmpty()) {
				targetMap = DEFAULT_PROFILES.containsKey(key) ?
						DEFAULT_PROFILES.get(key).getLayerTargets() :
						DEFAULT_PROFILES.get(DEFAULT_PROFILE_KEY).getLayerTargets();
			} else {
				targetMap = normalizeTargets(targetMap);
			}
			BigDecimal variance = parseVariance(profileNode.path("acceptable_variance_pct"));
			if (variance == null) {
				variance = DEFAULT_VARIANCE_PCT;
			}
			Integer minimumSavingPlanSize = parseMinimumSavingPlanSize(profileNode.path("minimum_saving_plan_size"));
			if (minimumSavingPlanSize == null) {
				minimumSavingPlanSize = DEFAULT_PROFILES.containsKey(key)
						? DEFAULT_PROFILES.get(key).getMinimumSavingPlanSize()
						: DEFAULT_MINIMUM_SAVING_PLAN_SIZE;
			}
			minimumSavingPlanSize = minimumSavingPlanSizeOrDefault(minimumSavingPlanSize);
			Integer minimumRebalancingAmount = parseMinimumRebalancingAmount(profileNode.path("minimum_rebalancing_amount"));
			if (minimumRebalancingAmount == null) {
				minimumRebalancingAmount = DEFAULT_PROFILES.containsKey(key)
						? DEFAULT_PROFILES.get(key).getMinimumRebalancingAmount()
						: DEFAULT_MINIMUM_REBALANCING_AMOUNT;
			}
			minimumRebalancingAmount = minimumRebalancingAmountOrDefault(minimumRebalancingAmount);
			Map<String, BigDecimal> constraints = parseConstraints(profileNode.path("constraints"));
			if (constraints.isEmpty() && DEFAULT_PROFILES.containsKey(key)) {
				constraints = DEFAULT_PROFILES.get(key).getConstraints();
			}
			LayerTargetRiskThresholds riskThresholds = parseRiskThresholds(profileNode.path("risk_thresholds"));
			LayerTargetRiskThresholds fallbackThresholds = DEFAULT_PROFILES.containsKey(key)
					? DEFAULT_PROFILES.get(key).getRiskThresholds()
					: DEFAULT_PROFILES.get(DEFAULT_PROFILE_KEY).getRiskThresholds();
			riskThresholds = normalizeRiskThresholds(riskThresholds, fallbackThresholds);
			profiles.put(key, new LayerTargetProfile(key,
					displayName,
					description,
					targetMap,
					variancePctOrDefault(variance),
					minimumSavingPlanSize,
					minimumRebalancingAmount,
					constraints,
					riskThresholds));
		});
		return Map.copyOf(profiles);
	}

	private Map<Integer, String> parseLayerNames(JsonNode node) {
		if (node == null || node.isMissingNode() || !node.isObject()) {
			return Map.of();
		}
		Map<Integer, String> names = new LinkedHashMap<>();
		node.properties().forEach(entry -> {
			Integer layer = toLayer(entry.getKey());
			if (layer == null) {
				return;
			}
			String value = entry.getValue().asText();
			if (value != null && !value.isBlank()) {
				names.put(layer, value);
			}
		});
		return Map.copyOf(names);
	}

	private Map<Integer, Integer> parseMaxSavingPlans(JsonNode node) {
		if (node == null || node.isMissingNode() || !node.isObject()) {
			return Map.of();
		}
		Map<Integer, Integer> values = new LinkedHashMap<>();
		node.properties().forEach(entry -> {
			Integer layer = toLayer(entry.getKey());
			if (layer == null) {
				return;
			}
			if (!entry.getValue().canConvertToInt()) {
				return;
			}
			int raw = entry.getValue().asInt();
			if (raw > 0) {
				values.put(layer, raw);
			}
		});
		return Map.copyOf(values);
	}

	private LayerTargetCustomOverrides parseCustomOverrides(JsonNode node) {
		if (node == null || node.isMissingNode()) {
			return null;
		}
		Map<Integer, BigDecimal> targets = parseLayerTargets(node.path("layer_targets"));
		boolean enabled = node.has("enabled") ? node.path("enabled").asBoolean(false) : !targets.isEmpty();
		BigDecimal variance = parseVariance(node.path("acceptable_variance_pct"));
		Integer minimumSavingPlanSize = parseMinimumSavingPlanSize(node.path("minimum_saving_plan_size"));
		Integer minimumRebalancingAmount = parseMinimumRebalancingAmount(node.path("minimum_rebalancing_amount"));
		String legacy = extractText(node, "legacy_import", null);
		if (!enabled) {
			return new LayerTargetCustomOverrides(false, Map.of(), null, null, null, legacy);
		}
		return new LayerTargetCustomOverrides(
				enabled,
				targets.isEmpty() ? Map.of() : normalizeTargets(targets),
				variance,
				minimumSavingPlanSize,
				minimumRebalancingAmount,
				legacy
		);
	}

	private Map<Integer, BigDecimal> parseLayerTargets(JsonNode node) {
		Map<Integer, BigDecimal> targets = new LinkedHashMap<>();
		if (node == null || node.isMissingNode()) {
			return targets;
		}
		if (node.isObject()) {
			node.properties().forEach(entry -> {
				Integer layer = toLayer(entry.getKey());
				if (layer == null) {
					return;
				}
				BigDecimal value = toBigDecimal(entry.getValue());
				if (value != null) {
					targets.put(layer, value);
				}
			});
		}
		return targets;
	}

	private Map<String, BigDecimal> parseConstraints(JsonNode node) {
		Map<String, BigDecimal> constraints = new LinkedHashMap<>();
		if (node == null || node.isMissingNode() || !node.isObject()) {
			return constraints;
		}
		node.properties().forEach(entry -> {
			BigDecimal value = toBigDecimal(entry.getValue());
			if (value != null) {
				constraints.put(entry.getKey(), value);
			}
		});
		return Map.copyOf(constraints);
	}

	private LayerTargetRiskThresholds parseRiskThresholds(JsonNode node) {
		if (node == null || node.isMissingNode() || !node.isObject()) {
			return null;
		}
		Integer lowMax = parseThresholdValue(node, "low_max");
		Integer highMin = parseThresholdValue(node, "high_min");
		if (lowMax == null && highMin == null) {
			return null;
		}
		return new LayerTargetRiskThresholds(lowMax, highMin);
	}

	private Integer parseThresholdValue(JsonNode node, String field) {
		if (node == null || node.isMissingNode() || field == null) {
			return null;
		}
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		if (value.isIntegralNumber()) {
			return value.intValue();
		}
		if (value.isNumber()) {
			return value.asInt();
		}
		if (value.isTextual()) {
			try {
				return Integer.parseInt(value.asText().trim());
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	private LayerTargetRiskThresholds normalizeRiskThresholds(LayerTargetRiskThresholds thresholds,
											  LayerTargetRiskThresholds fallback) {
		Integer lowMax = thresholds == null ? null : thresholds.getLowMax();
		Integer highMin = thresholds == null ? null : thresholds.getHighMin();
		Integer fallbackLow = fallback == null ? DEFAULT_RISK_LOW_MAX : fallback.getLowMax();
		Integer fallbackHigh = fallback == null ? DEFAULT_RISK_HIGH_MIN : fallback.getHighMin();
		lowMax = normalizeRiskValue(lowMax, fallbackLow);
		highMin = normalizeRiskValue(highMin, fallbackHigh);
		if (highMin <= lowMax) {
			highMin = Math.min(100, lowMax + 1);
			if (highMin <= lowMax) {
				lowMax = Math.max(0, highMin - 1);
			}
		}
		return new LayerTargetRiskThresholds(lowMax, highMin);
	}

	private Integer normalizeRiskValue(Integer value, Integer fallback) {
		int resolved = value == null ? fallback : value;
		if (resolved < 0) {
			return 0;
		}
		if (resolved > 100) {
			return 100;
		}
		return resolved;
	}

	private Map<String, LayerTargetProfile> applyRiskThresholdUpdates(
			Map<String, LayerTargetProfile> profiles,
			Map<String, LayerTargetRiskThresholdsDto> requested) {
		if (profiles == null || profiles.isEmpty() || requested == null || requested.isEmpty()) {
			return profiles == null ? Map.of() : profiles;
		}
		Map<String, LayerTargetProfile> updated = new LinkedHashMap<>(profiles);
		requested.forEach((key, value) -> {
			if (key == null || value == null) {
				return;
			}
			String normalizedKey = key.trim().toUpperCase(Locale.ROOT);
			LayerTargetProfile profile = updated.get(normalizedKey);
			if (profile == null) {
				return;
			}
			LayerTargetRiskThresholds fallback = profile.getRiskThresholds();
			LayerTargetRiskThresholds normalized = normalizeRiskThresholds(
					new LayerTargetRiskThresholds(value.lowMax(), value.highMin()),
					fallback
			);
			updated.put(normalizedKey, new LayerTargetProfile(
					profile.getKey(),
					profile.getDisplayName(),
					profile.getDescription(),
					profile.getLayerTargets(),
					profile.getAcceptableVariancePct(),
					profile.getMinimumSavingPlanSize(),
					profile.getMinimumRebalancingAmount(),
					profile.getConstraints(),
					normalized
			));
		});
		return Map.copyOf(updated);
	}

	private Map<Integer, BigDecimal> parseRequestTargets(Map<Integer, Double> payload) {
		if (payload == null || payload.isEmpty()) {
			return Map.of();
		}
		Map<Integer, BigDecimal> parsed = new HashMap<>();
		for (Map.Entry<Integer, Double> entry : payload.entrySet()) {
			if (entry.getKey() == null || entry.getValue() == null) {
				continue;
			}
			Integer layer = entry.getKey();
			if (layer < 1 || layer > 5) {
				continue;
			}
			parsed.put(layer, BigDecimal.valueOf(entry.getValue()));
		}
		return parsed;
	}

	private Map<Integer, Integer> parseRequestMaxSavingPlans(Map<Integer, Integer> payload) {
		if (payload == null || payload.isEmpty()) {
			return Map.of();
		}
		Map<Integer, Integer> parsed = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : payload.entrySet()) {
			Integer layer = entry.getKey();
			Integer value = entry.getValue();
			if (layer == null || value == null) {
				continue;
			}
			if (layer < 1 || layer > 5) {
				continue;
			}
			if (value < 1) {
				continue;
			}
			parsed.put(layer, value);
		}
		return parsed;
	}

	private Map<String, LayerTargetProfile> mergeProfilesWithDefaults(Map<String, LayerTargetProfile> custom) {
		Map<String, LayerTargetProfile> merged = new LinkedHashMap<>(DEFAULT_PROFILES);
		custom.forEach(merged::put);
		return Map.copyOf(merged);
	}

	private LayerTargetConfigModel createDefaultModel() {
		return new LayerTargetConfigModel(
				DEFAULT_PROFILE_KEY,
				DEFAULT_PROFILES,
				DEFAULT_LAYER_NAMES,
				DEFAULT_MAX_SAVING_PLANS,
				new LayerTargetCustomOverrides(false, Map.of(), null, null, null, null),
				null
		);
	}

	private String writeConfigJson(LayerTargetConfigModel config) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("active_profile", config.getActiveProfile());
		payload.put("profiles", buildProfilesPayload(config.getProfiles()));
		payload.put("layer_names", buildLayerNamesPayload(config.getLayerNames()));
		payload.put("max_saving_plans_per_layer", buildMaxSavingPlansPayload(config.getMaxSavingPlansPerLayer()));
		payload.put("custom_overrides", buildOverridesPayload(config.getCustomOverrides()));
		try {
			return jsonMapper.writeValueAsString(payload);
		} catch (Exception ignored) {
			return "{}";
		}
	}

	private Map<String, Object> buildProfilesPayload(Map<String, LayerTargetProfile> profiles) {
		Map<String, Object> payload = new LinkedHashMap<>();
		if (profiles == null) {
			return payload;
		}
		for (Map.Entry<String, LayerTargetProfile> entry : profiles.entrySet()) {
			LayerTargetProfile profile = entry.getValue();
			Map<String, Object> profileData = new LinkedHashMap<>();
			profileData.put("display_name", profile.getDisplayName());
			profileData.put("description", profile.getDescription());
			profileData.put("layer_targets", buildLayerTargetsPayload(profile.getLayerTargets()));
			profileData.put("acceptable_variance_pct", profile.getAcceptableVariancePct());
			if (profile.getMinimumSavingPlanSize() != null) {
				profileData.put("minimum_saving_plan_size", profile.getMinimumSavingPlanSize());
			}
			if (profile.getMinimumRebalancingAmount() != null) {
				profileData.put("minimum_rebalancing_amount", profile.getMinimumRebalancingAmount());
			}
			if (profile.getConstraints() != null && !profile.getConstraints().isEmpty()) {
				profileData.put("constraints", profile.getConstraints());
			}
			if (profile.getRiskThresholds() != null) {
				profileData.put("risk_thresholds", buildRiskThresholdsPayload(profile.getRiskThresholds()));
			}
			payload.put(entry.getKey(), profileData);
		}
		return payload;
	}

	private Map<String, Integer> buildRiskThresholdsPayload(LayerTargetRiskThresholds thresholds) {
		Map<String, Integer> payload = new LinkedHashMap<>();
		if (thresholds == null) {
			return payload;
		}
		payload.put("low_max", thresholds.getLowMax());
		payload.put("high_min", thresholds.getHighMin());
		return payload;
	}

	private Map<String, BigDecimal> buildLayerTargetsPayload(Map<Integer, BigDecimal> targets) {
		Map<String, BigDecimal> payload = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal value = targets != null ? targets.getOrDefault(layer, BigDecimal.ZERO) : BigDecimal.ZERO;
			payload.put(String.valueOf(layer), value);
		}
		return payload;
	}

	private Map<String, Object> buildLayerNamesPayload(Map<Integer, String> layerNames) {
		Map<String, Object> payload = new LinkedHashMap<>();
		if (layerNames == null) {
			return payload;
		}
		layerNames.forEach((layer, name) -> payload.put(String.valueOf(layer), name));
		return payload;
	}

	private Map<String, Object> buildMaxSavingPlansPayload(Map<Integer, Integer> maxSavingPlans) {
		Map<String, Object> payload = new LinkedHashMap<>();
		if (maxSavingPlans == null) {
			return payload;
		}
		for (int layer = 1; layer <= 5; layer++) {
			Integer value = maxSavingPlans.getOrDefault(layer, DEFAULT_MAX_SAVING_PLANS_PER_LAYER);
			payload.put(String.valueOf(layer), value);
		}
		return payload;
	}

	private Map<String, Object> buildOverridesPayload(LayerTargetCustomOverrides overrides) {
		Map<String, Object> payload = new LinkedHashMap<>();
		if (overrides == null) {
			payload.put("enabled", false);
			return payload;
		}
		payload.put("enabled", overrides.isEnabled());
		if (overrides.getLayerTargets() != null && !overrides.getLayerTargets().isEmpty()) {
			payload.put("layer_targets", buildLayerTargetsPayload(overrides.getLayerTargets()));
		}
		if (overrides.getAcceptableVariancePct() != null) {
			payload.put("acceptable_variance_pct", overrides.getAcceptableVariancePct());
		}
		if (overrides.getMinimumSavingPlanSize() != null) {
			payload.put("minimum_saving_plan_size", overrides.getMinimumSavingPlanSize());
		}
		if (overrides.getMinimumRebalancingAmount() != null) {
			payload.put("minimum_rebalancing_amount", overrides.getMinimumRebalancingAmount());
		}
		if (overrides.getLegacyImport() != null) {
			payload.put("legacy_import", overrides.getLegacyImport());
		}
		return payload;
	}

	private LayerTargetConfigResponseDto toResponse(LayerTargetConfigModel config) {
		if (config == null) {
			config = createDefaultModel();
		}
		LayerTargetEffectiveConfig effective = buildEffectiveConfig(config);
		Map<Integer, Double> effectiveTargets = buildDoubleMap(effective.effectiveLayerTargets());
		Map<String, LayerTargetProfile> profiles = config.getProfiles();
		Map<String, LayerTargetConfigResponseDto.LayerTargetProfileDto> profileDtos = buildProfileDtos(profiles);
		LayerTargetConfigModel defaults = loadDefaultConfig();
		Map<String, LayerTargetConfigResponseDto.LayerTargetProfileDto> seedProfiles =
				buildProfileDtos(defaults == null ? Map.of() : defaults.getProfiles());

		String selectedProfileKey = effective.selectedProfileKey();
		boolean customActive = !config.getProfiles().containsKey(selectedProfileKey);
		String displayName = customActive ? "Custom" : effective.selectedProfile().getDisplayName();
		String description = customActive ? "Custom overrides are active for this profile." : effective.selectedProfile().getDescription();
		return new LayerTargetConfigResponseDto(
				selectedProfileKey,
				displayName,
				description,
				Map.copyOf(profileDtos),
				Map.copyOf(seedProfiles),
				effectiveTargets,
				effective.acceptableVariancePct().doubleValue(),
				effective.minimumSavingPlanSize(),
				effective.minimumRebalancingAmount(),
				config.getLayerNames(),
				config.getMaxSavingPlansPerLayer(),
				config.getCustomOverrides().isEnabled(),
				buildDoubleMap(config.getCustomOverrides().getLayerTargets()),
				config.getCustomOverrides().getAcceptableVariancePct() == null ? null : config.getCustomOverrides().getAcceptableVariancePct().doubleValue(),
				config.getCustomOverrides().getMinimumSavingPlanSize(),
				config.getCustomOverrides().getMinimumRebalancingAmount(),
				config.getUpdatedAt()
		);
	}

	private Map<Integer, Double> buildDoubleMap(Map<Integer, BigDecimal> input) {
		Map<Integer, Double> output = new LinkedHashMap<>();
		if (input == null) {
			return output;
		}
		for (int layer = 1; layer <= 5; layer++) {
			BigDecimal value = input.getOrDefault(layer, BigDecimal.ZERO);
			output.put(layer, value.doubleValue());
		}
		return output;
	}

	private Map<String, Double> buildDoubleConstraintMap(Map<String, BigDecimal> input) {
		Map<String, Double> output = new LinkedHashMap<>();
		if (input == null) {
			return output;
		}
		input.forEach((key, value) -> output.put(key, value.doubleValue()));
		return output;
	}

	private Map<String, LayerTargetConfigResponseDto.LayerTargetProfileDto> buildProfileDtos(
			Map<String, LayerTargetProfile> profiles) {
		Map<String, LayerTargetConfigResponseDto.LayerTargetProfileDto> profileDtos = new LinkedHashMap<>();
		if (profiles == null) {
			return profileDtos;
		}
		profiles.forEach((key, profile) -> {
			if (profile == null) {
				return;
			}
			profileDtos.put(key, new LayerTargetConfigResponseDto.LayerTargetProfileDto(
					profile.getDisplayName(),
					profile.getDescription(),
					buildDoubleMap(profile.getLayerTargets()),
					profile.getAcceptableVariancePct() != null
							? profile.getAcceptableVariancePct().doubleValue()
							: DEFAULT_VARIANCE_PCT.doubleValue(),
					profile.getMinimumSavingPlanSize(),
					profile.getMinimumRebalancingAmount(),
					buildDoubleConstraintMap(profile.getConstraints()),
					buildRiskThresholdsDto(profile.getRiskThresholds())
			));
		});
		return profileDtos;
	}

	private LayerTargetRiskThresholdsDto buildRiskThresholdsDto(LayerTargetRiskThresholds thresholds) {
		if (thresholds == null) {
			return null;
		}
		return new LayerTargetRiskThresholdsDto(thresholds.getLowMax(), thresholds.getHighMin());
	}

	private String determineProfileKey(String raw) {
		if (raw == null || raw.isBlank()) {
			return DEFAULT_PROFILE_KEY;
		}
		String normalized = raw.trim().toUpperCase(Locale.ROOT);
		return DEFAULT_PROFILES.containsKey(normalized) ? normalized : DEFAULT_PROFILE_KEY;
	}

	private Map<Integer, BigDecimal> normalizeTargets(Map<Integer, BigDecimal> raw) {
		if (raw == null || raw.isEmpty()) {
			return Map.of();
		}
		Map<Integer, BigDecimal> targets = new LinkedHashMap<>();
		for (int layer = 1; layer <= 5; layer++) {
			targets.put(layer, raw.getOrDefault(layer, BigDecimal.ZERO));
		}
		BigDecimal total = targets.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
		if (total.compareTo(NORMALIZATION_THRESHOLD) > 0) {
			for (Map.Entry<Integer, BigDecimal> entry : targets.entrySet()) {
				entry.setValue(entry.getValue().divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
			}
		}
		return Map.copyOf(targets);
	}

	private BigDecimal variancePctOrDefault(BigDecimal variance) {
		if (variance == null || variance.signum() <= 0) {
			return DEFAULT_VARIANCE_PCT;
		}
		return variance;
	}

	private Integer minimumSavingPlanSizeOrDefault(Integer minimumSavingPlanSize) {
		if (minimumSavingPlanSize == null || minimumSavingPlanSize < 1) {
			return DEFAULT_MINIMUM_SAVING_PLAN_SIZE;
		}
		return minimumSavingPlanSize;
	}

	private Integer minimumRebalancingAmountOrDefault(Integer minimumRebalancingAmount) {
		if (minimumRebalancingAmount == null || minimumRebalancingAmount < 1) {
			return DEFAULT_MINIMUM_REBALANCING_AMOUNT;
		}
		return minimumRebalancingAmount;
	}

	private Integer normalizeMinimumSavingPlanSize(Integer minimumSavingPlanSize) {
		if (minimumSavingPlanSize == null || minimumSavingPlanSize < 1) {
			return null;
		}
		return minimumSavingPlanSize;
	}

	private Integer normalizeMinimumRebalancingAmount(Integer minimumRebalancingAmount) {
		if (minimumRebalancingAmount == null || minimumRebalancingAmount < 1) {
			return null;
		}
		return minimumRebalancingAmount;
	}

	private BigDecimal parseVariance(JsonNode node) {
		BigDecimal value = toBigDecimal(node);
		return value == null ? DEFAULT_VARIANCE_PCT : value;
	}

	private Integer parseMinimumSavingPlanSize(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		if (node.isIntegralNumber()) {
			int value = node.intValue();
			return value < 1 ? null : value;
		}
		if (node.isTextual()) {
			try {
				int value = Integer.parseInt(node.asText().trim());
				return value < 1 ? null : value;
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	private Integer parseMinimumRebalancingAmount(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		if (node.isIntegralNumber()) {
			int value = node.intValue();
			return value < 1 ? null : value;
		}
		if (node.isTextual()) {
			try {
				int value = Integer.parseInt(node.asText().trim());
				return value < 1 ? null : value;
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	private String extractText(JsonNode node, String field, String fallback) {
		if (node == null || node.isMissingNode()) {
			return fallback;
		}
		JsonNode child = node.path(field);
		if (child.isMissingNode()) {
			return fallback;
		}
		String text = child.asText(null);
		return text == null || text.isBlank() ? fallback : text;
	}

	private BigDecimal toBigDecimal(JsonNode node) {
		if (node == null || node.isMissingNode()) {
			return null;
		}
		if (node.isNumber()) {
			return node.decimalValue();
		}
		if (node.isTextual()) {
			try {
				return new BigDecimal(node.asText());
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	private BigDecimal toBigDecimal(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof BigDecimal decimal) {
			return decimal;
		}
		if (value instanceof Number number) {
			return BigDecimal.valueOf(number.doubleValue());
		}
		if (value instanceof String text) {
			try {
				return new BigDecimal(text.trim());
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	private Integer toLayer(String raw) {
		try {
			return Integer.parseInt(raw.trim());
		} catch (Exception ignored) {
			return null;
		}
	}

	private Integer toLayer(JsonNode node) {
		if (node == null || node.isMissingNode()) {
			return null;
		}
		if (node.isInt()) {
			return node.intValue();
		}
		if (node.isTextual()) {
			return toLayer(node.asText());
		}
		return null;
	}

	private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
		if (timestamp == null) {
			return null;
		}
		return timestamp.toInstant().atOffset(ZoneOffset.UTC);
	}

	private boolean isPostgres() {
		Boolean cached = isPostgres;
		if (cached != null) {
			return cached;
		}
		synchronized (this) {
			if (isPostgres != null) {
				return isPostgres;
			}
			boolean result = false;
			try {
				result = Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
					String productName = connection.getMetaData().getDatabaseProductName();
					return productName != null && productName.toLowerCase(Locale.ROOT).contains("postgres");
				}));
			} catch (Exception ignored) {
				result = false;
			}
			isPostgres = result;
			return result;
		}
	}

	private Map<String, LayerTargetProfile> refreshProfiles(Map<String, LayerTargetProfile> existing) {
		Map<String, LayerTargetProfile> merged = new LinkedHashMap<>(DEFAULT_PROFILES);
		if (existing != null) {
			merged.putAll(existing);
		}
		return Map.copyOf(merged);
	}

	private static Map<String, LayerTargetProfile> createDefaultProfiles() {
		Map<String, LayerTargetProfile> profiles = new LinkedHashMap<>();
		profiles.put("CLASSIC", new LayerTargetProfile(
				"CLASSIC",
				"Classic",
				"Very conservative allocation focused on the global core.",
				createLayerTargets(0.80, 0.15, 0.04, 0.01, 0.00),
				new BigDecimal("3.0"),
				DEFAULT_MINIMUM_SAVING_PLAN_SIZE,
				DEFAULT_MINIMUM_REBALANCING_AMOUNT,
				Map.of(
						"core_min", new BigDecimal("0.70"),
						"layer5_max", new BigDecimal("0.03"),
						"layer4_max", new BigDecimal("0.05")
				),
				createRiskThresholds(25, 41)
		));
		profiles.put("BALANCED", new LayerTargetProfile(
				"BALANCED",
				"Balanced",
				"Balanced mix of core and satellite themes.",
				createLayerTargets(0.70, 0.20, 0.08, 0.02, 0.00),
				new BigDecimal("3.0"),
				DEFAULT_MINIMUM_SAVING_PLAN_SIZE,
				DEFAULT_MINIMUM_REBALANCING_AMOUNT,
				Map.of(
						"core_min", new BigDecimal("0.70"),
						"layer5_max", new BigDecimal("0.03"),
						"layer4_max", new BigDecimal("0.05")
				),
				createRiskThresholds(30, 51)
		));
		profiles.put("GROWTH", new LayerTargetProfile(
				"GROWTH",
				"Growth",
				"Higher weight on thematic and growth segments.",
				createLayerTargets(0.60, 0.20, 0.15, 0.05, 0.00),
				new BigDecimal("3.0"),
				DEFAULT_MINIMUM_SAVING_PLAN_SIZE,
				DEFAULT_MINIMUM_REBALANCING_AMOUNT,
				Map.of(
						"core_min", new BigDecimal("0.70"),
						"layer5_max", new BigDecimal("0.03"),
						"layer4_max", new BigDecimal("0.10")
				),
				createRiskThresholds(35, 59)
		));
		profiles.put("AGGRESSIVE", new LayerTargetProfile(
				"AGGRESSIVE",
				"Aggressive",
				"Growth heavy profile with elevated thematic and emerging exposure.",
				createLayerTargets(0.50, 0.15, 0.25, 0.10, 0.00),
				new BigDecimal("3.0"),
				DEFAULT_MINIMUM_SAVING_PLAN_SIZE,
				DEFAULT_MINIMUM_REBALANCING_AMOUNT,
				Map.of(
						"core_min", new BigDecimal("0.60"),
						"layer5_max", new BigDecimal("0.03"),
						"layer4_max", new BigDecimal("0.20")
				),
				createRiskThresholds(38, 63)
		));
		profiles.put("OPPORTUNITY", new LayerTargetProfile(
				"OPPORTUNITY",
				"Opportunity",
				"Highest share of thematic and individual stock exposures.",
				createLayerTargets(0.40, 0.10, 0.30, 0.20, 0.00),
				new BigDecimal("3.0"),
				DEFAULT_MINIMUM_SAVING_PLAN_SIZE,
				DEFAULT_MINIMUM_REBALANCING_AMOUNT,
				Map.of(
						"core_min", new BigDecimal("0.60"),
						"layer5_max", new BigDecimal("0.03"),
						"layer4_max", new BigDecimal("0.20")
				),
				createRiskThresholds(40, 66)
		));
		return Map.copyOf(profiles);
	}

	private static Map<Integer, BigDecimal> createLayerTargets(double... values) {
		Map<Integer, BigDecimal> targets = new LinkedHashMap<>();
		for (int layer = 1; layer <= values.length; layer++) {
			targets.put(layer, BigDecimal.valueOf(values[layer - 1]));
		}
		return Map.copyOf(targets);
	}

	private static LayerTargetRiskThresholds createRiskThresholds(int lowMax, int highMin) {
		return new LayerTargetRiskThresholds(lowMax, highMin);
	}
}
