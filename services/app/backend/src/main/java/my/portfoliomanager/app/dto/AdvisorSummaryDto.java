package my.portfoliomanager.app.dto;

import java.util.List;

public record AdvisorSummaryDto(List<AllocationDto> layerAllocations,
								 List<AllocationDto> assetClassAllocations,
								 List<PositionDto> topPositions,
								 SavingPlanSummaryDto savingPlanSummary,
								 List<LayerTargetDto> savingPlanTargets,
								 SavingPlanProposalDto savingPlanProposal) {
}
