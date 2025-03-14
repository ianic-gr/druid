---
id: kafka-ingestion
title: "Apache Kafka ingestion"
sidebar_label: "Apache Kafka"
---

<!--
  ~ Licensed to the Apache Software Foundation (ASF) under one
  ~ or more contributor license agreements.  See the NOTICE file
  ~ distributed with this work for additional information
  ~ regarding copyright ownership.  The ASF licenses this file
  ~ to you under the Apache License, Version 2.0 (the
  ~ "License"); you may not use this file except in compliance
  ~ with the License.  You may obtain a copy of the License at
  ~
  ~   http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing,
  ~ software distributed under the License is distributed on an
  ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  ~ KIND, either express or implied.  See the License for the
  ~ specific language governing permissions and limitations
  ~ under the License.
  -->

When you enable the Kafka indexing service, you can configure *supervisors* on the Overlord to manage the creation and lifetime of Kafka indexing tasks. These indexing tasks read events using Kafka's own partition and offset mechanism to guarantee exactly-once ingestion. The supervisor oversees the state of the indexing tasks to:
  - coordinate handoffs
  - manage failures
  - ensure that scalability and replication requirements are maintained.

To use the Kafka indexing service, load the `druid-kafka-indexing-service` core Apache Druid extension. See [Including Extensions](../../development/extensions.md#loading-extensions)).

This topic covers the ingestion spec for Kafka. For a general `ingestionSpec` reference, see [Ingestion specs](../../ingestion/ingestion-spec.md). For a walk-through, check out the [Loading from Apache Kafka](../../tutorials/tutorial-kafka.md) tutorial.

## Kafka support
The Kafka indexing service supports transactional topics introduced in Kafka 0.11.x by default. The consumer for Kafka indexing service is incompatible with older Kafka brokers. If you are using an older version, refer to the [Kafka upgrade guide](https://kafka.apache.org/documentation/#upgrade).

Additionally, you can set `isolation.level` to `read_uncommitted` in `consumerProperties` if either:
- You don't need Druid to consume transactional topics.
- You need Druid to consume older versions of Kafka. Make sure offsets are sequential, since there is no offset gap check in Druid anymore.

If your Kafka cluster enables consumer-group based ACLs, you can set `group.id` in `consumerProperties` to override the default auto generated group id.

## Submitting a Supervisor Spec

To use the Kafka indexing service, load the `druid-kafka-indexing-service` extension on both the Overlord and the MiddleManagers. Druid starts a supervisor for a dataSource when you submit a supervisor spec. You can use the following endpoint:

`http://<OVERLORD_IP>:<OVERLORD_PORT>/druid/indexer/v1/supervisor`

For example: 

```
curl -X POST -H 'Content-Type: application/json' -d @supervisor-spec.json http://localhost:8090/druid/indexer/v1/supervisor
```

Where the file `supervisor-spec.json` contains a Kafka supervisor spec:

```json
{
  "type": "kafka",
  "spec": {
    "dataSchema": {
      "dataSource": "metrics-kafka",
      "timestampSpec": {
        "column": "timestamp",
        "format": "auto"
      },
      "dimensionsSpec": {
        "dimensions": [],
        "dimensionExclusions": [
          "timestamp",
          "value"
         ]
       },
      "metricsSpec": [
        {
          "name": "count",
          "type": "count"
        },
        {
          "name": "value_sum",
          "fieldName": "value",
          "type": "doubleSum"
        },
        {
          "name": "value_min",
          "fieldName": "value",
         "type": "doubleMin"
        },
        {
          "name": "value_max",
          "fieldName": "value",
          "type": "doubleMax"
       }
      ],
      "granularitySpec": {
        "type": "uniform",
        "segmentGranularity": "HOUR",
        "queryGranularity": "NONE"
      }
    },
    "ioConfig": {
      "topic": "metrics",
      "inputFormat": {
        "type": "json"
      },
      "consumerProperties": {
        "bootstrap.servers": "localhost:9092"
      },
      "taskCount": 1,
      "replicas": 1,
      "taskDuration": "PT1H"
    },
    "tuningConfig": {
      "type": "kafka",
      "maxRowsPerSegment": 5000000
    }
  } 
}
```

## Supervisor Configuration

|Field|Description|Required|
|--------|-----------|---------|
|`type`|Supervisor type. For Kafka streaming, set to `kafka`.|yes|
|`spec`| Container object for the supervisor configuration. | yes |
|`dataSchema`|Schema for the Kafka indexing task to use during ingestion.|yes|
|`ioConfig`|A `KafkaSupervisorIOConfig` object to define the Kafka connection and I/O-related settings for the supervisor and indexing task. See [KafkaSupervisorIOConfig](#kafkasupervisorioconfig).|yes|
|`tuningConfig`|A KafkaSupervisorTuningConfig object to define performance-related settings for the supervisor and indexing tasks. See [KafkaSupervisorTuningConfig](#kafkasupervisortuningconfig).|no|

### KafkaSupervisorIOConfig

|Field|Type|Description|Required|
|-----|----|-----------|--------|
|`topic`|String|The Kafka topic to read from. Must be a specific topic. Topic patterns are not supported.|yes|
|`inputFormat`|Object|`inputFormat` to define input data parsing. See [Specifying data format](#specifying-data-format) for details about specifying the input format.|yes|
|`consumerProperties`|Map<String, Object>|A map of properties to pass to the Kafka consumer. See [More on consumer properties](#more-on-consumerproperties).|yes|
|`pollTimeout`|Long|The length of time to wait for the Kafka consumer to poll records, in milliseconds|no (default == 100)|
|`replicas`|Integer|The number of replica sets. "1" means a single set of tasks without replication. Druid always assigns replica tasks to different workers to provide resiliency against worker failure.|no (default == 1)|
|`taskCount`|Integer|The maximum number of *reading* tasks in a *replica set*. The maximum number of reading tasks equals `taskCount * replicas`. Therefore, the total number of tasks, *reading* + *publishing*, is greater than this count. See [Capacity Planning](#capacity-planning) for more details. When `taskCount > {numKafkaPartitions}`, the actual number of reading tasks is less than the `taskCount` value.|no (default == 1)|
|`taskDuration`|ISO8601 Period|The length of time before tasks stop reading and begin publishing segments.|no (default == PT1H)|
|`startDelay`|ISO8601 Period|The period to wait before the supervisor starts managing tasks.|no (default == PT5S)|
|`period`|ISO8601 Period|Frequency at which the supervisor executes its management logic. The supervisor also runs in response to certain events. For example task success, task failure, and tasks reaching their `taskDuration`. The `period` value specifies the maximum time between iterations.|no (default == PT30S)|
|`useEarliestOffset`|Boolean|If a supervisor manages a dataSource for the first time, it obtains a set of starting offsets from Kafka. This flag determines whether it retrieves the earliest or latest offsets in Kafka. Under normal circumstances, subsequent tasks will start from where the previous segments ended. Therefore Druid only uses `useEarliestOffset` on first run.|no (default == false)|
|`completionTimeout`|ISO8601 Period|The length of time to wait before declaring a publishing task as failed and terminating it. If the value is too low, your tasks may never publish. The publishing clock for a task begins roughly after `taskDuration` elapses.|no (default == PT30M)|
|`lateMessageRejectionStartDateTime`|ISO8601 DateTime|Configure tasks to reject messages with timestamps earlier than this date time; for example if this is set to `2016-01-01T11:00Z` and the supervisor creates a task at *2016-01-01T12:00Z*, Druid drops messages with timestamps earlier than *2016-01-01T11:00Z*. This can prevent concurrency issues if your data stream has late messages and you have multiple pipelines that need to operate on the same segments (e.g. a realtime and a nightly batch ingestion pipeline).|no (default == none)|
|`lateMessageRejectionPeriod`|ISO8601 Period|Configure tasks to reject messages with timestamps earlier than this period before the task was created; for example if this is set to `PT1H` and the supervisor creates a task at *2016-01-01T12:00Z*, messages with timestamps earlier than *2016-01-01T11:00Z* will be dropped. This may help prevent concurrency issues if your data stream has late messages and you have multiple pipelines that need to operate on the same segments (e.g. a realtime and a nightly batch ingestion pipeline). Please note that only one of `lateMessageRejectionPeriod` or `lateMessageRejectionStartDateTime` can be specified.|no (default == none)|
|`earlyMessageRejectionPeriod`|ISO8601 Period|Configure tasks to reject messages with timestamps later than this period after the task reached its taskDuration; for example if this is set to `PT1H`, the taskDuration is set to `PT1H` and the supervisor creates a task at *2016-01-01T12:00Z*, messages with timestamps later than *2016-01-01T14:00Z* will be dropped. **Note:** Tasks sometimes run past their task duration, for example, in cases of supervisor failover. Setting earlyMessageRejectionPeriod too low may cause messages to be dropped unexpectedly whenever a task runs past its originally configured task duration.|no (default == none)|
|`autoScalerConfig`|Object|Defines auto scaling behavior for Kafka ingest tasks. See [Tasks Autoscaler Properties](#task-autoscaler-properties).|no (default == null)|

#### Task Autoscaler Properties

> Note that Task AutoScaler is currently designated as experimental.

| Property | Description | Required |
| ------------- | ------------- | ------------- |
| `enableTaskAutoScaler` | Enable or disable autoscaling. `false` or blank disables the `autoScaler` even when `autoScalerConfig` is not null| no (default == false) |
| `taskCountMax` | Maximum number of ingestion tasks. Set `taskCountMax >= taskCountMin`. If `taskCountMax > {numKafkaPartitions}`, Druid only scales reading tasks up to the `{numKafkaPartitions}`. In this case `taskCountMax` is ignored.  | yes |
| `taskCountMin` | Minimum number of ingestion tasks. When you enable autoscaler, Druid ignores the value of taskCount in `IOConfig` and starts with the `taskCountMin` number of tasks.| yes |
| `minTriggerScaleActionFrequencyMillis` | Minimum time interval between two scale actions. | no (default == 600000) |
| `autoScalerStrategy` | The algorithm of `autoScaler`. Only supports `lagBased`. See [Lag Based AutoScaler Strategy Related Properties](#lag-based-autoscaler-strategy-related-properties) for details.| no (default == `lagBased`) |

##### Lag Based AutoScaler Strategy Related Properties
| Property | Description | Required |
| ------------- | ------------- | ------------- |
| `lagCollectionIntervalMillis` | Period of lag points collection.  | no (default == 30000) |
| `lagCollectionRangeMillis` | The total time window of lag collection. Use with `lagCollectionIntervalMillis`，it means that in the recent `lagCollectionRangeMillis`, collect lag metric points every `lagCollectionIntervalMillis`. | no (default == 600000) |
| `scaleOutThreshold` | The threshold of scale out action | no (default == 6000000) |
| `triggerScaleOutFractionThreshold` | If `triggerScaleOutFractionThreshold` percent of lag points are higher than `scaleOutThreshold`, then do scale out action. | no (default == 0.3) |
| `scaleInThreshold` | The Threshold of scale in action | no (default == 1000000) |
| `triggerScaleInFractionThreshold` | If `triggerScaleInFractionThreshold` percent of lag points are lower than `scaleOutThreshold`, then do scale in action. | no (default == 0.9) |
| `scaleActionStartDelayMillis` | Number of milliseconds after supervisor starts when first check scale logic. | no (default == 300000) |
| `scaleActionPeriodMillis` | The frequency of checking whether to do scale action in millis | no (default == 60000) |
| `scaleInStep` | How many tasks to reduce at a time | no (default == 1) |
| `scaleOutStep` | How many tasks to add at a time | no (default == 2) |

The following example demonstrates supervisor spec with `lagBased` autoScaler enabled:
```json
{
    "type": "kafka",
    "spec": {
      "dataSchema": {
        ...
      },
      "ioConfig": {
         "topic": "metrics",
         "inputFormat": {
             "type": "json"
         },
          "consumerProperties": {
             "bootstrap.servers": "localhost:9092"
          },
         "autoScalerConfig": {
             "enableTaskAutoScaler": true,
              "taskCountMax": 6,
              "taskCountMin": 2,
             "minTriggerScaleActionFrequencyMillis": 600000,
             "autoScalerStrategy": "lagBased",
              "lagCollectionIntervalMillis": 30000,
              "lagCollectionRangeMillis": 600000,
              "scaleOutThreshold": 6000000,
              "triggerScaleOutFractionThreshold": 0.3,
             "scaleInThreshold": 1000000,
             "triggerScaleInFractionThreshold": 0.9,
              "scaleActionStartDelayMillis": 300000,
              "scaleActionPeriodMillis": 60000,
              "scaleInStep": 1,
             "scaleOutStep": 2
         },
         "taskCount":1,
         "replicas":1,
         "taskDuration":"PT1H"
      },
     "tuningConfig":{
        ...
     }
    }
}
```

#### More on consumerProperties

This must contain a property `bootstrap.servers` with a list of Kafka brokers in the form: `<BROKER_1>:<PORT_1>,<BROKER_2>:<PORT_2>,...`.
By default, `isolation.level` is set to `read_committed`. It should be set to `read_uncommitted` if you don't want Druid to consume only committed transactions or working with older versions of Kafka servers with no transactions support.

There are few cases that require fetching few/all of consumer properties at runtime e.g. when `bootstrap.servers` is not known upfront or not static, to enable SSL connections users might have to provide passwords for `keystore`, `truststore` and `key` secretly.
For such consumer properties, user can implement a [DynamicConfigProvider](../../operations/dynamic-config-provider.md) to supply them at runtime, by adding
`druid.dynamic.config.provider`=`{"type": "<registered_dynamic_config_provider_name>", ...}`
in consumerProperties map.

Note: SSL connections may also be supplied using the deprecated [Password Provider](../../operations/password-provider.md) interface to define the `keystore`, `truststore`, and `key`. This functionality might be removed in a future release.

#### Specifying data format

Kafka indexing service supports both [`inputFormat`](../../ingestion/data-formats.md#input-format) and [`parser`](../../ingestion/data-formats.md#parser) to specify the data format.
Use the `inputFormat` to specify the data format for Kafka indexing service unless you need a format only supported by the legacy `parser`.

Supported `inputFormat`s include:
- `csv`
- `delimited`
- `json`
- `avro_stream`
- `protobuf`

For more information, see [Data formats](../../ingestion/data-formats.md). You can also read [`thrift`](../extensions-contrib/thrift.md) formats using `parser`.

<a name="tuningconfig"></a>

### KafkaSupervisorTuningConfig

The tuningConfig is optional and default parameters will be used if no tuningConfig is specified.

| Field                             | Type           | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | Required                                                                                                     |
|-----------------------------------|----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| `type`                            | String         | The indexing task type, this should always be `kafka`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | yes                                                                                                          |
| `maxRowsInMemory`                 | Integer        | The number of rows to aggregate before persisting. This number is the post-aggregation rows, so it is not equivalent to the number of input events, but the number of aggregated rows that those events result in. This is used to manage the required JVM heap size. Maximum heap memory usage for indexing scales with maxRowsInMemory * (2 + maxPendingPersists). Normally user does not need to set this, but depending on the nature of data, if rows are short in terms of bytes, user may not want to store a million rows in memory and this value should be set.                                                                           | no (default == 1000000)                                                                                      |
| `maxBytesInMemory`                | Long           | The number of bytes to aggregate in heap memory before persisting. This is based on a rough estimate of memory usage and not actual usage. Normally this is computed internally and user does not need to set it. The maximum heap memory usage for indexing is maxBytesInMemory * (2 + maxPendingPersists).                                                                                                                                                                                                                                                                                                                                        | no (default == One-sixth of max JVM memory)                                                                  |
| `maxRowsPerSegment`               | Integer        | The number of rows to aggregate into a segment; this number is post-aggregation rows. Handoff will happen either if `maxRowsPerSegment` or `maxTotalRows` is hit or every `intermediateHandoffPeriod`, whichever happens earlier.                                                                                                                                                                                                                                                                                                                                                                                                                   | no (default == 5000000)                                                                                      |
| `maxTotalRows`                    | Long           | The number of rows to aggregate across all segments; this number is post-aggregation rows. Handoff will happen either if `maxRowsPerSegment` or `maxTotalRows` is hit or every `intermediateHandoffPeriod`, whichever happens earlier.                                                                                                                                                                                                                                                                                                                                                                                                              | no (default == unlimited)                                                                                    |
| `intermediatePersistPeriod`       | ISO8601 Period | The period that determines the rate at which intermediate persists occur.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | no (default == PT10M)                                                                                        |
| `maxPendingPersists`              | Integer        | Maximum number of persists that can be pending but not started. If this limit would be exceeded by a new intermediate persist, ingestion will block until the currently-running persist finishes. Maximum heap memory usage for indexing scales with maxRowsInMemory * (2 + maxPendingPersists).                                                                                                                                                                                                                                                                                                                                                    | no (default == 0, meaning one persist can be running concurrently with ingestion, and none can be queued up) |
| `indexSpec`                       | Object         | Tune how data is indexed. See [IndexSpec](#indexspec) for more information.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | no                                                                                                           |
| `indexSpecForIntermediatePersists`|                | Defines segment storage format options to be used at indexing time for intermediate persisted temporary segments. This can be used to disable dimension/metric compression on intermediate segments to reduce memory required for final merging. However, disabling compression on intermediate segments might increase page cache use while they are used before getting merged into final segment published, see [IndexSpec](#indexspec) for possible values.                                                                                                                                                                                     | no (default = same as indexSpec)                                                                             |
| `reportParseExceptions`           | Boolean        | *DEPRECATED*. If true, exceptions encountered during parsing will be thrown and will halt ingestion; if false, unparseable rows and fields will be skipped. Setting `reportParseExceptions` to true will override existing configurations for `maxParseExceptions` and `maxSavedParseExceptions`, setting `maxParseExceptions` to 0 and limiting `maxSavedParseExceptions` to no more than 1.                                                                                                                                                                                                                                                       | no (default == false)                                                                                        |
| `handoffConditionTimeout`         | Long           | Milliseconds to wait for segment handoff. It must be >= 0, where 0 means to wait forever.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | no (default == 0)                                                                                            |
| `resetOffsetAutomatically`        | Boolean        | Controls behavior when Druid needs to read Kafka messages that are no longer available (i.e. when OffsetOutOfRangeException is encountered).<br/><br/>If false, the exception will bubble up, which will cause your tasks to fail and ingestion to halt. If this occurs, manual intervention is required to correct the situation; potentially using the [Reset Supervisor API](../../operations/api-reference.md#supervisors). This mode is useful for production, since it will make you aware of issues with ingestion.<br/><br/>If true, Druid will automatically reset to the earlier or latest offset available in Kafka, based on the value of the `useEarliestOffset` property (earliest if true, latest if false). Please note that this can lead to data being _DROPPED_ (if `useEarliestOffset` is false) or _DUPLICATED_ (if `useEarliestOffset` is true) without your knowledge. Messages will be logged indicating that a reset has occurred, but ingestion will continue. This mode is useful for non-production situations, since it will make Druid attempt to recover from problems automatically, even if they lead to quiet dropping or duplicating of data.<br/><br/>This feature behaves similarly to the Kafka `auto.offset.reset` consumer property. | no (default == false) |
| `workerThreads`                   | Integer        | The number of threads that the supervisor uses to handle requests/responses for worker tasks, along with any other internal asynchronous operation.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | no (default == min(10, taskCount))                                                                           |
| `chatThreads`                     | Integer        | The number of threads that will be used for communicating with indexing tasks.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | no (default == min(10, taskCount * replicas))                                                                |
| `chatRetries`                     | Integer        | The number of times HTTP requests to indexing tasks will be retried before considering tasks unresponsive.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | no (default == 8)                                                                                            |
| `httpTimeout`                     | ISO8601 Period | How long to wait for a HTTP response from an indexing task.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | no (default == PT10S)                                                                                        |
| `shutdownTimeout`                 | ISO8601 Period | How long to wait for the supervisor to attempt a graceful shutdown of tasks before exiting.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | no (default == PT80S)                                                                                        |
| `offsetFetchPeriod`               | ISO8601 Period | How often the supervisor queries Kafka and the indexing tasks to fetch current offsets and calculate lag. If the user-specified value is below the minimum value (`PT5S`), the supervisor ignores the value and uses the minimum value instead.                                                                                                                                                                                                                                                                                                                                                                                                          | no (default == PT30S, min == PT5S)                                                                           |
| `segmentWriteOutMediumFactory`    | Object         | Segment write-out medium to use when creating segments. See below for more information.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | no (not specified by default, the value from `druid.peon.defaultSegmentWriteOutMediumFactory.type` is used)  |
| `intermediateHandoffPeriod`       | ISO8601 Period | How often the tasks should hand off segments. Handoff will happen either if `maxRowsPerSegment` or `maxTotalRows` is hit or every `intermediateHandoffPeriod`, whichever happens earlier.                                                                                                                                                                                                                                                                                                                                                                                                                                                           | no (default == P2147483647D)                                                                                 |
| `logParseExceptions`              | Boolean        | If true, log an error message when a parsing exception occurs, containing information about the row where the error occurred.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | no, default == false                                                                                         |
| `maxParseExceptions`              | Integer        | The maximum number of parse exceptions that can occur before the task halts ingestion and fails. Overridden if `reportParseExceptions` is set.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | no, unlimited default                                                                                        |
| `maxSavedParseExceptions`         | Integer        | When a parse exception occurs, Druid can keep track of the most recent parse exceptions. "maxSavedParseExceptions" limits how many exception instances will be saved. These saved exceptions will be made available after the task finishes in the [task completion report](../../ingestion/tasks.md#reports). Overridden if `reportParseExceptions` is set.                                                                                                                                                                                                                                                                                            | no, default == 0                                                                                             |

#### IndexSpec

|Field|Type|Description|Required|
|-----|----|-----------|--------|
|bitmap|Object|Compression format for bitmap indexes. Should be a JSON object. See [Bitmap types](#bitmap-types) below for options.|no (defaults to Roaring)|
|dimensionCompression|String|Compression format for dimension columns. Choose from `LZ4`, `LZF`, or `uncompressed`.|no (default == `LZ4`)|
|metricCompression|String|Compression format for primitive type metric columns. Choose from `LZ4`, `LZF`, `uncompressed`, or `none`.|no (default == `LZ4`)|
|longEncoding|String|Encoding format for metric and dimension columns with type long. Choose from `auto` or `longs`. `auto` encodes the values using offset or lookup table depending on column cardinality, and store them with variable size. `longs` stores the value as is with 8 bytes each.|no (default == `longs`)|

##### Bitmap types

For Roaring bitmaps:

|Field|Type|Description|Required|
|-----|----|-----------|--------|
|`type`|String|Must be `roaring`.|yes|
|`compressRunOnSerialization`|Boolean|Use a run-length encoding where it is estimated as more space efficient.|no (default == `true`)|

For Concise bitmaps:

|Field|Type|Description|Required|
|-----|----|-----------|--------|
|`type`|String|Must be `concise`.|yes|

#### SegmentWriteOutMediumFactory

|Field|Type|Description|Required|
|-----|----|-----------|--------|
|`type`|String|See [Additional Peon Configuration: SegmentWriteOutMediumFactory](../../configuration/index.md#segmentwriteoutmediumfactory) for explanation and available options.|yes|

## Operations

This section gives descriptions of how some supervisor APIs work specifically in Kafka Indexing Service.
For all supervisor APIs, please check [Supervisor APIs](../../operations/api-reference.md#supervisors).

### Getting Supervisor Status Report

`GET /druid/indexer/v1/supervisor/<supervisorId>/status` returns a snapshot report of the current state of the tasks managed by the given supervisor. This includes the latest
offsets as reported by Kafka, the consumer lag per partition, as well as the aggregate lag of all partitions. The
consumer lag per partition may be reported as negative values if the supervisor has not received a recent latest offset
response from Kafka. The aggregate lag value will always be >= 0.

The status report also contains the supervisor's state and a list of recently thrown exceptions (reported as
`recentErrors`, whose max size can be controlled using the `druid.supervisor.maxStoredExceptionEvents` configuration).
There are two fields related to the supervisor's state - `state` and `detailedState`. The `state` field will always be
one of a small number of generic states that are applicable to any type of supervisor, while the `detailedState` field
will contain a more descriptive, implementation-specific state that may provide more insight into the supervisor's
activities than the generic `state` field.

The list of possible `state` values are: [`PENDING`, `RUNNING`, `SUSPENDED`, `STOPPING`, `UNHEALTHY_SUPERVISOR`, `UNHEALTHY_TASKS`]

The list of `detailedState` values and their corresponding `state` mapping is as follows:

|Detailed State|Corresponding State|Description|
|--------------|-------------------|-----------|
|UNHEALTHY_SUPERVISOR|UNHEALTHY_SUPERVISOR|The supervisor has encountered errors on the past `druid.supervisor.unhealthinessThreshold` iterations|
|UNHEALTHY_TASKS|UNHEALTHY_TASKS|The last `druid.supervisor.taskUnhealthinessThreshold` tasks have all failed|
|UNABLE_TO_CONNECT_TO_STREAM|UNHEALTHY_SUPERVISOR|The supervisor is encountering connectivity issues with Kafka and has not successfully connected in the past|
|LOST_CONTACT_WITH_STREAM|UNHEALTHY_SUPERVISOR|The supervisor is encountering connectivity issues with Kafka but has successfully connected in the past|
|PENDING (first iteration only)|PENDING|The supervisor has been initialized and hasn't started connecting to the stream|
|CONNECTING_TO_STREAM (first iteration only)|RUNNING|The supervisor is trying to connect to the stream and update partition data|
|DISCOVERING_INITIAL_TASKS (first iteration only)|RUNNING|The supervisor is discovering already-running tasks|
|CREATING_TASKS (first iteration only)|RUNNING|The supervisor is creating tasks and discovering state|
|RUNNING|RUNNING|The supervisor has started tasks and is waiting for taskDuration to elapse|
|SUSPENDED|SUSPENDED|The supervisor has been suspended|
|STOPPING|STOPPING|The supervisor is stopping|

On each iteration of the supervisor's run loop, the supervisor completes the following tasks in sequence:
  1) Fetch the list of partitions from Kafka and determine the starting offset for each partition (either based on the
  last processed offset if continuing, or starting from the beginning or ending of the stream if this is a new topic).
  2) Discover any running indexing tasks that are writing to the supervisor's datasource and adopt them if they match
  the supervisor's configuration, else signal them to stop.
  3) Send a status request to each supervised task to update our view of the state of the tasks under our supervision.
  4) Handle tasks that have exceeded `taskDuration` and should transition from the reading to publishing state.
  5) Handle tasks that have finished publishing and signal redundant replica tasks to stop.
  6) Handle tasks that have failed and clean up the supervisor's internal state.
  7) Compare the list of healthy tasks to the requested `taskCount` and `replicas` configurations and create additional tasks if required.

The `detailedState` field will show additional values (those marked with "first iteration only") the first time the
supervisor executes this run loop after startup or after resuming from a suspension. This is intended to surface
initialization-type issues, where the supervisor is unable to reach a stable state (perhaps because it can't connect to
Kafka, it can't read from the Kafka topic, or it can't communicate with existing tasks). Once the supervisor is stable -
that is, once it has completed a full execution without encountering any issues - `detailedState` will show a `RUNNING`
state until it is stopped, suspended, or hits a task failure threshold and transitions to an unhealthy state.

### Getting Supervisor Ingestion Stats Report

`GET /druid/indexer/v1/supervisor/<supervisorId>/stats` returns a snapshot of the current ingestion row counters for each task being managed by the supervisor, along with moving averages for the row counters.

See [Task Reports: Row Stats](../../ingestion/tasks.md#row-stats) for more information.

### Supervisor Health Check

`GET /druid/indexer/v1/supervisor/<supervisorId>/health` returns `200 OK` if the supervisor is healthy and
`503 Service Unavailable` if it is unhealthy. Healthiness is determined by the supervisor's `state` (as returned by the
`/status` endpoint) and the `druid.supervisor.*` Overlord configuration thresholds.

### Updating Existing Supervisors

`POST /druid/indexer/v1/supervisor` can be used to update existing supervisor spec.
Calling this endpoint when there is already an existing supervisor for the same dataSource will cause:

- The running supervisor to signal its managed tasks to stop reading and begin publishing.
- The running supervisor to exit.
- A new supervisor to be created using the configuration provided in the request body. This supervisor will retain the
existing publishing tasks and will create new tasks starting at the offsets the publishing tasks ended on.

Seamless schema migrations can thus be achieved by simply submitting the new schema using this endpoint.

### Suspending and Resuming Supervisors

You can suspend and resume a supervisor using `POST /druid/indexer/v1/supervisor/<supervisorId>/suspend` and `POST /druid/indexer/v1/supervisor/<supervisorId>/resume`, respectively.

Note that the supervisor itself will still be operating and emitting logs and metrics,
it will just ensure that no indexing tasks are running until the supervisor is resumed.

### Resetting Supervisors

The `POST /druid/indexer/v1/supervisor/<supervisorId>/reset` operation clears stored 
offsets, causing the supervisor to start reading offsets from either the earliest or latest 
offsets in Kafka (depending on the value of `useEarliestOffset`). After clearing stored 
offsets, the supervisor kills and recreates any active tasks, so that tasks begin reading 
from valid offsets. 

Use care when using this operation! Resetting the supervisor may cause Kafka messages 
to be skipped or read twice, resulting in missing or duplicate data. 

The reason for using this operation is to recover from a state in which the supervisor 
ceases operating due to missing offsets. The indexing service keeps track of the latest 
persisted Kafka offsets in order to provide exactly-once ingestion guarantees across 
tasks. Subsequent tasks must start reading from where the previous task completed in 
order for the generated segments to be accepted. If the messages at the expected 
starting offsets are no longer available in Kafka (typically because the message retention 
period has elapsed or the topic was removed and re-created) the supervisor will refuse 
to start and in flight tasks will fail. This operation enables you to recover from this condition. 

Note that the supervisor must be running for this endpoint to be available.

### Terminating Supervisors

The `POST /druid/indexer/v1/supervisor/<supervisorId>/terminate` operation terminates a supervisor and causes all 
associated indexing tasks managed by this supervisor to immediately stop and begin
publishing their segments. This supervisor will still exist in the metadata store and it's history may be retrieved
with the supervisor history API, but will not be listed in the 'get supervisors' API response nor can it's configuration
or status report be retrieved. The only way this supervisor can start again is by submitting a functioning supervisor
spec to the create API.

### Capacity Planning

Kafka indexing tasks run on MiddleManagers and are thus limited by the resources available in the MiddleManager
cluster. In particular, you should make sure that you have sufficient worker capacity (configured using the
`druid.worker.capacity` property) to handle the configuration in the supervisor spec. Note that worker capacity is
shared across all types of indexing tasks, so you should plan your worker capacity to handle your total indexing load
(e.g. batch processing, realtime tasks, merging tasks, etc.). If your workers run out of capacity, Kafka indexing tasks
will queue and wait for the next available worker. This may cause queries to return partial results but will not result
in data loss (assuming the tasks run before Kafka purges those offsets).

A running task will normally be in one of two states: *reading* or *publishing*. A task will remain in reading state for
`taskDuration`, at which point it will transition to publishing state. A task will remain in publishing state for as long
as it takes to generate segments, push segments to deep storage, and have them be loaded and served by a Historical process
(or until `completionTimeout` elapses).

The number of reading tasks is controlled by `replicas` and `taskCount`. In general, there will be `replicas * taskCount`
reading tasks, the exception being if taskCount > {numKafkaPartitions} in which case {numKafkaPartitions} tasks will
be used instead. When `taskDuration` elapses, these tasks will transition to publishing state and `replicas * taskCount`
new reading tasks will be created. Therefore to allow for reading tasks and publishing tasks to run concurrently, there
should be a minimum capacity of:

```
workerCapacity = 2 * replicas * taskCount
```

This value is for the ideal situation in which there is at most one set of tasks publishing while another set is reading.
In some circumstances, it is possible to have multiple sets of tasks publishing simultaneously. This would happen if the
time-to-publish (generate segment, push to deep storage, loaded on Historical) > `taskDuration`. This is a valid
scenario (correctness-wise) but requires additional worker capacity to support. In general, it is a good idea to have
`taskDuration` be large enough that the previous set of tasks finishes publishing before the current set begins.

### Supervisor Persistence

When a supervisor spec is submitted via the `POST /druid/indexer/v1/supervisor` endpoint, it is persisted in the
configured metadata database. There can only be a single supervisor per dataSource, and submitting a second spec for
the same dataSource will overwrite the previous one.

When an Overlord gains leadership, either by being started or as a result of another Overlord failing, it will spawn
a supervisor for each supervisor spec in the metadata database. The supervisor will then discover running Kafka indexing
tasks and will attempt to adopt them if they are compatible with the supervisor's configuration. If they are not
compatible because they have a different ingestion spec or partition allocation, the tasks will be killed and the
supervisor will create a new set of tasks. In this way, the supervisors are persistent across Overlord restarts and
fail-overs.

A supervisor is stopped via the `POST /druid/indexer/v1/supervisor/<supervisorId>/terminate` endpoint. This places a
tombstone marker in the database (to prevent the supervisor from being reloaded on a restart) and then gracefully
shuts down the currently running supervisor. When a supervisor is shut down in this way, it will instruct its
managed tasks to stop reading and begin publishing their segments immediately. The call to the shutdown endpoint will
return after all tasks have been signaled to stop but before the tasks finish publishing their segments.

### Schema/Configuration Changes

Schema and configuration changes are handled by submitting the new supervisor spec via the same
`POST /druid/indexer/v1/supervisor` endpoint used to initially create the supervisor. The Overlord will initiate a
graceful shutdown of the existing supervisor which will cause the tasks being managed by that supervisor to stop reading
and begin publishing their segments. A new supervisor will then be started which will create a new set of tasks that
will start reading from the offsets where the previous now-publishing tasks left off, but using the updated schema.
In this way, configuration changes can be applied without requiring any pause in ingestion.

### Deployment Notes on Kafka partitions and Druid segments

Druid assigns each Kafka indexing task Kafka partitions. A task writes the events it consumes from Kafka into a single segment for the segment granularity interval until it reaches one of the following: `maxRowsPerSegment`, `maxTotalRows` or `intermediateHandoffPeriod` limit. At this point, the task creates a new partition for this segment granularity to contain subsequent events.

The Kafka Indexing Task also does incremental hand-offs. Therefore segments become available as they are ready and you do not have to wait for all segments until the end of  the task duration.  When the task reaches one of `maxRowsPerSegment`, `maxTotalRows`, or `intermediateHandoffPeriod`, it hands off all the segments and creates a new new set of segments will be created for further events. This allows the task to run for longer durations without accumulating old segments locally on Middle Manager processes.

The Kafka Indexing Service may still produce some small segments. For example, consider the following scenario:
- Task duration is 4 hours
- Segment granularity is set to an HOUR
- The supervisor was started at 9:10
After 4 hours at 13:10, Druid starts a new set of tasks. The events for the interval 13:00 - 14:00 may be split across existing tasks and the new set of tasks which could result in small segments. To merge them together into new segments of an ideal size (in the range of ~500-700 MB per segment), you can schedule re-indexing tasks, optionally with a different segment granularity.

For more detail, see [Segment size optimization](../../operations/segment-optimization.md).
There is also ongoing work to support automatic segment compaction of sharded segments as well as compaction not requiring
Hadoop (see [here](https://github.com/apache/druid/pull/5102)).
