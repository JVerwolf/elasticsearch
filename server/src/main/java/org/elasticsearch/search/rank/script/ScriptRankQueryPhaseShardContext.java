/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.rank.script;

import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.elasticsearch.search.rank.RankShardResult;
import org.elasticsearch.search.rank.context.QueryPhaseRankShardContext;

import java.util.List;

public class ScriptRankQueryPhaseShardContext extends QueryPhaseRankShardContext {

    public ScriptRankQueryPhaseShardContext(List<Query> queries, int windowSize) {
        super(queries, windowSize);
    }

    @Override
    public RankShardResult combineQueryPhaseResults(List<TopDocs> rankResults) {
        return new ScriptRankShardResult(rankResults);
    }
}
