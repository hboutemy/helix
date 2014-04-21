package org.apache.helix.task;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.helix.AccessOption;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixManager;
import org.apache.helix.PropertyKey;
import org.apache.helix.ZNRecord;
import org.apache.helix.controller.rebalancer.Rebalancer;
import org.apache.helix.controller.rebalancer.internal.MappingCalculator;
import org.apache.helix.controller.stages.ClusterDataCache;
import org.apache.helix.controller.stages.CurrentStateOutput;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.Partition;
import org.apache.helix.model.Resource;
import org.apache.helix.model.ResourceAssignment;
import org.apache.log4j.Logger;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;

/**
 * Custom rebalancer implementation for the {@code Task} state model.
 */
public abstract class TaskRebalancer implements Rebalancer, MappingCalculator {
  private static final Logger LOG = Logger.getLogger(TaskRebalancer.class);
  private HelixManager _manager;

  /**
   * Get all the partitions that should be created by this task
   * @param jobCfg the task configuration
   * @param jobCtx the task context
   * @param workflowCfg the workflow configuration
   * @param workflowCtx the workflow context
   * @param cache cluster snapshot
   * @return set of partition numbers
   */
  public abstract Set<Integer> getAllTaskPartitions(JobConfig jobCfg, JobContext jobCtx,
      WorkflowConfig workflowCfg, WorkflowContext workflowCtx, ClusterDataCache cache);

  /**
   * Compute an assignment of tasks to instances
   * @param currStateOutput the current state of the instances
   * @param prevAssignment the previous task partition assignment
   * @param instanceList the instances
   * @param jobCfg the task configuration
   * @param taskCtx the task context
   * @param workflowCfg the workflow configuration
   * @param workflowCtx the workflow context
   * @param partitionSet the partitions to assign
   * @param cache cluster snapshot
   * @return map of instances to set of partition numbers
   */
  public abstract Map<String, SortedSet<Integer>> getTaskAssignment(
      CurrentStateOutput currStateOutput, ResourceAssignment prevAssignment,
      Iterable<String> instanceList, JobConfig jobCfg, JobContext jobContext,
      WorkflowConfig workflowCfg, WorkflowContext workflowCtx, Set<Integer> partitionSet,
      ClusterDataCache cache);

  @Override
  public void init(HelixManager manager) {
    _manager = manager;
  }

  @Override
  public ResourceAssignment computeBestPossiblePartitionState(ClusterDataCache clusterData,
      IdealState taskIs, Resource resource, CurrentStateOutput currStateOutput) {
    final String resourceName = resource.getResourceName();

    // Fetch job configuration
    JobConfig jobCfg = TaskUtil.getJobCfg(_manager, resourceName);
    String workflowResource = jobCfg.getWorkflow();

    // Fetch workflow configuration and context
    WorkflowConfig workflowCfg = TaskUtil.getWorkflowCfg(_manager, workflowResource);
    WorkflowContext workflowCtx = TaskUtil.getWorkflowContext(_manager, workflowResource);

    // Initialize workflow context if needed
    if (workflowCtx == null) {
      workflowCtx = new WorkflowContext(new ZNRecord("WorkflowContext"));
      workflowCtx.setStartTime(System.currentTimeMillis());
    }

    // Check parent dependencies
    for (String parent : workflowCfg.getJobDag().getDirectParents(resourceName)) {
      if (workflowCtx.getJobState(parent) == null
          || !workflowCtx.getJobState(parent).equals(TaskState.COMPLETED)) {
        return emptyAssignment(resourceName);
      }
    }

    // Clean up if workflow marked for deletion
    TargetState targetState = workflowCfg.getTargetState();
    if (targetState == TargetState.DELETE) {
      cleanup(_manager, resourceName, workflowCfg, workflowResource);
      return emptyAssignment(resourceName);
    }

    // Check if this workflow has been finished past its expiry.
    if (workflowCtx.getFinishTime() != WorkflowContext.UNFINISHED
        && workflowCtx.getFinishTime() + workflowCfg.getExpiry() <= System.currentTimeMillis()) {
      markForDeletion(_manager, workflowResource);
      cleanup(_manager, resourceName, workflowCfg, workflowResource);
      return emptyAssignment(resourceName);
    }

    // Fetch any existing context information from the property store.
    JobContext jobCtx = TaskUtil.getJobContext(_manager, resourceName);
    if (jobCtx == null) {
      jobCtx = new JobContext(new ZNRecord("TaskContext"));
      jobCtx.setStartTime(System.currentTimeMillis());
    }

    // The job is already in a final state (completed/failed).
    if (workflowCtx.getJobState(resourceName) == TaskState.FAILED
        || workflowCtx.getJobState(resourceName) == TaskState.COMPLETED) {
      return emptyAssignment(resourceName);
    }

    ResourceAssignment prevAssignment = TaskUtil.getPrevResourceAssignment(_manager, resourceName);
    if (prevAssignment == null) {
      prevAssignment = new ResourceAssignment(resourceName);
    }

    // Will contain the list of partitions that must be explicitly dropped from the ideal state that
    // is stored in zk.
    // Fetch the previous resource assignment from the property store. This is required because of
    // HELIX-230.
    Set<Integer> partitionsToDrop = new TreeSet<Integer>();

    ResourceAssignment newAssignment =
        computeResourceMapping(resourceName, workflowCfg, jobCfg, prevAssignment, clusterData
            .getLiveInstances().keySet(), currStateOutput, workflowCtx, jobCtx, partitionsToDrop,
            clusterData);

    if (!partitionsToDrop.isEmpty()) {
      for (Integer pId : partitionsToDrop) {
        taskIs.getRecord().getMapFields().remove(pName(resourceName, pId));
      }
      HelixDataAccessor accessor = _manager.getHelixDataAccessor();
      PropertyKey propertyKey = accessor.keyBuilder().idealStates(resourceName);
      accessor.setProperty(propertyKey, taskIs);
    }

    // Update rebalancer context, previous ideal state.
    TaskUtil.setJobContext(_manager, resourceName, jobCtx);
    TaskUtil.setWorkflowContext(_manager, workflowResource, workflowCtx);
    TaskUtil.setPrevResourceAssignment(_manager, resourceName, newAssignment);

    return newAssignment;
  }

  private ResourceAssignment computeResourceMapping(String jobResource,
      WorkflowConfig workflowConfig, JobConfig jobCfg, ResourceAssignment prevAssignment,
      Iterable<String> liveInstances, CurrentStateOutput currStateOutput,
      WorkflowContext workflowCtx, JobContext jobCtx, Set<Integer> partitionsToDropFromIs,
      ClusterDataCache cache) {
    TargetState jobTgtState = workflowConfig.getTargetState();

    // Update running status in workflow context
    if (jobTgtState == TargetState.STOP) {
      workflowCtx.setJobState(jobResource, TaskState.STOPPED);
      // Workflow has been stopped if all jobs are stopped
      if (isWorkflowStopped(workflowCtx, workflowConfig)) {
        workflowCtx.setWorkflowState(TaskState.STOPPED);
      }
    } else {
      workflowCtx.setJobState(jobResource, TaskState.IN_PROGRESS);
      // Workflow is in progress if any task is in progress
      workflowCtx.setWorkflowState(TaskState.IN_PROGRESS);
    }

    // Used to keep track of tasks that have already been assigned to instances.
    Set<Integer> assignedPartitions = new HashSet<Integer>();

    // Keeps a mapping of (partition) -> (instance, state)
    Map<Integer, PartitionAssignment> paMap = new TreeMap<Integer, PartitionAssignment>();

    // Process all the current assignments of tasks.
    Set<Integer> allPartitions =
        getAllTaskPartitions(jobCfg, jobCtx, workflowConfig, workflowCtx, cache);
    Map<String, SortedSet<Integer>> taskAssignments =
        getTaskPartitionAssignments(liveInstances, prevAssignment, allPartitions);
    for (String instance : taskAssignments.keySet()) {
      Set<Integer> pSet = taskAssignments.get(instance);
      // Used to keep track of partitions that are in one of the final states: COMPLETED, TIMED_OUT,
      // TASK_ERROR, ERROR.
      Set<Integer> donePartitions = new TreeSet<Integer>();
      for (int pId : pSet) {
        final String pName = pName(jobResource, pId);

        // Check for pending state transitions on this (partition, instance).
        String pendingState =
            currStateOutput.getPendingState(jobResource, new Partition(pName), instance);
        if (pendingState != null) {
          // There is a pending state transition for this (partition, instance). Just copy forward
          // the state
          // assignment from the previous ideal state.
          Map<String, String> stateMap = prevAssignment.getReplicaMap(new Partition(pName));
          if (stateMap != null) {
            String prevState = stateMap.get(instance);
            paMap.put(pId, new PartitionAssignment(instance, prevState));
            assignedPartitions.add(pId);
            LOG.debug(String
                .format(
                    "Task partition %s has a pending state transition on instance %s. Using the previous ideal state which was %s.",
                    pName, instance, prevState));
          }

          continue;
        }

        TaskPartitionState currState =
            TaskPartitionState.valueOf(currStateOutput.getCurrentState(jobResource, new Partition(
                pName), instance));

        // Process any requested state transitions.
        String requestedStateStr =
            currStateOutput.getRequestedState(jobResource, new Partition(pName), instance);
        if (requestedStateStr != null && !requestedStateStr.isEmpty()) {
          TaskPartitionState requestedState = TaskPartitionState.valueOf(requestedStateStr);
          if (requestedState.equals(currState)) {
            LOG.warn(String.format(
                "Requested state %s is the same as the current state for instance %s.",
                requestedState, instance));
          }

          paMap.put(pId, new PartitionAssignment(instance, requestedState.name()));
          assignedPartitions.add(pId);
          LOG.debug(String.format(
              "Instance %s requested a state transition to %s for partition %s.", instance,
              requestedState, pName));
          continue;
        }

        switch (currState) {
        case RUNNING:
        case STOPPED: {
          TaskPartitionState nextState;
          if (jobTgtState == TargetState.START) {
            nextState = TaskPartitionState.RUNNING;
          } else {
            nextState = TaskPartitionState.STOPPED;
          }

          paMap.put(pId, new PartitionAssignment(instance, nextState.name()));
          assignedPartitions.add(pId);
          LOG.debug(String.format("Setting task partition %s state to %s on instance %s.", pName,
              nextState, instance));
        }
          break;
        case COMPLETED: {
          // The task has completed on this partition. Mark as such in the context object.
          donePartitions.add(pId);
          LOG.debug(String
              .format(
                  "Task partition %s has completed with state %s. Marking as such in rebalancer context.",
                  pName, currState));
          partitionsToDropFromIs.add(pId);
          markPartitionCompleted(jobCtx, pId);
        }
          break;
        case TIMED_OUT:
        case TASK_ERROR:
        case ERROR: {
          donePartitions.add(pId); // The task may be rescheduled on a different instance.
          LOG.debug(String.format(
              "Task partition %s has error state %s. Marking as such in rebalancer context.",
              pName, currState));
          markPartitionError(jobCtx, pId, currState);
          // The error policy is to fail the task as soon a single partition fails for a specified
          // maximum number of
          // attempts.
          if (jobCtx.getPartitionNumAttempts(pId) >= jobCfg.getMaxAttemptsPerTask()) {
            workflowCtx.setJobState(jobResource, TaskState.FAILED);
            workflowCtx.setWorkflowState(TaskState.FAILED);
            addAllPartitions(allPartitions, partitionsToDropFromIs);
            return emptyAssignment(jobResource);
          }
        }
          break;
        case INIT:
        case DROPPED: {
          // currState in [INIT, DROPPED]. Do nothing, the partition is eligible to be reassigned.
          donePartitions.add(pId);
          LOG.debug(String.format(
              "Task partition %s has state %s. It will be dropped from the current ideal state.",
              pName, currState));
        }
          break;
        default:
          throw new AssertionError("Unknown enum symbol: " + currState);
        }
      }

      // Remove the set of task partitions that are completed or in one of the error states.
      pSet.removeAll(donePartitions);
    }

    if (isJobComplete(jobCtx, allPartitions)) {
      workflowCtx.setJobState(jobResource, TaskState.COMPLETED);
      if (isWorkflowComplete(workflowCtx, workflowConfig)) {
        workflowCtx.setWorkflowState(TaskState.COMPLETED);
        workflowCtx.setFinishTime(System.currentTimeMillis());
      }
    }

    // Make additional task assignments if needed.
    if (jobTgtState == TargetState.START) {
      // Contains the set of task partitions that must be excluded from consideration when making
      // any new assignments.
      // This includes all completed, failed, already assigned partitions.
      Set<Integer> excludeSet = Sets.newTreeSet(assignedPartitions);
      addCompletedPartitions(excludeSet, jobCtx, allPartitions);
      // Get instance->[partition, ...] mappings for the target resource.
      Map<String, SortedSet<Integer>> tgtPartitionAssignments =
          getTaskAssignment(currStateOutput, prevAssignment, liveInstances, jobCfg, jobCtx,
              workflowConfig, workflowCtx, allPartitions, cache);
      for (Map.Entry<String, SortedSet<Integer>> entry : taskAssignments.entrySet()) {
        String instance = entry.getKey();
        if (!tgtPartitionAssignments.containsKey(instance)) {
          continue;
        }
        // Contains the set of task partitions currently assigned to the instance.
        Set<Integer> pSet = entry.getValue();
        int numToAssign = jobCfg.getNumConcurrentTasksPerInstance() - pSet.size();
        if (numToAssign > 0) {
          List<Integer> nextPartitions =
              getNextPartitions(tgtPartitionAssignments.get(instance), excludeSet, numToAssign);
          for (Integer pId : nextPartitions) {
            String pName = pName(jobResource, pId);
            paMap.put(pId, new PartitionAssignment(instance, TaskPartitionState.RUNNING.name()));
            excludeSet.add(pId);
            LOG.debug(String.format("Setting task partition %s state to %s on instance %s.", pName,
                TaskPartitionState.RUNNING, instance));
          }
        }
      }
    }

    // Construct a ResourceAssignment object from the map of partition assignments.
    ResourceAssignment ra = new ResourceAssignment(jobResource);
    for (Map.Entry<Integer, PartitionAssignment> e : paMap.entrySet()) {
      PartitionAssignment pa = e.getValue();
      ra.addReplicaMap(new Partition(pName(jobResource, e.getKey())),
          ImmutableMap.of(pa._instance, pa._state));
    }

    return ra;
  }

  /**
   * Checks if the job has completed.
   * @param ctx The rebalancer context.
   * @param allPartitions The set of partitions to check.
   * @return true if all task partitions have been marked with status
   *         {@link TaskPartitionState#COMPLETED} in the rebalancer
   *         context, false otherwise.
   */
  private static boolean isJobComplete(JobContext ctx, Set<Integer> allPartitions) {
    for (Integer pId : allPartitions) {
      TaskPartitionState state = ctx.getPartitionState(pId);
      if (state != TaskPartitionState.COMPLETED) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks if the workflow has completed.
   * @param ctx Workflow context containing job states
   * @param cfg Workflow config containing set of jobs
   * @return returns true if all tasks are {@link TaskState#COMPLETED}, false otherwise.
   */
  private static boolean isWorkflowComplete(WorkflowContext ctx, WorkflowConfig cfg) {
    for (String job : cfg.getJobDag().getAllNodes()) {
      if (ctx.getJobState(job) != TaskState.COMPLETED) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks if the workflow has been stopped.
   * @param ctx Workflow context containing task states
   * @param cfg Workflow config containing set of tasks
   * @return returns true if all tasks are {@link TaskState#STOPPED}, false otherwise.
   */
  private static boolean isWorkflowStopped(WorkflowContext ctx, WorkflowConfig cfg) {
    for (String job : cfg.getJobDag().getAllNodes()) {
      if (ctx.getJobState(job) != TaskState.STOPPED && ctx.getJobState(job) != null) {
        return false;
      }
    }
    return true;
  }

  private static void markForDeletion(HelixManager mgr, String resourceName) {
    mgr.getConfigAccessor().set(
        TaskUtil.getResourceConfigScope(mgr.getClusterName(), resourceName),
        WorkflowConfig.TARGET_STATE, TargetState.DELETE.name());
  }

  /**
   * Cleans up all Helix state associated with this job, wiping workflow-level information if this
   * is the last remaining job in its workflow.
   */
  private static void cleanup(HelixManager mgr, String resourceName, WorkflowConfig cfg,
      String workflowResource) {
    HelixDataAccessor accessor = mgr.getHelixDataAccessor();
    // Delete resource configs.
    PropertyKey cfgKey = getConfigPropertyKey(accessor, resourceName);
    if (!accessor.removeProperty(cfgKey)) {
      throw new RuntimeException(
          String
              .format(
                  "Error occurred while trying to clean up task %s. Failed to remove node %s from Helix. Aborting further clean up steps.",
                  resourceName, cfgKey));
    }
    // Delete property store information for this resource.
    String propStoreKey = getRebalancerPropStoreKey(resourceName);
    if (!mgr.getHelixPropertyStore().remove(propStoreKey, AccessOption.PERSISTENT)) {
      throw new RuntimeException(
          String
              .format(
                  "Error occurred while trying to clean up task %s. Failed to remove node %s from Helix. Aborting further clean up steps.",
                  resourceName, propStoreKey));
    }
    // Finally, delete the ideal state itself.
    PropertyKey isKey = getISPropertyKey(accessor, resourceName);
    if (!accessor.removeProperty(isKey)) {
      throw new RuntimeException(String.format(
          "Error occurred while trying to clean up task %s. Failed to remove node %s from Helix.",
          resourceName, isKey));
    }
    LOG.info(String.format("Successfully cleaned up task resource %s.", resourceName));

    boolean lastInWorkflow = true;
    for (String job : cfg.getJobDag().getAllNodes()) {
      // check if property store information or resource configs exist for this job
      if (mgr.getHelixPropertyStore().exists(getRebalancerPropStoreKey(job),
          AccessOption.PERSISTENT)
          || accessor.getProperty(getConfigPropertyKey(accessor, job)) != null
          || accessor.getProperty(getISPropertyKey(accessor, job)) != null) {
        lastInWorkflow = false;
      }
    }

    // clean up job-level info if this was the last in workflow
    if (lastInWorkflow) {
      // delete workflow config
      PropertyKey workflowCfgKey = getConfigPropertyKey(accessor, workflowResource);
      if (!accessor.removeProperty(workflowCfgKey)) {
        throw new RuntimeException(
            String
                .format(
                    "Error occurred while trying to clean up workflow %s. Failed to remove node %s from Helix. Aborting further clean up steps.",
                    workflowResource, workflowCfgKey));
      }
      // Delete property store information for this workflow
      String workflowPropStoreKey = getRebalancerPropStoreKey(workflowResource);
      if (!mgr.getHelixPropertyStore().remove(workflowPropStoreKey, AccessOption.PERSISTENT)) {
        throw new RuntimeException(
            String
                .format(
                    "Error occurred while trying to clean up workflow %s. Failed to remove node %s from Helix. Aborting further clean up steps.",
                    workflowResource, workflowPropStoreKey));
      }
    }

  }

  private static String getRebalancerPropStoreKey(String resource) {
    return Joiner.on("/").join(TaskConstants.REBALANCER_CONTEXT_ROOT, resource);
  }

  private static PropertyKey getISPropertyKey(HelixDataAccessor accessor, String resource) {
    return accessor.keyBuilder().idealStates(resource);
  }

  private static PropertyKey getConfigPropertyKey(HelixDataAccessor accessor, String resource) {
    return accessor.keyBuilder().resourceConfig(resource);
  }

  private static void addAllPartitions(Set<Integer> toAdd, Set<Integer> destination) {
    for (Integer pId : toAdd) {
      destination.add(pId);
    }
  }

  private static ResourceAssignment emptyAssignment(String name) {
    return new ResourceAssignment(name);
  }

  private static void addCompletedPartitions(Set<Integer> set, JobContext ctx,
      Iterable<Integer> pIds) {
    for (Integer pId : pIds) {
      TaskPartitionState state = ctx.getPartitionState(pId);
      if (state == TaskPartitionState.COMPLETED) {
        set.add(pId);
      }
    }
  }

  private static List<Integer> getNextPartitions(SortedSet<Integer> candidatePartitions,
      Set<Integer> excluded, int n) {
    List<Integer> result = new ArrayList<Integer>();
    for (Integer pId : candidatePartitions) {
      if (result.size() >= n) {
        break;
      }

      if (!excluded.contains(pId)) {
        result.add(pId);
      }
    }

    return result;
  }

  private static void markPartitionCompleted(JobContext ctx, int pId) {
    ctx.setPartitionState(pId, TaskPartitionState.COMPLETED);
    ctx.setPartitionFinishTime(pId, System.currentTimeMillis());
    ctx.incrementNumAttempts(pId);
  }

  private static void markPartitionError(JobContext ctx, int pId, TaskPartitionState state) {
    ctx.setPartitionState(pId, state);
    ctx.setPartitionFinishTime(pId, System.currentTimeMillis());
    ctx.incrementNumAttempts(pId);
  }

  /**
   * Return the assignment of task partitions per instance.
   */
  private static Map<String, SortedSet<Integer>> getTaskPartitionAssignments(
      Iterable<String> instanceList, ResourceAssignment assignment, Set<Integer> includeSet) {
    Map<String, SortedSet<Integer>> result = new HashMap<String, SortedSet<Integer>>();
    for (String instance : instanceList) {
      result.put(instance, new TreeSet<Integer>());
    }

    for (Partition partition : assignment.getMappedPartitions()) {
      int pId = pId(partition.getPartitionName());
      if (includeSet.contains(pId)) {
        Map<String, String> replicaMap = assignment.getReplicaMap(partition);
        for (String instance : replicaMap.keySet()) {
          SortedSet<Integer> pList = result.get(instance);
          if (pList != null) {
            pList.add(pId);
          }
        }
      }
    }

    return result;
  }

  /**
   * Computes the partition name given the resource name and partition id.
   */
  protected static String pName(String resource, int pId) {
    return resource + "_" + pId;
  }

  /**
   * Extracts the partition id from the given partition name.
   */
  protected static int pId(String pName) {
    String[] tokens = pName.split("_");
    return Integer.valueOf(tokens[tokens.length - 1]);
  }

  /**
   * An (instance, state) pair.
   */
  private static class PartitionAssignment {
    private final String _instance;
    private final String _state;

    private PartitionAssignment(String instance, String state) {
      _instance = instance;
      _state = state;
    }
  }

  @Override
  public IdealState computeNewIdealState(String resourceName, IdealState currentIdealState,
      CurrentStateOutput currentStateOutput, ClusterDataCache clusterData) {
    // All of the heavy lifting is in the ResourceAssignment computation,
    // so this part can just be a no-op.
    return currentIdealState;
  }
}
