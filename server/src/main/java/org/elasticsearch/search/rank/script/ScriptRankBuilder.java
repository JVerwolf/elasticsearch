/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.rank.script;

import org.apache.lucene.search.Query;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.rank.RankBuilder;
import org.elasticsearch.search.rank.context.QueryPhaseRankCoordinatorContext;
import org.elasticsearch.search.rank.context.QueryPhaseRankShardContext;
import org.elasticsearch.search.rank.context.RankFeaturePhaseRankCoordinatorContext;
import org.elasticsearch.search.rank.context.RankFeaturePhaseRankShardContext;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class ScriptRankBuilder extends RankBuilder {

    public static ScriptRankBuilder fromXContent(XContentParser parser) {
        throw new UnsupportedOperationException("Use Retrievers instead");
    }

    private final Script script;
    private final List<String> fieldNames;

    public ScriptRankBuilder(int rankWindowSize, Script script, List<String> fieldNames) {
        super(rankWindowSize);
        this.script = Objects.requireNonNull(script);
        this.fieldNames = Objects.requireNonNull(fieldNames);
    }

    public ScriptRankBuilder(StreamInput in) throws IOException {
        super(in);
        this.script = new Script(in);
        this.fieldNames = in.readStringCollectionAsList();
    }

    @Override
    public String getWriteableName() {
        return ScriptRankRetrieverBuilder.NAME;
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return TransportVersions.SCRIPT_RANK_ADDED;
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        this.script.writeTo(out);
        out.writeStringCollection(fieldNames);
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        throw new UnsupportedOperationException("TODO"); // Todo
    }

    @Override
    public boolean isCompoundBuilder() {
        return false;
    }

    @Override
    public QueryPhaseRankShardContext buildQueryPhaseShardContext(List<Query> queries, int from) {
        return new ScriptRankQueryPhaseShardContext(queries, rankWindowSize());
    }

    @Override
    public QueryPhaseRankCoordinatorContext buildQueryPhaseCoordinatorContext(int size, int from, ScriptService scriptService) {
        return new ScriptRankQueryPhaseCoordinatorContext(rankWindowSize(), scriptService, script);
    }

    @Override
    public RankFeaturePhaseRankShardContext buildRankFeaturePhaseShardContext(int size, int from, ScriptService scriptService) {
        return new ScriptRankRankPhaseShardContext(fieldNames);
    }

    @Override
    public RankFeaturePhaseRankCoordinatorContext buildRankFeaturePhaseCoordinatorContext(int size, int from, Client client) {
        return new ScriptRankRankPhaseCoordinatorContext(size, from, rankWindowSize(), script, fieldNames);
    }

    @Override
    protected boolean doEquals(RankBuilder other) {
        return Objects.equals(
            script,
            ((ScriptRankBuilder) other).script) && Objects.equals(fieldNames, ((ScriptRankBuilder) other).fieldNames
        );
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(script, fieldNames);
    }
}
