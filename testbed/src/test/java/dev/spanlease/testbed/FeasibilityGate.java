package dev.spanlease.testbed;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Test-only application gate used to establish B-FEAS lifecycle behavior. */
final class FeasibilityGate implements AutoCloseable {
  private final ReentrantLock lock = new ReentrantLock();
  private final Deque<Job> waiting = new ArrayDeque<>();
  private final ExecutorService workers;
  private final BlockingQueue<String> transitions;
  private final int waitingCapacity;
  private int freeSlots;
  private String owner;

  FeasibilityGate(int capacity, int waitingCapacity, BlockingQueue<String> transitions) {
    if (capacity <= 0 || waitingCapacity <= 0) {
      throw new IllegalArgumentException("gate capacities must be positive");
    }
    this.freeSlots = capacity;
    this.waitingCapacity = waitingCapacity;
    this.transitions = transitions;
    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable, "feasibility-app-worker");
          thread.setDaemon(true);
          return thread;
        };
    this.workers = Executors.newFixedThreadPool(capacity, factory);
  }

  boolean submit(String id, Runnable task) {
    Job dispatch = null;
    lock.lock();
    try {
      if (freeSlots > 0) {
        freeSlots--;
        owner = id;
        transitions.add("acquire:" + id);
        dispatch = new Job(id, task);
      } else if (waiting.size() < waitingCapacity) {
        waiting.addLast(new Job(id, task));
        transitions.add("wait:" + id);
      } else {
        transitions.add("reject:" + id);
        return false;
      }
    } finally {
      lock.unlock();
    }
    if (dispatch != null) {
      dispatch(dispatch);
    }
    return true;
  }

  String owner() {
    lock.lock();
    try {
      return owner;
    } finally {
      lock.unlock();
    }
  }

  int waitingCount() {
    lock.lock();
    try {
      return waiting.size();
    } finally {
      lock.unlock();
    }
  }

  private void dispatch(Job job) {
    workers.execute(
        () -> {
          try {
            job.task().run();
          } finally {
            complete(job.id());
          }
        });
  }

  private void complete(String id) {
    Job next = null;
    lock.lock();
    try {
      transitions.add("end:" + id);
      transitions.add("release:" + id);
      owner = null;
      if (waiting.isEmpty()) {
        freeSlots++;
      } else {
        next = waiting.removeFirst();
        owner = next.id();
        transitions.add("acquire:" + next.id());
      }
    } finally {
      lock.unlock();
    }
    if (next != null) {
      dispatch(next);
    }
  }

  @Override
  public void close() throws InterruptedException {
    workers.shutdownNow();
    if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("application gate did not terminate");
    }
  }

  private record Job(String id, Runnable task) {}
}
