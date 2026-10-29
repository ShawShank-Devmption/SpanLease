package dev.spanlease.common;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

final class ContractValidation {
  private static final int MAX_ID_LENGTH = 256;

  private ContractValidation() {}

  static String id(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank() || value.length() > MAX_ID_LENGTH) {
      throw new IllegalArgumentException(field + " must be nonblank and at most 256 characters");
    }
    return value;
  }

  static String optionalId(String value, String field) {
    Objects.requireNonNull(value, field);
    return value.isEmpty() ? value : id(value, field);
  }

  static long nonnegative(long value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must be nonnegative");
    }
    return value;
  }

  static int positive(int value, String field) {
    if (value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return value;
  }

  static long positive(long value, String field) {
    if (value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return value;
  }

  static List<String> ids(Collection<String> values, String field) {
    Objects.requireNonNull(values, field);
    List<String> result = values.stream().map(value -> id(value, field)).sorted().toList();
    if (new HashSet<>(result).size() != result.size()) {
      throw new IllegalArgumentException(field + " contains duplicates");
    }
    return result;
  }
}
