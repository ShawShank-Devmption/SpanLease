package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.spanlease.testbed.proto.Hop;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BoutiqueScenarioTest {
  @Test
  void boutiqueScenariosHaveExactTopology() {
    Scenario spare = Scenario.named("boutique-spare-capacity");
    assertThat(spare.application()).isEqualTo(Scenario.Application.BOUTIQUE);
    assertThat(spare.capacities())
        .containsExactlyInAnyOrderEntriesOf(Map.of("ads-a", 2, "ads-b", 1, "ads-c", 1));
    assertThat(spare.rootHops())
        .extracting(Hop::getTargetService)
        .containsExactly("ads-a", "ads-b", "ads-c", "ads-a");
    assertThat(spare.rootHops().getLast().getWorkMs()).isEqualTo(4_000);
    assertThat(spare.injectable()).isTrue();
    assertThat(spare.rootWithoutDeadline()).isFalse();

    Scenario deadlock = Scenario.named("boutique-deadlock");
    assertThat(deadlock.capacities().values()).containsOnly(1);
    assertThat(deadlock.rootWithoutDeadline()).isTrue();
    assertThat(deadlock.rootHops().getLast().getWorkMs()).isZero();

    Scenario live = Scenario.named("boutique-live");
    assertThat(live.injectable()).isFalse();
    assertThat(live.manualStop()).isTrue();
    assertThat(live.rootHops()).extracting(Hop::getTargetService).containsExactly("ads-a");
  }

  @Test
  void chainScenariosKeepTheirBehavior() {
    assertThat(Scenario.named("closed-ring").application()).isEqualTo(Scenario.Application.CHAIN);
    assertThat(Scenario.named("closed-ring").rootWithoutDeadline()).isTrue();
    assertThat(Scenario.named("spare-capacity").manualStop()).isFalse();

    Scenario fiveServiceRing = Scenario.closedRing(5);
    assertThat(fiveServiceRing.name()).isEqualTo("closed-ring-5");
    assertThat(fiveServiceRing.manualStop()).isTrue();
    assertThat(fiveServiceRing.rootWithoutDeadline()).isTrue();
  }

  @Test
  void unknownScenarioIsRejected() {
    assertThatThrownBy(() -> Scenario.named("boutique"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
