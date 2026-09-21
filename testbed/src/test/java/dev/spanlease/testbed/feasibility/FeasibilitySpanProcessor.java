package dev.spanlease.testbed.feasibility;

import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.APPLICATION_SPAN_END;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.APPLICATION_SPAN_START;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.Objects;

final class FeasibilitySpanProcessor implements SpanProcessor {
  static final AttributeKey<String> INVOCATION_ID_ATTRIBUTE =
      AttributeKey.stringKey("bfeas.invocation_id");

  private final FeasibilityJournal journal;

  FeasibilitySpanProcessor(FeasibilityJournal journal) {
    this.journal = Objects.requireNonNull(journal, "journal");
  }

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    journal.append(APPLICATION_SPAN_START, requiredInvocationId(span), "", "", "", "");
  }

  @Override
  public void onEnd(ReadableSpan span) {
    journal.append(APPLICATION_SPAN_END, requiredInvocationId(span), "", "", "", "");
  }

  @Override
  public boolean isStartRequired() {
    return true;
  }

  @Override
  public boolean isEndRequired() {
    return true;
  }

  private static String requiredInvocationId(ReadableSpan span) {
    String invocationId = span.getAttribute(INVOCATION_ID_ATTRIBUTE);
    if (invocationId == null || invocationId.isBlank()) {
      throw new IllegalStateException("application span requires an invocation ID");
    }
    return invocationId;
  }
}
