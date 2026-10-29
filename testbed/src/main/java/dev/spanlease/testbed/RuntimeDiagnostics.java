package dev.spanlease.testbed;

import dev.spanlease.instrumentation.SlotTrackingExecutor;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Operator diagnostics only. Sampling never supplies evidence to the analyzer. */
final class RuntimeDiagnostics {
  private final ConcurrentHashMap<String, Worker> active = new ConcurrentHashMap<>();

  void enter(String service, SlotTrackingExecutor.ExecutionContext execution) {
    active.put(execution.executionId(), new Worker(service, execution, Thread.currentThread()));
  }

  void exit(String executionId) {
    active.remove(executionId);
  }

  Snapshot sample() {
    var threads = ManagementFactory.getThreadMXBean();
    List<WorkerSnapshot> workers =
        active.values().stream()
            .map(
                worker -> {
                  ThreadInfo info = threads.getThreadInfo(worker.thread().threadId(), 48);
                  if (info == null) return null;
                  long cpu =
                      threads.isThreadCpuTimeSupported()
                          ? threads.getThreadCpuTime(info.getThreadId())
                          : -1;
                  return new WorkerSnapshot(
                      worker.service(),
                      worker.execution().executionId(),
                      worker.execution().inboundInvocationId(),
                      worker.execution().unitId(),
                      info.getThreadId(),
                      info.getThreadName(),
                      info.getThreadState().name(),
                      cpu < 0 ? null : cpu,
                      info.getBlockedCount(),
                      info.getWaitedCount(),
                      info.getLockName(),
                      info.getLockOwnerName(),
                      info.getLockOwnerId() < 0 ? null : info.getLockOwnerId(),
                      Arrays.stream(info.getStackTrace())
                          .map(StackTraceElement::toString)
                          .toList());
                })
            .filter(java.util.Objects::nonNull)
            .sorted(Comparator.comparing(WorkerSnapshot::executionId))
            .toList();
    var os = ManagementFactory.getOperatingSystemMXBean();
    var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    return new Snapshot(
        System.currentTimeMillis(),
        ProcessHandle.current().pid(),
        os.getName(),
        os.getVersion(),
        os.getArch(),
        os.getAvailableProcessors(),
        System.getProperty("java.runtime.version"),
        System.getProperty("java.vm.name"),
        memory.getUsed(),
        memory.getCommitted(),
        memory.getMax(),
        workers);
  }

  private record Worker(
      String service, SlotTrackingExecutor.ExecutionContext execution, Thread thread) {}

  public record Snapshot(
      long sampledAtWallMs,
      long pid,
      String osName,
      String osVersion,
      String architecture,
      int processors,
      String javaVersion,
      String vmName,
      long heapUsedBytes,
      long heapCommittedBytes,
      long heapMaxBytes,
      List<WorkerSnapshot> workers) {}

  public record WorkerSnapshot(
      String service,
      String executionId,
      String invocationId,
      String unitId,
      long javaThreadId,
      String threadName,
      String threadState,
      Long cpuTimeNs,
      long blockedCount,
      long waitedCount,
      String lockName,
      String lockOwnerName,
      Long lockOwnerJavaThreadId,
      List<String> stack) {}
}
