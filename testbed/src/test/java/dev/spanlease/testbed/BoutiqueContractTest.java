package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import hipstershop.AdServiceGrpc;
import hipstershop.Demo;
import io.grpc.MethodDescriptor;
import org.junit.jupiter.api.Test;

class BoutiqueContractTest {
  @Test
  void nativeGoogleUnaryMethodAndMessageFieldsArePreserved() {
    assertThat(AdServiceGrpc.getGetAdsMethod().getFullMethodName())
        .isEqualTo("hipstershop.AdService/GetAds");
    assertThat(AdServiceGrpc.getGetAdsMethod().getType())
        .isEqualTo(MethodDescriptor.MethodType.UNARY);
    assertThat(Demo.AdRequest.CONTEXT_KEYS_FIELD_NUMBER).isEqualTo(1);
    assertThat(Demo.AdResponse.ADS_FIELD_NUMBER).isEqualTo(1);
    assertThat(Demo.Ad.REDIRECT_URL_FIELD_NUMBER).isEqualTo(1);
    assertThat(Demo.Ad.TEXT_FIELD_NUMBER).isEqualTo(2);
  }
}
