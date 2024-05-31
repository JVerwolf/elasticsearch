/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.rank.script;

import org.elasticsearch.script.Script;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.retriever.RetrieverBuilder;
import org.elasticsearch.search.retriever.RetrieverParserContext;
import org.elasticsearch.xcontent.ObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class ScriptRankRetrieverBuilder extends RetrieverBuilder {
    public static final String NAME = "script_rank";

    public static final int DEFAULT_WINDOW_SIZE = 100;

    public static final ParseField RETRIEVERS_FIELD = new ParseField("retrievers");
    public static final ParseField WINDOW_SIZE_FIELD = new ParseField("window_size");
    public static final ParseField SCRIPT_FIELD = new ParseField("script");
    public static final ParseField FIELDS_FIELD = new ParseField("fields");

    public static final ObjectParser<ScriptRankRetrieverBuilder, RetrieverParserContext> PARSER = new ObjectParser<>(
        NAME,
        ScriptRankRetrieverBuilder::new
    );

    static {
        PARSER.declareObjectArray((v, l) -> v.retrieverBuilders = l, (p, c) -> {
            p.nextToken();
            String name = p.currentName();
            RetrieverBuilder retrieverBuilder = p.namedObject(RetrieverBuilder.class, name, c);
            p.nextToken();
            return retrieverBuilder;
        }, RETRIEVERS_FIELD);
        PARSER.declareInt((b, v) -> b.windowSize = v, WINDOW_SIZE_FIELD);
        PARSER.declareObject(
            (builder, parsedValue) -> builder.script = parsedValue,
            (parser, context) -> Script.parse(parser),
            SCRIPT_FIELD
        );
        PARSER.declareStringArray((b, v) -> b.fields = v, FIELDS_FIELD);

        RetrieverBuilder.declareBaseParserFields(NAME, PARSER);
    }

    public static ScriptRankRetrieverBuilder fromXContent(XContentParser parser, RetrieverParserContext context) throws IOException {
        return PARSER.apply(parser, context);
    }

    private List<? extends RetrieverBuilder> retrieverBuilders = Collections.emptyList();
    private int windowSize = ScriptRankRetrieverBuilder.DEFAULT_WINDOW_SIZE;
    private Script script = null;
    private List<String> fields = new ArrayList<>();

    public ScriptRankRetrieverBuilder() {}

    public ScriptRankRetrieverBuilder(
        List<? extends RetrieverBuilder> retrieverBuilders,
        int windowSize,
        Script script,
        List<String> fields
    ) {
        this.retrieverBuilders = retrieverBuilders;
        this.windowSize = windowSize;
        this.script = script;
        this.fields = fields;
    }

    @Override
    protected void doToXContent(XContentBuilder builder, Params params) throws IOException {
        for (RetrieverBuilder retrieverBuilder : retrieverBuilders) {
            builder.startArray(RETRIEVERS_FIELD.getPreferredName());
            retrieverBuilder.toXContent(builder, params);
            builder.endArray();
        }

        builder.field(WINDOW_SIZE_FIELD.getPreferredName(), windowSize);
        builder.field(SCRIPT_FIELD.getPreferredName(), script);
        builder.field(FIELDS_FIELD.getPreferredName(), fields);
    }

    @Override
    public void extractToSearchSourceBuilder(SearchSourceBuilder searchSourceBuilder, boolean compoundUsed) {
        for (RetrieverBuilder retrieverBuilder : retrieverBuilders) {
            retrieverBuilder.extractToSearchSourceBuilder(searchSourceBuilder, true);
        }

        if (searchSourceBuilder.rankBuilder() == null) {
            searchSourceBuilder.rankBuilder(new ScriptRankBuilder(windowSize, script, fields));
        } else {
            throw new IllegalStateException("[rank] cannot be declared as a retriever value and as a global value");
        }
    }

    @Override
    public String getName() {
        return "ScriptRankRetrieverBuilder";
    }

    @Override
    public boolean doEquals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (super.equals(o) == false) return false;
        ScriptRankRetrieverBuilder that = (ScriptRankRetrieverBuilder) o;
        return windowSize == that.windowSize
            && Objects.equals(retrieverBuilders, that.retrieverBuilders)
            && Objects.equals(script, that.script)
            && Objects.equals(fields, that.fields);
    }

    @Override
    public int doHashCode() {
        return Objects.hash(super.hashCode(), retrieverBuilders, windowSize, script, fields);
    }
}
