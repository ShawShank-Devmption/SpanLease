package dev.spanlease.analyzer;

import dev.spanlease.common.ContractJson;
import dev.spanlease.common.VerdictReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Usage: replay input.json output-reports.jsonl; never accepts historical v1 YAML implicitly. */
public final class ReplayMain {
  private ReplayMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2)
      throw new IllegalArgumentException("usage: replay input.json output.jsonl");
    var json = ContractJson.mapper();
    ReplaySession session = json.readValue(Path.of(args[0]).toFile(), ReplaySession.class);
    List<String> lines = new ArrayList<>();
    for (VerdictReport report : session.evaluate()) lines.add(json.writeValueAsString(report));
    Files.write(Path.of(args[1]), lines, StandardCharsets.UTF_8);
  }
}
