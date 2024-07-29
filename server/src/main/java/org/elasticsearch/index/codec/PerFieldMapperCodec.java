/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.codec;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.PostingsFormat;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.index.codec.zstd.Zstd814StoredFieldsFormat;
import org.elasticsearch.index.mapper.MapperService;

/**
 * {@link PerFieldMapperCodec This Lucene codec} provides the default
 * {@link PostingsFormat} and {@link KnnVectorsFormat} for Elasticsearch. It utilizes the
 * {@link MapperService} to lookup a {@link PostingsFormat} and {@link KnnVectorsFormat} per field. This
 * allows users to change the low level postings format and vectors format for individual fields
 * per index in real time via the mapping API. If no specific postings format or vector format is
 * configured for a specific field the default postings or vector format is used.
 */
public final class PerFieldMapperCodec extends Elasticsearch814Codec {
    private static final Logger logger = LogManager.getLogger(PerFieldMapperCodec.class); // todo remove

    private final PerFieldFormatSupplier formatSupplier;

    public PerFieldMapperCodec(
        Zstd814StoredFieldsFormat.Mode compressionMode,
        MapperService mapperService,
        BigArrays bigArrays,
        Settings nodeSettings
    ) {
        super(compressionMode);
        this.formatSupplier = new PerFieldFormatSupplier(mapperService, bigArrays, nodeSettings);
        // If the below assertion fails, it is a sign that Lucene released a new codec. You must create a copy of the current Elasticsearch
        // codec that delegates to this new Lucene codec, and make PerFieldMapperCodec extend this new Elasticsearch codec.
        assert Codec.forName(Lucene.LATEST_CODEC).getClass() == delegate.getClass()
            : "PerFieldMapperCodec must be on the latest lucene codec: " + Lucene.LATEST_CODEC;
    }

    /**
     * Only used at write time.
     */
    @Override
    public PostingsFormat getPostingsFormatForField(String field) {
        logger.warn("potato org.elasticsearch.index.codec.PerFieldMapperCodec.getPostingsFormatForField");
        return formatSupplier.getPostingsFormatForField(field);
    }

    @Override
    public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
        return formatSupplier.getKnnVectorsFormatForField(field);
    }

    @Override
    public DocValuesFormat getDocValuesFormatForField(String field) {
        return formatSupplier.getDocValuesFormatForField(field);
    }

}
