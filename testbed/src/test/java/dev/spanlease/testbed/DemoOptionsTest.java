package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.spanlease.testbed.proto.Hop;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DemoOptionsTest {
  @Test
  void defaultsRetainBothOriginalScenariosAndLegacyOutputDirectory() {
    DemoOptions options = DemoOptions.parse(new String[0]);
    assertThat(options.scenarios())
        .extracting(Scenario::name)
        .containsExactly("closed-ring", "spare-capacity");
    assertThat(options.runsDirectory()).isEqualTo(Path.of("live-runs"));
    assertThat(DemoOptions.parse(new String[] {"my-runs"}).runsDirectory())
        .isEqualTo(Path.of("my-runs"));
  }

  @Test
  void fiveServiceClosedRingReturnsToTheFirstOccupiedInstance() {
    DemoOptions options =
        DemoOptions.parse(new String[] {"--scenario", "closed-ring", "--services", "5"});
    Scenario scenario = options.scenarios().getFirst();
    assertThat(scenario.capacities())
        .hasSize(5)
        .allSatisfy((service, capacity) -> assertThat(capacity).isEqualTo(1));
    assertThat(scenario.rootHops())
        .extracting(Hop::getTargetService)
        .containsExactly("svcA", "svcB", "svcC", "svcD", "svcE", "svcA");
  }

  @Test
  void spareCapacityChangesOnlyTheReturnInstancesCapacityAndLeafWork() {
    Scenario scenario =
        DemoOptions.parse(new String[] {"--scenario", "spare-capacity", "--services", "2"})
            .scenarios()
            .getFirst();
    assertThat(scenario.capacities()).containsEntry("svcA", 2).containsEntry("svcB", 1);
    assertThat(scenario.rootHops())
        .extracting(Hop::getTargetService)
        .containsExactly("svcA", "svcB", "svcA");
    assertThat(scenario.rootHops().getLast().getWorkMs()).isEqualTo(4_000);
  }

  @Test
  void slowProgressHasAFiniteNonblockedLeafInsteadOfAQueuedReturnHop() {
    Scenario scenario =
        DemoOptions.parse(new String[] {"--scenario", "slow-progress"}).scenarios().getFirst();
    assertThat(scenario.rootHops())
        .extracting(Hop::getTargetService)
        .containsExactly("svcA", "svcB", "svcC");
    assertThat(scenario.rootHops().getLast().getWorkMs()).isEqualTo(4_000);
  }

  @Test
  void unsupportedOptionsFailBeforeAnyDeploymentStarts() {
    assertThatThrownBy(() -> DemoOptions.parse(new String[] {"--scenario", "typo"}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DemoOptions.parse(new String[] {"--services", "4"}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DemoOptions.parse(new String[] {"--scenario"}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DemoOptions.parse(new String[] {"--unknown", "value"}))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
