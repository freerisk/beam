/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.extensions.euphoria.executor.local;

import static java.util.Arrays.asList;

import java.time.Duration;
import java.util.List;
import org.apache.beam.sdk.extensions.euphoria.core.client.dataset.Dataset;
import org.apache.beam.sdk.extensions.euphoria.core.client.dataset.windowing.GlobalWindowing;
import org.apache.beam.sdk.extensions.euphoria.core.client.dataset.windowing.Time;
import org.apache.beam.sdk.extensions.euphoria.core.client.dataset.windowing.Windowing;
import org.apache.beam.sdk.extensions.euphoria.core.client.flow.Flow;
import org.apache.beam.sdk.extensions.euphoria.core.client.functional.UnaryFunctor;
import org.apache.beam.sdk.extensions.euphoria.core.client.io.ListDataSink;
import org.apache.beam.sdk.extensions.euphoria.core.client.io.ListDataSource;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.Filter;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.FlatMap;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.FullJoin;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.Join;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.MapElements;
import org.apache.beam.sdk.extensions.euphoria.core.client.util.Pair;
import org.apache.beam.sdk.extensions.euphoria.core.executor.Executor;
import org.apache.beam.sdk.extensions.euphoria.testing.DatasetAssert;
import org.junit.Before;
import org.junit.Test;

/**
 * Test of Join operator.
 */
public class JoinOperatorTest {

  private Executor executor;

  @Before
  public void setUp() {
    LocalExecutor exec = new LocalExecutor();
    // ~ see https://github.com/seznam/euphoria/issues/129
    exec.setTriggeringSchedulerSupplier(() -> new WatermarkTriggerScheduler<>(1));
    executor = exec;
  }

  @SuppressWarnings("unchecked")
  private void testJoin(
      boolean fullJoin,
      Windowing windowing,
      boolean bounded,
      List<I<String>> leftInput,
      List<I<String>> rightInput,
      List<String> expectedOutput,
      boolean makeOneArmLonger)
      throws Exception {
    final Flow flow = Flow.create("Test");

    final Dataset<String> first =
        MapElements.of(flow.createInput(ListDataSource.of(bounded, leftInput), i -> i.time))
            .using(i -> i.element)
            .output();

    final Dataset<String> second =
        MapElements.of(flow.createInput(ListDataSource.of(bounded, rightInput), i -> i.time))
            .using(i -> i.element)
            .output();

    final UnaryFunctor<String, Pair<String, Integer>> toPair =
        (s, c) -> {
          String[] parts = s.split("[\t ]+", 2);
          if (parts.length == 2) {
            c.collect(Pair.of(parts[0], Integer.valueOf(parts[1])));
          }
        };

    final Dataset<Pair<String, Integer>> firstPair = FlatMap.of(first).using(toPair).output();
    Dataset<Pair<String, Integer>> secondPair = FlatMap.of(second).using(toPair).output();

    if (makeOneArmLonger) {
      secondPair = Filter.of(secondPair).by(e -> true).output();
      secondPair = MapElements.of(secondPair).using(e -> e).output();
    }

    final Dataset<Pair<String, Object>> output;
    if (fullJoin) {
      output =
          FullJoin.of(firstPair, secondPair)
              .by(Pair::getFirst, Pair::getFirst)
              .using(
                  (l, r, c) -> {
                    final int x = l.isPresent() ? l.get().getSecond() : 0;
                    final int y = r.isPresent() ? r.get().getSecond() : 0;
                    c.collect(x + y);
                  })
              .windowBy(windowing)
              .output();
    } else {
      output =
          Join.of(firstPair, secondPair)
              .by(Pair::getFirst, Pair::getFirst)
              .using((l, r, c) -> c.collect((l.getSecond()) + (r.getSecond())))
              .windowBy(windowing)
              .output();
    }

    final ListDataSink<String> out = ListDataSink.get();

    MapElements.of(output).using(p -> p.getFirst() + ", " + p.getSecond()).output().persist(out);

    executor.submit(flow).get();

    DatasetAssert.unorderedEquals(out.getOutputs(), expectedOutput);
  }

  @Test
  public void testJoinOnBatch() throws Exception {
    testJoin(
        false,
        GlobalWindowing.get(),
        true,
        asList(I.of("one 1"), I.of("two 1"), I.of("one 22"), I.of("one 44")),
        asList(I.of("one 10"), I.of("two 20"), I.of("one 33"), I.of("three 55"), I.of("one 66")),
        asList(
            "one, 11",
            "one, 34",
            "one, 67",
            "one, 32",
            "one, 55",
            "one, 88",
            "one, 54",
            "one, 77",
            "one, 110",
            "two, 21"),
        false);
  }

  @Test
  public void testJoinOnStreams() throws Exception {
    testJoin(
        false,
        Time.of(Duration.ofSeconds(1)),
        false,
        asList(I.of("one 1", 1), I.of("two 1", 600), I.of("one 22", 1001), I.of("one 44", 2000)),
        asList(
            I.of("one 10", 1),
            I.of("two 20", 501),
            I.of("one 33", 1999),
            I.of("three 55", 2001),
            I.of("one 66", 3000)),
        asList("one, 11", "two, 21", "one, 55"),
        false);
  }

  @Test
  public void testOuterJoinOnBatch() throws Exception {
    testJoin(
        true,
        GlobalWindowing.get(),
        true,
        asList(I.of("one 1"), I.of("two 1"), I.of("one 22"), I.of("one 44")),
        asList(I.of("one 10"), I.of("two 20"), I.of("one 33"), I.of("three 55"), I.of("one 66")),
        asList(
            "one, 11",
            "one, 34",
            "one, 67",
            "one, 32",
            "one, 55",
            "one, 88",
            "one, 54",
            "one, 77",
            "one, 110",
            "two, 21",
            "three, 55"),
        false);
  }

  @Test
  public void testOuterJoinOnStream() throws Exception {
    testJoin(
        true,
        Time.of(Duration.ofMillis(1)),
        false,
        asList(I.of("one 1", 0), I.of("two 1", 1), I.of("one 22", 3), I.of("one 44", 4)),
        asList(
            I.of("one 10", 0),
            I.of("two 20", 1),
            I.of("one 33", 3),
            I.of("three 55", 4),
            I.of("one 66", 5)),
        asList("one, 11", "two, 21", "one, 55", "one, 44", "three, 55", "one, 66"),
        false);
  }

  @Test
  public void testOneArmLongerJoin() throws Exception {
    testJoin(
        false,
        GlobalWindowing.get(),
        true,
        asList(I.of("one 1"), I.of("two 1"), I.of("one 22"), I.of("one 44")),
        asList(I.of("one 10"), I.of("two 20"), I.of("one 33"), I.of("three 55"), I.of("one 66")),
        asList(
            "one, 11",
            "one, 34",
            "one, 67",
            "one, 32",
            "one, 55",
            "one, 88",
            "one, 54",
            "one, 77",
            "one, 110",
            "two, 21"),
        true);
  }

  static final class I<T> {
    T element;
    long time;

    static <T> I<T> of(T e) {
      return of(e, 0L);
    }

    static <T> I<T> of(T e, long time) {
      I<T> i = new I<>();
      i.element = e;
      i.time = time;
      return i;
    }
  }
}