/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.indices;

import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SystemIndexPlugin;

import java.util.Collection;
import java.util.List;

import static org.elasticsearch.index.IndexSettings.INDEX_FAST_REFRESH_SETTING;
import static org.elasticsearch.index.IndexSettings.INDEX_REFRESH_INTERVAL_SETTING;
import static org.elasticsearch.index.cache.bitset.BitsetFilterCache.INDEX_LOAD_RANDOM_ACCESS_FILTERS_EAGERLY_SETTING;

public class TestSystemIndexPluginThing extends Plugin implements SystemIndexPlugin {
    public static final String TEST_INDEX_NAME = ".test-bitset-filter-cache";

    Settings indexSettings = Settings.builder()
        .put(IndexMetadata.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 1)
        .put(IndexMetadata.INDEX_AUTO_EXPAND_REPLICAS_SETTING.getKey(), "0-1")
        .put(IndexMetadata.SETTING_PRIORITY, Integer.MAX_VALUE)
        .put(INDEX_REFRESH_INTERVAL_SETTING.getKey(), -1)
        .put(INDEX_FAST_REFRESH_SETTING.getKey(), true)
        .put(INDEX_LOAD_RANDOM_ACCESS_FILTERS_EAGERLY_SETTING.getKey(), true)
        .build();

    @Override
    public Collection<SystemIndexDescriptor> getSystemIndexDescriptors(Settings settings) {
        return List.of(new TestSystemIndexDescriptorThing(indexSettings));
    }

    @Override
    public String getFeatureName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public String getFeatureDescription() {
        return this.getClass().getCanonicalName();
    }

    public static class TestSystemIndexDescriptorThing extends TestSystemIndexDescriptor {

        public static final String PRIMARY_INDEX_NAME = TEST_INDEX_NAME + "-1";

        TestSystemIndexDescriptorThing(Settings settings) {
            super(TEST_INDEX_NAME, PRIMARY_INDEX_NAME, false, settings);
        }
    }
}
