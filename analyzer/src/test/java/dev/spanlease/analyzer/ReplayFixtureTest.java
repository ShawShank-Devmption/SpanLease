package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.ContractJson;
import dev.spanlease.common.VerdictReport;
import org.junit.jupiter.api.Test;

class ReplayFixtureTest {
  @Test
  void migratedV2SessionsProduceTheirExactGoldenReports() throws Exception {
    var json = ContractJson.mapper();
    for (String name : java.util.List.of("closed-ring", "spare-capacity")) {
      try (var input = getClass().getResourceAsStream("/replays-v2/" + name + "-v2.json");
          var expected = getClass().getResourceAsStream("/replays-v2/" + name + "-expected.json")) {
        var session = json.readValue(input, ReplaySession.class);
        var report = json.readValue(expected, VerdictReport.class);
        assertThat(session.evaluate().getLast()).isEqualTo(report);
      }
    }
  }
}
