package dev.spanlease.testbed;

import dev.spanlease.common.ContractJson;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Service JVM lifecycle. A hard crash emits no final checkpoint, execution exit or slot release.
 */
public final class ForkedNode implements AutoCloseable {
  private final Process process;
  private final PrintWriter commands;
  private final CompletableFuture<Integer> ready = new CompletableFuture<>();
  private final CompletableFuture<Void> connected = new CompletableFuture<>();

  public ForkedNode(NodeProcess.Configuration configuration, Path configPath) throws Exception {
    ContractJson.mapper().writeValue(configPath.toFile(), configuration);
    process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                NodeProcess.class.getName(),
                configPath.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    commands = new PrintWriter(new OutputStreamWriter(process.getOutputStream()), true);
    Thread reader =
        new Thread(
            () -> {
              try (var output =
                  new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                for (String line = output.readLine(); line != null; line = output.readLine()) {
                  if (line.startsWith("READY "))
                    ready.complete(Integer.parseInt(line.substring(6)));
                  else if (line.equals("CONNECTED")) connected.complete(null);
                }
                if (!ready.isDone())
                  ready.completeExceptionally(
                      new IllegalStateException("node exited before ready"));
                if (!connected.isDone())
                  connected.completeExceptionally(
                      new IllegalStateException("node exited before connect"));
              } catch (Exception failure) {
                ready.completeExceptionally(failure);
                connected.completeExceptionally(failure);
              }
            },
            "node-output");
    reader.setDaemon(true);
    reader.start();
  }

  public int port() throws Exception {
    return ready.get(30, TimeUnit.SECONDS);
  }

  public void connect(Map<String, Integer> ports) throws Exception {
    commands.println("CONNECT " + ContractJson.mapper().writeValueAsString(ports));
    connected.get(10, TimeUnit.SECONDS);
  }

  public long crash() throws Exception {
    long at = System.currentTimeMillis();
    process.destroyForcibly();
    if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IllegalStateException("node did not die");
    return at;
  }

  public long pid() {
    return process.pid();
  }

  @Override
  public void close() throws Exception {
    if (process.isAlive()) {
      commands.println("STOP");
      if (!process.waitFor(15, TimeUnit.SECONDS)) crash();
    }
    commands.close();
  }
}
