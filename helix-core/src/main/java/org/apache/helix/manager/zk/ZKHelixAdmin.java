package org.apache.helix.manager.zk;

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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.helix.AccessOption;
import org.apache.helix.BaseDataAccessor;
import org.apache.helix.ConfigAccessor;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixConstants;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixDefinedState;
import org.apache.helix.HelixException;
import org.apache.helix.InstanceType;
import org.apache.helix.PropertyKey;
import org.apache.helix.PropertyPathBuilder;
import org.apache.helix.PropertyType;
import org.apache.helix.SystemPropertyKeys;
import org.apache.helix.api.exceptions.HelixConflictException;
import org.apache.helix.api.status.ClusterManagementMode;
import org.apache.helix.api.status.ClusterManagementModeRequest;
import org.apache.helix.api.topology.ClusterTopology;
import org.apache.helix.constants.InstanceConstants;
import org.apache.helix.controller.rebalancer.strategy.RebalanceStrategy;
import org.apache.helix.controller.rebalancer.util.WagedValidationUtil;
import org.apache.helix.controller.rebalancer.waged.WagedRebalancer;
import org.apache.helix.model.CloudConfig;
import org.apache.helix.model.ClusterConfig;
import org.apache.helix.model.ClusterConstraints;
import org.apache.helix.model.ClusterConstraints.ConstraintType;
import org.apache.helix.model.ClusterStatus;
import org.apache.helix.model.ConstraintItem;
import org.apache.helix.model.ControllerHistory;
import org.apache.helix.model.CurrentState;
import org.apache.helix.model.CustomizedStateConfig;
import org.apache.helix.model.CustomizedView;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.HelixConfigScope;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.IdealState.RebalanceMode;
import org.apache.helix.model.InstanceConfig;
import org.apache.helix.model.LiveInstance;
import org.apache.helix.model.MaintenanceSignal;
import org.apache.helix.model.Message;
import org.apache.helix.model.Message.MessageState;
import org.apache.helix.model.Message.MessageType;
import org.apache.helix.model.ParticipantHistory;
import org.apache.helix.model.PauseSignal;
import org.apache.helix.model.ResourceConfig;
import org.apache.helix.model.StateModelDefinition;
import org.apache.helix.msdcommon.exception.InvalidRoutingDataException;
import org.apache.helix.tools.DefaultIdealStateCalculator;
import org.apache.helix.util.HelixUtil;
import org.apache.helix.util.RebalanceUtil;
import org.apache.helix.zookeeper.api.client.HelixZkClient;
import org.apache.helix.zookeeper.api.client.RealmAwareZkClient;
import org.apache.helix.zookeeper.constant.RoutingDataReaderType;
import org.apache.helix.zookeeper.datamodel.ZNRecord;
import org.apache.helix.zookeeper.exception.ZkClientException;
import org.apache.helix.zookeeper.impl.client.FederatedZkClient;
import org.apache.helix.zookeeper.impl.factory.SharedZkClientFactory;
import org.apache.helix.zookeeper.routing.RoutingDataManager;
import org.apache.helix.zookeeper.zkclient.DataUpdater;
import org.apache.helix.zookeeper.zkclient.NetworkUtil;
import org.apache.helix.zookeeper.zkclient.exception.ZkException;
import org.apache.helix.zookeeper.zkclient.exception.ZkNoNodeException;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ZKHelixAdmin implements HelixAdmin {
  private static final Logger LOG = LoggerFactory.getLogger(ZKHelixAdmin.class);

  public static final String CONNECTION_TIMEOUT = "helixAdmin.timeOutInSec";
  private static final String MAINTENANCE_ZNODE_ID = "maintenance";
  private static final int DEFAULT_SUPERCLUSTER_REPLICA = 3;

  private final RealmAwareZkClient _zkClient;
  private final ConfigAccessor _configAccessor;
  // true if ZKHelixAdmin was instantiated with a RealmAwareZkClient, false otherwise
  // This is used for close() to determine how ZKHelixAdmin should close the underlying ZkClient
  private final boolean _usesExternalZkClient;

  private static Logger logger = LoggerFactory.getLogger(ZKHelixAdmin.class);

  /**
   * @deprecated it is recommended to use the builder constructor {@link Builder}
   * instead to avoid having to manually create and maintain a RealmAwareZkClient
   * outside of ZKHelixAdmin.
   *
   * @param zkClient A created RealmAwareZkClient
   */
  @Deprecated
  public ZKHelixAdmin(RealmAwareZkClient zkClient) {
    _zkClient = zkClient;
    _configAccessor = new ConfigAccessor(zkClient);
    _usesExternalZkClient = true;
  }

  /**
   * There are 2 realm-aware modes to connect to ZK:
   * 1. if system property {@link SystemPropertyKeys#MULTI_ZK_ENABLED} is set to <code>"true"</code>
   * , or zkAddress is null, it will connect on multi-realm mode;
   * 2. otherwise, it will connect on single-realm mode to the <code>zkAddress</code> provided.
   *
   * @param zkAddress ZK address
   * @exception HelixException if not able to connect on multi-realm mode
   *
   * @deprecated it is recommended to use the builder constructor {@link Builder}
   */
  @Deprecated
  public ZKHelixAdmin(String zkAddress) {
    int timeOutInSec = Integer.parseInt(System.getProperty(CONNECTION_TIMEOUT, "30"));
    RealmAwareZkClient.RealmAwareZkClientConfig clientConfig =
        new RealmAwareZkClient.RealmAwareZkClientConfig()
            .setConnectInitTimeout(timeOutInSec * 1000L)
            .setZkSerializer(new ZNRecordSerializer());

    RealmAwareZkClient zkClient;

    if (Boolean.getBoolean(SystemPropertyKeys.MULTI_ZK_ENABLED) || zkAddress == null) {
      try {
        zkClient = new FederatedZkClient(
            new RealmAwareZkClient.RealmAwareZkConnectionConfig.Builder().build(), clientConfig);
      } catch (IllegalStateException | InvalidRoutingDataException e) {
        throw new HelixException("Not able to connect on multi-realm mode.", e);
      }
    } else {
      zkClient = SharedZkClientFactory.getInstance()
          .buildZkClient(new HelixZkClient.ZkConnectionConfig(zkAddress),
              clientConfig.createHelixZkClientConfig());
      zkClient.waitUntilConnected(timeOutInSec, TimeUnit.SECONDS);
    }

    _zkClient = zkClient;
    _configAccessor = new ConfigAccessor(_zkClient);
    _usesExternalZkClient = false;
  }

  private ZKHelixAdmin(RealmAwareZkClient zkClient, boolean usesExternalZkClient) {
    _zkClient = zkClient;
    _configAccessor = new ConfigAccessor(_zkClient);
    _usesExternalZkClient = usesExternalZkClient;
  }

  @Override
  public void addInstance(String clusterName, InstanceConfig instanceConfig) {
    logger.info("Add instance {} to cluster {}.", instanceConfig.getInstanceName(), clusterName);
    if (!ZKUtil.isClusterSetup(clusterName, _zkClient)) {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }
    String instanceConfigsPath = PropertyPathBuilder.instanceConfig(clusterName);
    String nodeId = instanceConfig.getId();
    String instanceConfigPath = instanceConfigsPath + "/" + nodeId;

    if (_zkClient.exists(instanceConfigPath)) {
      throw new HelixException("Node " + nodeId + " already exists in cluster " + clusterName);
    }

    ZKUtil.createChildren(_zkClient, instanceConfigsPath, instanceConfig.getRecord());

    _zkClient.createPersistent(PropertyPathBuilder.instanceMessage(clusterName, nodeId), true);
    _zkClient.createPersistent(PropertyPathBuilder.instanceCurrentState(clusterName, nodeId), true);
    _zkClient
        .createPersistent(PropertyPathBuilder.instanceTaskCurrentState(clusterName, nodeId), true);
    _zkClient.createPersistent(PropertyPathBuilder.instanceCustomizedState(clusterName, nodeId), true);
    _zkClient.createPersistent(PropertyPathBuilder.instanceError(clusterName, nodeId), true);
    _zkClient.createPersistent(PropertyPathBuilder.instanceStatusUpdate(clusterName, nodeId), true);
    _zkClient.createPersistent(PropertyPathBuilder.instanceHistory(clusterName, nodeId), true);
  }

  @Override
  public void dropInstance(String clusterName, InstanceConfig instanceConfig) {
    logger.info("Drop instance {} from cluster {}.", instanceConfig.getInstanceName(), clusterName);
    String instanceName = instanceConfig.getInstanceName();

    String instanceConfigPath = PropertyPathBuilder.instanceConfig(clusterName, instanceName);
    if (!_zkClient.exists(instanceConfigPath)) {
      throw new HelixException(
          "Node " + instanceName + " does not exist in config for cluster " + clusterName);
    }

    String instancePath = PropertyPathBuilder.instance(clusterName, instanceName);
    if (!_zkClient.exists(instancePath)) {
      throw new HelixException(
          "Node " + instanceName + " does not exist in instances for cluster " + clusterName);
    }

    String liveInstancePath = PropertyPathBuilder.liveInstance(clusterName, instanceName);
    if (_zkClient.exists(liveInstancePath)) {
      throw new HelixException(
          "Node " + instanceName + " is still alive for cluster " + clusterName + ", can't drop.");
    }

    // delete config path
    String instanceConfigsPath = PropertyPathBuilder.instanceConfig(clusterName);
    ZKUtil.dropChildren(_zkClient, instanceConfigsPath, instanceConfig.getRecord());
    // delete instance path
    dropInstancePathRecursively(instancePath, instanceConfig.getInstanceName());
  }

  private void dropInstancePathRecursively(String instancePath, String instanceName) {
    int retryCnt = 0;
    while (true) {
      try {
        _zkClient.deleteRecursively(instancePath);
        return;
      } catch (ZkClientException e) {
        if (retryCnt < 3 && e.getCause() instanceof ZkException && e.getCause()
            .getCause() instanceof KeeperException.NotEmptyException) {
          // Racing condition with controller's persisting node history, retryable.
          // We don't need to backoff here as this racing condition only happens once (controller
          // does not repeatedly write instance history)
          logger.warn("Retrying dropping instance {} with exception {}", instanceName,
              e.getCause().getMessage());
          retryCnt++;
        } else {
          String errorMessage =
              "Failed to drop instance: " + instanceName + ". Retry times: " + retryCnt;
          logger.error(errorMessage, e);
          throw new HelixException(errorMessage, e);
        }
      }
    }
  }

  /**
   * Please note that the purge function should only be called when there is no new instance
   * joining happening in the cluster. The reason is that current implementation is not thread safe,
   * meaning that if the offline instance comes online while the purging is ongoing, race
   * condition may happen, and we may have live instance in the cluster without corresponding
   * instance config.
   * TODO: consider using Helix lock to prevent race condition, and make sure zookeeper is ok
   *  with the extra traffic caused by lock.
   */
  @Override
  public void purgeOfflineInstances(String clusterName, long offlineDuration) {
    List<String> failToPurgeInstances = new ArrayList<>();
    findTimeoutOfflineInstances(clusterName, offlineDuration).forEach(instance -> {
      try {
        purgeInstance(clusterName, instance);
      } catch (HelixException e) {
        failToPurgeInstances.add(instance);
      }
    });
    if (failToPurgeInstances.size() > 0) {
      LOG.error("ZKHelixAdmin::purgeOfflineInstances(): failed to drop the following instances: "
          + failToPurgeInstances);
    }
  }

  private void purgeInstance(String clusterName, String instanceName) {
    logger.info("Purge instance {} from cluster {}.", instanceName, clusterName);

    String instanceConfigPath = PropertyPathBuilder.instanceConfig(clusterName, instanceName);
    _zkClient.delete(instanceConfigPath);
    String instancePath = PropertyPathBuilder.instance(clusterName, instanceName);
    dropInstancePathRecursively(instancePath, instanceName);
  }

  @Override
  public InstanceConfig getInstanceConfig(String clusterName, String instanceName) {
    logger.info("Get instance config for instance {} from cluster {}.", instanceName, clusterName);
    String instanceConfigPath = PropertyPathBuilder.instanceConfig(clusterName, instanceName);
    if (!_zkClient.exists(instanceConfigPath)) {
      throw new HelixException(
          "instance" + instanceName + " does not exist in cluster " + clusterName);
    }

    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();

    return accessor.getProperty(keyBuilder.instanceConfig(instanceName));
  }

  @Override
  public boolean setInstanceConfig(String clusterName, String instanceName,
      InstanceConfig newInstanceConfig) {
    logger.info("Set instance config for instance {} to cluster {} with new InstanceConfig {}.",
        instanceName, clusterName,
        newInstanceConfig == null ? "NULL" : newInstanceConfig.toString());
    String instanceConfigPath = PropertyPathBuilder.getPath(PropertyType.CONFIGS, clusterName,
        HelixConfigScope.ConfigScopeProperty.PARTICIPANT.toString(), instanceName);
    if (!_zkClient.exists(instanceConfigPath)) {
      throw new HelixException(
          "instance" + instanceName + " does not exist in cluster " + clusterName);
    }

    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey instanceConfigPropertyKey = accessor.keyBuilder().instanceConfig(instanceName);
    InstanceConfig currentInstanceConfig = accessor.getProperty(instanceConfigPropertyKey);
    if (!newInstanceConfig.getHostName().equals(currentInstanceConfig.getHostName())
        || !newInstanceConfig.getPort().equals(currentInstanceConfig.getPort())) {
      throw new HelixException(
          "Hostname and port cannot be changed, current hostname: " + currentInstanceConfig
              .getHostName() + " and port: " + currentInstanceConfig.getPort()
              + " is different from new hostname: " + newInstanceConfig.getHostName()
              + "and new port: " + newInstanceConfig.getPort());
    }
    return accessor.setProperty(instanceConfigPropertyKey, newInstanceConfig);
  }

  @Override
  public void enableInstance(final String clusterName, final String instanceName,
      final boolean enabled) {
    enableInstance(clusterName, instanceName, enabled, null, null);
  }

  @Override
  public void enableInstance(final String clusterName, final String instanceName,
      final boolean enabled, InstanceConstants.InstanceDisabledType disabledType, String reason) {
    logger.info("{} instance {} in cluster {}.", enabled ? "Enable" : "Disable", instanceName,
        clusterName);
    BaseDataAccessor<ZNRecord> baseAccessor = new ZkBaseDataAccessor<>(_zkClient);
    enableSingleInstance(clusterName, instanceName, enabled, baseAccessor, disabledType, reason);
    // TODO: Reenable this after storage node bug fixed.
    // enableBatchInstances(clusterName, Collections.singletonList(instanceName), enabled, baseAccessor);

  }

  @Override
  public void enableInstance(String clusterName, List<String> instances, boolean enabled) {
    // TODO: Considering adding another batched API with  disabled type and reason.
    // TODO: Reenable this after storage node bug fixed.
    if (true) {
      throw new HelixException("Current batch enable/disable instances are temporarily disabled!");
    }
    logger.info("Batch {} instances {} in cluster {}.", enabled ? "enable" : "disable",
        HelixUtil.serializeByComma(instances), clusterName);
    BaseDataAccessor<ZNRecord> baseAccessor = new ZkBaseDataAccessor<>(_zkClient);
    if (enabled) {
      for (String instance : instances) {
        enableSingleInstance(clusterName, instance, enabled, baseAccessor, null, null);
      }
    }
    enableBatchInstances(clusterName, instances, enabled, baseAccessor);
  }

  @Override
  public void enableResource(final String clusterName, final String resourceName,
      final boolean enabled) {
    logger.info("{} resource {} in cluster {}.", enabled ? "Enable" : "Disable", resourceName,
        clusterName);
    String path = PropertyPathBuilder.idealState(clusterName, resourceName);
    BaseDataAccessor<ZNRecord> baseAccessor = new ZkBaseDataAccessor<ZNRecord>(_zkClient);
    if (!baseAccessor.exists(path, 0)) {
      throw new HelixException("Cluster " + clusterName + ", resource: " + resourceName
          + ", ideal-state does not exist");
    }
    baseAccessor.update(path, new DataUpdater<ZNRecord>() {
      @Override
      public ZNRecord update(ZNRecord currentData) {
        if (currentData == null) {
          throw new HelixException(
              "Cluster: " + clusterName + ", resource: " + resourceName + ", ideal-state is null");
        }
        IdealState idealState = new IdealState(currentData);
        idealState.enable(enabled);
        return idealState.getRecord();
      }
    }, AccessOption.PERSISTENT);
  }

  @Override
  public void enablePartition(final boolean enabled, final String clusterName,
      final String instanceName, final String resourceName, final List<String> partitionNames) {
    logger.info("{} partitions {} for resource {} on instance {} in cluster {}.",
        enabled ? "Enable" : "Disable", HelixUtil.serializeByComma(partitionNames), resourceName,
        instanceName, clusterName);
    String path = PropertyPathBuilder.instanceConfig(clusterName, instanceName);

    BaseDataAccessor<ZNRecord> baseAccessor = new ZkBaseDataAccessor<ZNRecord>(_zkClient);

    // check instanceConfig exists
    if (!baseAccessor.exists(path, 0)) {
      throw new HelixException("Cluster: " + clusterName + ", instance: " + instanceName
          + ", instance config does not exist");
    }

    // check resource exists
    String idealStatePath = PropertyPathBuilder.idealState(clusterName, resourceName);

    ZNRecord idealStateRecord = null;
    try {
      idealStateRecord = baseAccessor.get(idealStatePath, null, 0);
    } catch (ZkNoNodeException e) {
      // OK.
    }

    // check resource exist. warn if not.
    if (idealStateRecord == null) {
      // throw new HelixException("Cluster: " + clusterName + ", resource: " + resourceName
      // + ", ideal state does not exist");
      logger.warn(
          "Disable partitions: " + partitionNames + " but Cluster: " + clusterName + ", resource: "
              + resourceName
              + " does not exists. probably disable it during ERROR->DROPPED transtition");
    } else {
      // check partitions exist. warn if not
      IdealState idealState = new IdealState(idealStateRecord);
      for (String partitionName : partitionNames) {
        if ((idealState.getRebalanceMode() == RebalanceMode.SEMI_AUTO
            && idealState.getPreferenceList(partitionName) == null) || (
            idealState.getRebalanceMode() == RebalanceMode.USER_DEFINED
                && idealState.getPreferenceList(partitionName) == null) || (
            idealState.getRebalanceMode() == RebalanceMode.TASK
                && idealState.getPreferenceList(partitionName) == null) || (
            idealState.getRebalanceMode() == RebalanceMode.CUSTOMIZED
                && idealState.getInstanceStateMap(partitionName) == null)) {
          logger.warn("Cluster: " + clusterName + ", resource: " + resourceName + ", partition: "
              + partitionName + ", partition does not exist in ideal state");
        }
      }
    }

    // update participantConfig
    // could not use ZNRecordUpdater since it doesn't do listField merge/subtract
    baseAccessor.update(path, new DataUpdater<ZNRecord>() {
      @Override
      public ZNRecord update(ZNRecord currentData) {
        if (currentData == null) {
          throw new HelixException("Cluster: " + clusterName + ", instance: " + instanceName
              + ", participant config is null");
        }

        InstanceConfig instanceConfig = new InstanceConfig(currentData);
        for (String partitionName : partitionNames) {
          instanceConfig.setInstanceEnabledForPartition(resourceName, partitionName, enabled);
        }

        return instanceConfig.getRecord();
      }
    }, AccessOption.PERSISTENT);
  }

  @Override
  public void enableCluster(String clusterName, boolean enabled) {
    enableCluster(clusterName, enabled, null);
  }

  /**
   * @param clusterName
   * @param enabled
   * @param reason      set additional string description on why the cluster is disabled when
   *                    <code>enabled</code> is false.
   */
  @Override
  public void enableCluster(String clusterName, boolean enabled, String reason) {
    logger.info("{} cluster {} for reason {}.", enabled ? "Enable" : "Disable", clusterName,
        reason == null ? "NULL" : reason);
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();

    if (enabled) {
      accessor.removeProperty(keyBuilder.pause());
    } else {
      PauseSignal pauseSignal = new PauseSignal("pause");
      if (reason != null) {
        pauseSignal.setReason(reason);
      }
      if (!accessor.createPause(pauseSignal)) {
        throw new HelixException("Failed to create pause signal");
      }
    }
  }

  @Override
  @Deprecated
  public void enableMaintenanceMode(String clusterName, boolean enabled) {
    manuallyEnableMaintenanceMode(clusterName, enabled, null, null);
  }

  @Override
  public boolean isInMaintenanceMode(String clusterName) {
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    return accessor.getBaseDataAccessor()
        .exists(keyBuilder.maintenance().getPath(), AccessOption.PERSISTENT);
  }

  @Override
  public void setClusterManagementMode(ClusterManagementModeRequest request) {
    ClusterManagementMode.Type mode = request.getMode();
    String clusterName = request.getClusterName();
    String reason = request.getReason();

    // TODO: support other modes
    switch (mode) {
      case CLUSTER_FREEZE:
        enableClusterPauseMode(clusterName, request.isCancelPendingST(), reason);
        break;
      case NORMAL:
        // If from other modes, should check what mode it is in and call the api accordingly.
        // If we put all mode config in one znode, one generic method is good enough.
        disableClusterPauseMode(clusterName);
        break;
      default:
        throw new IllegalArgumentException("ClusterManagementMode " + mode + " is not supported");
    }
  }

  @Override
  public ClusterManagementMode getClusterManagementMode(String clusterName) {
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<>(_zkClient));
    ClusterStatus status = accessor.getProperty(accessor.keyBuilder().clusterStatus());
    return status == null ? null
        : new ClusterManagementMode(status.getManagementMode(), status.getManagementModeStatus());
  }

  private void enableClusterPauseMode(String clusterName, boolean cancelPendingST, String reason) {
    String hostname = NetworkUtil.getLocalhostName();
    logger.info(
        "Enable cluster pause mode for cluster: {}. CancelPendingST: {}. Reason: {}. From Host: {}",
        clusterName, cancelPendingST, reason, hostname);

    BaseDataAccessor<ZNRecord> baseDataAccessor = new ZkBaseDataAccessor<>(_zkClient);
    HelixDataAccessor accessor = new ZKHelixDataAccessor(clusterName, baseDataAccessor);

    if (baseDataAccessor.exists(accessor.keyBuilder().pause().getPath(), AccessOption.PERSISTENT)) {
      throw new HelixConflictException(clusterName + " pause signal already exists");
    }

    // check whether cancellation is enabled
    ClusterConfig config = accessor.getProperty(accessor.keyBuilder().clusterConfig());
    if (cancelPendingST && !config.isStateTransitionCancelEnabled()) {
      throw new HelixConflictException(
          "State transition cancellation not enabled in " + clusterName);
    }

    PauseSignal pauseSignal = new PauseSignal();
    pauseSignal.setClusterPause(true);
    pauseSignal.setCancelPendingST(cancelPendingST);
    pauseSignal.setFromHost(hostname);
    pauseSignal.setTriggerTime(Instant.now().toEpochMilli());
    if (reason != null && !reason.isEmpty()) {
      pauseSignal.setReason(reason);
    }
    // TODO: merge management status signal into one znode to avoid race condition
    if (!accessor.createPause(pauseSignal)) {
      throw new HelixException("Failed to create pause signal");
    }
  }

  private void disableClusterPauseMode(String clusterName) {
    logger.info("Disable cluster pause mode for cluster: {}", clusterName);
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<>(_zkClient));
    PropertyKey pausePropertyKey = accessor.keyBuilder().pause();
    PauseSignal pauseSignal = accessor.getProperty(pausePropertyKey);
    if (pauseSignal == null || !pauseSignal.isClusterPause()) {
      throw new HelixException("Cluster pause mode is not enabled for cluster " + clusterName);
    }

    if (!accessor.removeProperty(pausePropertyKey)) {
      throw new HelixException("Failed to disable cluster pause mode for cluster: " + clusterName);
    }
  }

  @Override
  @Deprecated
  public void enableMaintenanceMode(String clusterName, boolean enabled, String reason) {
    manuallyEnableMaintenanceMode(clusterName, enabled, reason, null);
  }

  @Override
  public void autoEnableMaintenanceMode(String clusterName, boolean enabled, String reason,
      MaintenanceSignal.AutoTriggerReason internalReason) {
    processMaintenanceMode(clusterName, enabled, reason, internalReason, null,
        MaintenanceSignal.TriggeringEntity.CONTROLLER);
  }

  @Override
  public void manuallyEnableMaintenanceMode(String clusterName, boolean enabled, String reason,
      Map<String, String> customFields) {
    processMaintenanceMode(clusterName, enabled, reason,
        MaintenanceSignal.AutoTriggerReason.NOT_APPLICABLE, customFields,
        MaintenanceSignal.TriggeringEntity.USER);
  }

  /**
   * Helper method for enabling/disabling maintenance mode.
   * @param clusterName
   * @param enabled
   * @param reason
   * @param internalReason
   * @param customFields
   * @param triggeringEntity
   */
  private void processMaintenanceMode(String clusterName, final boolean enabled,
      final String reason, final MaintenanceSignal.AutoTriggerReason internalReason,
      final Map<String, String> customFields,
      final MaintenanceSignal.TriggeringEntity triggeringEntity) {
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    logger.info("Cluster {} {} {} maintenance mode for reason {}.", clusterName,
        triggeringEntity == MaintenanceSignal.TriggeringEntity.CONTROLLER ? "automatically"
            : "manually", enabled ? "enters" : "exits", reason == null ? "NULL" : reason);
    final long currentTime = System.currentTimeMillis();
    if (!enabled) {
      // Exit maintenance mode
      accessor.removeProperty(keyBuilder.maintenance());
    } else {
      // Enter maintenance mode
      MaintenanceSignal maintenanceSignal = new MaintenanceSignal(MAINTENANCE_ZNODE_ID);
      if (reason != null) {
        maintenanceSignal.setReason(reason);
      }
      maintenanceSignal.setTimestamp(currentTime);
      maintenanceSignal.setTriggeringEntity(triggeringEntity);
      switch (triggeringEntity) {
        case CONTROLLER:
          // autoEnable
          maintenanceSignal.setAutoTriggerReason(internalReason);
          break;
        case USER:
        case UNKNOWN:
          // manuallyEnable
          if (customFields != null && !customFields.isEmpty()) {
            // Enter all custom fields provided by the user
            Map<String, String> simpleFields = maintenanceSignal.getRecord().getSimpleFields();
            for (Map.Entry<String, String> entry : customFields.entrySet()) {
              if (!simpleFields.containsKey(entry.getKey())) {
                simpleFields.put(entry.getKey(), entry.getValue());
              }
            }
          }
          break;
      }
      if (!accessor.createMaintenance(maintenanceSignal)) {
        throw new HelixException("Failed to create maintenance signal!");
      }
    }

    // Record a MaintenanceSignal history
    if (!accessor.getBaseDataAccessor()
        .update(keyBuilder.controllerLeaderHistory().getPath(),
            (DataUpdater<ZNRecord>) oldRecord -> {
              try {
                if (oldRecord == null) {
                  oldRecord = new ZNRecord(PropertyType.HISTORY.toString());
                }
                return new ControllerHistory(oldRecord)
                    .updateMaintenanceHistory(enabled, reason, currentTime, internalReason,
                        customFields, triggeringEntity);
              } catch (IOException e) {
                logger.error("Failed to update maintenance history! Exception: {}", e);
                return oldRecord;
              }
            }, AccessOption.PERSISTENT)) {
      logger.error("Failed to write maintenance history to ZK!");
    }
  }

  private enum ResetPartitionFailureReason {
    INSTANCE_NOT_ALIVE("%s is not alive in cluster %s"),
    INSTANCE_NON_EXISTENT("%s does not exist in cluster %s"),
    RESOURCE_NON_EXISTENT("resource %s is not added to cluster %s"),
    PARTITION_NON_EXISTENT("not all %s exist in cluster %s"),
    PARTITION_NOT_ERROR("%s is NOT found in cluster %s or not in ERROR state"),
    STATE_MODEL_NON_EXISTENT("%s is NOT found in cluster %s");

    private String message;

    ResetPartitionFailureReason(String message) {
      this.message = message;
    }

    public String getMessage(String resourceName, List<String> partitionNames, String instanceName,
        String errorStateEntity, String clusterName) {
      return String.format("Can't reset state for %s.%s on %s, because " + message, resourceName,
          partitionNames, instanceName, errorStateEntity, clusterName);
    }
  }

  @Override
  public void resetPartition(String clusterName, String instanceName, String resourceName,
      List<String> partitionNames) {
    logger.info("Reset partitions {} for resource {} on instance {} in cluster {}.",
        partitionNames == null ? "NULL" : HelixUtil.serializeByComma(partitionNames), resourceName,
        instanceName, clusterName);
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();

    // check the instance is alive
    LiveInstance liveInstance = accessor.getProperty(keyBuilder.liveInstance(instanceName));
    if (liveInstance == null) {
      // check if the instance exists in the cluster
      String instanceConfigPath = PropertyPathBuilder.instanceConfig(clusterName, instanceName);
      throw new HelixException(String.format(
          (_zkClient.exists(instanceConfigPath) ? ResetPartitionFailureReason.INSTANCE_NOT_ALIVE
              : ResetPartitionFailureReason.INSTANCE_NON_EXISTENT)
              .getMessage(resourceName, partitionNames, instanceName, instanceName, clusterName)));
    }

    // check resource group exists
    IdealState idealState = accessor.getProperty(keyBuilder.idealStates(resourceName));
    if (idealState == null) {
      throw new HelixException(String.format(ResetPartitionFailureReason.RESOURCE_NON_EXISTENT
          .getMessage(resourceName, partitionNames, instanceName, resourceName, clusterName)));
    }

    // check partition exists in resource group
    Set<String> resetPartitionNames = new HashSet<String>(partitionNames);
    Set<String> partitions =
        (idealState.getRebalanceMode() == RebalanceMode.CUSTOMIZED) ? idealState.getRecord()
            .getMapFields().keySet() : idealState.getRecord().getListFields().keySet();
    if (!partitions.containsAll(resetPartitionNames)) {
      throw new HelixException(String.format(ResetPartitionFailureReason.PARTITION_NON_EXISTENT
          .getMessage(resourceName, partitionNames, instanceName, partitionNames.toString(),
              clusterName)));
    }

    // check partition is in ERROR state
    String sessionId = liveInstance.getEphemeralOwner();
    CurrentState curState =
        accessor.getProperty(keyBuilder.currentState(instanceName, sessionId, resourceName));
    for (String partitionName : resetPartitionNames) {
      if (!curState.getState(partitionName).equals(HelixDefinedState.ERROR.toString())) {
        throw new HelixException(String.format(ResetPartitionFailureReason.PARTITION_NOT_ERROR
            .getMessage(resourceName, partitionNames, instanceName, partitionNames.toString(),
                clusterName)));
      }
    }

    // check stateModelDef exists and get initial state
    String stateModelDef = idealState.getStateModelDefRef();
    StateModelDefinition stateModel = accessor.getProperty(keyBuilder.stateModelDef(stateModelDef));
    if (stateModel == null) {
      throw new HelixException(String.format(ResetPartitionFailureReason.STATE_MODEL_NON_EXISTENT
          .getMessage(resourceName, partitionNames, instanceName, stateModelDef, clusterName)));
    }

    // check there is no pending messages for the partitions exist
    List<Message> messages = accessor.getChildValues(keyBuilder.messages(instanceName), true);
    for (Message message : messages) {
      if (!MessageType.STATE_TRANSITION.name().equalsIgnoreCase(message.getMsgType()) || !sessionId
          .equals(message.getTgtSessionId()) || !resourceName.equals(message.getResourceName())
          || !resetPartitionNames.contains(message.getPartitionName())) {
        continue;
      }

      throw new HelixException(String.format(
          "Can't reset state for %s.%s on %s, because a pending message %s exists for resource %s",
          resourceName, partitionNames, instanceName, message.toString(),
          message.getResourceName()));
    }

    String adminName = null;
    try {
      adminName = InetAddress.getLocalHost().getCanonicalHostName() + "-ADMIN";
    } catch (UnknownHostException e) {
      // can ignore it
      logger.info("Unable to get host name. Will set it to UNKNOWN, mostly ignorable", e);
      adminName = "UNKNOWN";
    }

    List<Message> resetMessages = new ArrayList<Message>();
    List<PropertyKey> messageKeys = new ArrayList<PropertyKey>();
    for (String partitionName : resetPartitionNames) {
      // send ERROR to initialState message
      String msgId = UUID.randomUUID().toString();
      Message message = new Message(MessageType.STATE_TRANSITION, msgId);
      message.setSrcName(adminName);
      message.setTgtName(instanceName);
      message.setMsgState(MessageState.NEW);
      message.setPartitionName(partitionName);
      message.setResourceName(resourceName);
      message.setTgtSessionId(sessionId);
      message.setStateModelDef(stateModelDef);
      message.setFromState(HelixDefinedState.ERROR.toString());
      message.setToState(stateModel.getInitialState());
      message.setStateModelFactoryName(idealState.getStateModelFactoryName());

      if (idealState.getResourceGroupName() != null) {
        message.setResourceGroupName(idealState.getResourceGroupName());
      }
      if (idealState.getInstanceGroupTag() != null) {
        message.setResourceTag(idealState.getInstanceGroupTag());
      }

      resetMessages.add(message);
      messageKeys.add(keyBuilder.message(instanceName, message.getId()));
    }

    accessor.setChildren(messageKeys, resetMessages);
  }

  @Override
  public void resetInstance(String clusterName, List<String> instanceNames) {
    // TODO: not mp-safe
    logger.info("Reset instances {} in cluster {}.",
        instanceNames == null ? "NULL" : HelixUtil.serializeByComma(instanceNames), clusterName);
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    List<ExternalView> extViews = accessor.getChildValues(keyBuilder.externalViews(), true);

    Set<String> resetInstanceNames = new HashSet<String>(instanceNames);
    for (String instanceName : resetInstanceNames) {
      List<String> resetPartitionNames = new ArrayList<String>();
      for (ExternalView extView : extViews) {
        Map<String, Map<String, String>> stateMap = extView.getRecord().getMapFields();
        for (String partitionName : stateMap.keySet()) {
          Map<String, String> instanceStateMap = stateMap.get(partitionName);

          if (instanceStateMap.containsKey(instanceName) && instanceStateMap.get(instanceName)
              .equals(HelixDefinedState.ERROR.toString())) {
            resetPartitionNames.add(partitionName);
          }
        }
        resetPartition(clusterName, instanceName, extView.getResourceName(), resetPartitionNames);
      }
    }
  }

  @Override
  public void resetResource(String clusterName, List<String> resourceNames) {
    // TODO: not mp-safe
    logger.info("Reset resources {} in cluster {}.",
        resourceNames == null ? "NULL" : HelixUtil.serializeByComma(resourceNames), clusterName);
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    List<ExternalView> extViews = accessor.getChildValues(keyBuilder.externalViews(), true);

    Set<String> resetResourceNames = new HashSet<String>(resourceNames);
    for (ExternalView extView : extViews) {
      if (!resetResourceNames.contains(extView.getResourceName())) {
        continue;
      }

      // instanceName -> list of resetPartitionNames
      Map<String, List<String>> resetPartitionNames = new HashMap<String, List<String>>();

      Map<String, Map<String, String>> stateMap = extView.getRecord().getMapFields();
      for (String partitionName : stateMap.keySet()) {
        Map<String, String> instanceStateMap = stateMap.get(partitionName);
        for (String instanceName : instanceStateMap.keySet()) {
          if (instanceStateMap.get(instanceName).equals(HelixDefinedState.ERROR.toString())) {
            if (!resetPartitionNames.containsKey(instanceName)) {
              resetPartitionNames.put(instanceName, new ArrayList<String>());
            }
            resetPartitionNames.get(instanceName).add(partitionName);
          }
        }
      }

      for (String instanceName : resetPartitionNames.keySet()) {
        resetPartition(clusterName, instanceName, extView.getResourceName(),
            resetPartitionNames.get(instanceName));
      }
    }
  }

  @Override
  public boolean addCluster(String clusterName) {
    return addCluster(clusterName, false);
  }

  @Override
  public boolean addCluster(String clusterName, boolean recreateIfExists) {
    logger.info("Add cluster {}.", clusterName);
    String root = "/" + clusterName;

    if (_zkClient.exists(root)) {
      if (recreateIfExists) {
        logger.warn("Root directory exists.Cleaning the root directory:" + root);
        _zkClient.deleteRecursively(root);
      } else {
        logger.info("Cluster " + clusterName + " already exists");
        return true;
      }
    }
    try {
      _zkClient.createPersistent(root, true);
    } catch (Exception e) {
      // some other process might have created the cluster
      if (_zkClient.exists(root)) {
        return true;
      }
      logger.error("Error creating cluster:" + clusterName, e);
      return false;
    }
    try {
      createZKPaths(clusterName);
    } catch (Exception e) {
      logger.error("Error creating cluster:" + clusterName, e);
      return false;
    }
    logger.info("Created cluster:" + clusterName);
    return true;
  }

  private void createZKPaths(String clusterName) {
    String path;

    // IDEAL STATE
    _zkClient.createPersistent(PropertyPathBuilder.idealState(clusterName));
    // CONFIGURATIONS
    path = PropertyPathBuilder.clusterConfig(clusterName);
    _zkClient.createPersistent(path, true);
    _zkClient.writeData(path, new ZNRecord(clusterName));
    path = PropertyPathBuilder.instanceConfig(clusterName);
    _zkClient.createPersistent(path);
    path = PropertyPathBuilder.resourceConfig(clusterName);
    _zkClient.createPersistent(path);
    path = PropertyPathBuilder.customizedStateConfig(clusterName);
    _zkClient.createPersistent(path);
    // PROPERTY STORE
    path = PropertyPathBuilder.propertyStore(clusterName);
    _zkClient.createPersistent(path);
    // LIVE INSTANCES
    _zkClient.createPersistent(PropertyPathBuilder.liveInstance(clusterName));
    // MEMBER INSTANCES
    _zkClient.createPersistent(PropertyPathBuilder.instance(clusterName));
    // External view
    _zkClient.createPersistent(PropertyPathBuilder.externalView(clusterName));
    // State model definition
    _zkClient.createPersistent(PropertyPathBuilder.stateModelDef(clusterName));

    // controller
    _zkClient.createPersistent(PropertyPathBuilder.controller(clusterName));
    path = PropertyPathBuilder.controllerHistory(clusterName);
    final ZNRecord emptyHistory = new ZNRecord(PropertyType.HISTORY.toString());
    final List<String> emptyList = new ArrayList<String>();
    emptyHistory.setListField(clusterName, emptyList);
    _zkClient.createPersistent(path, emptyHistory);

    path = PropertyPathBuilder.controllerMessage(clusterName);
    _zkClient.createPersistent(path);

    path = PropertyPathBuilder.controllerStatusUpdate(clusterName);
    _zkClient.createPersistent(path);

    path = PropertyPathBuilder.controllerError(clusterName);
    _zkClient.createPersistent(path);
  }

  @Override
  public List<String> getInstancesInCluster(String clusterName) {
    String memberInstancesPath = PropertyPathBuilder.instance(clusterName);
    return _zkClient.getChildren(memberInstancesPath);
  }

  @Override
  public List<String> getInstancesInClusterWithTag(String clusterName, String tag) {
    String memberInstancesPath = PropertyPathBuilder.instance(clusterName);
    List<String> instances = _zkClient.getChildren(memberInstancesPath);
    List<String> result = new ArrayList<String>();

    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();

    for (String instanceName : instances) {
      InstanceConfig config = accessor.getProperty(keyBuilder.instanceConfig(instanceName));
      if (config == null) {
        throw new IllegalStateException(String
            .format("Instance %s does not have a config, cluster might be in bad state",
                instanceName));
      }
      if (config.containsTag(tag)) {
        result.add(instanceName);
      }
    }
    return result;
  }

  @Override
  public void addResource(String clusterName, String resourceName, int partitions,
      String stateModelRef) {
    addResource(clusterName, resourceName, partitions, stateModelRef,
        RebalanceMode.SEMI_AUTO.toString(), 0);
  }

  @Override
  public void addResource(String clusterName, String resourceName, int partitions,
      String stateModelRef, String rebalancerMode) {
    addResource(clusterName, resourceName, partitions, stateModelRef, rebalancerMode, 0);
  }

  @Override
  public void addResource(String clusterName, String resourceName, int partitions,
      String stateModelRef, String rebalancerMode, String rebalanceStrategy) {
    addResource(clusterName, resourceName, partitions, stateModelRef, rebalancerMode,
        rebalanceStrategy, 0, -1);
  }

  @Override
  public void addResource(String clusterName, String resourceName, IdealState idealstate) {
    logger.info("Add resource {} in cluster {}.", resourceName, clusterName);
    String stateModelRef = idealstate.getStateModelDefRef();
    String stateModelDefPath = PropertyPathBuilder.stateModelDef(clusterName, stateModelRef);
    if (!_zkClient.exists(stateModelDefPath)) {
      throw new HelixException(
          "State model " + stateModelRef + " not found in the cluster STATEMODELDEFS path");
    }

    String idealStatePath = PropertyPathBuilder.idealState(clusterName);
    String resourceIdealStatePath = idealStatePath + "/" + resourceName;
    if (_zkClient.exists(resourceIdealStatePath)) {
      throw new HelixException("Skip the operation. Resource ideal state directory already exists:"
          + resourceIdealStatePath);
    }

    ZKUtil.createChildren(_zkClient, idealStatePath, idealstate.getRecord());
  }

  @Override
  public void addResource(String clusterName, String resourceName, int partitions,
      String stateModelRef, String rebalancerMode, int bucketSize) {
    addResource(clusterName, resourceName, partitions, stateModelRef, rebalancerMode, bucketSize,
        -1);
  }

  @Override
  public void addResource(String clusterName, String resourceName, int partitions,
      String stateModelRef, String rebalancerMode, int bucketSize, int maxPartitionsPerInstance) {
    addResource(clusterName, resourceName, partitions, stateModelRef, rebalancerMode,
        RebalanceStrategy.DEFAULT_REBALANCE_STRATEGY, bucketSize, maxPartitionsPerInstance);
  }

  @Override
  public void addResource(String clusterName, String resourceName, int partitions,
      String stateModelRef, String rebalancerMode, String rebalanceStrategy, int bucketSize,
      int maxPartitionsPerInstance) {
    if (!ZKUtil.isClusterSetup(clusterName, _zkClient)) {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }

    IdealState idealState = new IdealState(resourceName);
    idealState.setNumPartitions(partitions);
    idealState.setStateModelDefRef(stateModelRef);
    RebalanceMode mode =
        idealState.rebalanceModeFromString(rebalancerMode, RebalanceMode.SEMI_AUTO);
    idealState.setRebalanceMode(mode);
    idealState.setRebalanceStrategy(rebalanceStrategy);
    idealState.setReplicas("" + 0);
    idealState.setStateModelFactoryName(HelixConstants.DEFAULT_STATE_MODEL_FACTORY);
    if (maxPartitionsPerInstance > 0 && maxPartitionsPerInstance < Integer.MAX_VALUE) {
      idealState.setMaxPartitionsPerInstance(maxPartitionsPerInstance);
    }
    if (bucketSize > 0) {
      idealState.setBucketSize(bucketSize);
    }
    addResource(clusterName, resourceName, idealState);
  }

  @Override
  public List<String> getClusters() {
    List<String> zkToplevelPaths;

    if (Boolean.getBoolean(SystemPropertyKeys.MULTI_ZK_ENABLED)
        || _zkClient instanceof FederatedZkClient) {
      // If on multi-zk mode, we retrieve cluster information from Metadata Store Directory Service.
      Map<String, List<String>> realmToShardingKeys;
      String routingDataSourceEndpoint =
          _zkClient.getRealmAwareZkConnectionConfig().getRoutingDataSourceEndpoint();
      if (routingDataSourceEndpoint == null || routingDataSourceEndpoint.isEmpty()) {
        // If endpoint is not given explicitly, use HTTP and the endpoint set in System Properties
        realmToShardingKeys = RoutingDataManager.getInstance().getRawRoutingData();
      } else {
        realmToShardingKeys = RoutingDataManager.getInstance().getRawRoutingData(
            RoutingDataReaderType
                .lookUp(_zkClient.getRealmAwareZkConnectionConfig().getRoutingDataSourceType()),
            routingDataSourceEndpoint);
      }

      if (realmToShardingKeys == null || realmToShardingKeys.isEmpty()) {
        return Collections.emptyList();
      }
      // Preceding "/"s are removed: e.g.) "/CLUSTER-SHARDING-KEY" -> "CLUSTER-SHARDING-KEY"
      zkToplevelPaths = realmToShardingKeys.values().stream().flatMap(List::stream)
          .map(shardingKey -> shardingKey.substring(1)).collect(Collectors.toList());
    } else {
      // single-zk mode
      zkToplevelPaths = _zkClient.getChildren("/");
    }

    List<String> result = new ArrayList<>();
    for (String pathName : zkToplevelPaths) {
      if (ZKUtil.isClusterSetup(pathName, _zkClient)) {
        result.add(pathName);
      }
    }
    return result;
  }

  @Override
  public List<String> getResourcesInCluster(String clusterName) {
    return _zkClient.getChildren(PropertyPathBuilder.idealState(clusterName));
  }

  @Override
  public List<String> getResourcesInClusterWithTag(String clusterName, String tag) {
    List<String> resourcesWithTag = new ArrayList<String>();

    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();

    for (String resourceName : getResourcesInCluster(clusterName)) {
      IdealState is = accessor.getProperty(keyBuilder.idealStates(resourceName));
      if (is != null && is.getInstanceGroupTag() != null && is.getInstanceGroupTag().equals(tag)) {
        resourcesWithTag.add(resourceName);
      }
    }

    return resourcesWithTag;
  }

  @Override
  public IdealState getResourceIdealState(String clusterName, String resourceName) {
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();

    return accessor.getProperty(keyBuilder.idealStates(resourceName));
  }

  @Override
  public void setResourceIdealState(String clusterName, String resourceName,
      IdealState idealState) {
    logger
        .info("Set IdealState for resource {} in cluster {} with new IdealState {}.", resourceName,
            clusterName, idealState == null ? "NULL" : idealState.toString());
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();

    accessor.setProperty(keyBuilder.idealStates(resourceName), idealState);
  }

  /**
   * Partially updates the fields appearing in the given IdealState (input).
   * @param clusterName
   * @param resourceName
   * @param idealState
   */
  @Override
  public void updateIdealState(String clusterName, String resourceName, IdealState idealState) {
    if (!ZKUtil.isClusterSetup(clusterName, _zkClient)) {
      throw new HelixException(
          "updateIdealState failed. Cluster: " + clusterName + " is NOT setup properly.");
    }
    String zkPath = PropertyPathBuilder.idealState(clusterName, resourceName);
    if (!_zkClient.exists(zkPath)) {
      throw new HelixException(String.format(
          "updateIdealState failed. The IdealState for the given resource does not already exist. Resource name: %s",
          resourceName));
    }
    // Update by way of merge
    ZKUtil.createOrUpdate(_zkClient, zkPath, idealState.getRecord(), true, true);
  }

  /**
   * Selectively removes fields appearing in the given IdealState (input) from the IdealState in ZK.
   * @param clusterName
   * @param resourceName
   * @param idealState
   */
  @Override
  public void removeFromIdealState(String clusterName, String resourceName, IdealState idealState) {
    String zkPath = PropertyPathBuilder.idealState(clusterName, resourceName);
    ZKUtil.subtract(_zkClient, zkPath, idealState.getRecord());
  }

  @Override
  public ExternalView getResourceExternalView(String clusterName, String resourceName) {
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    return accessor.getProperty(keyBuilder.externalView(resourceName));
  }

  @Override
  public CustomizedView getResourceCustomizedView(String clusterName, String resourceName,
      String customizedStateType) {
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    return accessor.getProperty(keyBuilder.customizedView(customizedStateType, resourceName));
  }

  @Override
  public void addStateModelDef(String clusterName, String stateModelDef,
      StateModelDefinition stateModel) {
    addStateModelDef(clusterName, stateModelDef, stateModel, false);
  }

  @Override
  public void addStateModelDef(String clusterName, String stateModelDef,
      StateModelDefinition stateModel, boolean recreateIfExists) {
    logger
        .info("Add StateModelDef {} in cluster {} with StateModel {}.", stateModelDef, clusterName,
            stateModel == null ? "NULL" : stateModel.toString());
    if (!ZKUtil.isClusterSetup(clusterName, _zkClient)) {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }
    String stateModelDefPath = PropertyPathBuilder.stateModelDef(clusterName);
    String stateModelPath = stateModelDefPath + "/" + stateModelDef;
    if (_zkClient.exists(stateModelPath)) {
      if (recreateIfExists) {
        logger.info(
            "Operation.State Model directory exists:" + stateModelPath + ", remove and recreate.");
        _zkClient.deleteRecursively(stateModelPath);
      } else {
        logger.info("Skip the operation. State Model directory exists:" + stateModelPath);
        return;
      }
    }

    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    accessor.setProperty(keyBuilder.stateModelDef(stateModelDef), stateModel);
  }

  @Override
  public void dropResource(String clusterName, String resourceName) {
    logger.info("Drop resource {} from cluster {}", resourceName, clusterName);
    if (!ZKUtil.isClusterSetup(clusterName, _zkClient)) {
      throw new HelixException("Cluster " + clusterName + " is not setup yet");
    }
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();

    accessor.removeProperty(keyBuilder.idealStates(resourceName));
    accessor.removeProperty(keyBuilder.resourceConfig(resourceName));
  }

  @Override
  public void addCloudConfig(String clusterName, CloudConfig cloudConfig) {
    logger.info("Add CloudConfig to cluster {}, CloudConfig is {}.", clusterName,
        cloudConfig.toString());

    if (!ZKUtil.isClusterSetup(clusterName, _zkClient)) {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }

    CloudConfig.Builder builder = new CloudConfig.Builder(cloudConfig);
    CloudConfig cloudConfigBuilder = builder.build();

    ZKHelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    accessor.setProperty(keyBuilder.cloudConfig(), cloudConfigBuilder);
  }

  @Override
  public void removeCloudConfig(String clusterName) {
    logger.info("Remove Cloud Config for cluster {}.", clusterName);
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    accessor.removeProperty(keyBuilder.cloudConfig());
  }

  @Override
  public ClusterTopology getClusterTopology(String clusterName) {
    Map<String, InstanceConfig> instanceConfigMap = new HashMap<>();
    String path = PropertyPathBuilder.instanceConfig(clusterName);
    BaseDataAccessor<ZNRecord> baseAccessor = new ZkBaseDataAccessor<>(_zkClient);
    List<ZNRecord> znRecords = baseAccessor.getChildren(path, null, 0, 0, 0);
    for (ZNRecord record : znRecords) {
      if (record != null) {
        InstanceConfig instanceConfig = new InstanceConfig(record);
        instanceConfigMap.put(instanceConfig.getInstanceName(), instanceConfig);
      }
    }
    path = PropertyPathBuilder.liveInstance(clusterName);
    List<String> liveNodes = baseAccessor.getChildNames(path, 0);
    ConfigAccessor configAccessor = new ConfigAccessor(_zkClient);
    ClusterConfig clusterConfig = configAccessor.getClusterConfig(clusterName);
    return new ClusterTopology(liveNodes, instanceConfigMap, clusterConfig);
  }

  @Override
  public List<String> getStateModelDefs(String clusterName) {
    return _zkClient.getChildren(PropertyPathBuilder.stateModelDef(clusterName));
  }

  @Override
  public StateModelDefinition getStateModelDef(String clusterName, String stateModelName) {
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();

    return accessor.getProperty(keyBuilder.stateModelDef(stateModelName));
  }

  @Override
  public void dropCluster(String clusterName) {
    logger.info("Deleting cluster {}.", clusterName);
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();

    String root = "/" + clusterName;
    if (accessor.getChildNames(keyBuilder.liveInstances()).size() > 0) {
      throw new HelixException(
          "There are still live instances in the cluster, shut them down first.");
    }

    if (accessor.getProperty(keyBuilder.controllerLeader()) != null) {
      throw new HelixException("There are still LEADER in the cluster, shut them down first.");
    }

    _zkClient.deleteRecursively(root);
  }

  @Override
  public void addClusterToGrandCluster(String clusterName, String grandCluster) {
    logger.info("Add cluster {} to grand cluster {}.", clusterName, grandCluster);
    if (!ZKUtil.isClusterSetup(grandCluster, _zkClient)) {
      throw new HelixException("Grand cluster " + grandCluster + " is not setup yet");
    }

    if (!ZKUtil.isClusterSetup(clusterName, _zkClient)) {
      throw new HelixException("Cluster " + clusterName + " is not setup yet");
    }

    IdealState idealState = new IdealState(clusterName);

    idealState.setNumPartitions(1);
    idealState.setStateModelDefRef("LeaderStandby");
    idealState.setRebalanceMode(RebalanceMode.FULL_AUTO);
    idealState.setRebalancerClassName(WagedRebalancer.class.getName());
    // TODO: Give user an option, say from RestAPI to config the number of replicas.
    idealState.setReplicas(Integer.toString(DEFAULT_SUPERCLUSTER_REPLICA));
    idealState.getRecord().setListField(clusterName, new ArrayList<>());

    List<String> controllers = getInstancesInCluster(grandCluster);
    if (controllers.size() == 0) {
      throw new HelixException("Grand cluster " + grandCluster + " has no instances");
    }

    ZKHelixDataAccessor accessor =
        new ZKHelixDataAccessor(grandCluster, new ZkBaseDataAccessor<>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();

    accessor.setProperty(keyBuilder.idealStates(idealState.getResourceName()), idealState);
    LOG.info("Cluster {} has been added to grand cluster {} with rebalance configuration {}.",
        clusterName, grandCluster, idealState.getRecord().getSimpleFields().toString());
  }

  @Override
  public void setConfig(HelixConfigScope scope, Map<String, String> properties) {
    logger.info("Set configs with keys ");
    _configAccessor.set(scope, properties);
  }

  @Override
  public Map<String, String> getConfig(HelixConfigScope scope, List<String> keys) {
    return _configAccessor.get(scope, keys);
  }

  @Override
  public void addCustomizedStateConfig(String clusterName,
      CustomizedStateConfig customizedStateConfig) {
    logger.info(
        "Add CustomizedStateConfig to cluster {}, CustomizedStateConfig is {}",
        clusterName, customizedStateConfig.toString());

    if (!ZKUtil.isClusterSetup(clusterName, _zkClient)) {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }

    CustomizedStateConfig.Builder builder =
        new CustomizedStateConfig.Builder(customizedStateConfig);
    CustomizedStateConfig customizedStateConfigFromBuilder = builder.build();

    ZKHelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    accessor.setProperty(keyBuilder.customizedStateConfig(),
        customizedStateConfigFromBuilder);
  }

  @Override
  public void removeCustomizedStateConfig(String clusterName) {
    logger.info(
        "Remove CustomizedStateConfig from cluster {}.", clusterName);

    ZKHelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    accessor.removeProperty(keyBuilder.customizedStateConfig());

  }

  @Override
  public void addTypeToCustomizedStateConfig(String clusterName, String type) {
    logger.info("Add type {} to CustomizedStateConfig of cluster {}", type, clusterName);

    if (!ZKUtil.isClusterSetup(clusterName, _zkClient)) {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }
    CustomizedStateConfig.Builder builder =
        new CustomizedStateConfig.Builder();

    builder.addAggregationEnabledType(type);
    CustomizedStateConfig customizedStateConfigFromBuilder = builder.build();

    ZKHelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    if(!accessor.updateProperty(keyBuilder.customizedStateConfig(),
        customizedStateConfigFromBuilder)) {
      throw new HelixException(
          "Failed to add customized state config type " + type + " to cluster" + clusterName);
    }
  }


  @Override
  public void removeTypeFromCustomizedStateConfig(String clusterName, String type) {
    logger.info("Remove type {} to CustomizedStateConfig of cluster {}", type,
        clusterName);

    if (!ZKUtil.isClusterSetup(clusterName, _zkClient)) {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }

    CustomizedStateConfig.Builder builder = new CustomizedStateConfig.Builder(
        _configAccessor.getCustomizedStateConfig(clusterName));

    if (!builder.getAggregationEnabledTypes().contains(type)) {
      throw new HelixException("Type " + type
          + " is missing from the CustomizedStateConfig of cluster " + clusterName);
    }

    builder.removeAggregationEnabledType(type);
    CustomizedStateConfig customizedStateConfigFromBuilder = builder.build();
    ZKHelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    accessor.setProperty(keyBuilder.customizedStateConfig(),
        customizedStateConfigFromBuilder);
  }

  @Override
  public List<String> getConfigKeys(HelixConfigScope scope) {
    return _configAccessor.getKeys(scope);
  }

  @Override
  public void removeConfig(HelixConfigScope scope, List<String> keys) {
    _configAccessor.remove(scope, keys);
  }

  @Override
  public void rebalance(String clusterName, String resourceName, int replica) {
    rebalance(clusterName, resourceName, replica, resourceName, "");
  }

  @Override
  public void rebalance(String clusterName, String resourceName, int replica, String keyPrefix,
      String group) {
    List<String> instanceNames = new LinkedList<String>();
    if (keyPrefix == null || keyPrefix.length() == 0) {
      keyPrefix = resourceName;
    }
    if (group != null && group.length() > 0) {
      instanceNames = getInstancesInClusterWithTag(clusterName, group);
    }
    if (instanceNames.size() == 0) {
      logger.info("No tags found for resource " + resourceName + ", use all instances");
      instanceNames = getInstancesInCluster(clusterName);
      group = "";
    } else {
      logger.info("Found instances with tag for " + resourceName + " " + instanceNames);
    }
    rebalance(clusterName, resourceName, replica, keyPrefix, instanceNames, group);
  }

  @Override
  public void rebalance(String clusterName, String resourceName, int replica,
      List<String> instances) {
    rebalance(clusterName, resourceName, replica, resourceName, instances, "");
  }

  void rebalance(String clusterName, String resourceName, int replica, String keyPrefix,
      List<String> instanceNames, String groupId) {
    logger.info("Rebalance resource {} with replica {} in cluster {}.", resourceName, replica,
        clusterName);
    // ensure we get the same idealState with the same set of instances
    Collections.sort(instanceNames);

    IdealState idealState = getResourceIdealState(clusterName, resourceName);
    if (idealState == null) {
      throw new HelixException("Resource: " + resourceName + " has NOT been added yet");
    }

    if (groupId != null && groupId.length() > 0) {
      idealState.setInstanceGroupTag(groupId);
    }
    idealState.setReplicas(Integer.toString(replica));
    int partitions = idealState.getNumPartitions();
    String stateModelName = idealState.getStateModelDefRef();
    StateModelDefinition stateModDef = getStateModelDef(clusterName, stateModelName);

    if (stateModDef == null) {
      throw new HelixException("cannot find state model: " + stateModelName);
    }
    // StateModelDefinition def = new StateModelDefinition(stateModDef);

    List<String> statePriorityList = stateModDef.getStatesPriorityList();

    String masterStateValue = null;
    String slaveStateValue = null;
    replica--;

    for (String state : statePriorityList) {
      String count = stateModDef.getNumInstancesPerState(state);
      if (count.equals("1")) {
        if (masterStateValue != null) {
          throw new HelixException("Invalid or unsupported state model definition");
        }
        masterStateValue = state;
      } else if (count.equalsIgnoreCase("R")) {
        if (slaveStateValue != null) {
          throw new HelixException("Invalid or unsupported state model definition");
        }
        slaveStateValue = state;
      } else if (count.equalsIgnoreCase("N")) {
        if (!(masterStateValue == null && slaveStateValue == null)) {
          throw new HelixException("Invalid or unsupported state model definition");
        }
        replica = instanceNames.size() - 1;
        masterStateValue = slaveStateValue = state;
      }
    }
    if (masterStateValue == null && slaveStateValue == null) {
      throw new HelixException("Invalid or unsupported state model definition");
    }

    if (masterStateValue == null) {
      masterStateValue = slaveStateValue;
    }
    if (idealState.getRebalanceMode() != RebalanceMode.FULL_AUTO
        && idealState.getRebalanceMode() != RebalanceMode.USER_DEFINED) {
      ZNRecord newIdealState = DefaultIdealStateCalculator
          .calculateIdealState(instanceNames, partitions, replica, keyPrefix, masterStateValue,
              slaveStateValue);

      // for now keep mapField in SEMI_AUTO mode and remove listField in CUSTOMIZED mode
      if (idealState.getRebalanceMode() == RebalanceMode.SEMI_AUTO) {
        idealState.getRecord().setListFields(newIdealState.getListFields());
        // TODO: need consider to remove this.
        idealState.getRecord().setMapFields(newIdealState.getMapFields());
      }
      if (idealState.getRebalanceMode() == RebalanceMode.CUSTOMIZED) {
        idealState.getRecord().setMapFields(newIdealState.getMapFields());
      }
    } else {
      for (int i = 0; i < partitions; i++) {
        String partitionName = keyPrefix + "_" + i;
        idealState.getRecord().setMapField(partitionName, new HashMap<String, String>());
        idealState.getRecord().setListField(partitionName, new ArrayList<String>());
      }
    }
    setResourceIdealState(clusterName, resourceName, idealState);
  }

  @Override
  public void addIdealState(String clusterName, String resourceName, String idealStateFile)
      throws IOException {
    logger.info("Add IdealState for resource {} to cluster {} by file name {}.", resourceName,
        clusterName, idealStateFile);
    ZNRecord idealStateRecord =
        (ZNRecord) (new ZNRecordSerializer().deserialize(readFile(idealStateFile)));
    if (idealStateRecord.getId() == null || !idealStateRecord.getId().equals(resourceName)) {
      throw new IllegalArgumentException("ideal state must have same id as resource name");
    }
    setResourceIdealState(clusterName, resourceName, new IdealState(idealStateRecord));
  }

  private static byte[] readFile(String filePath)
      throws IOException {
    File file = new File(filePath);

    int size = (int) file.length();
    byte[] bytes = new byte[size];
    DataInputStream dis = null;
    try {
      dis = new DataInputStream(new FileInputStream(file));
      int read = 0;
      int numRead = 0;
      while (read < bytes.length && (numRead = dis.read(bytes, read, bytes.length - read)) >= 0) {
        read = read + numRead;
      }
      return bytes;
    } finally {
      if (dis != null) {
        dis.close();
      }
    }
  }

  @Override
  public void addStateModelDef(String clusterName, String stateModelDefName,
      String stateModelDefFile)
      throws IOException {
    ZNRecord record =
        (ZNRecord) (new ZNRecordSerializer().deserialize(readFile(stateModelDefFile)));
    if (record == null || record.getId() == null || !record.getId().equals(stateModelDefName)) {
      throw new IllegalArgumentException(
          "state model definition must have same id as state model def name");
    }
    addStateModelDef(clusterName, stateModelDefName, new StateModelDefinition(record), false);
  }

  @Override
  public void setConstraint(String clusterName, final ConstraintType constraintType,
      final String constraintId, final ConstraintItem constraintItem) {
    logger.info("Set constraint type {} with constraint id {} for cluster {}.", constraintType,
        constraintId, clusterName);
    BaseDataAccessor<ZNRecord> baseAccessor = new ZkBaseDataAccessor<ZNRecord>(_zkClient);

    PropertyKey.Builder keyBuilder = new PropertyKey.Builder(clusterName);
    String path = keyBuilder.constraint(constraintType.toString()).getPath();

    baseAccessor.update(path, new DataUpdater<ZNRecord>() {
      @Override
      public ZNRecord update(ZNRecord currentData) {
        ClusterConstraints constraints =
            currentData == null ? new ClusterConstraints(constraintType)
                : new ClusterConstraints(currentData);

        constraints.addConstraintItem(constraintId, constraintItem);
        return constraints.getRecord();
      }
    }, AccessOption.PERSISTENT);
  }

  @Override
  public void removeConstraint(String clusterName, final ConstraintType constraintType,
      final String constraintId) {
    logger.info("Remove constraint type {} with constraint id {} for cluster {}.", constraintType,
        constraintId, clusterName);
    BaseDataAccessor<ZNRecord> baseAccessor = new ZkBaseDataAccessor<ZNRecord>(_zkClient);

    PropertyKey.Builder keyBuilder = new PropertyKey.Builder(clusterName);
    String path = keyBuilder.constraint(constraintType.toString()).getPath();

    baseAccessor.update(path, new DataUpdater<ZNRecord>() {
      @Override
      public ZNRecord update(ZNRecord currentData) {
        if (currentData != null) {
          ClusterConstraints constraints = new ClusterConstraints(currentData);

          constraints.removeConstraintItem(constraintId);
          return constraints.getRecord();
        }
        return null;
      }
    }, AccessOption.PERSISTENT);
  }

  @Override
  public ClusterConstraints getConstraints(String clusterName, ConstraintType constraintType) {
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));

    PropertyKey.Builder keyBuilder = new PropertyKey.Builder(clusterName);
    return accessor.getProperty(keyBuilder.constraint(constraintType.toString()));
  }

  /**
   * Takes the existing idealstate as input and computes newIdealState such that the partition
   * movement is minimized. The partitions are redistributed among the instances provided.
   *
   * @param clusterName
   * @param currentIdealState
   * @param instanceNames
   * @return
   */
  @Override
  public void rebalance(String clusterName, IdealState currentIdealState,
      List<String> instanceNames) {
    logger.info("Rebalance resource {} in cluster {} with IdealState {}.",
        currentIdealState.getResourceName(), clusterName,
        currentIdealState == null ? "NULL" : currentIdealState.toString());
    Set<String> activeInstances = new HashSet<String>();
    for (String partition : currentIdealState.getPartitionSet()) {
      activeInstances.addAll(currentIdealState.getRecord().getListField(partition));
    }
    instanceNames.removeAll(activeInstances);
    Map<String, Object> previousIdealState =
        RebalanceUtil.buildInternalIdealState(currentIdealState);

    Map<String, Object> balancedRecord =
        DefaultIdealStateCalculator.calculateNextIdealState(instanceNames, previousIdealState);
    StateModelDefinition stateModDef =
        this.getStateModelDef(clusterName, currentIdealState.getStateModelDefRef());

    if (stateModDef == null) {
      throw new HelixException(
          "cannot find state model: " + currentIdealState.getStateModelDefRef());
    }
    String[] states = RebalanceUtil.parseStates(clusterName, stateModDef);

    ZNRecord newIdealStateRecord = DefaultIdealStateCalculator
        .convertToZNRecord(balancedRecord, currentIdealState.getResourceName(), states[0],
            states[1]);
    Set<String> partitionSet = new HashSet<String>();
    partitionSet.addAll(newIdealStateRecord.getMapFields().keySet());
    partitionSet.addAll(newIdealStateRecord.getListFields().keySet());

    Map<String, String> reversePartitionIndex =
        (Map<String, String>) balancedRecord.get("reversePartitionIndex");
    for (String partition : partitionSet) {
      if (reversePartitionIndex.containsKey(partition)) {
        String originPartitionName = reversePartitionIndex.get(partition);
        if (partition.equals(originPartitionName)) {
          continue;
        }
        newIdealStateRecord.getMapFields()
            .put(originPartitionName, newIdealStateRecord.getMapField(partition));
        newIdealStateRecord.getMapFields().remove(partition);

        newIdealStateRecord.getListFields()
            .put(originPartitionName, newIdealStateRecord.getListField(partition));
        newIdealStateRecord.getListFields().remove(partition);
      }
    }

    newIdealStateRecord.getSimpleFields().putAll(currentIdealState.getRecord().getSimpleFields());
    IdealState newIdealState = new IdealState(newIdealStateRecord);
    setResourceIdealState(clusterName, newIdealStateRecord.getId(), newIdealState);
  }

  @Override
  public void addInstanceTag(String clusterName, String instanceName, String tag) {
    logger
        .info("Add instance tag {} for instance {} in cluster {}.", tag, instanceName, clusterName);
    if (!ZKUtil.isClusterSetup(clusterName, _zkClient)) {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }

    if (!ZKUtil.isInstanceSetup(_zkClient, clusterName, instanceName, InstanceType.PARTICIPANT)) {
      throw new HelixException(
          "cluster " + clusterName + " instance " + instanceName + " is not setup yet");
    }
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();

    InstanceConfig config = accessor.getProperty(keyBuilder.instanceConfig(instanceName));
    config.addTag(tag);
    accessor.setProperty(keyBuilder.instanceConfig(instanceName), config);
  }

  @Override
  public void removeInstanceTag(String clusterName, String instanceName, String tag) {
    logger.info("Remove instance tag {} for instance {} in cluster {}.", tag, instanceName,
        clusterName);
    if (!ZKUtil.isClusterSetup(clusterName, _zkClient)) {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }

    if (!ZKUtil.isInstanceSetup(_zkClient, clusterName, instanceName, InstanceType.PARTICIPANT)) {
      throw new HelixException(
          "cluster " + clusterName + " instance " + instanceName + " is not setup yet");
    }
    ZKHelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();

    InstanceConfig config = accessor.getProperty(keyBuilder.instanceConfig(instanceName));
    config.removeTag(tag);
    accessor.setProperty(keyBuilder.instanceConfig(instanceName), config);
  }

  @Override
  public void setInstanceZoneId(String clusterName, String instanceName, String zoneId) {
    logger.info("Set instance zoneId {} for instance {} in cluster {}.", zoneId, instanceName,
        clusterName);
    if (!ZKUtil.isClusterSetup(clusterName, _zkClient)) {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }

    if (!ZKUtil.isInstanceSetup(_zkClient, clusterName, instanceName, InstanceType.PARTICIPANT)) {
      throw new HelixException(
          "cluster " + clusterName + " instance " + instanceName + " is not setup yet");
    }
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();

    InstanceConfig config = accessor.getProperty(keyBuilder.instanceConfig(instanceName));
    config.setZoneId(zoneId);
    accessor.setProperty(keyBuilder.instanceConfig(instanceName), config);
  }

  @Override
  public void enableBatchMessageMode(String clusterName, boolean enabled) {
    logger
        .info("{} batch message mode for cluster {}.", enabled ? "Enable" : "Disable", clusterName);
    if (!ZKUtil.isClusterSetup(clusterName, _zkClient)) {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }
    ConfigAccessor accessor = new ConfigAccessor(_zkClient);

    ClusterConfig clusterConfig = accessor.getClusterConfig(clusterName);
    clusterConfig.setBatchMessageMode(enabled);
    accessor.setClusterConfig(clusterName, clusterConfig);
  }

  @Override
  public void enableBatchMessageMode(String clusterName, String resourceName, boolean enabled) {
    logger.info("{} batch message mode for resource {} in cluster {}.",
        enabled ? "Enable" : "Disable", resourceName, clusterName);
    // TODO: Change IdealState to ResourceConfig when configs are migrated to ResourceConfig
    IdealState idealState = getResourceIdealState(clusterName, resourceName);
    if (idealState == null) {
      throw new HelixException("Cluster " + clusterName + ", resource: " + resourceName
          + ", ideal-state does not exist");
    }

    idealState.setBatchMessageMode(enabled);
    setResourceIdealState(clusterName, resourceName, idealState);
  }

  private void enableSingleInstance(final String clusterName, final String instanceName,
      final boolean enabled, BaseDataAccessor<ZNRecord> baseAccessor,
      InstanceConstants.InstanceDisabledType disabledType, String reason) {
    String path = PropertyPathBuilder.instanceConfig(clusterName, instanceName);

    if (!baseAccessor.exists(path, 0)) {
      throw new HelixException("Cluster " + clusterName + ", instance: " + instanceName
          + ", instance config does not exist");
    }

    baseAccessor.update(path, new DataUpdater<ZNRecord>() {
      @Override
      public ZNRecord update(ZNRecord currentData) {
        if (currentData == null) {
          throw new HelixException("Cluster: " + clusterName + ", instance: " + instanceName
              + ", participant config is null");
        }

        InstanceConfig config = new InstanceConfig(currentData);
        config.setInstanceEnabled(enabled);
        if (!enabled) {
          if (reason != null) {
            config.setInstanceDisabledReason(reason);
          }
          if (disabledType != null) {
            config.setInstanceDisabledType(disabledType);
          }
        }
        return config.getRecord();
      }
    }, AccessOption.PERSISTENT);
  }

  private void enableBatchInstances(final String clusterName, final List<String> instances,
      final boolean enabled, BaseDataAccessor<ZNRecord> baseAccessor) {
    // TODO : Due to Espresso storage node depends on map field. Current disable the feature now
    // include tests.
    if (true) {
      throw new HelixException("Current batch enable/disable instances are temporarily disabled!");
    }

    String path = PropertyPathBuilder.clusterConfig(clusterName);

    if (!baseAccessor.exists(path, 0)) {
      throw new HelixException("Cluster " + clusterName + ": cluster config does not exist");
    }

    baseAccessor.update(path, new DataUpdater<ZNRecord>() {
      @Override
      public ZNRecord update(ZNRecord currentData) {
        if (currentData == null) {
          throw new HelixException("Cluster: " + clusterName + ": cluster config is null");
        }

        ClusterConfig clusterConfig = new ClusterConfig(currentData);
        Map<String, String> disabledInstances = new TreeMap<>();
        if (clusterConfig.getDisabledInstances() != null) {
          disabledInstances.putAll(clusterConfig.getDisabledInstances());
        }

        if (enabled) {
          disabledInstances.keySet().removeAll(instances);
        } else {
          for (String disabledInstance : instances) {
            if (!disabledInstances.containsKey(disabledInstance)) {
              disabledInstances.put(disabledInstance, String.valueOf(System.currentTimeMillis()));
            }
          }
        }
        clusterConfig.setDisabledInstances(disabledInstances);

        return clusterConfig.getRecord();
      }
    }, AccessOption.PERSISTENT);
  }

  @Override
  public Map<String, String> getBatchDisabledInstances(String clusterName) {
    ConfigAccessor accessor = new ConfigAccessor(_zkClient);
    return accessor.getClusterConfig(clusterName).getDisabledInstances();
  }

  @Override
  public List<String> getInstancesByDomain(String clusterName, String domain) {
    List<String> instances = new ArrayList<>();
    String path = PropertyPathBuilder.instanceConfig(clusterName);
    BaseDataAccessor<ZNRecord> baseAccessor = new ZkBaseDataAccessor<>(_zkClient);
    List<ZNRecord> znRecords = baseAccessor.getChildren(path, null, 0, 0, 0);
    for (ZNRecord record : znRecords) {
      if (record != null) {
        InstanceConfig instanceConfig = new InstanceConfig(record);
        if (instanceConfig.isInstanceInDomain(domain)) {
          instances.add(instanceConfig.getInstanceName());
        }
      }
    }
    return instances;
  }

  /**
   * Closes the ZkClient only if it was generated internally.
   */
  @Override
  public void close() {
    if (_zkClient != null && !_usesExternalZkClient) {
      _zkClient.close();
    }
  }

  @Override
  public void finalize() {
    close();
  }

  @Override
  public boolean addResourceWithWeight(String clusterName, IdealState idealState,
      ResourceConfig resourceConfig) {
    // Null checks
    if (clusterName == null || clusterName.isEmpty()) {
      throw new HelixException("Cluster name is null or empty!");
    }
    if (idealState == null || !idealState.isValid()) {
      throw new HelixException("IdealState is null or invalid!");
    }
    if (resourceConfig == null || !resourceConfig.isValid()) {
      // TODO This might be okay because of default weight?
      throw new HelixException("ResourceConfig is null or invalid!");
    }

    // Make sure IdealState and ResourceConfig are for the same resource
    if (!idealState.getResourceName().equals(resourceConfig.getResourceName())) {
      throw new HelixException("Resource names in IdealState and ResourceConfig are different!");
    }

    // Order in which a resource should be added:
    // 1. Validate the weights in ResourceConfig against ClusterConfig
    // Check that all capacity keys in ClusterConfig are set up in every partition in ResourceConfig field
    if (!validateWeightForResourceConfig(_configAccessor.getClusterConfig(clusterName),
        resourceConfig, idealState)) {
      throw new HelixException(String
          .format("Could not add resource %s with weight! Failed to validate the ResourceConfig!",
              idealState.getResourceName()));
    }

    // 2. Add the resourceConfig to ZK
    _configAccessor
        .setResourceConfig(clusterName, resourceConfig.getResourceName(), resourceConfig);

    // 3. Add the idealState to ZK
    setResourceIdealState(clusterName, idealState.getResourceName(), idealState);

    // 4. rebalance the resource
    rebalance(clusterName, idealState.getResourceName(), Integer.parseInt(idealState.getReplicas()),
        idealState.getResourceName(), idealState.getInstanceGroupTag());

    return true;
  }

  @Override
  public boolean enableWagedRebalance(String clusterName, List<String> resourceNames) {
    // Null checks
    if (clusterName == null || clusterName.isEmpty()) {
      throw new HelixException("Cluster name is invalid!");
    }
    if (resourceNames == null || resourceNames.isEmpty()) {
      throw new HelixException("Resource name list is invalid!");
    }

    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    List<IdealState> enabledIdealStates = new ArrayList<>();
    List<PropertyKey> enabledIdealStateKeys = new ArrayList<>();
    Set<String> enabledResourceNames = new HashSet<>();

    List<IdealState> idealStates = accessor.getChildValues(keyBuilder.idealStates(), true);
    for (IdealState idealState : idealStates) {
      if (idealState != null && resourceNames.contains(idealState.getResourceName())) {
        idealState.setRebalancerClassName(WagedRebalancer.class.getName());
        idealState.setRebalanceMode(RebalanceMode.FULL_AUTO);
        enabledIdealStates.add(idealState);
        enabledIdealStateKeys.add(keyBuilder.idealStates(idealState.getResourceName()));
        enabledResourceNames.add(idealState.getResourceName());
      }
    }
    List<String> resourcesNotFound =
        resourceNames.stream().filter(resourceName -> !enabledResourceNames.contains(resourceName))
            .collect(Collectors.toList());
    if (!resourcesNotFound.isEmpty()) {
      throw new HelixException(
          String.format("Some resources do not have IdealStates: %s", resourcesNotFound));
    }
    boolean[] success = accessor.setChildren(enabledIdealStateKeys, enabledIdealStates);
    for (boolean s : success) {
      if (!s) {
        return false;
      }
    }
    return true;
  }

  @Override
  public Map<String, Boolean> validateResourcesForWagedRebalance(String clusterName,
      List<String> resourceNames) {
    // Null checks
    if (clusterName == null || clusterName.isEmpty()) {
      throw new HelixException("Cluster name is invalid!");
    }
    if (resourceNames == null || resourceNames.isEmpty()) {
      throw new HelixException("Resource name list is invalid!");
    }

    // Ensure that all instances are valid
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    List<String> instances = accessor.getChildNames(keyBuilder.instanceConfigs());
    if (validateInstancesForWagedRebalance(clusterName, instances).containsValue(false)) {
      throw new HelixException(String
          .format("Instance capacities haven't been configured properly for cluster %s",
              clusterName));
    }

    Map<String, Boolean> result = new HashMap<>();
    ClusterConfig clusterConfig = _configAccessor.getClusterConfig(clusterName);
    for (String resourceName : resourceNames) {
      IdealState idealState = getResourceIdealState(clusterName, resourceName);
      if (idealState == null || !idealState.isValid()) {
        result.put(resourceName, false);
        continue;
      }
      ResourceConfig resourceConfig = _configAccessor.getResourceConfig(clusterName, resourceName);
      result.put(resourceName,
          validateWeightForResourceConfig(clusterConfig, resourceConfig, idealState));
    }
    return result;
  }

  @Override
  public Map<String, Boolean> validateInstancesForWagedRebalance(String clusterName,
      List<String> instanceNames) {
    // Null checks
    if (clusterName == null || clusterName.isEmpty()) {
      throw new HelixException("Cluster name is invalid!");
    }
    if (instanceNames == null || instanceNames.isEmpty()) {
      throw new HelixException("Instance name list is invalid!");
    }

    Map<String, Boolean> result = new HashMap<>();
    ClusterConfig clusterConfig = _configAccessor.getClusterConfig(clusterName);
    for (String instanceName : instanceNames) {
      InstanceConfig instanceConfig = _configAccessor.getInstanceConfig(clusterName, instanceName);
      if (instanceConfig == null || !instanceConfig.isValid()) {
        result.put(instanceName, false);
        continue;
      }
      WagedValidationUtil.validateAndGetInstanceCapacity(clusterConfig, instanceConfig);
      result.put(instanceName, true);
    }

    return result;
  }

  /**
   * Validates ResourceConfig's weight field against the given ClusterConfig.
   * @param clusterConfig
   * @param resourceConfig
   * @param idealState
   * @return true if ResourceConfig has all the required fields. False otherwise.
   */
  private boolean validateWeightForResourceConfig(ClusterConfig clusterConfig,
      ResourceConfig resourceConfig, IdealState idealState) {
    if (resourceConfig == null) {
      if (clusterConfig.getDefaultPartitionWeightMap().isEmpty()) {
        logger.error(
            "ResourceConfig for {} is null, and there are no default weights set in ClusterConfig!",
            idealState.getResourceName());
        return false;
      }
      // If ResourceConfig is null AND the default partition weight map is defined, and the map has all the required keys, we consider this valid since the default weights will be used
      // Need to check the map contains all the required keys
      if (clusterConfig.getDefaultPartitionWeightMap().keySet()
          .containsAll(clusterConfig.getInstanceCapacityKeys())) {
        // Contains all the required keys, so consider it valid since it will use the default weights
        return true;
      }
      logger.error(
          "ResourceConfig for {} is null, and ClusterConfig's default partition weight map doesn't have all the required keys!",
          idealState.getResourceName());
      return false;
    }

    // Parse the entire capacityMap from ResourceConfig
    Map<String, Map<String, Integer>> capacityMap;
    try {
      capacityMap = resourceConfig.getPartitionCapacityMap();
    } catch (IOException ex) {
      logger.error("Invalid partition capacity configuration of resource: {}",
          idealState.getResourceName(), ex);
      return false;
    }

    Set<String> capacityMapSet = new HashSet<>(capacityMap.keySet());
    boolean hasDefaultCapacity = capacityMapSet.contains(ResourceConfig.DEFAULT_PARTITION_KEY);
    // Remove DEFAULT key
    capacityMapSet.remove(ResourceConfig.DEFAULT_PARTITION_KEY);

    // Make sure capacityMap contains all partitions defined in IdealState
    // Here, IdealState has not been rebalanced, so listFields might be null, in which case, we would get an emptyList from getPartitionSet()
    // So check using numPartitions instead
    // This check allows us to fail early on instead of having to loop through all partitions
    if (capacityMapSet.size() != idealState.getNumPartitions() && !hasDefaultCapacity) {
      logger.error(
          "ResourceConfig for {} does not have all partitions defined in PartitionCapacityMap!",
          idealState.getResourceName());
      return false;
    }

    // Loop through all partitions and validate
    capacityMap.keySet().forEach(partitionName -> WagedValidationUtil
        .validateAndGetPartitionCapacity(partitionName, resourceConfig, capacityMap,
            clusterConfig));
    return true;
  }

  public static class Builder extends GenericZkHelixApiBuilder<Builder> {
    public Builder() {
    }

    public ZKHelixAdmin build() {
      validate();
      return new ZKHelixAdmin(
          createZkClient(_realmMode, _realmAwareZkConnectionConfig, _realmAwareZkClientConfig,
              _zkAddress), false);
    }
  }

  private Set<String> findTimeoutOfflineInstances(String clusterName, long offlineDuration) {
    // in case there is no customized timeout value, use the one defined in cluster config
    if (offlineDuration == ClusterConfig.OFFLINE_DURATION_FOR_PURGE_NOT_SET) {
      offlineDuration =
          _configAccessor.getClusterConfig(clusterName).getOfflineDurationForPurge();
      if (offlineDuration == ClusterConfig.OFFLINE_DURATION_FOR_PURGE_NOT_SET) {
        return Collections.emptySet();
      }
    }

    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    List<String> instanceConfigNames = accessor.getChildNames(keyBuilder.instanceConfigs());
    List<String> instancePathNames = accessor.getChildNames(keyBuilder.instances());
    List<String> liveNodes = accessor.getChildNames(keyBuilder.liveInstances());

    Set<String> offlineInstanceNames = new HashSet<>(instancePathNames);
    liveNodes.forEach(offlineInstanceNames::remove);
    long finalOfflineDuration = offlineDuration;
    offlineInstanceNames.removeIf(instanceName -> {
      ParticipantHistory participantHistory =
          accessor.getProperty(keyBuilder.participantHistory(instanceName));
      if (participantHistory == null && instanceConfigNames.contains(instanceName)) {
        // this is likely caused by a new instance joining and should not be purged
        return true;
      }
      // If participant history is null without config, a race condition happened and should be
      // cleaned up.
      // Otherwise, if the participant has not been offline for more than the duration, no clean up
      return (participantHistory != null && (
          participantHistory.getLastOfflineTime() == ParticipantHistory.ONLINE
              || System.currentTimeMillis() - participantHistory.getLastOfflineTime()
              < finalOfflineDuration));
    });

    return offlineInstanceNames;
  }
}
