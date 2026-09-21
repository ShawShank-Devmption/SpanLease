package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.testbed.proto.CallRequest;
import dev.spanlease.testbed.proto.ChainGrpc;
import dev.spanlease.testbed.proto.Hop;
import io.grpc.MethodDescriptor;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class ChainContractTest {
  @Test
  void generatedContractRemainsUnaryWithFrozenFieldNumbers() {
    assertThat(ChainGrpc.getCallMethod().getType()).isEqualTo(MethodDescriptor.MethodType.UNARY);
    assertThat(ChainGrpc.getCallMethod().getFullMethodName())
        .isEqualTo("spanlease.testbed.Chain/Call");
    assertThat(CallRequest.REQUEST_ID_FIELD_NUMBER).isEqualTo(1);
    assertThat(CallRequest.REMAINING_FIELD_NUMBER).isEqualTo(2);
    assertThat(Hop.TARGET_SERVICE_FIELD_NUMBER).isEqualTo(1);
    assertThat(Hop.WORK_MS_FIELD_NUMBER).isEqualTo(2);
    assertThat(Hop.POST_HOLD_MS_FIELD_NUMBER).isEqualTo(3);
  }

  @Property(tries = 100, seed = "42")
  void protobufPreservesFiniteChain(@ForAll @IntRange(min = 0, max = 30_000) int workMs)
      throws Exception {
    CallRequest request =
        CallRequest.newBuilder()
            .setRequestId("bootstrap-42")
            .addRemaining(Hop.newBuilder().setTargetService("svcA").setWorkMs(workMs))
            .addRemaining(Hop.newBuilder().setTargetService("svcB").setPostHoldMs(workMs))
            .build();
    assertThat(CallRequest.parseFrom(request.toByteArray())).isEqualTo(request);
  }
}
