package dev.spanlease.testbed.feasibility;

import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.APPLICATION_SPAN_END;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.APPLICATION_SPAN_START;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.ARRIVAL;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.READY;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.SENT;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class FeasibilityJournalTest {
  @Test
  void assignsStrictlyIncreasingOrdinalsAndFiltersByInvocation() {
    FeasibilityJournal journal = new FeasibilityJournal();
    FeasibilityJournal.Entry sent = journal.append(SENT, "inv-a", "", "", "client@probe#1", "");
    FeasibilityJournal.Entry arrival =
        journal.append(ARRIVAL, "inv-a", "", "", "client@probe#1", "");

    assertThat(arrival.ordinal()).isGreaterThan(sent.ordinal());
    assertThat(journal.entriesFor("inv-a")).containsExactly(sent, arrival);
  }

  @Test
  void awaitReturnsOnlyAfterMatchingEntryIsAppended() throws Exception {
    FeasibilityJournal journal = new FeasibilityJournal();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<FeasibilityJournal.Entry> awaited =
          executor.submit(() -> journal.await(READY, "inv-a", Duration.ofSeconds(2)));
      journal.append(READY, "inv-a", "", "", "", "");

      assertThat(awaited.get(2, TimeUnit.SECONDS).hook()).isEqualTo(READY);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void spanProcessorRecordsSynchronousStartAndEnd() {
    FeasibilityJournal journal = new FeasibilityJournal();
    FeasibilitySpanProcessor processor = new FeasibilitySpanProcessor(journal);
    try (SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(processor).build()) {
      Span span =
          provider
              .get("b-feas")
              .spanBuilder("application")
              .setAttribute(FeasibilitySpanProcessor.INVOCATION_ID_ATTRIBUTE, "inv-a")
              .startSpan();
      span.end();
    }

    assertThat(journal.entriesFor("inv-a"))
        .extracting(FeasibilityJournal.Entry::hook)
        .containsExactly(APPLICATION_SPAN_START, APPLICATION_SPAN_END);
  }
}
