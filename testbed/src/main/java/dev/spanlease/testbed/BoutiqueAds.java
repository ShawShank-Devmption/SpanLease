/*
 * Copyright 2018 Google LLC
 * Licensed under the Apache License, Version 2.0; see third_party/online-boutique/LICENSE.
 * Adapted from Online Boutique AdService.java; provenance and changes are in SOURCE.md.
 */
package dev.spanlease.testbed;

import hipstershop.Demo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Google's category selection and uniform two-ad fallback, with an injected workload RNG. */
final class BoutiqueAds {
  static final String UPSTREAM_REVISION = "38e7348eb289eb5b87c0c6e8cb19ced0449dc389";
  private final Map<String, List<Demo.Ad>> byCategory;
  private final List<Demo.Ad> allAds;
  private final RandomGenerator random;

  BoutiqueAds(RandomGenerator random) {
    this.random = Objects.requireNonNull(random);
    Map<String, List<Demo.Ad>> catalog = new LinkedHashMap<>();
    catalog.put("clothing", List.of(ad("66VCHSJNUP", "Tank top for sale. 20% off.")));
    catalog.put(
        "accessories",
        List.of(ad("1YMWWN1N4O", "Watch for sale. Buy one, get second kit for free")));
    catalog.put(
        "footwear",
        List.of(ad("L9ECAV7KIM", "Loafers for sale. Buy one, get second one for free")));
    catalog.put("hair", List.of(ad("2ZYFJ3GM2N", "Hairdryer for sale. 50% off.")));
    catalog.put("decor", List.of(ad("0PUK6V6EV0", "Candle holder for sale. 30% off.")));
    catalog.put(
        "kitchen",
        List.of(
            ad("0PUK6V6EV0", "Bamboo glass jar for sale. 10% off."),
            ad("6E92ZMYYFZ", "Mug for sale. Buy two, get third one for free")));
    byCategory = Map.copyOf(catalog);
    allAds = catalog.values().stream().flatMap(List::stream).toList();
  }

  Demo.AdResponse getAds(Demo.AdRequest request) {
    List<Demo.Ad> selected = new ArrayList<>();
    for (String key : request.getContextKeysList())
      selected.addAll(byCategory.getOrDefault(key, List.of()));
    if (selected.isEmpty()) {
      selected.add(allAds.get(random.nextInt(allAds.size())));
      selected.add(allAds.get(random.nextInt(allAds.size())));
    }
    return Demo.AdResponse.newBuilder().addAllAds(selected).build();
  }

  private static Demo.Ad ad(String product, String message) {
    return Demo.Ad.newBuilder().setRedirectUrl("/product/" + product).setText(message).build();
  }
}
