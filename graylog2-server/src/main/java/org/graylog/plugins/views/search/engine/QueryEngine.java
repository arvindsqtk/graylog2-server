/*
 * Copyright (C) 2020 Graylog, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Server Side Public License, version 1,
 * as published by MongoDB, Inc.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * Server Side Public License for more details.
 *
 * You should have received a copy of the Server Side Public License
 * along with this program. If not, see
 * <http://www.mongodb.com/licensing/server-side-public-license>.
 */
package org.graylog.plugins.views.search.engine;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.graylog.plugins.views.search.Query;
import org.graylog.plugins.views.search.QueryMetadata;
import org.graylog.plugins.views.search.QueryMetadataDecorator;
import org.graylog.plugins.views.search.QueryResult;
import org.graylog.plugins.views.search.Search;
import org.graylog.plugins.views.search.SearchJob;
import org.graylog.plugins.views.search.errors.QueryError;
import org.graylog.plugins.views.search.errors.SearchError;
import org.graylog.plugins.views.search.errors.SearchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.apache.commons.lang3.StringUtils.defaultIfEmpty;

@Singleton
public class QueryEngine {
    private static final Logger LOG = LoggerFactory.getLogger(QueryEngine.class);

    private final Set<QueryMetadataDecorator> queryMetadataDecorators;
    private final QueryParser queryParser;

    // TODO proper thread pool with tunable settings
    private final Executor queryPool = Executors.newFixedThreadPool(4, new ThreadFactoryBuilder().setNameFormat("query-engine-%d").build());
    private final QueryBackend<? extends GeneratedQueryContext> elasticsearchBackend;
    private final Provider<SearchConfig> searchConfig;

    @Inject
    public QueryEngine(QueryBackend<? extends GeneratedQueryContext> elasticsearchBackend,
                       Set<QueryMetadataDecorator> queryMetadataDecorators,
                       QueryParser queryParser, Provider<SearchConfig> searchConfig) {
        this.elasticsearchBackend = elasticsearchBackend;
        this.queryMetadataDecorators = queryMetadataDecorators;
        this.queryParser = queryParser;
        this.searchConfig = searchConfig;
    }

    // TODO: Backwards-compatible constructor to avoid breakage. Remove at some point.
    @Deprecated
    public QueryEngine(Map<String, QueryBackend<? extends GeneratedQueryContext>> backends,
                       Set<QueryMetadataDecorator> queryMetadataDecorators,
                       QueryParser queryParser, Provider<SearchConfig> searchConfig) {
        this(backends.get("elasticsearch"), queryMetadataDecorators, queryParser, searchConfig);
    }

    public QueryMetadata parse(Search search, Query query) {
        final QueryMetadata parsedMetadata = queryParser.parse(query);

        return this.queryMetadataDecorators.stream()
                .reduce((decorator1, decorator2) -> (s, q, metadata) -> decorator1.decorate(s, q, decorator2.decorate(s, q, metadata)))
                .map(decorator -> decorator.decorate(search, query, parsedMetadata))
                .orElse(parsedMetadata);
    }

    public SearchJob execute(SearchJob searchJob) {
        searchJob.getSearch().queries().forEach(query -> searchJob.addQueryResultFuture(query.id(),
                // generate and run each query, making sure we never let an exception escape
                // if need be we default to an empty result with a failed state and the wrapped exception
                CompletableFuture.supplyAsync(() -> prepareAndRun(searchJob, query), queryPool)
                        .handle((queryResult, throwable) -> {
                            if (throwable != null) {
                                final Throwable cause = throwable.getCause();
                                final SearchError error;
                                if (cause instanceof SearchException) {
                                    error = ((SearchException) cause).error();
                                } else {
                                    error = new QueryError(query, cause);
                                }
                                LOG.debug("Running query {} failed: {}", query.id(), cause);
                                searchJob.addError(error);
                                return QueryResult.failedQueryWithError(query, error);
                            }
                            return queryResult;
                        })
        ));

        searchJob.getSearch().queries().forEach(query -> {
            final CompletableFuture<QueryResult> queryResultFuture = searchJob.getQueryResultFuture(query.id());
            if (!queryResultFuture.isDone()) {
                // this is not going to throw an exception, because we will always replace it with a placeholder "FAILED" result above
                final QueryResult result = queryResultFuture.join();

            } else {
                LOG.debug("[{}] Not generating query for query {}", defaultIfEmpty(query.id(), "root"), query);
            }
        });

        LOG.debug("Search job {} executing", searchJob.getId());
        return searchJob.seal();
    }

    private QueryResult prepareAndRun(SearchJob searchJob, Query query) {

        final QueryBackend<? extends GeneratedQueryContext> backend = getQueryBackend(query);
        LOG.debug("[{}] Using {} to generate query", query.id(), backend);
        // with all the results done, we can execute the current query and eventually complete our own result
        // if any of this throws an exception, the handle in #execute will convert it to an error and return a "failed" result instead
        // if the backend already returns a "failed result" then nothing special happens here
        final GeneratedQueryContext generatedQueryContext = backend.generate(searchJob, query, searchConfig.get());
        LOG.trace("[{}] Generated query {}, running it on backend {}", query.id(), generatedQueryContext, backend);
        final QueryResult result = backend.run(searchJob, query, generatedQueryContext);
        LOG.debug("[{}] Query returned {}", query.id(), result);
        if (!generatedQueryContext.errors().isEmpty()) {
            generatedQueryContext.errors().forEach(searchJob::addError);
        }
        return result;
    }

    private QueryBackend<? extends GeneratedQueryContext> getQueryBackend(Query query) {
        return elasticsearchBackend;
    }

}
