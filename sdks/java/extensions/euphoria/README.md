<!--
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
-->

# Euphoria

Euphoria is an open source Java API for creating unified big-data
processing flows.  It provides an engine independent programming model
that can express both batch and stream transformations.

The main goal of the API is to ease the creation of programs with
business logic independent of a specific runtime framework/engine and
independent of the source or destination of the processed data.  Such
programs are then transferable with little effort to new environments
and new data sources or destinations - idealy just by configuration.


## Key features

 * Unified API that supports both batch and stream processing using
   the same code
 * Avoids vendor lock-in - migrating between different engines is
   matter of configuration
 * Declarative Java API using Java 8 Lambda expressions
 * Support for different notions of time (_event time, ingestion
   time_)
 * Flexible windowing (_Time, TimeSliding, Session, Count_)

## Download

The best way to use Euphoria is by adding the following Maven dependency to your _pom.xml_:

```xml
<dependency>
  <groupId>cz.seznam.euphoria</groupId>
  <artifactId>euphoria-core</artifactId>
  <version>0.7.0</version>
</dependency>
```
You may want to add additional modules, such as support of various engines or I/O data sources/sinks. For more details read the [Maven Dependencies](https://github.com/seznam/euphoria/wiki/Maven-dependencies) wiki page.


## WordCount example

```java
// Define data source and data sinks
DataSource<String> dataSource = new SimpleHadoopTextFileSource(inputPath);
DataSink<String> dataSink = new SimpleHadoopTextFileSink<>(outputPath);

// Define a flow, i.e. a chain of transformations
Flow flow = Flow.create("WordCount");

Dataset<String> lines = flow.createInput(dataSource);

Dataset<String> words = FlatMap.named("TOKENIZER")
    .of(lines)
    .using((String line, Collector<String> context) -> {
      for (String word : line.split("\\s+")) {
        context.collect(word);
      }
    })
    .output();

Dataset<Pair<String, Long>> counted = ReduceByKey.named("COUNT")
    .of(words)
    .keyBy(w -> w)
    .valueBy(w -> 1L)
    .combineBy(Sums.ofLongs())
    .output();

MapElements.named("FORMAT")
    .of(counted)
    .using(p -> p.getFirst() + "\n" + p.getSecond())
    .output()
    .persist(dataSink);

// Initialize an executor and run the flow (using Apache Flink)
try {
  Executor executor = new FlinkExecutor();
  executor.submit(flow).get();
} catch (InterruptedException ex) {
  LOG.warn("Interrupted while waiting for the flow to finish.", ex);
} catch (IOException | ExecutionException ex) {
  throw new RuntimeException(ex);
}
```

## Supported Engines

Euphoria translates flows, also known as data transformation
pipelines, into the specific API of a chosen, supported big-data
processing engine.  Currently, the following are supported:

 * [Apache Flink](https://flink.apache.org/)
 * [Apache Spark](http://spark.apache.org/)
 * An independent, standalone, in-memory engine which is part of the
   Euphoria project suitable for running flows in unit tests.

In the WordCount example from above, to switch the execution engine
from Apache Flink to Apache Spark, we'd merely need to replace
`FlinkExecutor` with `SparkExecutor`.

## Bugs / Features / Contributing

There's still a lot of room for improvements and extensions.  Have a
look into the [issue tracker](https://github.com/seznam/euphoria/issues)
and feel free to contribute by reporting new problems, contributing to
existing ones, or even open issues in case of questions.  Any constructive
feedback is warmly welcome!

As usually with open source, don't hesitate to fork the repo and
submit a pull requests if you see something to be changed.  We'll be
happy see euphoria improving over time.

## Building

To build the Euphoria artifacts, the following is required:

* Git
* Java 8

Building the project itself is a matter of:

```
git clone https://github.com/seznam/euphoria
cd euphoria
./gradlew publishToMavenLocal -xtest
```

## Documentation

* An incipient documentation is currently maintained in the form of a
  [Wiki on Github](https://github.com/seznam/euphoria/wiki), including a brief [FAQ page](https://github.com/seznam/euphoria/wiki/FAQ).

* Another source of documentation are deliberately simple examples
  maintained in the [euphoria-examples module](https://github.com/seznam/euphoria/tree/master/euphoria-examples).
  
## Contact us

* Feel free to open an issue in the [issue tracker](https://github.com/seznam/euphoria/issues)

## License

Euphoria is licensed under the terms of the Apache License 2.0.