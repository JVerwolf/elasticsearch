/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.indices;

import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.stats.ClusterStatsResponse;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.CollectionUtils;
import org.elasticsearch.node.NodeRoleSettings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.xcontent.XContentBuilder;
import org.junit.Ignore;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.elasticsearch.cluster.node.DiscoveryNode.STATELESS_ENABLED_SETTING_NAME;
import static org.elasticsearch.index.IndexSettings.INDEX_FAST_REFRESH_SETTING;
import static org.elasticsearch.index.IndexSettings.INDEX_REFRESH_INTERVAL_SETTING;
import static org.elasticsearch.index.cache.bitset.BitsetFilterCache.INDEX_LOAD_RANDOM_ACCESS_FILTERS_EAGERLY_SETTING;
import static org.elasticsearch.index.query.QueryBuilders.nestedQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.elasticsearch.indices.TestSystemIndexPluginThing.TEST_INDEX_NAME;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCountAndNoFailures;
import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 0)
public class BitSetFilterCacheIT extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return CollectionUtils.appendToCopy(super.nodePlugins(), TestSystemIndexPluginThing.class);
    }

    @Override
    protected Collection<Class<? extends Plugin>> getMockPlugins() {
        return List.of(TestSettingsPlugin.class);
    }

    public static final class TestSettingsPlugin extends Plugin {
        @Override
        public List<Setting<?>> getSettings() {
            return Collections.singletonList(TEST_STATELESS_SETTING);
        }
    }

    protected static final Setting<Boolean> TEST_STATELESS_SETTING = Setting.boolSetting(
        STATELESS_ENABLED_SETTING_NAME,
        true,
        Setting.Property.NodeScope
    );

    @Ignore

    private void setupIndexingNode() {
        Settings nodeSettings = Settings.builder()
            .putList(
                NodeRoleSettings.NODE_ROLES_SETTING.getKey(),
                DiscoveryNodeRole.INDEX_ROLE.roleName(),
                DiscoveryNodeRole.MASTER_ROLE.roleName()
            )
            .put(STATELESS_ENABLED_SETTING_NAME, true)
            .build();

        internalCluster().startNode(nodeSettings);
        waitForNodes(1);
    }

    public void testEagerLoadingOnIndexNode() throws Exception {
        setupIndexingNode();
        var indexSettingsBuilder = Settings.builder()
            .put(indexSettings())
            .put(INDEX_REFRESH_INTERVAL_SETTING.getKey(), -1)
            .put(INDEX_FAST_REFRESH_SETTING.getKey(), true)
            .put(INDEX_LOAD_RANDOM_ACCESS_FILTERS_EAGERLY_SETTING.getKey(), true);
        var indexName = TEST_INDEX_NAME;
        // assertAcked(prepareCreate(indexName).setSettings(indexSettingsBuilder));

        // index and search non nested docs, which should not populate the BitSetFilterCache.
        indexAndSearchNonNestedDocs(indexName);

        // Check that the BitSetFilterCache is not populated.
        ClusterStatsResponse clusterStatsResponse1 = clusterAdmin().prepareClusterStats().get();
        assertThat(clusterStatsResponse1.getIndicesStats().getSegments().getBitsetMemoryInBytes(), equalTo(0L));

        // Index and query nested docs, which should populate the BitSetFilterCache.
        indexAndQueryNestedDocs(indexName);

        // Check that the BitSetFilterCache is populated.
        ClusterStatsResponse clusterStatsResponse2 = clusterAdmin().prepareClusterStats().get();
        assertThat(clusterStatsResponse2.getIndicesStats().getSegments().getBitsetMemoryInBytes(), greaterThan(0L));
    }

    private void indexAndSearchNonNestedDocs(String indexName) {
        prepareIndex(indexName).setId("0").setSource("field", "value").get();
        refresh();
        ensureSearchable(indexName);
    }

    private void indexAndQueryNestedDocs(String indexName) throws IOException {
        assertAcked(indicesAdmin().preparePutMapping(indexName).setSource("array1", "type=nested"));
        XContentBuilder builder = jsonBuilder().startObject()
            .startArray("array1")
            .startObject()
            .field("field1", "value1")
            .endObject()
            .endArray()
            .endObject();
        // index simple data
        prepareIndex(indexName).setId("2").setSource(builder).get();
        prepareIndex(indexName).setId("3").setSource(builder).get();
        prepareIndex(indexName).setId("4").setSource(builder).get();
        prepareIndex(indexName).setId("5").setSource(builder).get();
        prepareIndex(indexName).setId("6").setSource(builder).get();
        refresh();
        ensureSearchable(indexName);

        assertHitCountAndNoFailures(
            prepareSearch(indexName).setQuery(nestedQuery("array1", termQuery("array1.field1", "value1"), ScoreMode.Avg)),
            5L
        );
    }

    private void waitForNodes(int numNodes) {
        ClusterHealthResponse actionGet = clusterAdmin().health(
            new ClusterHealthRequest(new String[] {}).waitForEvents(Priority.LANGUID).waitForNodes(Integer.toString(numNodes))
        ).actionGet();
        assertThat(actionGet.isTimedOut(), is(false));
    }
}
