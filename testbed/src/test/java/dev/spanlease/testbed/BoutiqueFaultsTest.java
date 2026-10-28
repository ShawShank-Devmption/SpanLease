package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoutiqueFaultsTest {
  @Test
  void missingMetadataIsAlwaysLocalAndCanonicalBudgetsAreBounded() {
    assertThat(BoutiqueFaults.parse(new Metadata(), false)).isEqualTo(BoutiqueFaults.ABSENT);
    assertThat(BoutiqueFaults.parse(new Metadata(), true)).isEqualTo(BoutiqueFaults.ABSENT);
    for (int value = 0; value <= 3; value++)
      assertThat(BoutiqueFaults.parse(BoutiqueFaults.headers(value), true)).isEqualTo(value);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "4", "", "x", " 1", "+1", "01", "10000000000000000"})
  void malformedDiagnosticBudgetsAreRejected(String value) {
    Metadata headers = new Metadata();
    headers.put(BoutiqueFaults.HOPS, value);
    assertThatThrownBy(() -> BoutiqueFaults.parse(headers, true))
        .isInstanceOf(StatusRuntimeException.class);
  }

  @Test
  void duplicatesAndNormalModeCannotEnableAForwardingFault() {
    Metadata duplicate = BoutiqueFaults.headers(1);
    duplicate.put(BoutiqueFaults.HOPS, "0");
    assertThatThrownBy(() -> BoutiqueFaults.parse(duplicate, true))
        .isInstanceOf(StatusRuntimeException.class);
    assertThatThrownBy(() -> BoutiqueFaults.parse(BoutiqueFaults.headers(0), false))
        .isInstanceOf(StatusRuntimeException.class);
  }
}
