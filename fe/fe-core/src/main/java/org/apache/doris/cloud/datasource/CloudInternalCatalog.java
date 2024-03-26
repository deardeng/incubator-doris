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

package org.apache.doris.cloud.datasource;

import org.apache.doris.analysis.DataSortInfo;
import org.apache.doris.catalog.BinlogConfig;
import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.DataProperty;
import org.apache.doris.catalog.Database;
import org.apache.doris.catalog.DistributionInfo;
import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.EnvFactory;
import org.apache.doris.catalog.Index;
import org.apache.doris.catalog.KeysType;
import org.apache.doris.catalog.MaterializedIndex;
import org.apache.doris.catalog.MaterializedIndex.IndexExtState;
import org.apache.doris.catalog.MaterializedIndex.IndexState;
import org.apache.doris.catalog.MaterializedIndexMeta;
import org.apache.doris.catalog.MetaIdGenerator.IdGeneratorBuffer;
import org.apache.doris.catalog.OlapTable;
import org.apache.doris.catalog.Partition;
import org.apache.doris.catalog.Replica;
import org.apache.doris.catalog.Replica.ReplicaState;
import org.apache.doris.catalog.ReplicaAllocation;
import org.apache.doris.catalog.Tablet;
import org.apache.doris.catalog.TabletMeta;
import org.apache.doris.cloud.catalog.CloudPartition;
import org.apache.doris.cloud.catalog.CloudReplica;
import org.apache.doris.cloud.persist.UpdateCloudReplicaInfo;
import org.apache.doris.cloud.proto.Cloud;
import org.apache.doris.cloud.rpc.MetaServiceProxy;
import org.apache.doris.cloud.system.CloudSystemInfoService;
import org.apache.doris.common.Config;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.MetaNotFoundException;
import org.apache.doris.datasource.InternalCatalog;
import org.apache.doris.proto.OlapCommon;
import org.apache.doris.proto.OlapFile;
import org.apache.doris.proto.Types;
import org.apache.doris.rpc.RpcException;
import org.apache.doris.thrift.TCompressionType;
import org.apache.doris.thrift.TSortType;
import org.apache.doris.thrift.TTabletType;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import doris.segment_v2.SegmentV2;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CloudInternalCatalog extends InternalCatalog {
    private static final Logger LOG = LogManager.getLogger(CloudInternalCatalog.class);

    public CloudInternalCatalog() {
        super();
    }

    // BEGIN CREATE TABLE
    @Override
    protected Partition createPartitionWithIndices(long dbId, OlapTable tbl, long partitionId,
                                                   String partitionName, Map<Long, MaterializedIndexMeta> indexIdToMeta,
                                                   DistributionInfo distributionInfo, DataProperty dataProperty,
                                                   ReplicaAllocation replicaAlloc,
                                                   Long versionInfo, Set<String> bfColumns, Set<Long> tabletIdSet,
                                                   boolean isInMemory,
                                                   TTabletType tabletType,
                                                   String storagePolicy,
                                                   IdGeneratorBuffer idGeneratorBuffer,
                                                   BinlogConfig binlogConfig,
                                                   boolean isStorageMediumSpecified, List<Integer> clusterKeyIndexes)
            throws DdlException {
        // create base index first.
        Preconditions.checkArgument(tbl.getBaseIndexId() != -1);
        MaterializedIndex baseIndex = new MaterializedIndex(tbl.getBaseIndexId(), IndexState.NORMAL);

        LOG.info("begin create cloud partition");
        // create partition with base index
        Partition partition = new CloudPartition(partitionId, partitionName, baseIndex,
                distributionInfo, dbId, tbl.getId());

        // add to index map
        Map<Long, MaterializedIndex> indexMap = Maps.newHashMap();
        indexMap.put(tbl.getBaseIndexId(), baseIndex);

        // create rollup index if has
        for (long indexId : indexIdToMeta.keySet()) {
            if (indexId == tbl.getBaseIndexId()) {
                continue;
            }

            MaterializedIndex rollup = new MaterializedIndex(indexId, IndexState.NORMAL);
            indexMap.put(indexId, rollup);
        }

        // version and version hash
        if (versionInfo != null) {
            partition.updateVisibleVersion(versionInfo);
        }
        long version = partition.getVisibleVersion();

        final String storageVaultName = tbl.getTableProperty().getStorageVauldName();
        boolean storageVaultIdSet = false;
        // We don't need to set the vault name if the table has no property
        if (storageVaultName == null || storageVaultName.isEmpty()) {
            storageVaultIdSet = true;
        }

        // short totalReplicaNum = replicaAlloc.getTotalReplicaNum();
        for (Map.Entry<Long, MaterializedIndex> entry : indexMap.entrySet()) {
            long indexId = entry.getKey();
            MaterializedIndex index = entry.getValue();
            MaterializedIndexMeta indexMeta = indexIdToMeta.get(indexId);

            // create tablets
            int schemaHash = indexMeta.getSchemaHash();
            TabletMeta tabletMeta = new TabletMeta(dbId, tbl.getId(), partitionId,
                    indexId, schemaHash, dataProperty.getStorageMedium());
            createCloudTablets(index, ReplicaState.NORMAL, distributionInfo, version, replicaAlloc,
                    tabletMeta, tabletIdSet);

            short shortKeyColumnCount = indexMeta.getShortKeyColumnCount();
            // TStorageType storageType = indexMeta.getStorageType();
            List<Column> columns = indexMeta.getSchema();
            KeysType keysType = indexMeta.getKeysType();

            List<Index> indexes;
            if (index.getId() == tbl.getBaseIndexId()) {
                indexes = tbl.getIndexes();
            } else {
                indexes = Lists.newArrayList();
            }
            Cloud.CreateTabletsRequest.Builder requestBuilder = Cloud.CreateTabletsRequest.newBuilder();
            for (Tablet tablet : index.getTablets()) {
                OlapFile.TabletMetaCloudPB.Builder builder = createTabletMetaBuilder(tbl.getId(), indexId,
                        partitionId, tablet, tabletType, schemaHash, keysType, shortKeyColumnCount,
                        bfColumns, tbl.getBfFpp(), indexes, columns, tbl.getDataSortInfo(),
                        tbl.getCompressionType(), storagePolicy, isInMemory, false, tbl.getName(), tbl.getTTLSeconds(),
                        tbl.getEnableUniqueKeyMergeOnWrite(), tbl.storeRowColumn(), indexMeta.getSchemaVersion());
                requestBuilder.addTabletMetas(builder);
            }
            if (!storageVaultIdSet) {
                requestBuilder.setStorageVaultName(storageVaultName);
            }

            LOG.info("create tablets, dbId: {}, tableId: {}, tableName: {}, partitionId: {}, partitionName: {}, "
                    + "indexId: {}",
                    dbId, tbl.getId(), tbl.getName(), partitionId, partitionName, indexId);
            Cloud.CreateTabletsResponse resp = sendCreateTabletsRpc(requestBuilder);
            if (resp.hasStorageVaultId() && !storageVaultIdSet) {
                tbl.getTableProperty().setStorageVaultId(resp.getStorageVaultId());
                storageVaultIdSet = true;
            }
            if (index.getId() != tbl.getBaseIndexId()) {
                // add rollup index to partition
                partition.createRollupIndex(index);
            }
        }

        LOG.info("succeed in creating partition[{}-{}], table : [{}-{}]", partitionId, partitionName,
                tbl.getId(), tbl.getName());

        return partition;
    }

    public OlapFile.TabletMetaCloudPB.Builder createTabletMetaBuilder(long tableId, long indexId,
            long partitionId, Tablet tablet, TTabletType tabletType, int schemaHash, KeysType keysType,
            short shortKeyColumnCount, Set<String> bfColumns, double bfFpp, List<Index> indexes,
            List<Column> schemaColumns, DataSortInfo dataSortInfo, TCompressionType compressionType,
            String storagePolicy, boolean isInMemory, boolean isShadow,
            String tableName, long ttlSeconds, boolean enableUniqueKeyMergeOnWrite,
            boolean storeRowColumn, int schemaVersion) throws DdlException {
        OlapFile.TabletMetaCloudPB.Builder builder = OlapFile.TabletMetaCloudPB.newBuilder();
        builder.setTableId(tableId);
        builder.setIndexId(indexId);
        builder.setPartitionId(partitionId);
        builder.setTabletId(tablet.getId());
        builder.setSchemaHash(schemaHash);
        builder.setTableName(tableName);
        builder.setCreationTime(System.currentTimeMillis() / 1000);
        builder.setCumulativeLayerPoint(-1);
        builder.setTabletState(isShadow ? OlapFile.TabletStatePB.PB_NOTREADY : OlapFile.TabletStatePB.PB_RUNNING);
        builder.setIsInMemory(isInMemory);
        builder.setTtlSeconds(ttlSeconds);
        builder.setSchemaVersion(schemaVersion);

        UUID uuid = UUID.randomUUID();
        Types.PUniqueId tabletUid = Types.PUniqueId.newBuilder()
                .setHi(uuid.getMostSignificantBits())
                .setLo(uuid.getLeastSignificantBits())
                .build();
        builder.setTabletUid(tabletUid);

        builder.setPreferredRowsetType(OlapFile.RowsetTypePB.BETA_ROWSET);
        builder.setTabletType(tabletType == TTabletType.TABLET_TYPE_DISK
                ? OlapFile.TabletTypePB.TABLET_TYPE_DISK : OlapFile.TabletTypePB.TABLET_TYPE_MEMORY);

        builder.setReplicaId(tablet.getReplicas().get(0).getId());
        builder.setEnableUniqueKeyMergeOnWrite(enableUniqueKeyMergeOnWrite);

        OlapFile.TabletSchemaCloudPB.Builder schemaBuilder = OlapFile.TabletSchemaCloudPB.newBuilder();
        schemaBuilder.setSchemaVersion(schemaVersion);

        if (keysType == KeysType.DUP_KEYS) {
            schemaBuilder.setKeysType(OlapFile.KeysType.DUP_KEYS);
        } else if (keysType == KeysType.UNIQUE_KEYS) {
            schemaBuilder.setKeysType(OlapFile.KeysType.UNIQUE_KEYS);
        } else if (keysType == KeysType.AGG_KEYS) {
            schemaBuilder.setKeysType(OlapFile.KeysType.AGG_KEYS);
        } else {
            throw new DdlException("invalid key types");
        }
        schemaBuilder.setNumShortKeyColumns(shortKeyColumnCount);
        schemaBuilder.setNumRowsPerRowBlock(1024);
        schemaBuilder.setCompressKind(OlapCommon.CompressKind.COMPRESS_LZ4);
        schemaBuilder.setBfFpp(bfFpp);

        int deleteSign = -1;
        int sequenceCol = -1;
        for (int i = 0; i < schemaColumns.size(); i++) {
            Column column = schemaColumns.get(i);
            if (column.isDeleteSignColumn()) {
                deleteSign = i;
            }
            if (column.isSequenceColumn()) {
                sequenceCol = i;
            }
        }
        schemaBuilder.setDeleteSignIdx(deleteSign);
        schemaBuilder.setSequenceColIdx(sequenceCol);
        schemaBuilder.setStoreRowColumn(storeRowColumn);

        if (dataSortInfo.getSortType() == TSortType.LEXICAL) {
            schemaBuilder.setSortType(OlapFile.SortType.LEXICAL);
        } else if (dataSortInfo.getSortType() == TSortType.ZORDER) {
            schemaBuilder.setSortType(OlapFile.SortType.ZORDER);
        } else {
            LOG.warn("invalid sort types:{}", dataSortInfo.getSortType());
            throw new DdlException("invalid sort types");
        }

        switch (compressionType) {
            case NO_COMPRESSION:
                schemaBuilder.setCompressionType(SegmentV2.CompressionTypePB.NO_COMPRESSION);
                break;
            case SNAPPY:
                schemaBuilder.setCompressionType(SegmentV2.CompressionTypePB.SNAPPY);
                break;
            case LZ4:
                schemaBuilder.setCompressionType(SegmentV2.CompressionTypePB.LZ4);
                break;
            case LZ4F:
                schemaBuilder.setCompressionType(SegmentV2.CompressionTypePB.LZ4F);
                break;
            case ZLIB:
                schemaBuilder.setCompressionType(SegmentV2.CompressionTypePB.ZLIB);
                break;
            case ZSTD:
                schemaBuilder.setCompressionType(SegmentV2.CompressionTypePB.ZSTD);
                break;
            default:
                schemaBuilder.setCompressionType(SegmentV2.CompressionTypePB.LZ4F);
                break;
        }

        schemaBuilder.setSortColNum(dataSortInfo.getColNum());
        for (int i = 0; i < schemaColumns.size(); i++) {
            Column column = schemaColumns.get(i);
            schemaBuilder.addColumn(column.toPb(bfColumns, indexes));
        }

        if (indexes != null) {
            for (int i = 0; i < indexes.size(); i++) {
                Index index = indexes.get(i);
                schemaBuilder.addIndex(index.toPb(schemaColumns));
            }
        }
        OlapFile.TabletSchemaCloudPB schema = schemaBuilder.build();
        builder.setSchema(schema);
        // rowset
        OlapFile.RowsetMetaCloudPB.Builder rowsetBuilder = createInitialRowset(tablet, partitionId,
                schemaHash, schema);
        builder.addRsMetas(rowsetBuilder);
        return builder;
    }

    private OlapFile.RowsetMetaCloudPB.Builder createInitialRowset(Tablet tablet, long partitionId,
            int schemaHash, OlapFile.TabletSchemaCloudPB schema) {
        OlapFile.RowsetMetaCloudPB.Builder rowsetBuilder = OlapFile.RowsetMetaCloudPB.newBuilder();
        rowsetBuilder.setRowsetId(0);
        rowsetBuilder.setPartitionId(partitionId);
        rowsetBuilder.setTabletId(tablet.getId());
        rowsetBuilder.setTabletSchemaHash(schemaHash);
        rowsetBuilder.setRowsetType(OlapFile.RowsetTypePB.BETA_ROWSET);
        rowsetBuilder.setRowsetState(OlapFile.RowsetStatePB.VISIBLE);
        rowsetBuilder.setStartVersion(0);
        rowsetBuilder.setEndVersion(1);
        rowsetBuilder.setNumRows(0);
        rowsetBuilder.setTotalDiskSize(0);
        rowsetBuilder.setDataDiskSize(0);
        rowsetBuilder.setIndexDiskSize(0);
        rowsetBuilder.setSegmentsOverlapPb(OlapFile.SegmentsOverlapPB.NONOVERLAPPING);
        rowsetBuilder.setNumSegments(0);
        rowsetBuilder.setEmpty(true);

        UUID uuid = UUID.randomUUID();
        String rowsetIdV2Str = String.format("%016X", 2L << 56)
                + String.format("%016X", uuid.getMostSignificantBits())
                + String.format("%016X", uuid.getLeastSignificantBits());
        rowsetBuilder.setRowsetIdV2(rowsetIdV2Str);

        rowsetBuilder.setTabletSchema(schema);
        return rowsetBuilder;
    }

    private void createCloudTablets(MaterializedIndex index, ReplicaState replicaState,
            DistributionInfo distributionInfo, long version, ReplicaAllocation replicaAlloc,
            TabletMeta tabletMeta, Set<Long> tabletIdSet) throws DdlException {
        for (int i = 0; i < distributionInfo.getBucketNum(); ++i) {
            Tablet tablet = EnvFactory.getInstance().createTablet(Env.getCurrentEnv().getNextId());

            // add tablet to inverted index first
            index.addTablet(tablet, tabletMeta);
            tabletIdSet.add(tablet.getId());

            long replicaId = Env.getCurrentEnv().getNextId();
            Replica replica = new CloudReplica(replicaId, null, replicaState, version,
                    tabletMeta.getOldSchemaHash(), tabletMeta.getDbId(), tabletMeta.getTableId(),
                    tabletMeta.getPartitionId(), tabletMeta.getIndexId(), i);
            tablet.addReplica(replica);
        }
    }

    @Override
    protected void beforeCreatePartitions(long dbId, long tableId, List<Long> partitionIds, List<Long> indexIds)
            throws DdlException {
        if (partitionIds == null) {
            prepareMaterializedIndex(tableId, indexIds, 0);
        } else {
            preparePartition(dbId, tableId, partitionIds, indexIds);
        }
    }

    @Override
    protected void afterCreatePartitions(long dbId, long tableId, List<Long> partitionIds, List<Long> indexIds,
                                         boolean isCreateTable)
            throws DdlException {
        if (partitionIds == null) {
            commitMaterializedIndex(dbId, tableId, indexIds, isCreateTable);
        } else {
            commitPartition(dbId, tableId, partitionIds, indexIds);
        }
    }

    private void preparePartition(long dbId, long tableId, List<Long> partitionIds, List<Long> indexIds)
            throws DdlException {
        Cloud.PartitionRequest.Builder partitionRequestBuilder = Cloud.PartitionRequest.newBuilder();
        partitionRequestBuilder.setCloudUniqueId(Config.cloud_unique_id);
        partitionRequestBuilder.setTableId(tableId);
        partitionRequestBuilder.addAllPartitionIds(partitionIds);
        partitionRequestBuilder.addAllIndexIds(indexIds);
        partitionRequestBuilder.setExpiration(0);
        if (dbId > 0) {
            partitionRequestBuilder.setDbId(dbId);
        }
        final Cloud.PartitionRequest partitionRequest = partitionRequestBuilder.build();

        Cloud.PartitionResponse response = null;
        int tryTimes = 0;
        while (tryTimes++ < Config.meta_service_rpc_retry_times) {
            try {
                response = MetaServiceProxy.getInstance().preparePartition(partitionRequest);
                if (response.getStatus().getCode() != Cloud.MetaServiceCode.KV_TXN_CONFLICT) {
                    break;
                }
            } catch (RpcException e) {
                LOG.warn("tryTimes:{}, preparePartition RpcException", tryTimes, e);
                if (tryTimes + 1 >= Config.meta_service_rpc_retry_times) {
                    throw new DdlException(e.getMessage());
                }
            }
            sleepSeveralMs();
        }

        if (response.getStatus().getCode() != Cloud.MetaServiceCode.OK) {
            LOG.warn("preparePartition response: {} ", response);
            throw new DdlException(response.getStatus().getMsg());
        }
    }

    private void commitPartition(long dbId, long tableId, List<Long> partitionIds, List<Long> indexIds)
            throws DdlException {
        Cloud.PartitionRequest.Builder partitionRequestBuilder = Cloud.PartitionRequest.newBuilder();
        partitionRequestBuilder.setCloudUniqueId(Config.cloud_unique_id);
        partitionRequestBuilder.addAllPartitionIds(partitionIds);
        partitionRequestBuilder.addAllIndexIds(indexIds);
        partitionRequestBuilder.setDbId(dbId);
        partitionRequestBuilder.setTableId(tableId);
        final Cloud.PartitionRequest partitionRequest = partitionRequestBuilder.build();

        Cloud.PartitionResponse response = null;
        int tryTimes = 0;
        while (tryTimes++ < Config.meta_service_rpc_retry_times) {
            try {
                response = MetaServiceProxy.getInstance().commitPartition(partitionRequest);
                if (response.getStatus().getCode() != Cloud.MetaServiceCode.KV_TXN_CONFLICT) {
                    break;
                }
            } catch (RpcException e) {
                LOG.warn("tryTimes:{}, commitPartition RpcException", tryTimes, e);
                if (tryTimes + 1 >= Config.meta_service_rpc_retry_times) {
                    throw new DdlException(e.getMessage());
                }
            }
            sleepSeveralMs();
        }

        if (response.getStatus().getCode() != Cloud.MetaServiceCode.OK) {
            LOG.warn("commitPartition response: {} ", response);
            throw new DdlException(response.getStatus().getMsg());
        }
    }

    // if `expiration` = 0, recycler will delete uncommitted indexes in `retention_seconds`
    public void prepareMaterializedIndex(Long tableId, List<Long> indexIds, long expiration) throws DdlException {
        Cloud.IndexRequest.Builder indexRequestBuilder = Cloud.IndexRequest.newBuilder();
        indexRequestBuilder.setCloudUniqueId(Config.cloud_unique_id);
        indexRequestBuilder.addAllIndexIds(indexIds);
        indexRequestBuilder.setTableId(tableId);
        indexRequestBuilder.setExpiration(expiration);
        final Cloud.IndexRequest indexRequest = indexRequestBuilder.build();

        Cloud.IndexResponse response = null;
        int tryTimes = 0;
        while (tryTimes++ < Config.meta_service_rpc_retry_times) {
            try {
                response = MetaServiceProxy.getInstance().prepareIndex(indexRequest);
                if (response.getStatus().getCode() != Cloud.MetaServiceCode.KV_TXN_CONFLICT) {
                    break;
                }
            } catch (RpcException e) {
                LOG.warn("tryTimes:{}, prepareIndex RpcException", tryTimes, e);
                if (tryTimes + 1 >= Config.meta_service_rpc_retry_times) {
                    throw new DdlException(e.getMessage());
                }
            }
            sleepSeveralMs();
        }

        if (response.getStatus().getCode() != Cloud.MetaServiceCode.OK) {
            LOG.warn("prepareIndex response: {} ", response);
            throw new DdlException(response.getStatus().getMsg());
        }
    }

    public void commitMaterializedIndex(long dbId, long tableId, List<Long> indexIds, boolean isCreateTable)
            throws DdlException {
        Cloud.IndexRequest.Builder indexRequestBuilder = Cloud.IndexRequest.newBuilder();
        indexRequestBuilder.setCloudUniqueId(Config.cloud_unique_id);
        indexRequestBuilder.addAllIndexIds(indexIds);
        indexRequestBuilder.setDbId(dbId);
        indexRequestBuilder.setTableId(tableId);
        indexRequestBuilder.setIsNewTable(isCreateTable);
        final Cloud.IndexRequest indexRequest = indexRequestBuilder.build();

        Cloud.IndexResponse response = null;
        int tryTimes = 0;
        while (tryTimes++ < Config.meta_service_rpc_retry_times) {
            try {
                response = MetaServiceProxy.getInstance().commitIndex(indexRequest);
                if (response.getStatus().getCode() != Cloud.MetaServiceCode.KV_TXN_CONFLICT) {
                    break;
                }
            } catch (RpcException e) {
                LOG.warn("tryTimes:{}, commitIndex RpcException", tryTimes, e);
                if (tryTimes + 1 >= Config.meta_service_rpc_retry_times) {
                    throw new DdlException(e.getMessage());
                }
            }
            sleepSeveralMs();
        }

        if (response.getStatus().getCode() != Cloud.MetaServiceCode.OK) {
            LOG.warn("commitIndex response: {} ", response);
            throw new DdlException(response.getStatus().getMsg());
        }
    }

    public Cloud.CreateTabletsResponse
            sendCreateTabletsRpc(Cloud.CreateTabletsRequest.Builder requestBuilder) throws DdlException  {
        requestBuilder.setCloudUniqueId(Config.cloud_unique_id);
        Cloud.CreateTabletsRequest createTabletsReq = requestBuilder.build();

        if (LOG.isDebugEnabled()) {
            LOG.debug("send create tablets rpc, createTabletsReq: {}", createTabletsReq);
        }
        Cloud.CreateTabletsResponse response = null;
        int tryTimes = 0;
        while (tryTimes++ < Config.meta_service_rpc_retry_times) {
            try {
                response = MetaServiceProxy.getInstance().createTablets(createTabletsReq);
                if (response.getStatus().getCode() != Cloud.MetaServiceCode.KV_TXN_CONFLICT) {
                    break;
                }
            } catch (RpcException e) {
                LOG.warn("tryTimes:{}, create tablets RpcException", tryTimes, e);
                if (tryTimes + 1 >= Config.meta_service_rpc_retry_times) {
                    throw new DdlException(e.getMessage());
                }
            }
            sleepSeveralMs();
        }
        LOG.info("create tablets response: {}", response);

        if (response.getStatus().getCode() != Cloud.MetaServiceCode.OK) {
            throw new DdlException(response.getStatus().getMsg());
        }
        return response;
    }

    // END CREATE TABLE

    // BEGIN DROP TABLE

    @Override
    public void eraseTableDropBackendReplicas(OlapTable olapTable, boolean isReplay) {
        if (!Env.getCurrentEnv().isMaster()) {
            return;
        }

        List<Long> indexs = Lists.newArrayList();
        for (Partition partition : olapTable.getAllPartitions()) {
            List<MaterializedIndex> allIndices = partition.getMaterializedIndices(IndexExtState.ALL);
            for (MaterializedIndex materializedIndex : allIndices) {
                long indexId = materializedIndex.getId();
                indexs.add(indexId);
            }
        }

        int tryCnt = 0;
        while (true) {
            if (tryCnt++ > Config.drop_rpc_retry_num) {
                LOG.warn("failed to drop index {} of table {}, try cnt {} reaches maximum retry count",
                            indexs, olapTable.getId(), tryCnt);
                break;
            }

            try {
                if (indexs.isEmpty()) {
                    break;
                }
                dropMaterializedIndex(olapTable.getId(), indexs, true);
            } catch (Exception e) {
                LOG.warn("failed to drop index {} of table {}, try cnt {}, execption {}",
                        indexs, olapTable.getId(), tryCnt, e);
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    LOG.warn("Thread sleep is interrupted");
                }
                continue;
            }
            break;
        }
    }

    @Override
    public void erasePartitionDropBackendReplicas(List<Partition> partitions) {
        if (!Env.getCurrentEnv().isMaster() || partitions.isEmpty()) {
            return;
        }

        long tableId = -1;
        List<Long> partitionIds = Lists.newArrayList();
        List<Long> indexIds = Lists.newArrayList();
        for (Partition partition : partitions) {
            for (MaterializedIndex index : partition.getMaterializedIndices(IndexExtState.ALL)) {
                indexIds.add(index.getId());
                if (tableId == -1) {
                    tableId = ((CloudReplica) index.getTablets().get(0).getReplicas().get(0)).getTableId();
                }
            }
            partitionIds.add(partition.getId());
        }

        CloudPartition partition0 = (CloudPartition) partitions.get(0);

        int tryCnt = 0;
        while (true) {
            if (tryCnt++ > Config.drop_rpc_retry_num) {
                LOG.warn("failed to drop partition {} of table {}, try cnt {} reaches maximum retry count",
                        partitionIds, tableId, tryCnt);
                break;
            }
            try {
                dropCloudPartition(partition0.getDbId(), tableId, partitionIds, indexIds);
            } catch (Exception e) {
                LOG.warn("failed to drop partition {} of table {}, try cnt {}, execption {}",
                        partitionIds, tableId, tryCnt, e);
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    LOG.warn("Thread sleep is interrupted");
                }
                continue;
            }
            break;
        }
    }

    private void dropCloudPartition(long dbId, long tableId, List<Long> partitionIds, List<Long> indexIds)
            throws DdlException {
        Cloud.PartitionRequest.Builder partitionRequestBuilder =
                Cloud.PartitionRequest.newBuilder();
        partitionRequestBuilder.setCloudUniqueId(Config.cloud_unique_id);
        partitionRequestBuilder.setTableId(tableId);
        partitionRequestBuilder.addAllPartitionIds(partitionIds);
        partitionRequestBuilder.addAllIndexIds(indexIds);
        if (dbId > 0) {
            partitionRequestBuilder.setDbId(dbId);
        }
        final Cloud.PartitionRequest partitionRequest = partitionRequestBuilder.build();

        Cloud.PartitionResponse response = null;
        int tryTimes = 0;
        while (tryTimes++ < Config.meta_service_rpc_retry_times) {
            try {
                response = MetaServiceProxy.getInstance().dropPartition(partitionRequest);
                if (response.getStatus().getCode() != Cloud.MetaServiceCode.KV_TXN_CONFLICT) {
                    break;
                }
            } catch (RpcException e) {
                LOG.warn("tryTimes:{}, dropPartition RpcException", tryTimes, e);
                if (tryTimes + 1 >= Config.meta_service_rpc_retry_times) {
                    throw new DdlException(e.getMessage());
                }
            }
            sleepSeveralMs();
        }

        if (response.getStatus().getCode() != Cloud.MetaServiceCode.OK) {
            LOG.warn("dropPartition response: {} ", response);
            throw new DdlException(response.getStatus().getMsg());
        }
    }

    public void dropMaterializedIndex(long tableId, List<Long> indexIds, boolean dropTable) throws DdlException {
        Cloud.IndexRequest.Builder indexRequestBuilder = Cloud.IndexRequest.newBuilder();
        indexRequestBuilder.setCloudUniqueId(Config.cloud_unique_id);
        indexRequestBuilder.addAllIndexIds(indexIds);
        indexRequestBuilder.setTableId(tableId);
        final Cloud.IndexRequest indexRequest = indexRequestBuilder.build();

        Cloud.IndexResponse response = null;
        int tryTimes = 0;
        while (tryTimes++ < Config.meta_service_rpc_retry_times) {
            try {
                response = MetaServiceProxy.getInstance().dropIndex(indexRequest);
                if (response.getStatus().getCode() != Cloud.MetaServiceCode.KV_TXN_CONFLICT) {
                    break;
                }
            } catch (RpcException e) {
                LOG.warn("tryTimes:{}, dropIndex RpcException", tryTimes, e);
                if (tryTimes + 1 >= Config.meta_service_rpc_retry_times) {
                    throw new DdlException(e.getMessage());
                }
            }
            sleepSeveralMs();
        }

        if (response.getStatus().getCode() != Cloud.MetaServiceCode.OK) {
            LOG.warn("dropIndex response: {} ", response);
            throw new DdlException(response.getStatus().getMsg());
        }
    }

    // END DROP TABLE

    @Override
    public void checkAvailableCapacity(Database db) throws DdlException {
    }

    private void sleepSeveralMs() {
        // sleep random millis [20, 200] ms, avoid txn conflict
        int randomMillis = 20 + (int) (Math.random() * (200 - 20));
        if (LOG.isDebugEnabled()) {
            LOG.debug("randomMillis:{}", randomMillis);
        }
        try {
            Thread.sleep(randomMillis);
        } catch (InterruptedException e) {
            LOG.info("ignore InterruptedException: ", e);
        }
    }

    public void dropStage(Cloud.StagePB.StageType stageType, String userName, String userId,
                          String stageName, String reason, boolean ifExists)
            throws DdlException {
        Cloud.DropStageRequest.Builder builder = Cloud.DropStageRequest.newBuilder()
                .setCloudUniqueId(Config.cloud_unique_id).setType(stageType);
        if (userName != null) {
            builder.setMysqlUserName(userName);
        }
        if (userId != null) {
            builder.setMysqlUserId(userId);
        }
        if (stageName != null) {
            builder.setStageName(stageName);
        }
        if (reason != null) {
            builder.setReason(reason);
        }
        Cloud.DropStageResponse response = null;
        int retryTime = 0;
        while (retryTime++ < 3) {
            try {
                response = MetaServiceProxy.getInstance().dropStage(builder.build());
                LOG.info("drop stage, stageType:{}, userName:{}, userId:{}, stageName:{}, reason:{}, "
                        + "retry:{}, response: {}", stageType, userName, userId, stageName, reason, retryTime,
                        response);
                // just retry kv conflict
                if (response.getStatus().getCode() != Cloud.MetaServiceCode.KV_TXN_CONFLICT) {
                    break;
                }
            } catch (RpcException e) {
                LOG.warn("dropStage response: {} ", response);
            }
            // sleep random millis [20, 200] ms, avoid txn conflict
            int randomMillis = 20 + (int) (Math.random() * (200 - 20));
            LOG.debug("randomMillis:{}", randomMillis);
            try {
                Thread.sleep(randomMillis);
            } catch (InterruptedException e) {
                LOG.info("InterruptedException: ", e);
            }
        }

        if (response == null || !response.hasStatus()) {
            throw new DdlException("metaService exception");
        }

        if (response.getStatus().getCode() != Cloud.MetaServiceCode.OK) {
            LOG.warn("dropStage response: {} ", response);
            if (response.getStatus().getCode() == Cloud.MetaServiceCode.STAGE_NOT_FOUND) {
                if (ifExists) {
                    return;
                } else {
                    throw new DdlException("Stage does not exists: " + stageName);
                }
            }
            throw new DdlException("internal error, try later");
        }
    }

    public void replayUpdateCloudReplica(UpdateCloudReplicaInfo info) throws MetaNotFoundException {
        Database db = getDbNullable(info.getDbId());
        if (db == null) {
            LOG.warn("replay update cloud replica, unknown database {}", info.toString());
            return;
        }
        OlapTable olapTable = (OlapTable) db.getTableNullable(info.getTableId());
        if (olapTable == null) {
            LOG.warn("replay update cloud replica, unknown table {}", info.toString());
            return;
        }

        olapTable.writeLock();
        try {
            unprotectUpdateCloudReplica(olapTable, info);
        } catch (Exception e) {
            LOG.warn("unexpected exception", e);
        } finally {
            olapTable.writeUnlock();
        }
    }

    private void unprotectUpdateCloudReplica(OlapTable olapTable, UpdateCloudReplicaInfo info) {
        LOG.debug("replay update a cloud replica {}", info);
        Partition partition = olapTable.getPartition(info.getPartitionId());
        MaterializedIndex materializedIndex = partition.getIndex(info.getIndexId());

        try {
            if (info.getTabletId() != -1) {
                Tablet tablet = materializedIndex.getTablet(info.getTabletId());
                Replica replica = tablet.getReplicaById(info.getReplicaId());
                Preconditions.checkNotNull(replica, info);

                String clusterId = info.getClusterId();
                String realClusterId = ((CloudSystemInfoService) Env.getCurrentSystemInfo())
                        .getCloudClusterIdByName(clusterId);
                LOG.debug("cluster Id {}, real cluster Id {}", clusterId, realClusterId);
                if (!Strings.isNullOrEmpty(realClusterId)) {
                    clusterId = realClusterId;
                }

                ((CloudReplica) replica).updateClusterToBe(clusterId, info.getBeId());

                LOG.debug("update single cloud replica cluster {} replica {} be {}", info.getClusterId(),
                        replica.getId(), info.getBeId());
            } else {
                List<Long> tabletIds = info.getTabletIds();
                for (int i = 0; i < tabletIds.size(); ++i) {
                    Tablet tablet = materializedIndex.getTablet(tabletIds.get(i));
                    Replica replica = tablet.getReplicas().get(0);
                    Preconditions.checkNotNull(replica, info);

                    String clusterId = info.getClusterId();
                    String realClusterId = ((CloudSystemInfoService) Env.getCurrentSystemInfo())
                            .getCloudClusterIdByName(clusterId);
                    LOG.debug("cluster Id {}, real cluster Id {}", clusterId, realClusterId);
                    if (!Strings.isNullOrEmpty(realClusterId)) {
                        clusterId = realClusterId;
                    }

                    LOG.debug("update cloud replica cluster {} replica {} be {}", info.getClusterId(),
                            replica.getId(), info.getBeIds().get(i));
                    ((CloudReplica) replica).updateClusterToBe(clusterId, info.getBeIds().get(i));
                }
            }
        } catch (Exception e) {
            LOG.warn("unexpected exception", e);
        }
    }
}
