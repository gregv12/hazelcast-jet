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

package com.hazelcast.jet.sql.impl.connector.map;

import com.hazelcast.function.BiFunctionEx;
import com.hazelcast.function.SupplierEx;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.jet.Traverser;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.impl.execution.init.Contexts.ProcSupplierCtx;
import com.hazelcast.jet.impl.processor.AsyncTransformUsingServiceOrderedP;
import com.hazelcast.jet.pipeline.ServiceFactories;
import com.hazelcast.jet.sql.impl.ExpressionUtil;
import com.hazelcast.jet.sql.impl.connector.keyvalue.KvRowProjector;
import com.hazelcast.map.IMap;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.DataSerializable;
import com.hazelcast.query.impl.getters.Extractors;
import com.hazelcast.sql.impl.expression.Expression;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.hazelcast.jet.Traversers.singleton;
import static com.hazelcast.jet.Util.entry;
import static com.hazelcast.jet.impl.util.Util.padRight;
import static java.util.concurrent.CompletableFuture.completedFuture;

@SuppressFBWarnings(
        value = {"SE_BAD_FIELD", "SE_NO_SERIALVERSIONID"},
        justification = "the class is never java-serialized"
)
final class JoinByPrimitiveKeyProcessorSupplier implements ProcessorSupplier, DataSerializable {

    private static final int MAX_CONCURRENT_OPS = 8;

    private boolean inner;
    private int leftEquiJoinIndex;
    private Expression<Boolean> condition;
    private String mapName;
    private KvRowProjector.Supplier rightRowProjectorSupplier;

    private transient IMap<Object, Object> map;
    private transient InternalSerializationService serializationService;
    private transient Extractors extractors;

    @SuppressWarnings("unused")
    private JoinByPrimitiveKeyProcessorSupplier() {
    }

    JoinByPrimitiveKeyProcessorSupplier(
            boolean inner,
            int leftEquiJoinIndex,
            Expression<Boolean> condition,
            String mapName,
            KvRowProjector.Supplier rightRowProjectorSupplier
    ) {
        this.inner = inner;
        this.leftEquiJoinIndex = leftEquiJoinIndex;
        this.condition = condition;
        this.mapName = mapName;
        this.rightRowProjectorSupplier = rightRowProjectorSupplier;
    }

    @Override
    public void init(@Nonnull Context context) {
        map = context.jetInstance().getMap(mapName);
        serializationService = ((ProcSupplierCtx) context).serializationService();
        extractors = Extractors.newBuilder(serializationService).build();
    }

    @Nonnull
    @Override
    public Collection<? extends Processor> get(int count) {
        SupplierEx<KvRowProjector> rightRowProjectorSupplier =
                () -> this.rightRowProjectorSupplier.get(serializationService, extractors);

        List<Processor> processors = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Processor processor = new AsyncTransformUsingServiceOrderedP<>(
                    ServiceFactories.iMapService(mapName),
                    map,
                    MAX_CONCURRENT_OPS,
                    joinFn(inner, leftEquiJoinIndex, condition, rightRowProjectorSupplier)
            );
            processors.add(processor);
        }
        return processors;
    }

    private static BiFunctionEx<IMap<Object, Object>, Object[], CompletableFuture<Traverser<Object[]>>> joinFn(
            boolean inner,
            int leftEquiJoinIndex,
            Expression<Boolean> condition,
            SupplierEx<KvRowProjector> rightRowProjectorSupplier
    ) {
        BiFunctionEx<Object[], Object[], Object[]> joinFn = ExpressionUtil.joinFn(condition);

        return (map, left) -> {
            Object key = left[leftEquiJoinIndex];
            // TODO: somehow avoid projector instantiation for each row ?
            KvRowProjector rightRowProjector = rightRowProjectorSupplier.get();

            if (key == null) {
                return inner ? null : completedFuture(singleton(padRight(left, rightRowProjector.getColumnCount())));
            }

            return map.getAsync(key).toCompletableFuture()
                      .thenApply(value -> {
                          Object[] joined = join(left, key, value, rightRowProjector, joinFn);
                          return joined != null ? singleton(joined)
                                  : inner ? null
                                  : singleton(padRight(left, rightRowProjector.getColumnCount()));
                      });
        };
    }

    private static Object[] join(
            Object[] left,
            Object key,
            Object value,
            KvRowProjector rightRowProjector,
            BiFunctionEx<Object[], Object[], Object[]> joinFn
    ) {
        if (value == null) {
            return null;
        }

        Object[] right = rightRowProjector.project(entry(key, value));
        if (right == null) {
            return null;
        }

        return joinFn.apply(left, right);
    }

    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        out.writeBoolean(inner);
        out.writeInt(leftEquiJoinIndex);
        out.writeObject(condition);
        out.writeObject(mapName);
        out.writeObject(rightRowProjectorSupplier);
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        inner = in.readBoolean();
        leftEquiJoinIndex = in.readInt();
        condition = in.readObject();
        mapName = in.readObject();
        rightRowProjectorSupplier = in.readObject();
    }
}
