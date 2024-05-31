/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.rank.feature;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.search.rank.RankDoc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A {@link RankDoc} that contains field data to be used later by the reranker on the coordinator node.
 */
public class RankFeatureDoc extends RankDoc {

    // todo: update to support more than 1 fields; and not restrict to string data
    public List<Object> fieldValues = new ArrayList<>();

    public RankFeatureDoc(int doc, float score, int shardIndex) {
        super(doc, score, shardIndex);
    }

    public RankFeatureDoc(int doc, float score, int shardIndex, List<Object> fieldValues) {
        super(doc, score, shardIndex);
        this.fieldValues = fieldValues;
    }

    public RankFeatureDoc(StreamInput in) throws IOException {
        super(in);
        fieldValues = in.readCollectionAsList(StreamInput::readGenericValue);
    }

    public void featureData(List<Object> featureData) {
        this.fieldValues = featureData;
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeGenericList(fieldValues, StreamOutput::writeGenericValue);
    }

    @Override
    protected boolean doEquals(RankDoc rd) {
        RankFeatureDoc other = (RankFeatureDoc) rd;
        return Objects.equals(this.fieldValues, other.fieldValues);
    }

    @Override
    protected int doHashCode() {
        return Objects.hashCode(fieldValues);
    }
}
