/*
 * Copyright (c) 2008-2020, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.sql.impl.connector.keyvalue;

import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.DataSerializable;
import com.hazelcast.query.impl.getters.Extractors;
import com.hazelcast.sql.impl.expression.ConstantExpression;
import com.hazelcast.sql.impl.expression.Expression;
import com.hazelcast.sql.impl.extract.QueryExtractor;
import com.hazelcast.sql.impl.extract.QueryPath;
import com.hazelcast.sql.impl.extract.QueryTarget;
import com.hazelcast.sql.impl.extract.QueryTargetDescriptor;
import com.hazelcast.sql.impl.row.Row;
import com.hazelcast.sql.impl.type.QueryDataType;

import java.io.IOException;
import java.util.List;
import java.util.Map.Entry;

import static com.hazelcast.jet.sql.impl.ExpressionUtil.evaluate;

public class KvRowProjector implements Row {

    private final QueryTarget keyTarget;
    private final QueryTarget valueTarget;
    private final QueryExtractor[] extractors;

    private final Expression<Boolean> predicate;
    private final List<Expression<?>> projections;

    @SuppressWarnings("unchecked")
    KvRowProjector(
            QueryPath[] paths,
            QueryDataType[] types,
            QueryTarget keyTarget,
            QueryTarget valueTarget,
            Expression<Boolean> predicate,
            List<Expression<?>> projections
    ) {
        this.keyTarget = keyTarget;
        this.valueTarget = valueTarget;
        this.extractors = createExtractors(paths, types, keyTarget, valueTarget);

        this.predicate = predicate != null ? predicate
                : (Expression<Boolean>) ConstantExpression.create(true, QueryDataType.BOOLEAN);
        this.projections = projections;
    }

    private static QueryExtractor[] createExtractors(
            QueryPath[] paths,
            QueryDataType[] types,
            QueryTarget keyTarget,
            QueryTarget valueTarget
    ) {
        QueryExtractor[] extractors = new QueryExtractor[paths.length];
        for (int i = 0; i < paths.length; i++) {
            QueryPath path = paths[i];
            QueryDataType type = types[i];

            extractors[i] = path.isKey()
                    ? keyTarget.createExtractor(path.getPath(), type)
                    : valueTarget.createExtractor(path.getPath(), type);
        }
        return extractors;
    }

    public Object[] project(Entry<Object, Object> entry) {
        keyTarget.setTarget(entry.getKey());
        valueTarget.setTarget(entry.getValue());

        if (!Boolean.TRUE.equals(evaluate(predicate, this))) {
            return null;
        }

        Object[] row = new Object[projections.size()];
        for (int i = 0; i < projections.size(); i++) {
            row[i] = evaluate(projections.get(i), this);
        }
        return row;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(int index) {
        return (T) extractors[index].get();
    }

    @Override
    public int getColumnCount() {
        return extractors.length;
    }

    public static Supplier supplier(
            QueryPath[] paths,
            QueryDataType[] types,
            QueryTargetDescriptor keyDescriptor,
            QueryTargetDescriptor valueDescriptor,
            Expression<Boolean> predicate,
            List<Expression<?>> projections
    ) {
        return new Supplier(paths, types, keyDescriptor, valueDescriptor, predicate, projections);
    }

    public static class Supplier implements DataSerializable {

        private QueryPath[] paths;
        private QueryDataType[] types;

        private QueryTargetDescriptor keyDescriptor;
        private QueryTargetDescriptor valueDescriptor;

        private Expression<Boolean> predicate;
        private List<Expression<?>> projections;

        @SuppressWarnings("unused")
        private Supplier() {
        }

        Supplier(
                QueryPath[] paths,
                QueryDataType[] types,
                QueryTargetDescriptor keyDescriptor,
                QueryTargetDescriptor valueDescriptor,
                Expression<Boolean> predicate,
                List<Expression<?>> projections
        ) {
            this.paths = paths;
            this.types = types;
            this.keyDescriptor = keyDescriptor;
            this.valueDescriptor = valueDescriptor;
            this.predicate = predicate;
            this.projections = projections;
        }

        public KvRowProjector get(InternalSerializationService serializationService, Extractors extractors) {
            return new KvRowProjector(
                    paths,
                    types,
                    keyDescriptor.create(serializationService, extractors, true),
                    valueDescriptor.create(serializationService, extractors, false),
                    predicate,
                    projections
            );
        }

        @Override
        public void writeData(ObjectDataOutput out) throws IOException {
            out.writeInt(paths.length);
            for (QueryPath path : paths) {
                out.writeObject(path);
            }
            out.writeInt(types.length);
            for (QueryDataType type : types) {
                out.writeObject(type);
            }
            out.writeObject(keyDescriptor);
            out.writeObject(valueDescriptor);
            out.writeObject(predicate);
            out.writeObject(projections);
        }

        @Override
        public void readData(ObjectDataInput in) throws IOException {
            paths = new QueryPath[in.readInt()];
            for (int i = 0; i < paths.length; i++) {
                paths[i] = in.readObject();
            }
            types = new QueryDataType[in.readInt()];
            for (int i = 0; i < types.length; i++) {
                types[i] = in.readObject();
            }
            keyDescriptor = in.readObject();
            valueDescriptor = in.readObject();
            predicate = in.readObject();
            projections = in.readObject();
        }
    }
}