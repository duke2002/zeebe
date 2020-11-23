/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.test;

import static io.zeebe.test.UpdateTestCaseProvider.PROCESS_ID;
import static org.assertj.core.api.Assertions.assertThat;

import io.zeebe.test.PartitionsActuatorClient.PartitionStatus;
import io.zeebe.test.util.asserts.EitherAssert;
import io.zeebe.util.Either;
import io.zeebe.util.VersionUtil;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.testcontainers.containers.Network;

@Execution(ExecutionMode.CONCURRENT)
@ExtendWith(ContainerStateExtension.class)
class UpdateTest {
  private static final String LAST_VERSION = VersionUtil.getPreviousVersion();
  private static final String CURRENT_VERSION = "current-test";
  private static Network network;

  @BeforeAll
  static void setUp() {
    network = Network.newNetwork();
  }

  @AfterAll
  static void tearDown() {
    Optional.ofNullable(network).ifPresent(Network::close);
  }

  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  @ParameterizedTest(name = "{0}")
  @ArgumentsSource(UpdateTestCaseProvider.class)
  void oldGatewayWithNewBroker(
      final String name, final UpdateTestCase testCase, final ContainerState state) {
    // given
    state
        .withNetwork(network)
        .broker(CURRENT_VERSION)
        .withStandaloneGateway(LAST_VERSION)
        .start(true);
    final long wfInstanceKey = testCase.setUp(state.client());

    // when
    final long key = testCase.runBefore(state);

    // then
    testCase.runAfter(state, wfInstanceKey, key);
    awaitProcessCompletion(state);
  }

  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  @ParameterizedTest(name = "{0}")
  @ArgumentsSource(UpdateTestCaseProvider.class)
  void updateWithSnapshot(
      final String name, final UpdateTestCase testCase, final ContainerState state) {
    updateZeebe(state, testCase, true);
  }

  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  @ParameterizedTest(name = "{0}")
  @ArgumentsSource(UpdateTestCaseProvider.class)
  void updateWithoutSnapshot(
      final String name, final UpdateTestCase testCase, final ContainerState state) {
    updateZeebe(state, testCase, false);
  }

  private void updateZeebe(
      final ContainerState state, final UpdateTestCase testCase, final boolean withSnapshot) {
    // given
    state.withNetwork(network).broker(LAST_VERSION).start(true);
    final long wfInstanceKey = testCase.setUp(state.client());
    final long key = testCase.runBefore(state);

    // when
    if (withSnapshot) {
      // it's necessary to restart without the debug exporter to allow snapshotting
      state.close();
      state.broker(LAST_VERSION).start(false);
      EitherAssert.assertThat(state.getPartitionsActuatorClient().takeSnapshot())
          .as("expect successful response as right member")
          .isRight();
      Awaitility.await("until a snapshot is available")
          .atMost(Duration.ofSeconds(30))
          .pollInterval(Duration.ofMillis(500))
          .untilAsserted(() -> assertSnapshotAvailable(state));
    }

    // perform the update
    state.close();
    state.broker(CURRENT_VERSION).start(true);
    if (withSnapshot) {
      assertSnapshotAvailable(state);
    } else {
      assertNoSnapshotAvailable(state);
    }

    // then
    testCase.runAfter(state, wfInstanceKey, key);
    awaitProcessCompletion(state);
  }

  private void awaitProcessCompletion(final ContainerState state) {
    Awaitility.await("until process is completed")
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> assertThat(state.hasElementInState(PROCESS_ID, "ELEMENT_COMPLETED")).isTrue());
  }

  private void assertSnapshotAvailable(final ContainerState state) {
    final Either<Throwable, Map<String, PartitionStatus>> response =
        state.getPartitionsActuatorClient().queryPartitions();
    EitherAssert.assertThat(response).isRight();

    final PartitionStatus partitionStatus = response.get().get("1");
    assertThat(partitionStatus).isNotNull();
    assertThat(partitionStatus.snapshotId).isNotBlank();
  }

  private void assertNoSnapshotAvailable(final ContainerState state) {
    final Either<Throwable, Map<String, PartitionStatus>> response =
        state.getPartitionsActuatorClient().queryPartitions();
    EitherAssert.assertThat(response).isRight();

    final PartitionStatus partitionStatus = response.get().get("1");
    assertThat(partitionStatus).isNotNull();
    assertThat(partitionStatus.snapshotId).isBlank();
  }
}
