package dev.spanlease.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JacksonCompatibilityTest {
  @Test
  void jacksonPreservesLongIdentifiersWithoutTransportDependencies() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String encoded = mapper.writeValueAsString(Map.of("counter", Long.MAX_VALUE));
    assertThat(mapper.readTree(encoded).get("counter").longValue()).isEqualTo(Long.MAX_VALUE);
  }
}
