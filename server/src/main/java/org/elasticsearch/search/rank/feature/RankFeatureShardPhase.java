/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.rank.feature;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.search.SearchContextSourcePrinter;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.fetch.FetchSearchResult;
import org.elasticsearch.search.fetch.subphase.FetchFieldsContext;
import org.elasticsearch.search.fetch.subphase.FieldAndFormat;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.rank.context.RankFeaturePhaseRankShardContext;
import org.elasticsearch.tasks.TaskCancelledException;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;

/**
 * The {@code RankFeatureShardPhase} executes the rank feature phase on the shard, iff there is a {@code RankBuilder} that requires it.
 * This phase is responsible for reading field data for a set of docids. To do this, it reuses the {@code FetchPhase} to read the required
 * fields for all requested documents using the `FetchFieldPhase` sub-phase.
 */
public final class RankFeatureShardPhase {

    private static final Logger LOGGER = LogManager.getLogger(RankFeatureShardPhase.class);

    public static final RankFeatureShardResult EMPTY_RESULT = new RankFeatureShardResult(new RankFeatureDoc[0]);

    public RankFeatureShardPhase() {}

    public void prepareForFetch(SearchContext searchContext, RankFeatureShardRequest request) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("{}", new SearchContextSourcePrinter(searchContext));
        }

        if (searchContext.isCancelled()) {
            throw new TaskCancelledException("cancelled");
        }

        RankFeaturePhaseRankShardContext rankFeaturePhaseRankShardContext = searchContext.request().source().rankBuilder() != null
            ? searchContext.request().source().rankBuilder().buildRankFeaturePhaseShardContext()
            : null;
        if (rankFeaturePhaseRankShardContext != null) {
            int[] docIds = request.getDocIds();
            if (docIds == null || docIds.length == 0) {
                return;
            }
            assert rankFeaturePhaseRankShardContext.getField() != null : "field must not be null";
            searchContext.fetchFieldsContext(
                new FetchFieldsContext(Collections.singletonList(new FieldAndFormat(rankFeaturePhaseRankShardContext.getField(), null)))
            );
            searchContext.addFetchResult();
            Arrays.sort(request.getDocIds());
        }
    }

    public void processFetch(SearchContext searchContext) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("{}", new SearchContextSourcePrinter(searchContext));
        }

        if (searchContext.isCancelled()) {
            throw new TaskCancelledException("cancelled");
        }

        RankFeaturePhaseRankShardContext rankFeaturePhaseRankShardContext = searchContext.request().source().rankBuilder() != null
            ? searchContext.request().source().rankBuilder().buildRankFeaturePhaseShardContext()
            : null;
        if (rankFeaturePhaseRankShardContext != null) {
            RankFeatureShardResult featureRankShardResult = null;
            SearchHits hits = null;
            try {
                // TODO: here we populate the profile part of the fetchResult as well
                // we need to see what info we want to include on the overall profiling section. This is something that is per-shard
                // so most likely we will still care about the `FetchFieldPhase` profiling info as we could potentially
                // operate on `rank_window_size` instead of just `size` results, so this could be much more expensive.
                FetchSearchResult fetchSearchResult = searchContext.fetchResult();
                if (fetchSearchResult == null || fetchSearchResult.hits() == null) {
                    return;
                }
                hits = fetchSearchResult.hits();
                featureRankShardResult = (RankFeatureShardResult) rankFeaturePhaseRankShardContext.buildRankFeatureShardResult(
                    hits,
                    searchContext.shardTarget().getShardId().id()
                );
            } finally {
                if (hits != null) {
                    hits.decRef();
                }
                // save the result in the search context
                // need to add profiling info as well
                // available from fetch
                searchContext.rankFeatureResult().shardResult(Objects.requireNonNullElse(featureRankShardResult, EMPTY_RESULT));
                searchContext.rankFeatureResult().incRef();
            }
        }
    }
}
