package tech.kayys.gamelan.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons;

/**
 * Diagnostic result produced by executor selection.
 */
public record ExecutorSelectionReport(
        ExecutorSelectionRequest request,
        Optional<ExecutorInfo> selectedExecutor,
        int totalExecutors,
        int cachedExecutors,
        int cachedCandidateExecutors,
        int typeCompatibleExecutors,
        int healthyExecutors,
        int placementCompatibleExecutors,
        int candidateExecutors,
        Map<String, Integer> rejectionCounts,
        Map<String, Object> diagnostics) {

    public ExecutorSelectionReport {
        java.util.Objects.requireNonNull(request, "request cannot be null");
        selectedExecutor = selectedExecutor != null ? selectedExecutor : Optional.empty();
        rejectionCounts = immutableMap(rejectionCounts);
        diagnostics = immutableMap(diagnostics);
    }

    public static ExecutorSelectionReport selectionOnly(
            ExecutorSelectionRequest request,
            Optional<ExecutorInfo> selectedExecutor) {
        Optional<ExecutorInfo> effectiveSelection = selectedExecutor != null
                ? selectedExecutor
                : Optional.empty();
        int selectedCount = effectiveSelection.isPresent() ? 1 : 0;
        return new ExecutorSelectionReport(
                request,
                effectiveSelection,
                selectedCount,
                0,
                selectedCount,
                selectedCount,
                selectedCount,
                selectedCount,
                selectedCount,
                Map.of(),
                Map.of("diagnosticCompleteness", "selection-only"));
    }

    public boolean hasSelection() {
        return selectedExecutor.isPresent();
    }

    public boolean hasRejections() {
        return !rejectionCounts.isEmpty();
    }

    public String primaryRejectionReason() {
        return ExecutorSelectionRejectionReasons.primaryReason(rejectionCounts);
    }

    public boolean hasPermanentRejection() {
        return ExecutorSelectionRejectionReasons.isPermanent(primaryRejectionReason());
    }

    public Map<String, Object> toErrorContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("nodeId", request.nodeId().value());
        if (request.hasExecutorType()) {
            context.put("executorType", request.executorType());
        }
        if (request.hasSelectionStrategy()) {
            context.put("selectionStrategy", request.selectionStrategy());
        }
        if (request.hasRequiredCapabilities()) {
            context.put("requiredCapabilities", request.requiredCapabilities().stream().sorted().toList());
        }
        if (request.hasPreferredCapabilities()) {
            context.put("preferredCapabilities", request.preferredCapabilities().stream().sorted().toList());
        }
        if (request.hasExcludedCapabilities()) {
            context.put("excludedCapabilities", request.excludedCapabilities().stream().sorted().toList());
        }
        if (request.hasResourceRequirements()) {
            context.put("resourceRequirements", request.resourceRequirements().toContextMap());
        }
        context.put("requireHealthy", request.requireHealthy());
        if (!request.placement().isEmpty()) {
            context.put("placement", request.placement().toContextMap());
        }
        if (!request.selectionContext().isEmpty()) {
            context.put("selectionContext", request.selectionContext());
        }
        selectedExecutor.map(ExecutorInfo::executorId)
                .ifPresent(executorId -> context.put("selectedExecutorId", executorId));
        context.put("totalExecutors", totalExecutors);
        context.put("cachedExecutors", cachedExecutors);
        context.put("cachedCandidateExecutors", cachedCandidateExecutors);
        context.put("typeCompatibleExecutors", typeCompatibleExecutors);
        context.put("healthyExecutors", healthyExecutors);
        context.put("placementCompatibleExecutors", placementCompatibleExecutors);
        context.put("candidateExecutors", candidateExecutors);
        context.put("rejectionCounts", rejectionCounts);
        if (!hasSelection() || hasRejections()) {
            context.put("primaryRejectionReason", primaryRejectionReason());
            context.put("permanentRejection", hasPermanentRejection());
        }
        if (!diagnostics.isEmpty()) {
            context.put("diagnostics", diagnostics);
        }
        return immutableMap(context);
    }

    private static <V> Map<String, V> immutableMap(Map<String, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
