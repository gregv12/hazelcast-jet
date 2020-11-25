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

package com.hazelcast.jet.sql.impl.opt.physical;

import com.hazelcast.cluster.Address;
import com.hazelcast.function.ConsumerEx;
import com.hazelcast.function.FunctionEx;
import com.hazelcast.function.PredicateEx;
import com.hazelcast.jet.aggregate.AggregateOperation;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.Edge;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.core.processor.Processors;
import com.hazelcast.jet.sql.impl.ExpressionUtil;
import com.hazelcast.jet.sql.impl.connector.SqlConnector.NestedLoopJoin;
import com.hazelcast.sql.impl.calcite.schema.HazelcastTable;
import com.hazelcast.sql.impl.schema.Table;
import org.apache.calcite.rel.RelNode;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;

import static com.hazelcast.function.Functions.entryKey;
import static com.hazelcast.jet.core.Edge.between;
import static com.hazelcast.jet.core.processor.Processors.filterP;
import static com.hazelcast.jet.core.processor.Processors.mapP;
import static com.hazelcast.jet.core.processor.SourceProcessors.convenientSourceP;
import static com.hazelcast.jet.sql.impl.connector.SqlConnectorUtil.getJetSqlConnector;
import static com.hazelcast.jet.sql.impl.processors.RootResultConsumerSink.rootResultConsumerSink;
import static java.util.Collections.singletonList;

public class CreateDagVisitor {

    private final DAG dag = new DAG();
    private final Address localMemberAddress;

    public CreateDagVisitor(Address localMemberAddress) {
        this.localMemberAddress = localMemberAddress;
    }

    public Vertex onValues(ValuesPhysicalRel rel) {
        List<Object[]> values = rel.values();

        return dag.newUniqueVertex("Values", convenientSourceP(
                pCtx -> null,
                (ignored, buffer) -> {
                    values.forEach(buffer::add);
                    buffer.close();
                },
                ctx -> null,
                (ctx, states) -> {
                },
                ConsumerEx.noop(),
                0,
                true)
        );
    }

    public Vertex onInsert(InsertPhysicalRel rel) {
        Table table = rel.getTable().unwrap(HazelcastTable.class).getTarget();

        Vertex vertex = getJetSqlConnector(table).sink(dag, table);
        connectInput(rel.getInput(), vertex, null);
        return vertex;
    }

    public Vertex onFullScan(FullScanPhysicalRel rel) {
        Table table = rel.getTable().unwrap(HazelcastTable.class).getTarget();

        return getJetSqlConnector(table)
                .fullScanReader(dag, table, rel.filter(), rel.projection());
    }

    public Vertex onFilter(FilterPhysicalRel rel) {
        PredicateEx<Object[]> filter = ExpressionUtil.filterFn(rel.filter());

        Vertex vertex = dag.newUniqueVertex("Filter", filterP(filter::test));
        connectInput(rel.getInput(), vertex, null);
        return vertex;
    }

    public Vertex onProject(ProjectPhysicalRel rel) {
        FunctionEx<Object[], Object[]> projection = ExpressionUtil.projectionFn(rel.projection());

        Vertex vertex = dag.newUniqueVertex("Project", mapP(projection));
        connectInput(rel.getInput(), vertex, null);
        return vertex;
    }

    public Vertex onAggregate(AggregatePhysicalRel rel) {
        AggregateOperation<?, Object[]> aggregateOperation = rel.aggrOp();

        Vertex vertex = dag.newUniqueVertex(
                "Aggregate",
                ProcessorMetaSupplier.forceTotalParallelismOne(
                        ProcessorSupplier.of(Processors.aggregateP(aggregateOperation)),
                        localMemberAddress
                )
        );
        connectInput(rel.getInput(), vertex, edge -> edge.allToOne("").distributeTo(localMemberAddress));
        return vertex;
    }

    public Vertex onAccumulate(AggregateAccumulatePhysicalRel rel) {
        AggregateOperation<?, Object[]> aggregateOperation = rel.aggrOp();

        Vertex vertex = dag.newUniqueVertex(
                "Accumulate",
                Processors.accumulateP(aggregateOperation)
        );
        connectInput(rel.getInput(), vertex, null);
        return vertex;
    }

    public Vertex onCombine(AggregateCombinePhysicalRel rel) {
        AggregateOperation<?, Object[]> aggregateOperation = rel.aggrOp();

        Vertex vertex = dag.newUniqueVertex(
                "Combine",
                ProcessorMetaSupplier.forceTotalParallelismOne(
                        ProcessorSupplier.of(Processors.combineP(aggregateOperation)),
                        localMemberAddress
                )
        );
        connectInput(rel.getInput(), vertex, edge -> edge.allToOne("").distributeTo(localMemberAddress));
        return vertex;
    }

    public Vertex onAggregateByKey(AggregateByKeyPhysicalRel rel) {
        FunctionEx<Object[], ?> groupKeyFn = rel.groupKeyFn();
        AggregateOperation<?, Object[]> aggregateOperation = rel.aggrOp();

        Vertex vertex = dag.newUniqueVertex(
                "AggregateByKey",
                Processors.aggregateByKeyP(singletonList(groupKeyFn), aggregateOperation, (key, value) -> value)
        );
        connectInput(rel.getInput(), vertex, edge -> edge.partitioned(groupKeyFn).distributed());
        return vertex;
    }

    public Vertex onAccumulateByKey(AggregateAccumulateByKeyPhysicalRel rel) {
        FunctionEx<Object[], ?> groupKeyFn = rel.groupKeyFn();
        AggregateOperation<?, Object[]> aggregateOperation = rel.aggrOp();

        Vertex vertex = dag.newUniqueVertex(
                "AccumulateByKey",
                Processors.accumulateByKeyP(singletonList(groupKeyFn), aggregateOperation)
        );
        connectInput(rel.getInput(), vertex, edge -> edge.partitioned(groupKeyFn));
        return vertex;
    }

    public Vertex onCombineByKey(AggregateCombineByKeyPhysicalRel rel) {
        AggregateOperation<?, Object[]> aggregateOperation = rel.aggrOp();

        Vertex vertex = dag.newUniqueVertex(
                "CombineByKey",
                Processors.combineByKeyP(aggregateOperation, (key, value) -> value)
        );
        connectInput(rel.getInput(), vertex, edge -> edge.partitioned(entryKey()).distributed());
        return vertex;
    }

    public Vertex onNestedLoopJoin(JoinNestedLoopPhysicalRel rel) {
        assert rel.getRight() instanceof FullScanPhysicalRel : rel.getRight().getClass();

        Table rightTable = rel.getRight().getTable().unwrap(HazelcastTable.class).getTarget();

        NestedLoopJoin join = getJetSqlConnector(rightTable).nestedLoopReader(
                dag,
                rightTable,
                rel.rightFilter(),
                rel.rightProjection(),
                rel.joinInfo()
        );
        Vertex vertex = join.vertex();
        connectInput(rel.getLeft(), vertex, join.configureEdgeFn());
        return vertex;
    }

    public Vertex onRoot(JetRootRel rootRel) {
        Vertex vertex = dag.newUniqueVertex(
                "ClientSink",
                rootResultConsumerSink(rootRel.getInitiatorAddress(), rootRel.getQueryId())
        );

        // We use distribute-to-one edge to send all the items to the initiator member.
        // Such edge has to be partitioned, but the sink is LP=1 anyway, so we can use
        // allToOne with any key, it goes to a single processor on a single member anyway.
        connectInput(rootRel.getInput(), vertex, edge -> edge.allToOne("").distributeTo(localMemberAddress));
        return vertex;
    }

    public DAG getDag() {
        return dag;
    }

    /**
     * Converts the {@code inputRel} into a {@code Vertex} by visiting it and
     * create an edge from the input vertex into {@code thisVertex}.
     *
     * @param configureEdgeFn optional function to configure the edge
     */
    private void connectInput(
            RelNode inputRel,
            Vertex thisVertex,
            @Nullable Consumer<Edge> configureEdgeFn
    ) {
        Vertex inputVertex = ((PhysicalRel) inputRel).accept(this);
        Edge edge = between(inputVertex, thisVertex);
        if (configureEdgeFn != null) {
            configureEdgeFn.accept(edge);
        }
        dag.edge(edge);
    }
}
