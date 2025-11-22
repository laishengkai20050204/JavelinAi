package com.example.tools.support;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * 坐标定位并发协调器：流水线式处理行/列判断
 */
@Slf4j
public class GridLocalizationPipeline {

    private final ExecutorService executor;

    public GridLocalizationPipeline(int threads) {
        this.executor = new ThreadPoolExecutor(
                threads, threads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Data
    public static class RowResult {
        private final Integer rowIndex;
        private final Boolean containsTarget;
        private final String selectRaw;
        private final String checkRaw;
        private final Set<Integer> bannedRows;
    }

    @Data
    public static class ColResult {
        private final Integer colIndex;
        private final Boolean containsTarget;
        private final String selectRaw;
        private final String checkRaw;
        private final Set<Integer> bannedCols;
    }

    /**
     * 🔥 并发行定位：选择和确认可以流水线处理
     */
    public RowResult findRowConcurrently(
            int gridRows,
            int maxAttempts,
            RowSelector selector,
            RowChecker checker
    ) throws Exception {

        Set<Integer> bannedRows = ConcurrentHashMap.newKeySet();

        for (int attempt = 1; attempt <= maxAttempts && bannedRows.size() < gridRows; attempt++) {

            // 🔥 并发：同时启动"选择下一行"和"准备裁剪"
            CompletableFuture<SelectResult> selectFuture = CompletableFuture.supplyAsync(
                    () -> selector.select(bannedRows),
                    executor
            );

            SelectResult selectResult = selectFuture.get(60, TimeUnit.SECONDS);

            if (selectResult == null || selectResult.rowIndex == null) {
                log.warn("[Pipeline] Row selection failed at attempt {}", attempt);
                continue;
            }

            int candidateRow = selectResult.rowIndex;

            if (candidateRow < 0 || candidateRow >= gridRows || bannedRows.contains(candidateRow)) {
                log.warn("[Pipeline] Invalid or banned row {} at attempt {}", candidateRow, attempt);
                continue;
            }

            // 🔥 并发：同时启动"裁剪图像"和"调用确认API"
            CompletableFuture<CheckResult> checkFuture = CompletableFuture.supplyAsync(
                    () -> checker.check(candidateRow),
                    executor
            );

            CheckResult checkResult = checkFuture.get(120, TimeUnit.SECONDS);

            if (Boolean.TRUE.equals(checkResult.contains)) {
                return new RowResult(
                        candidateRow,
                        true,
                        selectResult.raw,
                        checkResult.raw,
                        bannedRows
                );
            } else {
                bannedRows.add(candidateRow);
                log.warn("[Pipeline] Row {} has no target, banned. Total banned: {}",
                        candidateRow, bannedRows.size());
            }
        }

        return new RowResult(null, false, null, null, bannedRows);
    }

    /**
     * 🔥 并发列定位（逻辑同上）
     */
    public ColResult findColConcurrently(
            int gridCols,
            int maxAttempts,
            ColSelector selector,
            ColChecker checker
    ) throws Exception {

        Set<Integer> bannedCols = ConcurrentHashMap.newKeySet();

        for (int attempt = 1; attempt <= maxAttempts && bannedCols.size() < gridCols; attempt++) {

            CompletableFuture<SelectResult> selectFuture = CompletableFuture.supplyAsync(
                    () -> selector.select(bannedCols),
                    executor
            );

            SelectResult selectResult = selectFuture.get(60, TimeUnit.SECONDS);

            if (selectResult == null || selectResult.colIndex == null) {
                continue;
            }

            int candidateCol = selectResult.colIndex;

            if (candidateCol < 0 || candidateCol >= gridCols || bannedCols.contains(candidateCol)) {
                continue;
            }

            CompletableFuture<CheckResult> checkFuture = CompletableFuture.supplyAsync(
                    () -> checker.check(candidateCol),
                    executor
            );

            CheckResult checkResult = checkFuture.get(120, TimeUnit.SECONDS);

            if (Boolean.TRUE.equals(checkResult.contains)) {
                return new ColResult(
                        candidateCol,
                        true,
                        selectResult.raw,
                        checkResult.raw,
                        bannedCols
                );
            } else {
                bannedCols.add(candidateCol);
            }
        }

        return new ColResult(null, false, null, null, bannedCols);
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ========== 回调接口 ==========

    @Data
    public static class SelectResult {
        private final Integer rowIndex;
        private final Integer colIndex;
        private final String raw;
    }

    @Data
    public static class CheckResult {
        private final Boolean contains;
        private final String raw;
    }

    @FunctionalInterface
    public interface RowSelector {
        SelectResult select(Set<Integer> bannedRows);
    }

    @FunctionalInterface
    public interface RowChecker {
        CheckResult check(int rowIndex);
    }

    @FunctionalInterface
    public interface ColSelector {
        SelectResult select(Set<Integer> bannedCols);
    }

    @FunctionalInterface
    public interface ColChecker {
        CheckResult check(int colIndex);
    }
}