// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.cloud.catalog;

import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.Partition;
import org.apache.doris.catalog.Replica;
import org.apache.doris.cloud.proto.Cloud;
import org.apache.doris.cloud.qe.ClusterException;
import org.apache.doris.cloud.system.CloudSystemInfoService;
import org.apache.doris.common.Config;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.io.Text;
import org.apache.doris.common.util.DebugPointUtil;
import org.apache.doris.qe.ConnectContext;
import org.apache.doris.system.Backend;

import com.google.common.base.Strings;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class CloudReplica extends Replica {
    private static final Logger LOG = LogManager.getLogger(CloudReplica.class);

    // In the future, a replica may be mapped to multiple BEs in a cluster,
    // so this value is be list
    private Map<String, List<Long>> primaryClusterToBackends = new ConcurrentHashMap<String, List<Long>>();
    @SerializedName(value = "dbId")
    private long dbId = -1;
    @SerializedName(value = "tableId")
    private long tableId = -1;
    @SerializedName(value = "partitionId")
    private long partitionId = -1;
    @SerializedName(value = "indexId")
    private long indexId = -1;
    @SerializedName(value = "idx")
    private long idx = -1;

    private Random rand = new Random();

    private Map<String, List<Long>> memClusterToBackends = new ConcurrentHashMap<String, List<Long>>();

    // clusterId, secondaryBe, changeTimestamp
    private Map<String, List<Long>> secondaryClusterToBackends = new ConcurrentHashMap<String, List<Long>>();

    public CloudReplica() {
    }

    public CloudReplica(ReplicaContext context) {
        this(context.replicaId, context.backendId, context.state, context.version,
                context.schemaHash, context.dbId, context.tableId, context.partitionId,
                context.indexId,
                context.originReplica != null ? ((CloudReplica) context.originReplica).getIdx() : -1);
    }

    public CloudReplica(long replicaId, Long backendId, ReplicaState state, long version, int schemaHash,
            long dbId, long tableId, long partitionId, long indexId, long idx) {
        super(replicaId, -1, state, version, schemaHash);
        this.dbId = dbId;
        this.tableId = tableId;
        this.partitionId = partitionId;
        this.indexId = indexId;
        this.idx = idx;
    }

    private boolean isColocated() {
        return Env.getCurrentColocateIndex().isColocateTable(tableId);
    }

    private long getColocatedBeId(String cluster) throws ClusterException {
        List<Backend> bes = ((CloudSystemInfoService) Env.getCurrentSystemInfo()).getBackendsByClusterId(cluster);
        String clusterName = ((CloudSystemInfoService) Env.getCurrentSystemInfo()).getClusterNameByClusterId(cluster);
        if (bes.isEmpty()) {
            throw new ClusterException(
                String.format("There are no Backend nodes in the current cluster %s", clusterName),
                ClusterException.FailedTypeEnum.CURRENT_CLUSTER_NO_BE);
        }
        List<Backend> availableBes = new ArrayList<>();
        for (Backend be : bes) {
            if (be.isAlive()) {
                availableBes.add(be);
            }
        }

        if (availableBes.isEmpty()) {
            LOG.warn("failed to get available be, clusterId: {}", cluster);
            throw new ClusterException(
                String.format("All the Backend nodes in the current cluster %s are in an abnormal state", clusterName),
                ClusterException.FailedTypeEnum.CLUSTERS_NO_ALIVE_BE);
        }

        // Tablets with the same idx will be hashed to the same BE, which
        // meets the requirements of colocated table.
        long index = idx % availableBes.size();
        long pickedBeId = availableBes.get((int) index).getId();

        return pickedBeId;
    }

    public long getBackendId(String beEndpoint) {
        String cluster = ((CloudSystemInfoService) Env.getCurrentSystemInfo()).getClusterIdByBeAddr(beEndpoint);
        return getBackendId(cluster);
    }

    @Override
    public long getBackendId() throws ClusterException {
        String cluster = null;
        // Not in a connect session
        ConnectContext context = ConnectContext.get();
        if (context != null) {
            if (!Strings.isNullOrEmpty(context.getSessionVariable().getCloudCluster())) {
                cluster = context.getSessionVariable().getCloudCluster();
                try {
                    ((CloudEnv) Env.getCurrentEnv()).checkCloudClusterPriv(cluster);
                } catch (Exception e) {
                    LOG.warn("get cluster by session context exception");
                    throw new ClusterException(String.format("default cluster %s check auth failed", cluster),
                        ClusterException.FailedTypeEnum.CURRENT_USER_NO_AUTH_TO_USE_DEFAULT_CLUSTER);
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("get cluster by session context cluster: {}", cluster);
                }
            } else {
                cluster = context.getCloudCluster(false);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("get cluster by context {}", cluster);
                }
                String clusterStatus = ((CloudSystemInfoService) Env.getCurrentSystemInfo())
                        .getCloudStatusByName(cluster);
                if (!Strings.isNullOrEmpty(clusterStatus)
                        && Cloud.ClusterStatus.valueOf(clusterStatus)
                        == Cloud.ClusterStatus.MANUAL_SHUTDOWN) {
                    LOG.warn("auto start cluster {} in manual shutdown status", cluster);
                    throw new ClusterException(
                        String.format("The current cluster %s has been manually shutdown", cluster),
                        ClusterException.FailedTypeEnum.CURRENT_CLUSTER_BEEN_MANUAL_SHUTDOWN);
                }
            }
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("connect context is null in getBackendId");
            }
            throw new ClusterException("connect context not set",
                ClusterException.FailedTypeEnum.CONNECT_CONTEXT_NOT_SET);
        }
        return getBackendIdImpl(cluster);
    }

    private long getBackendIdImpl(String cluster) throws ClusterException {
        // if cluster is SUSPENDED, wait
        String wakeUPCluster = "";
        try {
            wakeUPCluster = ((CloudSystemInfoService) Env.getCurrentSystemInfo()).waitForAutoStart(cluster);
        } catch (DdlException e) {
            // this function cant throw exception. so just log it
            LOG.warn("cant resume cluster {}, exception", cluster, e);
        }
        if (!Strings.isNullOrEmpty(wakeUPCluster) && !cluster.equals(wakeUPCluster)) {
            cluster = wakeUPCluster;
            LOG.warn("get backend input cluster {} useless, so auto start choose a new one cluster {}",
                    cluster, wakeUPCluster);
        }
        // check default cluster valid.
        if (Strings.isNullOrEmpty(cluster)) {
            LOG.warn("failed to get available be, clusterName: {}", cluster);
            throw new ClusterException("cluster name is empty",
                ClusterException.FailedTypeEnum.CONNECT_CONTEXT_NOT_SET_CLUSTER);
        }
        boolean exist = ((CloudSystemInfoService) Env.getCurrentSystemInfo())
                .getCloudClusterNames().contains(cluster);
        if (!exist) {
            // can't use this default cluster, plz change another
            LOG.warn("cluster: {} is not existed", cluster);
            throw new ClusterException(String.format("The current cluster %s is not registered in the system", cluster),
                ClusterException.FailedTypeEnum.CURRENT_CLUSTER_NOT_EXIST);
        }

        String clusterId = ((CloudSystemInfoService) Env.getCurrentSystemInfo()).getCloudClusterIdByName(cluster);

        if (isColocated()) {
            return getColocatedBeId(clusterId);
        }

        if (Config.enable_cloud_multi_replica) {
            int indexRand = rand.nextInt(Config.cloud_replica_num);
            int coldReadRand = rand.nextInt(100);
            boolean allowColdRead = coldReadRand < Config.cloud_cold_read_percent;
            boolean replicaEnough = memClusterToBackends.get(clusterId) != null
                    && memClusterToBackends.get(clusterId).size() > indexRand;

            long backendId = -1;
            if (replicaEnough) {
                backendId = memClusterToBackends.get(clusterId).get(indexRand);
            }

            if (!replicaEnough && !allowColdRead && primaryClusterToBackends.containsKey(clusterId)) {
                backendId = primaryClusterToBackends.get(clusterId).get(0);
            }

            if (backendId > 0) {
                Backend be = Env.getCurrentSystemInfo().getBackend(backendId);
                if (be != null && be.isQueryAvailable()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("backendId={} ", backendId);
                    }
                    return backendId;
                }
            }

            List<Long> res = hashReplicaToBes(clusterId, false, Config.cloud_replica_num);
            if (res.size() < indexRand + 1) {
                if (res.isEmpty()) {
                    return -1;
                } else {
                    return res.get(0);
                }
            } else {
                return res.get(indexRand);
            }
        }

        // use primaryClusterToBackends, if find be normal
        long pickBeId = getAvaliableBeId(clusterId, primaryClusterToBackends);
        if (pickBeId != -1) {
            return pickBeId;
        }

        if (!Config.enable_immediate_be_assign) {
            // use secondaryClusterToBackends, if find be normal
            pickBeId = getAvaliableBeId(clusterId, secondaryClusterToBackends);
            if (pickBeId != -1) {
                return pickBeId;
            }
        }

        if (DebugPointUtil.isEnable("CloudReplica.getBackendIdImpl.primaryClusterToBackends")) {
            LOG.info("Debug Point enable CloudReplica.getBackendIdImpl.primaryClusterToBackends");
            return -1;
        }

        // be abnormal, rehash it. configure settings to different maps
        pickBeId = hashReplicaToBe(clusterId, false);
        updateClusterToBe(clusterId, pickBeId, Config.enable_immediate_be_assign);
        return pickBeId;
    }

    private long getAvaliableBeId(String clusterId, Map<String, List<Long>> clusterToBackends) {
        List<Long> backendIds = clusterToBackends.get(clusterId);
        if (backendIds == null || backendIds.isEmpty()) {
            return -1;
        }

        long backendId = backendIds.get(0);
        Backend be = Env.getCurrentSystemInfo().getBackend(backendId);
        if (be != null && be.isQueryAvailable()) {
            // be normal
            if (LOG.isDebugEnabled()) {
                LOG.debug("backendId={} ", backendId);
            }
            return backendId;
        }
        return -1;
    }

    public long hashReplicaToBe(String clusterId, boolean isBackGround) throws ClusterException {
        // TODO(luwei) list should be sorted
        List<Backend> clusterBes = ((CloudSystemInfoService) Env.getCurrentSystemInfo())
                .getBackendsByClusterId(clusterId);
        String clusterName = ((CloudSystemInfoService) Env.getCurrentSystemInfo()).getClusterNameByClusterId(clusterId);
        if (clusterBes.isEmpty()) {
            throw new ClusterException(
                String.format("There are no Backend nodes in the current cluster %s", clusterName),
                ClusterException.FailedTypeEnum.CURRENT_CLUSTER_NO_BE);
        }
        // use alive be to exec sql
        List<Backend> availableBes = new ArrayList<>();
        for (Backend be : clusterBes) {
            long lastUpdateMs = be.getLastUpdateMs();
            long missTimeMs = Math.abs(lastUpdateMs - System.currentTimeMillis());
            // be core or restart must in heartbeat_interval_second
            if ((be.isAlive() || missTimeMs <= Config.heartbeat_interval_second * 1000L)
                    && !be.isSmoothUpgradeSrc()) {
                availableBes.add(be);
            }
        }
        if (availableBes.isEmpty()) {
            if (!isBackGround) {
                LOG.warn("failed to get available be, clusterId: {}", clusterId);
            }
            throw new ClusterException(
                String.format("All the Backend nodes in the current cluster %s are in an abnormal state", clusterName),
                ClusterException.FailedTypeEnum.CLUSTERS_NO_ALIVE_BE);
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("availableBes={}", availableBes);
        }
        long index = -1;
        HashCode hashCode = null;
        if (idx == -1) {
            index = getId() % availableBes.size();
        } else {
            hashCode = Hashing.murmur3_128().hashLong(partitionId);
            int beNum = availableBes.size();
            // (hashCode.asLong() + idx) % beNum may be a negative value, so we
            // need to take the modulus of beNum again to ensure that index is
            // a positive value
            index = ((hashCode.asLong() + idx) % beNum + beNum) % beNum;
        }
        long pickedBeId = availableBes.get((int) index).getId();
        LOG.info("picked beId {}, replicaId {}, partitionId {}, beNum {}, replicaIdx {}, picked Index {}, hashVal {}",
                pickedBeId, getId(), partitionId, availableBes.size(), idx, index,
                hashCode == null ? -1 : hashCode.asLong());

        return pickedBeId;
    }

    public List<Long> hashReplicaToBes(String clusterId, boolean isBackGround, int replicaNum) throws ClusterException {
        // TODO(luwei) list should be sorted
        List<Backend> clusterBes = ((CloudSystemInfoService) Env.getCurrentSystemInfo())
                .getBackendsByClusterId(clusterId);
        String clusterName = ((CloudSystemInfoService) Env.getCurrentSystemInfo()).getClusterNameByClusterId(clusterId);
        if (clusterBes.isEmpty()) {
            throw new ClusterException(
                String.format("There are no Backend nodes in the current cluster %s", clusterName),
                ClusterException.FailedTypeEnum.CURRENT_CLUSTER_NO_BE);
        }
        // use alive be to exec sql
        List<Backend> availableBes = new ArrayList<>();
        for (Backend be : clusterBes) {
            long lastUpdateMs = be.getLastUpdateMs();
            long missTimeMs = Math.abs(lastUpdateMs - System.currentTimeMillis());
            // be core or restart must in heartbeat_interval_second
            if ((be.isAlive() || missTimeMs <= Config.heartbeat_interval_second * 1000L)
                    && !be.isSmoothUpgradeSrc()) {
                availableBes.add(be);
            }
        }
        if (availableBes.isEmpty()) {
            if (!isBackGround) {
                LOG.warn("failed to get available be, clusterId: {}", clusterId);
            }
            throw new ClusterException(
                String.format("All the Backend nodes in the current cluster %s are in an abnormal state", clusterName),
                ClusterException.FailedTypeEnum.CLUSTERS_NO_ALIVE_BE);
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("availableBes={}", availableBes);
        }

        int realReplicaNum = Math.min(replicaNum, availableBes.size());
        List<Long> bes = new ArrayList<Long>();
        for (int i = 0; i < realReplicaNum; ++i) {
            long index = -1;
            HashCode hashCode = null;
            if (idx == -1) {
                index = getId() % availableBes.size();
            } else {
                hashCode = Hashing.murmur3_128().hashLong(partitionId + i);
                int beNum = availableBes.size();
                // (hashCode.asLong() + idx) % beNum may be a negative value, so we
                // need to take the modulus of beNum again to ensure that index is
                // a positive value
                index = ((hashCode.asLong() + idx) % beNum + beNum) % beNum;
            }
            long pickedBeId = availableBes.get((int) index).getId();
            availableBes.remove((int) index);
            LOG.info("picked beId {}, replicaId {}, partId {}, beNum {}, replicaIdx {}, picked Index {}, hashVal {}",
                    pickedBeId, getId(), partitionId, availableBes.size(), idx, index,
                    hashCode == null ? -1 : hashCode.asLong());
            // save to memClusterToBackends map
            bes.add(pickedBeId);
        }

        memClusterToBackends.put(clusterId, bes);

        return bes;
    }

    @Override
    public boolean checkVersionCatchUp(long expectedVersion, boolean ignoreAlter) {
        // ATTN: expectedVersion is not used here, and OlapScanNode.addScanRangeLocations
        // depends this feature to implement snapshot partition version. See comments in
        // OlapScanNode.addScanRangeLocations for details.
        if (ignoreAlter && getState() == ReplicaState.ALTER
                && getVersion() == Partition.PARTITION_INIT_VERSION) {
            return true;
        }

        if (expectedVersion == Partition.PARTITION_INIT_VERSION) {
            // no data is loaded into this replica, just return true
            return true;
        }

        return true;
    }

    @Deprecated
    @Override
    public void readFields(DataInput in) throws IOException {
        super.readFields(in);
        dbId = in.readLong();
        tableId = in.readLong();
        partitionId = in.readLong();
        indexId = in.readLong();
        idx = in.readLong();
        int count = in.readInt();
        for (int i = 0; i < count; ++i) {
            String clusterId = Text.readString(in);
            String realClusterId = ((CloudSystemInfoService) Env.getCurrentSystemInfo())
                    .getCloudClusterIdByName(clusterId);
            if (LOG.isDebugEnabled()) {
                LOG.debug("cluster Id {}, real cluster Id {}", clusterId, realClusterId);
            }

            if (!Strings.isNullOrEmpty(realClusterId)) {
                clusterId = realClusterId;
            }

            long beId = in.readLong();
            List<Long> bes = new ArrayList<Long>();
            bes.add(beId);
            primaryClusterToBackends.put(clusterId, bes);
        }
    }

    public long getDbId() {
        return dbId;
    }

    public long getTableId() {
        return tableId;
    }

    public long getPartitionId() {
        return partitionId;
    }

    public long getIndexId() {
        return indexId;
    }

    public long getIdx() {
        return idx;
    }

    public Map<String, List<Long>> getprimaryClusterToBackends() {
        return primaryClusterToBackends;
    }

    // save to primaryClusterToBackends or secondaryClusterToBackends map
    public void updateClusterToBe(String cluster, long beId, boolean isUpdatePrimary) {
        // write lock
        List<Long> bes = new ArrayList<Long>();
        bes.add(beId);
        if (isUpdatePrimary) {
            primaryClusterToBackends.put(cluster, bes);
        } else {
            secondaryClusterToBackends.put(cluster, bes);
        }
    }
}
