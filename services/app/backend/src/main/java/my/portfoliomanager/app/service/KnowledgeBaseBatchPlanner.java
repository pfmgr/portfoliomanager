package my.portfoliomanager.app.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

public class KnowledgeBaseBatchPlanner {
	public <T> List<List<T>> buildBatches(List<T> items,
										  int maxBatchSize,
										  int maxInputChars,
										  ToIntFunction<T> charEstimator) {
		if (items == null || items.isEmpty()) {
			return List.of();
		}
		int batchLimit = Math.max(1, maxBatchSize);
		int charLimit = Math.max(1, maxInputChars);
		List<List<T>> batches = new ArrayList<>();
		List<T> current = new ArrayList<>();
		int currentChars = 0;
		for (T item : items) {
			int itemChars = Math.max(0, charEstimator.applyAsInt(item));
			boolean wouldOverflow = !current.isEmpty()
					&& (current.size() + 1 > batchLimit || currentChars + itemChars > charLimit);
			if (wouldOverflow) {
				batches.add(List.copyOf(current));
				current.clear();
				currentChars = 0;
			}
			current.add(item);
			currentChars += itemChars;
			if (current.size() >= batchLimit) {
				batches.add(List.copyOf(current));
				current.clear();
				currentChars = 0;
			}
		}
		if (!current.isEmpty()) {
			batches.add(List.copyOf(current));
		}
		return batches;
	}
}
