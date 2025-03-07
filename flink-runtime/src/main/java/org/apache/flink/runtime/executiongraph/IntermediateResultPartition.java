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

package org.apache.flink.runtime.executiongraph;

import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.jobgraph.DistributionPattern;
import org.apache.flink.runtime.jobgraph.IntermediateResultPartitionID;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.scheduler.strategy.ConsumedPartitionGroup;
import org.apache.flink.runtime.scheduler.strategy.ConsumerVertexGroup;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.apache.flink.util.Preconditions.checkState;

public class IntermediateResultPartition {

    static final int NUM_SUBPARTITIONS_UNKNOWN = -1;

    private final IntermediateResult totalResult;

    private final ExecutionVertex producer;

    private final IntermediateResultPartitionID partitionId;

    private final EdgeManager edgeManager;

    /** Number of subpartitions for dynamic graph. */
    private final int numberOfSubpartitionsForDynamicGraph;

    private boolean isNumberOfPartitionConsumersUndefined = false;

    /** Whether this partition has produced all data. */
    private boolean dataAllProduced = false;

    /**
     * Releasable {@link ConsumedPartitionGroup}s for this result partition. This result partition
     * can be released if all {@link ConsumedPartitionGroup}s are releasable.
     */
    private final Set<ConsumedPartitionGroup> releasablePartitionGroups = new HashSet<>();

    public IntermediateResultPartition(
            IntermediateResult totalResult,
            ExecutionVertex producer,
            int partitionNumber,
            EdgeManager edgeManager) {
        this.totalResult = totalResult;
        this.producer = producer;
        this.partitionId = new IntermediateResultPartitionID(totalResult.getId(), partitionNumber);
        this.edgeManager = edgeManager;

        if (!producer.getExecutionGraphAccessor().isDynamic()) {
            this.numberOfSubpartitionsForDynamicGraph = NUM_SUBPARTITIONS_UNKNOWN;
        } else {
            this.numberOfSubpartitionsForDynamicGraph =
                    computeNumberOfSubpartitionsForDynamicGraph();
            checkState(
                    numberOfSubpartitionsForDynamicGraph > 0,
                    "Number of subpartitions is an unexpected value: "
                            + numberOfSubpartitionsForDynamicGraph);
        }
    }

    public void markPartitionGroupReleasable(ConsumedPartitionGroup partitionGroup) {
        releasablePartitionGroups.add(partitionGroup);
    }

    public boolean canBeReleased() {
        if (releasablePartitionGroups.size()
                != edgeManager.getNumberOfConsumedPartitionGroupsById(partitionId)) {
            return false;
        }

        // for dynamic graph, if any consumer vertex is still not initialized or not transfer to
        // job vertex, this result partition can not be released
        if (!totalResult.areAllConsumerVerticesCreated()) {
            return false;
        }
        for (JobVertexID jobVertexId : totalResult.getConsumerVertices()) {
            if (!producer.getExecutionGraphAccessor().getJobVertex(jobVertexId).isInitialized()) {
                return false;
            }
        }

        return true;
    }

    public ExecutionVertex getProducer() {
        return producer;
    }

    public int getPartitionNumber() {
        return partitionId.getPartitionNumber();
    }

    public IntermediateResult getIntermediateResult() {
        return totalResult;
    }

    public IntermediateResultPartitionID getPartitionId() {
        return partitionId;
    }

    public ResultPartitionType getResultType() {
        return totalResult.getResultType();
    }

    public List<ConsumerVertexGroup> getConsumerVertexGroups() {
        return getEdgeManager().getConsumerVertexGroupsForPartition(partitionId);
    }

    public List<ConsumedPartitionGroup> getConsumedPartitionGroups() {
        return getEdgeManager().getConsumedPartitionGroupsById(partitionId);
    }

    public boolean isNumberOfPartitionConsumersUndefined() {
        getNumberOfSubpartitions();
        return isNumberOfPartitionConsumersUndefined;
    }

    public int getNumberOfSubpartitions() {
        if (!getProducer().getExecutionGraphAccessor().isDynamic()) {
            List<ConsumerVertexGroup> consumerVertexGroups = getConsumerVertexGroups();
            checkState(!consumerVertexGroups.isEmpty());

            // The produced data is partitioned among a number of subpartitions, one for each
            // consuming sub task. All vertex groups must have the same number of consumers
            // for non-dynamic graph.
            return consumerVertexGroups.get(0).size();
        } else {
            return numberOfSubpartitionsForDynamicGraph;
        }
    }

    private int computeNumberOfSubpartitionsForDynamicGraph() {
        if (totalResult.isSingleSubpartitionContainsAllData() || totalResult.isForward()) {
            // for dynamic graph and broadcast result, and forward result, we only produced one
            // subpartition, and all the downstream vertices should consume this subpartition.
            return 1;
        } else {
            return computeNumberOfMaxPossiblePartitionConsumers();
        }
    }

    private int computeNumberOfMaxPossiblePartitionConsumers() {
        isNumberOfPartitionConsumersUndefined = true;
        final DistributionPattern distributionPattern =
                getIntermediateResult().getConsumingDistributionPattern();

        // decide the max possible consumer job vertex parallelism
        int maxConsumerJobVertexParallelism = getIntermediateResult().getConsumersParallelism();
        if (maxConsumerJobVertexParallelism <= 0) {
            maxConsumerJobVertexParallelism = getIntermediateResult().getConsumersMaxParallelism();
            checkState(
                    maxConsumerJobVertexParallelism > 0,
                    "Neither the parallelism nor the max parallelism of a job vertex is set");
        }

        // compute number of subpartitions according to the distribution pattern
        if (distributionPattern == DistributionPattern.ALL_TO_ALL) {
            return maxConsumerJobVertexParallelism;
        } else {
            int numberOfPartitions = getIntermediateResult().getNumParallelProducers();
            return (int) Math.ceil(((double) maxConsumerJobVertexParallelism) / numberOfPartitions);
        }
    }

    public boolean hasDataAllProduced() {
        return dataAllProduced;
    }

    void resetForNewExecution() {
        if (!getResultType().canBePipelinedConsumed() && dataAllProduced) {
            // A BLOCKING result partition with data produced means it is finished
            // Need to add the running producer count of the result on resetting it
            for (ConsumedPartitionGroup consumedPartitionGroup : getConsumedPartitionGroups()) {
                consumedPartitionGroup.partitionUnfinished();
            }
        }
        releasablePartitionGroups.clear();
        dataAllProduced = false;
        for (ConsumedPartitionGroup consumedPartitionGroup : getConsumedPartitionGroups()) {
            totalResult.clearCachedInformationForPartitionGroup(consumedPartitionGroup);
        }
    }

    public void addConsumers(ConsumerVertexGroup consumers) {
        getEdgeManager().connectPartitionWithConsumerVertexGroup(partitionId, consumers);
    }

    private EdgeManager getEdgeManager() {
        return edgeManager;
    }

    void markFinished() {
        // Sanity check that this is only called on not must be pipelined partitions.
        if (getResultType().mustBePipelinedConsumed()) {
            throw new IllegalStateException(
                    "Tried to mark a must-be-pipelined result partition as finished");
        }

        // Sanity check to make sure a result partition cannot be marked as finished twice.
        if (dataAllProduced) {
            throw new IllegalStateException(
                    "Tried to mark a finished result partition as finished.");
        }

        dataAllProduced = true;

        for (ConsumedPartitionGroup consumedPartitionGroup : getConsumedPartitionGroups()) {
            totalResult.markPartitionFinished(consumedPartitionGroup, this);
            consumedPartitionGroup.partitionFinished();
        }
    }
}
