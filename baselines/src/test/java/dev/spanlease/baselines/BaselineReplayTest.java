package dev.spanlease.baselines;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.analyzer.ReplaySession;
import dev.spanlease.common.ContractJson;
import dev.spanlease.common.Verdict;
import org.junit.jupiter.api.Test;

class BaselineReplayTest {
  @Test
  void enrichedNativeSpanFactsProduceTheSameReportsAsDirectAnalysis() throws Exception {
    for (String name : java.util.List.of("closed-ring", "spare-capacity")) {
      var session = fixture(name);
      var results = BaselineReplay.evaluate(session, 8192);
      assertThat(results)
          .extracting(BaselineReplay.Result::enrichedLiveFacts)
          .containsExactlyElementsOf(session.evaluate());
      var last = results.getLast();
      assertThat(last.completedHandlerSpans()).isZero();
      assertThat(last.ordinaryLiveHandlerSpans()).isGreaterThan(0);
      assertThat(last.invocationCycleAlarm()).isTrue();
      assertThat(last.enrichedLiveFacts().verdict())
          .isEqualTo(
              name.equals("closed-ring")
                  ? Verdict.CONFIRMED_DEADLOCK
                  : Verdict.CONFIRMED_NO_DEADLOCK);
    }
  }

  @Test
  void evictedStartupEvidenceCannotBeRepairedByTriggeredBuffer() throws Exception {
    var results = BaselineReplay.evaluate(fixture("closed-ring"), 1);
    assertThat(results.getLast().bufferTriggered()).isTrue();
    assertThat(results.getLast().triggeredBufferFacts().verdict())
        .isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
  }

  private static ReplaySession fixture(String name) throws Exception {
    try (var input =
        BaselineReplayTest.class.getResourceAsStream("/replays-v2/" + name + "-v2.json")) {
      return ContractJson.mapper().readValue(input, ReplaySession.class);
    }
  }
}
