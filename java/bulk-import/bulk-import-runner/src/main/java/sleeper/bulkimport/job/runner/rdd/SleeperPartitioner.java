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
package sleeper.bulkimport.job.runner.rdd;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.spark.Partitioner;
import org.apache.spark.broadcast.Broadcast;

import sleeper.core.key.Key;
import sleeper.core.partition.Partition;
import sleeper.core.partition.PartitionTree;
import sleeper.core.schema.Schema;
import sleeper.core.schema.SchemaSerDe;

/**
 * A {@link SleeperPartitioner} is a custom {@link Partitioner} which uses the
 * Sleeper partitions to split the data into different partitions.
 */
public class SleeperPartitioner extends Partitioner {
    private static final long serialVersionUID = -4686777638868174263L;

    private final Broadcast<List<Partition>> broadcastPartitions;
    private final String schemaAsString;
    private transient int numRowKeyFields;
    private transient PartitionTree partitionTree;
    private transient int numLeafPartitions;
    private transient Map<String, Integer> partitionIdToInt;

    public SleeperPartitioner(String schemaAsString, Broadcast<List<Partition>> broadcastPartitions) {
        this.schemaAsString = schemaAsString;
        this.broadcastPartitions = broadcastPartitions;
    }

    private void init() {
        Schema schema = new SchemaSerDe().fromJson(this.schemaAsString);
        numRowKeyFields = schema.getRowKeyFields().size();
        List<Partition> partitions = this.broadcastPartitions.getValue();
        partitionTree = new PartitionTree(schema, partitions);
        numLeafPartitions = (int) partitions.stream().filter(Partition::isLeafPartition).count();
        partitionIdToInt = new HashMap<>();
        List<String> leafPartitions = partitions.stream()
                .filter(Partition::isLeafPartition)
                .map(p -> p.getId())
                .collect(Collectors.toList());
        SortedSet<String> sortedPartitionIds = new TreeSet<>(leafPartitions);
        int i = 0;
        for (String partitionId : sortedPartitionIds) {
            this.partitionIdToInt.put(partitionId, i);
            i++;
        }
    }

    @Override
    public int numPartitions() {
        if (null == partitionTree) {
            init();
        }
        return numLeafPartitions;
    }

    @Override
    public int getPartition(Object obj) {
        if (null == partitionTree) {
            init();
        }
        Key key = (Key) obj;
        List<Object> rowKeys = new ArrayList<>(numRowKeyFields);
        for (int i = 0; i < numRowKeyFields; i++) {
            rowKeys.add(key.get(i));
        }
        Key rowKey = Key.create(rowKeys);
        String partitionId = partitionTree.getLeafPartition(rowKey).getId();
        int partitionAsInt = partitionIdToInt.get(partitionId);
        return partitionAsInt;
    }
}
