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
package sleeper.io.parquet.record;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.hadoop.api.WriteSupport;
import org.apache.parquet.io.api.RecordConsumer;
import org.apache.parquet.schema.MessageType;
import sleeper.core.record.Record;
import sleeper.core.schema.Schema;

import java.util.HashMap;

/**
 * A {@link WriteSupport} for {@link Record}s.
 */
public class RecordWriteSupport extends WriteSupport<Record> {
    private final Path path;
    private final MessageType messageType;
    private final Schema schema;
    private RecordWriter recordWriter;

    public RecordWriteSupport(Path path, MessageType messageType, Schema schema) {
        this.path = path;
        this.messageType = messageType;
        this.schema = schema;
    }

    public WriteContext init(Configuration configuration) {
        return new WriteContext(messageType, new HashMap<>());
    }

    @Override
    public void prepareForWrite(RecordConsumer recordConsumer) {
        recordWriter = new RecordWriter(recordConsumer, schema);
    }

    public void write(Record record) {
        recordWriter.write(record);
    }
}
