/*
 * Copyright 2022 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sleeper.athena.record;

import com.amazonaws.athena.connector.lambda.data.Block;
import com.amazonaws.athena.connector.lambda.data.BlockAllocatorImpl;
import com.amazonaws.athena.connector.lambda.domain.Split;
import com.amazonaws.athena.connector.lambda.domain.TableName;
import com.amazonaws.athena.connector.lambda.domain.predicate.Constraints;
import com.amazonaws.athena.connector.lambda.domain.predicate.EquatableValueSet;
import com.amazonaws.athena.connector.lambda.domain.predicate.Range;
import com.amazonaws.athena.connector.lambda.domain.predicate.SortedRangeSet;
import com.amazonaws.athena.connector.lambda.domain.predicate.ValueSet;
import com.amazonaws.athena.connector.lambda.domain.spill.S3SpillLocation;
import com.amazonaws.athena.connector.lambda.records.ReadRecordsRequest;
import com.amazonaws.athena.connector.lambda.records.ReadRecordsResponse;
import com.amazonaws.athena.connector.lambda.records.RecordResponse;
import com.amazonaws.services.athena.AmazonAthena;
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.util.Text;
import org.apache.hadoop.fs.Path;
import org.junit.Test;
import sleeper.athena.TestUtils;
import sleeper.configuration.properties.InstanceProperties;
import sleeper.configuration.properties.table.TableProperties;
import sleeper.core.partition.Partition;
import sleeper.core.schema.Schema;
import sleeper.io.parquet.record.ParquetReaderIterator;
import sleeper.io.parquet.record.ParquetRecordReader;
import sleeper.statestore.dynamodb.DynamoDBStateStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static sleeper.athena.metadata.SleeperMetadataHandler.RELEVANT_FILES_FIELD;
import static sleeper.configuration.properties.SystemDefinedInstanceProperty.CONFIG_BUCKET;
import static sleeper.configuration.properties.table.TableProperty.TABLE_NAME;

public class SimpleRecordHandlerIT extends AbstractRecordHandlerIT {

    @Test
    public void shouldReturnNoRecordsWhenFileDoesNotContainExactValue() throws Exception {
        InstanceProperties instanceProperties = getInstanceProperties();
        TableProperties tableProperties = createTable(instanceProperties, 2018, 2019, 2020);

        // When
        DynamoDBStateStore stateStore = new DynamoDBStateStore(tableProperties, createDynamoClient());
        String file = stateStore.getActiveFiles().get(0).getFilename();

        SimpleRecordHandler sleeperRecordHandler = new SimpleRecordHandler(createS3Client(), instanceProperties.get(CONFIG_BUCKET),
                mock(AWSSecretsManager.class), mock(AmazonAthena.class));

        String tableName = tableProperties.get(TABLE_NAME);
        S3SpillLocation spillLocation = S3SpillLocation.newBuilder()
                .withBucket(SPILL_BUCKET_NAME)
                .build();

        Map<String, ValueSet> predicates = new HashMap<>();
        predicates.put("month", EquatableValueSet
                .newBuilder(new BlockAllocatorImpl(), Types.MinorType.INT.getType(), true, false)
                .add(2).build()
        );
        predicates.put("day", EquatableValueSet
                .newBuilder(new BlockAllocatorImpl(), Types.MinorType.INT.getType(), true, false)
                .add(30).build()
        );

        RecordResponse response = sleeperRecordHandler.doReadRecords(new BlockAllocatorImpl(), new ReadRecordsRequest(
                TestUtils.createIdentity(),
                "abc",
                UUID.randomUUID().toString(),
                new TableName(tableName, tableName),
                createArrowSchema(),
                Split.newBuilder(spillLocation, null)
                        .add(RELEVANT_FILES_FIELD, file)
                        .build(),
                new Constraints(predicates),
                1_000_000L,
                1_000L
        ));

        // Then
        assertTrue(response instanceof ReadRecordsResponse);
        assertEquals(0, ((ReadRecordsResponse) response).getRecordCount());
    }

    @Test
    public void shouldReturnNoRecordsWhenFileDoesNotContainRange() throws Exception {
        InstanceProperties instanceProperties = getInstanceProperties();
        TableProperties tableProperties = createTable(instanceProperties, 2018, 2019, 2020);

        // When
        DynamoDBStateStore stateStore = new DynamoDBStateStore(tableProperties, createDynamoClient());
        String file = stateStore.getActiveFiles().get(0).getFilename();

        SimpleRecordHandler sleeperRecordHandler = new SimpleRecordHandler(createS3Client(), instanceProperties.get(CONFIG_BUCKET),
                mock(AWSSecretsManager.class), mock(AmazonAthena.class));

        String tableName = tableProperties.get(TABLE_NAME);
        S3SpillLocation spillLocation = S3SpillLocation.newBuilder()
                .withBucket(SPILL_BUCKET_NAME)
                .build();

        Map<String, ValueSet> predicates = new HashMap<>();
        predicates.put("year", SortedRangeSet.of(Range.range(new BlockAllocatorImpl(), Types.MinorType.INT.getType(),
                2022, true, 2024, false))
        );

        RecordResponse response = sleeperRecordHandler.doReadRecords(new BlockAllocatorImpl(), new ReadRecordsRequest(
                TestUtils.createIdentity(),
                "abc",
                UUID.randomUUID().toString(),
                new TableName(tableName, tableName),
                createArrowSchema(),
                Split.newBuilder(spillLocation, null)
                        .add(RELEVANT_FILES_FIELD, file)
                        .build(),
                new Constraints(predicates),
                1_000_000L,
                1_000L
        ));

        // Then
        assertTrue(response instanceof ReadRecordsResponse);
        assertEquals(0, ((ReadRecordsResponse) response).getRecordCount());
    }

    @Test
    public void shouldReturnSomeRecordsWhenFileContainsPartOfRange() throws Exception {
        InstanceProperties instanceProperties = getInstanceProperties();
        TableProperties tableProperties = createTable(instanceProperties, 2018, 2019, 2020);

        // When
        DynamoDBStateStore stateStore = new DynamoDBStateStore(tableProperties, createDynamoClient());
        Map<String, List<String>> partitionToActiveFilesMap = stateStore.getPartitionToActiveFilesMap();
        String file2018 = stateStore.getLeafPartitions().stream()
                .filter(p -> (Integer) p.getRegion().getRange("year").getMin() == 2018)
                .map(Partition::getId)
                .map(partitionToActiveFilesMap::get)
                .flatMap(List::stream)
                .findAny()
                .orElseThrow(RuntimeException::new);

        SimpleRecordHandler sleeperRecordHandler = new SimpleRecordHandler(createS3Client(), instanceProperties.get(CONFIG_BUCKET),
                mock(AWSSecretsManager.class), mock(AmazonAthena.class));

        String tableName = tableProperties.get(TABLE_NAME);
        S3SpillLocation spillLocation = S3SpillLocation.newBuilder()
                .withBucket(SPILL_BUCKET_NAME)
                .build();

        Map<String, ValueSet> predicates = new HashMap<>();
        predicates.put("year", SortedRangeSet.of(Range.range(new BlockAllocatorImpl(), Types.MinorType.INT.getType(),
                2018, true, 2020, false))
        );
        predicates.put("month", SortedRangeSet.of(Range.range(new BlockAllocatorImpl(), Types.MinorType.INT.getType(),
                6, true, 8, false))
        );


        RecordResponse response = sleeperRecordHandler.doReadRecords(new BlockAllocatorImpl(), new ReadRecordsRequest(
                TestUtils.createIdentity(),
                "abc",
                UUID.randomUUID().toString(),
                new TableName(tableName, tableName),
                createArrowSchema(),
                Split.newBuilder(spillLocation, null)
                        .add(RELEVANT_FILES_FIELD, file2018)
                        .build(),
                new Constraints(predicates),
                1_000_000L,
                1_000_000L
        ));

        // Then
        assertTrue(response instanceof ReadRecordsResponse);
        assertEquals(61, ((ReadRecordsResponse) response).getRecordCount());
    }

    @Test
    public void shouldFilterOnValueFields() throws Exception {
        InstanceProperties instanceProperties = getInstanceProperties();
        TableProperties tableProperties = createTable(instanceProperties, 2018, 2019, 2020);

        // When
        DynamoDBStateStore stateStore = new DynamoDBStateStore(tableProperties, createDynamoClient());
        Map<String, List<String>> partitionToActiveFilesMap = stateStore.getPartitionToActiveFilesMap();
        String file = stateStore.getLeafPartitions().stream()
                .filter(p -> (Integer) p.getRegion().getRange("year").getMin() == 2018)
                .map(Partition::getId)
                .map(partitionToActiveFilesMap::get)
                .flatMap(List::stream)
                .findAny()
                .orElseThrow(RuntimeException::new);

        SimpleRecordHandler sleeperRecordHandler = new SimpleRecordHandler(createS3Client(), instanceProperties.get(CONFIG_BUCKET),
                mock(AWSSecretsManager.class), mock(AmazonAthena.class));

        String tableName = tableProperties.get(TABLE_NAME);
        S3SpillLocation spillLocation = S3SpillLocation.newBuilder()
                .withBucket(SPILL_BUCKET_NAME)
                .build();

        Map<String, ValueSet> predicates = new HashMap<>();
        predicates.put("str", SortedRangeSet.of(Range.range(new BlockAllocatorImpl(), Types.MinorType.VARCHAR.getType(),
                "2018-01-05", true, "2018-01-10", true))
        );

        RecordResponse response = sleeperRecordHandler.doReadRecords(new BlockAllocatorImpl(), new ReadRecordsRequest(
                TestUtils.createIdentity(),
                "abc",
                UUID.randomUUID().toString(),
                new TableName(tableName, tableName),
                createArrowSchema(),
                Split.newBuilder(spillLocation, null)
                        .add(RELEVANT_FILES_FIELD, file)
                        .build(),
                new Constraints(predicates),
                1_000_000L,
                1_000L
        ));

        // Then
        assertTrue(response instanceof ReadRecordsResponse);
        assertEquals(6, ((ReadRecordsResponse) response).getRecordCount());
        Block records = ((ReadRecordsResponse) response).getRecords();
        assertFieldContainedValue(records, 0, "str", new Text("2018-01-05"));
        assertFieldContainedValue(records, 5, "str", new Text("2018-01-10"));
    }

    @Test
    public void shouldReturnAllValuesFromFileWhenNoConstraintsArePresent() throws Exception {
        InstanceProperties instanceProperties = getInstanceProperties();
        TableProperties tableProperties = createTable(instanceProperties, 2018, 2019, 2020);

        // When
        DynamoDBStateStore stateStore = new DynamoDBStateStore(tableProperties, createDynamoClient());
        String file = stateStore.getActiveFiles().get(0).getFilename();

        SimpleRecordHandler sleeperRecordHandler = new SimpleRecordHandler(createS3Client(), instanceProperties.get(CONFIG_BUCKET),
                mock(AWSSecretsManager.class), mock(AmazonAthena.class));

        String tableName = tableProperties.get(TABLE_NAME);
        S3SpillLocation spillLocation = S3SpillLocation.newBuilder()
                .withBucket(SPILL_BUCKET_NAME)
                .build();

        RecordResponse response = sleeperRecordHandler.doReadRecords(new BlockAllocatorImpl(), new ReadRecordsRequest(
                TestUtils.createIdentity(),
                "abc",
                UUID.randomUUID().toString(),
                new TableName(tableName, tableName),
                createArrowSchema(),
                Split.newBuilder(spillLocation, null)
                        .add(RELEVANT_FILES_FIELD, file)
                        .build(),
                new Constraints(new HashMap<>()),
                1_000_000L,
                1_000_000L
        ));

        // Then
        ParquetReaderIterator parquetReaderIterator = new ParquetReaderIterator(new ParquetRecordReader(new Path(file), new Schema()));
        while (parquetReaderIterator.hasNext()) {
            parquetReaderIterator.next();
        }

        long numberOfRecords = parquetReaderIterator.getNumberOfRecordsRead();

        assertTrue(response instanceof ReadRecordsResponse);
        assertEquals(numberOfRecords, ((ReadRecordsResponse) response).getRecordCount());
    }

    @Test
    public void shouldNotBringBackValueIfItWasNotAskedFor() throws Exception {
        InstanceProperties instanceProperties = getInstanceProperties();
        TableProperties tableProperties = createTable(instanceProperties, 2018, 2019, 2020);

        // When
        DynamoDBStateStore stateStore = new DynamoDBStateStore(tableProperties, createDynamoClient());
        Map<String, List<String>> partitionToActiveFilesMap = stateStore.getPartitionToActiveFilesMap();
        String file = stateStore.getLeafPartitions().stream()
                .map(Partition::getId)
                .map(partitionToActiveFilesMap::get)
                .filter(list -> list.size() == 1)   // Ensure the partition has a single file, otherwise the file might
                                                    // not contain the entirety of Feb
                .flatMap(List::stream)
                .findAny()
                .orElseThrow(RuntimeException::new);

        SimpleRecordHandler sleeperRecordHandler = new SimpleRecordHandler(createS3Client(), instanceProperties.get(CONFIG_BUCKET),
                mock(AWSSecretsManager.class), mock(AmazonAthena.class));

        String tableName = tableProperties.get(TABLE_NAME);
        S3SpillLocation spillLocation = S3SpillLocation.newBuilder()
                .withBucket(SPILL_BUCKET_NAME)
                .build();

        Map<String, ValueSet> predicates = new HashMap<>();
        predicates.put("month", EquatableValueSet
                .newBuilder(new BlockAllocatorImpl(), Types.MinorType.INT.getType(), true, false)
                .add(2).build()
        );


        org.apache.arrow.vector.types.pojo.Schema schemaWithoutDay = new org.apache.arrow.vector.types.pojo.Schema(
                createArrowSchema().getFields()
                .stream()
                .filter(field -> !field.getName().equals("day"))
                .collect(Collectors.toList()));


        RecordResponse response = sleeperRecordHandler.doReadRecords(new BlockAllocatorImpl(), new ReadRecordsRequest(
                TestUtils.createIdentity(),
                "abc",
                UUID.randomUUID().toString(),
                new TableName(tableName, tableName),
                schemaWithoutDay,
                Split.newBuilder(spillLocation, null)
                        .add(RELEVANT_FILES_FIELD, file)
                        .build(),
                new Constraints(predicates),
                1_000_000L,
                1_000_000L
        ));

        // Then
        assertTrue(response instanceof ReadRecordsResponse);
        assertEquals(28, ((ReadRecordsResponse) response).getRecordCount());
        Block records = ((ReadRecordsResponse) response).getRecords();
        // Just to show the difference
        assertNotNull(records.getFieldVector("month"));
        assertNull(records.getFieldVector("day"));
    }
}
