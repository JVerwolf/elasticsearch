/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.rank.script;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.rank.RankShardResult;
import org.elasticsearch.search.rank.context.RankFeaturePhaseRankShardContext;
import org.elasticsearch.search.rank.feature.RankFeatureDoc;
import org.elasticsearch.search.rank.feature.RankFeatureShardResult;

import java.util.Arrays;
import java.util.List;

public class ScriptRankRankPhaseShardContext extends RankFeaturePhaseRankShardContext {

    private static final Logger logger = LogManager.getLogger(ScriptRankRankPhaseShardContext.class);

    public ScriptRankRankPhaseShardContext(List<String> fieldNames) {
        super(fieldNames);
    }

    @Override
    public RankShardResult buildRankFeatureShardResult(SearchHits hits, int shardId) {
        try {
            RankFeatureDoc[] rankFeatureDocs = new RankFeatureDoc[hits.getHits().length];
            for (int i = 0; i < hits.getHits().length; i++) {
                rankFeatureDocs[i] = new RankFeatureDoc(hits.getHits()[i].docId(), hits.getHits()[i].getScore(), shardId);
                for (int j = 0; j < fieldNames.size(); j++) {
                    DocumentField docField = hits.getHits()[i].field(fieldNames.get(j));
                    if(docField.getValues().size() >1) {
                        rankFeatureDocs[i].fieldValues.add(docField.getValues());
                    }else{
                        rankFeatureDocs[i].fieldValues.add(docField.getValue());
                    }
                }
            }
            return new RankFeatureShardResult(rankFeatureDocs);
        } catch (Exception ex) {
            logger.warn(
                "Error while fetching feature data for {field: ["
                    + fieldNames
                    + "]} and {docIds: ["
                    + Arrays.stream(hits.getHits()).map(SearchHit::docId).toList()
                    + "]}.",
                ex
            );
            return null;
        }
    }
}
