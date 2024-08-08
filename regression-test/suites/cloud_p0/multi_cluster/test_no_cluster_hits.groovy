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

import org.apache.doris.regression.suite.ClusterOptions
import groovy.json.JsonSlurper
import org.awaitility.Awaitility;
import static java.util.concurrent.TimeUnit.SECONDS;

suite('test_no_cluster_hits', 'multi_cluster') {
    if (!isCloudMode()) {
        return;
    }
    def options = new ClusterOptions()
    options.feConfigs += [
        'cloud_cluster_check_interval_second=1',
        'sys_log_verbose_modules=org',
        'max_query_retry_time=2000'
    ]
    options.setFeNum(3)
    options.setBeNum(3)
    options.cloudMode = true
    // options.connectToFollower = true
    options.enableDebugPoints()
    
    def user = "test_no_cluster_hits_user"
    def table = "test_no_cluster_table"

    docker(options) {
        sql """create user $user"""
        sql """CREATE TABLE $table (
            `k1` int(11) NULL,
            `k2` int(11) NULL
            )
            DUPLICATE KEY(`k1`, `k2`)
            COMMENT 'OLAP'
            DISTRIBUTED BY HASH(`k1`) BUCKETS 1
            PROPERTIES (
            "replication_num"="1"
            );
        """
        // no cluster auth
        sql """GRANT SELECT_PRIV ON *.*.* TO ${user}"""
        try {
            connectInDocker(user = user, password = '') {
                // errCode = 2, detailMessage = the user is not granted permission to the cluster, ClusterException: CURRENT_USER_NO_AUTH_TO_USE_ANY_CLUSTER
                sql """select * from information_schema.columns"""
            }
        } catch (Exception e) {
            logger.info("exception: {}", e.getMessage())
            assertTrue(e.getMessage().contains("ClusterException: CURRENT_USER_NO_AUTH_TO_USE_ANY_CLUSTER"))
            assertTrue(e.getMessage().contains("the user is not granted permission to the cluster"))
        } 
        def result = sql_return_maparray """show clusters"""
        logger.info("show cluster1 : {}", result)
        def currentCluster = result.stream().filter(cluster -> cluster.is_current == "TRUE").findFirst().orElse(null)
        sql """GRANT USAGE_PRIV ON CLUSTER ${currentCluster.cluster} TO $user"""
        connectInDocker(user = user, password = '') {
            try {
                sql """select * from information_schema.columns"""
            } catch (Exception e) {
                logger.info("exception: {}", e.getMessage())
                assertFalse(false, "impossible go here, somewhere has error")
            } 
        }

        sql """
            insert into $table values (1, 1)
        """

        // cluster all be abnormal
        cluster.stopBackends(1, 2, 3)
        try {
            // errCode = 2, detailMessage = All the Backend nodes in the current cluster compute_cluster are in an abnormal state, ClusterException: CLUSTERS_NO_ALIVE_BE
            sql """
                insert into $table values (2, 2)
            """
        } catch (Exception e) {
            logger.info("exception: {}", e.getMessage())
            assertTrue(e.getMessage().contains("ClusterException: CLUSTERS_NO_ALIVE_BE"))
            assertTrue(e.getMessage().contains("are in an abnormal state"))
        }
        
        try {
            // errCode = 2, detailMessage = tablet 10901 err: All the Backend nodes in the current cluster compute_cluster are in an abnormal state, ClusterException: CLUSTERS_NO_ALIVE_BE
            sql """
                select * from $table
            """
        } catch (Exception e) {
            logger.info("exception: {}", e.getMessage())
            assertTrue(e.getMessage().contains("ClusterException: CLUSTERS_NO_ALIVE_BE"))
            assertTrue(e.getMessage().contains("are in an abnormal state"))
        }

        cluster.startBackends(1, 2, 3)
        result = sql """insert into $table values (3, 3)"""
        result = sql """select * from $table"""
        log.info("result = {}", result)
        assertEquals(2, result.size())

        sql """REVOKE USAGE_PRIV ON CLUSTER ${currentCluster.cluster} from $user"""
        try {
            connectInDocker(user = user, password = '') {
                sql """SET PROPERTY FOR '${user}' 'default_cloud_cluster' = '${currentCluster.cluster}'"""
                // errCode = 2, detailMessage = tablet 10901 err: default cluster compute_cluster check auth failed, ClusterException: CURRENT_USER_NO_AUTH_TO_USE_DEFAULT_CLUSTER
                sql """select * from $table"""
            }
        } catch (Exception e) {
            logger.info("exception: {}", e.getMessage())
            assertTrue(e.getMessage().contains("ClusterException: CURRENT_USER_NO_AUTH_TO_USE_DEFAULT_CLUSTER"))
            assertTrue(e.getMessage().contains("check auth failed"))
        } 

        // no cluster
        def tag = getCloudBeTagByName(currentCluster.cluster)
        logger.info("cluster1 = {}, tag = {}", currentCluster, tag)

        def jsonSlurper = new JsonSlurper()
        def jsonObject = jsonSlurper.parseText(tag)
        def cloudClusterId = jsonObject.cloud_cluster_id

        def ms = cluster.getAllMetaservices().get(0)
        logger.info("ms addr={}, port={}", ms.host, ms.httpPort)
        drop_cluster(currentCluster.cluster, cloudClusterId, ms)

        dockerAwaitUntil(5) {
            result = sql_return_maparray """show clusters"""
            logger.info("show cluster2 : {}", result) 
            result.size() == 0
        }

        try {
            // errCode = 2, detailMessage = tablet 10901 err: The current cluster compute_cluster is not registered in the system, ClusterException: CURRENT_CLUSTER_NOT_EXIST
            sql """
                select * from $table
            """
        } catch (Exception e) {
            logger.info("exception: {}", e.getMessage())
            assertTrue(e.getMessage().contains("ClusterException: CURRENT_CLUSTER_NOT_EXIST"))
            assertTrue(e.getMessage().contains("The current cluster compute_cluster is not registered in the system"))
        } 
    }
}
