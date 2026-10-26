package dev.spanlease.instrumentation;

import dev.spanlease.common.ContractJson;
import dev.spanlease.common.ObservationEvent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, asynchronous custom-LogRecord sink; application publication is an immediate offer. */
public final class OtelEventSink implements EventSink, AutoCloseable {
  private static final AttributeKey<Long> SCHEMA_VERSION =
      AttributeKey.longKey("sl.schema_version");
  private static final AttributeKey<String> EVENT_TYPE = AttributeKey.stringKey("sl.event_type");
  private static final AttributeKey<String> INSTANCE_ID = AttributeKey.stringKey("sl.instance_id");
  private static final AttributeKey<Long> LOCAL_SEQUENCE = AttributeKey.longKey("sl.local_seq");
  private static final AttributeKey<String> CAUSAL_PARENT =
      AttributeKey.stringKey("sl.causal_parent");
  private static final AttributeKey<Long> WALL_TIME = AttributeKey.longKey("sl.wall_time_ms");
  private static final AttributeKey<Long> MONOTONIC_TIME =
      AttributeKey.longKey("sl.monotonic_time_ns");

  private final BlockingQueue<ObservationEvent> queue;
  private final Logger logger;
  private final Thread exporterThread;
  private final AtomicBoolean closing = new AtomicBoolean();
  private final AtomicLong rejectedOffers = new AtomicLong();
  private final AtomicLong encodedBytes = new AtomicLong();
  private final AtomicLong encodingFailures = new AtomicLong();

  public OtelEventSink(int capacity, Logger logger, String threadName) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.logger = Objects.requireNonNull(logger, "logger");
    if (threadName == null || threadName.isBlank()) {
      throw new IllegalArgumentException("threadName must be nonblank");
    }
    this.exporterThread = new Thread(this::exportLoop, threadName);
    this.exporterThread.setDaemon(true);
    this.exporterThread.start();
  }

  @Override
  public boolean tryPublish(ObservationEvent event) {
    Objects.requireNonNull(event, "event");
    boolean accepted = !closing.get() && queue.offer(event);
    if (!accepted) {
      rejectedOffers.incrementAndGet();
    }
    return accepted;
  }

  public long rejectedOffers() {
    return rejectedOffers.get();
  }

  public long encodedBytes() {
    return encodedBytes.get();
  }

  public long encodingFailures() {
    return encodingFailures.get();
  }

  private void exportLoop() {
    while (!closing.get() || !queue.isEmpty()) {
      try {
        ObservationEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
        if (event != null) {
          emit(event);
        }
      } catch (InterruptedException interrupted) {
        if (!closing.get()) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void emit(ObservationEvent event) {
    try {
      String body = ContractJson.mapper().writeValueAsString(event);
      encodedBytes.addAndGet(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
      logger
          .logRecordBuilder()
          .setTimestamp(event.wallTimeMs(), TimeUnit.MILLISECONDS)
          .setBody(body)
          .setAttribute(SCHEMA_VERSION, (long) event.schemaVersion())
          .setAttribute(EVENT_TYPE, event.eventType().wireName())
          .setAttribute(INSTANCE_ID, event.instanceId())
          .setAttribute(LOCAL_SEQUENCE, event.localSeq())
          .setAttribute(CAUSAL_PARENT, event.causalParent())
          .setAttribute(WALL_TIME, event.wallTimeMs())
          .setAttribute(MONOTONIC_TIME, event.monotonicTimeNs())
          .emit();
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException failure) {
      encodingFailures.incrementAndGet();
    }
  }

  @Override
  public void close() throws InterruptedException {
    if (!closing.compareAndSet(false, true)) {
      return;
    }
    exporterThread.interrupt();
    exporterThread.join(TimeUnit.SECONDS.toMillis(5));
    if (exporterThread.isAlive()) {
      throw new IllegalStateException("event exporter thread did not terminate");
    }
  }
}
