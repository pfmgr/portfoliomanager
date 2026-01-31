package my.portfoliomanager.app.service;

import my.portfoliomanager.app.dto.LayerTargetConfigRequestDto;
import my.portfoliomanager.app.dto.LayerTargetConfigResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LayerTargetConfigServiceTest {
	@Mock
	private JdbcTemplate jdbcTemplate;

	@Mock
	private ResourceLoader resourceLoader;

	@Mock
	private Resource resource;

	@InjectMocks
	private LayerTargetConfigService layerTargetConfigService;

	@BeforeEach
	void resetPostgresFlag() {
		ReflectionTestUtils.setField(layerTargetConfigService, "isPostgres", null);
	}

	@Test
	void saveConfigUsesJsonbCastForPostgres() {
		when(resourceLoader.getResource(anyString())).thenReturn(resource);
		when(resource.exists()).thenReturn(false);
		when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(true);

		layerTargetConfigService.saveConfig(new LayerTargetConfigRequestDto(
				null,
				null,
				Map.of(1, 0.4, 2, 0.3, 3, 0.2, 4, 0.1, 5, 0.0),
				1.5,
				15,
				10,
				null,
				null
		));

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate, atLeastOnce()).update(sqlCaptor.capture(), any(Object[].class));
		assertThat(sqlCaptor.getAllValues())
				.anyMatch(sql -> sql.contains("cast(? as jsonb)"));
	}

	@Test
	void loadEffectiveConfigDefaultsWhenResourceMissing() {
		when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
				.thenReturn(List.of());
		when(resourceLoader.getResource(anyString())).thenReturn(resource);
		when(resource.exists()).thenReturn(false);

		var config = layerTargetConfigService.loadEffectiveConfig();

		assertThat(config.selectedProfileKey()).isEqualTo("BALANCED");
		assertThat(config.effectiveLayerTargets().get(1)).isEqualByComparingTo(new BigDecimal("0.70"));
		assertThat(config.minimumSavingPlanSize()).isEqualTo(15);
		assertThat(config.minimumRebalancingAmount()).isEqualTo(10);
	}

	@Test
	void loadEffectiveConfigHandlesResourceFailure() throws IOException {
		when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
				.thenReturn(List.of());
		when(resourceLoader.getResource(anyString())).thenReturn(resource);
		when(resource.exists()).thenReturn(true);
		when(resource.getInputStream()).thenThrow(new IOException("boom"));

		var config = layerTargetConfigService.loadEffectiveConfig();

		assertThat(config.selectedProfileKey()).isEqualTo("BALANCED");
	}

	@Test
	void saveConfigNormalizesLargeWeights() {
		when(jdbcTemplate.update(anyString())).thenReturn(1);
		when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
		when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(false);
		when(resourceLoader.getResource(anyString())).thenReturn(new ClassPathResource("layer_targets.json"));

		LayerTargetConfigRequestDto request = new LayerTargetConfigRequestDto(
				null,
				null,
				Map.of(1, 200.0, 2, 100.0, 3, 100.0),
				5.0,
				20,
				10,
				null,
				null
		);

		LayerTargetConfigResponseDto response = layerTargetConfigService.saveConfig(request);

		assertThat(response.getEffectiveLayerTargets().get(1)).isEqualTo(2.0);
		assertThat(response.getAcceptableVariancePct()).isEqualTo(5.0);
		assertThat(response.getMinimumSavingPlanSize()).isEqualTo(20);
		assertThat(response.getMinimumRebalancingAmount()).isEqualTo(10);
	}

	@Test
	void saveConfigAppliesCustomOverrides() {
		when(jdbcTemplate.update(anyString())).thenReturn(1);
		when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
		when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(false);
		when(resourceLoader.getResource(anyString())).thenReturn(new ClassPathResource("layer_targets.json"));

		LayerTargetConfigResponseDto response = layerTargetConfigService.saveConfig(new LayerTargetConfigRequestDto(
				"BALANCED",
				true,
				Map.of(1, 0.4, 2, 0.3, 3, 0.2, 4, 0.1, 5, 0.0),
				1.5,
				20,
				10,
				Map.of(1, 10, 2, 10, 3, 10, 4, 10, 5, 10),
				null
		));

		assertThat(response.isCustomOverridesEnabled()).isTrue();
		assertThat(response.getCustomLayerTargets().get(1)).isEqualTo(0.4);
		assertThat(response.getEffectiveLayerTargets().get(1)).isEqualTo(0.4);
	}

	@Test
	void saveConfigIgnoresInvalidEntriesAndDefaultsVariance() {
		when(jdbcTemplate.update(anyString())).thenReturn(1);
		when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
		when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(false);
		when(resourceLoader.getResource(anyString())).thenReturn(new ClassPathResource("layer_targets.json"));

		Map<Integer, Double> targets = new HashMap<>();
		targets.put(null, 0.5);
		targets.put(6, 0.4);
		targets.put(2, 0.3);
		targets.put(5, null);

		LayerTargetConfigRequestDto request = new LayerTargetConfigRequestDto(null, null, targets, -5.0, null, null, null, null);

		LayerTargetConfigResponseDto response = layerTargetConfigService.saveConfig(request);

		assertThat(response.getEffectiveLayerTargets().get(2)).isEqualTo(0.3);
		assertThat(response.getAcceptableVariancePct()).isEqualTo(3.0);
	}

	@Test
	void saveConfigAppliesProfileDefaultsWhenNoOverrides() {
		when(jdbcTemplate.update(anyString())).thenReturn(1);
		when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
		when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(false);
		when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
				.thenReturn(List.of());
		when(resourceLoader.getResource(anyString())).thenReturn(new ClassPathResource("layer_targets.json"));

		LayerTargetConfigResponseDto response = layerTargetConfigService.saveConfig(
				new LayerTargetConfigRequestDto("CLASSIC", false, null, null, null, null, null, null));

		assertThat(response.getActiveProfileKey()).isEqualTo("CLASSIC");
		assertThat(response.getEffectiveLayerTargets()).containsEntry(1, 0.8);
		assertThat(response.getEffectiveLayerTargets()).containsEntry(4, 0.01);
		assertThat(response.isCustomOverridesEnabled()).isFalse();
		assertThat(response.getMaxSavingPlansPerLayer()).containsEntry(1, 2);
	}
}
