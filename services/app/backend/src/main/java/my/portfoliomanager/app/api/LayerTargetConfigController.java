package my.portfoliomanager.app.api;

import my.portfoliomanager.app.dto.LayerTargetConfigRequestDto;
import my.portfoliomanager.app.dto.LayerTargetConfigResponseDto;
import my.portfoliomanager.app.service.LayerTargetConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/layer-targets")
public class LayerTargetConfigController {
	private final LayerTargetConfigService layerTargetConfigService;

	public LayerTargetConfigController(LayerTargetConfigService layerTargetConfigService) {
		this.layerTargetConfigService = layerTargetConfigService;
	}

	@GetMapping
	public LayerTargetConfigResponseDto getConfig() {
		return layerTargetConfigService.getConfigResponse();
	}

	@PutMapping
	public LayerTargetConfigResponseDto saveConfig(@RequestBody LayerTargetConfigRequestDto request) {
		return layerTargetConfigService.saveConfig(request);
	}

	@PostMapping("/reset")
	public LayerTargetConfigResponseDto resetConfig() {
		return layerTargetConfigService.resetToDefault();
	}
}
