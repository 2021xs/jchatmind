package com.kama.jchatmind.agent;

import com.kama.jchatmind.model.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskEvidenceStateTest {

    @Test
    void exactChunkDuplicateDoesNotCountAsNewEvidence() {
        TaskEvidenceState state = new TaskEvidenceState();
        state.observeSearch("repo-1", "first", List.of(evidence("C1", "A.java", "A#run", 1, 30)));

        TaskEvidenceState.SearchObservation duplicate = state.observeSearch(
                "repo-1", "rewritten", List.of(evidence("C1", "A.java", "A#run", 1, 30)));

        assertThat(duplicate.returnedEvidenceCount()).isEqualTo(1);
        assertThat(duplicate.newEvidenceCount()).isZero();
        assertThat(duplicate.duplicateEvidenceCount()).isEqualTo(1);
    }

    @Test
    void differentChunkInsideCoveredRangeIsDuplicate() {
        TaskEvidenceState state = new TaskEvidenceState();
        state.observeSearch("repo-1", "first", List.of(evidence("C1", "A.java", "A#run", 1, 50)));

        TaskEvidenceState.SearchObservation covered = state.observeSearch(
                "repo-1", "narrower", List.of(evidence("C2", "A.java", "A#run", 10, 30)));

        assertThat(covered.newEvidenceCount()).isZero();
        assertThat(covered.duplicateEvidenceCount()).isEqualTo(1);
    }

    @Test
    void partiallyUncoveredRangeIsNovelAndMergesCoverage() {
        TaskEvidenceState state = new TaskEvidenceState();
        state.observeSearch("repo-1", "first", List.of(evidence("C1", "A.java", "A#run", 1, 30)));

        TaskEvidenceState.SearchObservation partial = state.observeSearch(
                "repo-1", "extend", List.of(evidence("C2", "A.java", "A#run", 20, 50)));

        assertThat(partial.newEvidenceCount()).isEqualTo(1);
        assertThat(state.snapshot().coverage()).singleElement()
                .satisfies(coverage -> assertThat(coverage.lineRanges()).isEqualTo("1-50"));
    }

    @Test
    void newFileAndSymbolRemainNovel() {
        TaskEvidenceState state = new TaskEvidenceState();
        state.observeSearch("repo-1", "script", List.of(
                evidence("C1", "src/main/resources/seckill.lua", null, 1, 47)));

        TaskEvidenceState.SearchObservation service = state.observeSearch("repo-1", "caller", List.of(
                evidence("C2", "VoucherOrderServiceImpl.java", "VoucherOrderServiceImpl#execute", 80, 120)));

        assertThat(service.newEvidenceCount()).isEqualTo(1);
        assertThat(service.newFiles()).containsExactly("VoucherOrderServiceImpl.java");
        assertThat(service.newSymbols()).containsExactly("VoucherOrderServiceImpl#execute");
    }

    @Test
    void twoNoNoveltySearchesActivateGuardButNovelSearchResetsSequence() {
        TaskEvidenceState state = new TaskEvidenceState();
        state.observeSearch("repo-1", "seed", List.of(evidence("C1", "A.java", "A#run", 1, 50)));
        state.observeSearch("repo-1", "duplicate-1", List.of(evidence("C1", "A.java", "A#run", 1, 50)));
        assertThat(state.isCodeSearchBlocked()).isFalse();

        state.observeSearch("repo-1", "new", List.of(evidence("C2", "B.java", "B#run", 1, 20)));
        assertThat(state.snapshot().consecutiveNoNoveltySearches()).isZero();
        assertThat(state.isCodeSearchBlocked()).isFalse();

        state.observeSearch("repo-1", "duplicate-2", List.of(evidence("C2", "B.java", "B#run", 1, 20)));
        state.observeSearch("repo-1", "duplicate-3", List.of(evidence("C3", "B.java", "B#run", 5, 10)));
        assertThat(state.isCodeSearchBlocked()).isTrue();
    }

    @Test
    void completeScriptRangeMakesRewrittenQueriesDuplicateWithoutBusinessHardcoding() {
        TaskEvidenceState state = new TaskEvidenceState();
        state.observeSearch("repo-1", "script implementation", List.of(
                evidence("SCRIPT-FULL", "src/main/resources/seckill.lua", null, 1, 47)));

        TaskEvidenceState.SearchObservation repeated = state.observeSearch("repo-1", "reservation return values", List.of(
                evidence("SCRIPT-SLICE", "src/main/resources/seckill.lua", null, 8, 42)));

        assertThat(repeated.newEvidenceCount()).isZero();
        assertThat(repeated.duplicateEvidenceCount()).isEqualTo(1);
        assertThat(state.snapshot().compactCoverage(5)).contains("src/main/resources/seckill.lua", "1-47");
    }

    private CodeSearchResult evidence(String chunkId, String file, String symbol, int start, int end) {
        return CodeSearchResult.builder()
                .chunkId(chunkId)
                .repoId("repo-1")
                .filePath(file)
                .symbolName(symbol)
                .startLine(start)
                .endLine(end)
                .contentPreview("evidence")
                .build();
    }
}
