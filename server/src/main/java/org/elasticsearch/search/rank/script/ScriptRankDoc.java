/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.rank.script;

import org.apache.lucene.search.ScoreDoc;

import java.util.Map;

/**
 * The context object for each doc passed into the ScriptRankQuery's script. Each retriever in
 * the original query has a corresponding list of ScriptRankDoc outputs which are passed into
 * to the script.
 */
public final class ScriptRankDoc {
    private final ScoreDoc scoreDoc;
    private final Map<String, Object> fields;
    private float newScore;

    private Object userContext;

    /**
     * @param scoreDoc A ScoreDoc containing the doc's (retriever) score, doc id, and shard id.
     * @param fields   A Map of field names to values, as per those requested in the "fields"
     *                 parameter of the script rank query.
     */
    public ScriptRankDoc(ScoreDoc scoreDoc, Map<String, Object> fields) {
        this.scoreDoc = scoreDoc;
        this.fields = fields;
    }

    /**
     * @return The original score, as assigned by the retriever.
     */
    public float getOriginalScore() {
        return scoreDoc.score;
    }

    /**
     * TODO
     *
     * @return
     */
    public float getNewScore() {
        return newScore;
    }

    /**
     * TODO
     *
     * @param newScore
     */
    public void setNewScore(float newScore) {
        this.newScore = newScore;
    }

    /**
     * Use to access a field, as specified for retrieval by the "fields" param
     * of the request.
     *
     * @param field The name of the field.
     * @return The field value as contained in the _source. Returns null if
     * the given document does not contain this field.
     */
    public Object getField(String field) {
        return fields.get(field);
    }

    /**
     * TODO update documentation
     * A convenience method for stashing any context for later use by the
     * script. Discarded after script execution.
     * <p>
     * Examples include script-generated scores/ranks, which can then be
     * accessed at a later time by the script for sorting/ordering results
     * prior to returning.
     */
    public Object getUserContext() {
        return userContext;
    }

    /**
     * TODO update documentation
     * A convenience method for stashing any context for later use by the
     * script. Discarded after script execution.
     * <p>
     * Examples include script-generated scores/ranks, which can then be
     * accessed at a later time by the script for sorting/ordering results
     * prior to returning.
     *
     * @param userCtx
     */
    public void setUserContext(Object userCtx) {
        this.userContext = userCtx;
    }

    /**
     * Not exposed to the script, for internal processing.
     *
     * @return The underlying ScoreDoc.
     */
    ScoreDoc getScoreDoc() {
        return scoreDoc;
    }
}
