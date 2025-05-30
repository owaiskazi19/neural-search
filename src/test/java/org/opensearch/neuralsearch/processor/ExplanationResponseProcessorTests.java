/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor;

import lombok.SneakyThrows;
import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TotalHits;
import org.opensearch.action.OriginalIndices;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.search.SearchResponseSections;
import org.opensearch.action.search.ShardSearchFailure;
import org.opensearch.core.index.Index;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.neuralsearch.processor.explain.CombinedExplanationDetails;
import org.opensearch.neuralsearch.processor.explain.ExplanationDetails;
import org.opensearch.neuralsearch.processor.explain.ExplanationPayload;
import org.opensearch.search.DocValueFormat;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.search.SearchShardTarget;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.internal.InternalSearchResponse;
import org.opensearch.search.pipeline.PipelineProcessingContext;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.RemoteClusterAware;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.opensearch.neuralsearch.plugin.NeuralSearch.EXPLANATION_RESPONSE_KEY;
import static org.opensearch.neuralsearch.processor.explain.ExplanationPayload.PayloadType.NORMALIZATION_PROCESSOR;
import static org.opensearch.neuralsearch.util.TestUtils.DELTA_FOR_FLOATS_ASSERTION;
import static org.opensearch.neuralsearch.util.TestUtils.DELTA_FOR_SCORE_ASSERTION;

public class ExplanationResponseProcessorTests extends OpenSearchTestCase {
    private static final String PROCESSOR_TAG = "mockTag";
    private static final String DESCRIPTION = "mockDescription";

    public void testClassFields_whenCreateNewObject_thenAllFieldsPresent() {
        ExplanationResponseProcessor explanationResponseProcessor = new ExplanationResponseProcessor(DESCRIPTION, PROCESSOR_TAG, false);

        assertEquals(DESCRIPTION, explanationResponseProcessor.getDescription());
        assertEquals(PROCESSOR_TAG, explanationResponseProcessor.getTag());
        assertFalse(explanationResponseProcessor.isIgnoreFailure());
    }

    @SneakyThrows
    public void testPipelineContext_whenPipelineContextHasNoExplanationInfo_thenProcessorIsNoOp() {
        ExplanationResponseProcessor explanationResponseProcessor = new ExplanationResponseProcessor(DESCRIPTION, PROCESSOR_TAG, false);
        SearchRequest searchRequest = mock(SearchRequest.class);
        SearchResponse searchResponse = new SearchResponse(
            null,
            null,
            1,
            1,
            0,
            1000,
            new ShardSearchFailure[0],
            SearchResponse.Clusters.EMPTY
        );

        SearchResponse processedResponse = explanationResponseProcessor.processResponse(searchRequest, searchResponse);
        assertEquals(searchResponse, processedResponse);

        SearchResponse processedResponse2 = explanationResponseProcessor.processResponse(searchRequest, searchResponse, null);
        assertEquals(searchResponse, processedResponse2);

        PipelineProcessingContext pipelineProcessingContext = new PipelineProcessingContext();
        SearchResponse processedResponse3 = explanationResponseProcessor.processResponse(
            searchRequest,
            searchResponse,
            pipelineProcessingContext
        );
        assertEquals(searchResponse, processedResponse3);
    }

    @SneakyThrows
    public void testParsingOfExplanations_whenResponseHasExplanations_thenSuccessful() {
        // Setup
        ExplanationResponseProcessor explanationResponseProcessor = new ExplanationResponseProcessor(DESCRIPTION, PROCESSOR_TAG, false);
        SearchRequest searchRequest = mock(SearchRequest.class);
        float maxScore = 1.0f;
        SearchHits searchHits = getSearchHits(maxScore);
        SearchResponse searchResponse = getSearchResponse(searchHits);
        PipelineProcessingContext pipelineProcessingContext = new PipelineProcessingContext();
        Map<SearchShard, List<CombinedExplanationDetails>> combinedExplainDetails = getCombinedExplainDetails(searchHits);
        Map<ExplanationPayload.PayloadType, Object> explainPayload = Map.of(NORMALIZATION_PROCESSOR, combinedExplainDetails);
        ExplanationPayload explanationPayload = ExplanationPayload.builder().explainPayload(explainPayload).build();
        pipelineProcessingContext.setAttribute(EXPLANATION_RESPONSE_KEY, explanationPayload);

        // Act
        SearchResponse processedResponse = explanationResponseProcessor.processResponse(
            searchRequest,
            searchResponse,
            pipelineProcessingContext
        );

        // Assert
        assertOnExplanationResults(processedResponse, maxScore);
    }

    @SneakyThrows
    public void testParsingOfExplanations_whenFieldSortingAndExplanations_thenSuccessful() {
        // Setup
        ExplanationResponseProcessor explanationResponseProcessor = new ExplanationResponseProcessor(DESCRIPTION, PROCESSOR_TAG, false);
        SearchRequest searchRequest = mock(SearchRequest.class);

        float maxScore = 1.0f;
        SearchHits searchHitsWithoutSorting = getSearchHits(maxScore);
        for (SearchHit searchHit : searchHitsWithoutSorting.getHits()) {
            Explanation explanation = Explanation.match(1.0f, "combined score of:", Explanation.match(1.0f, "field1:[0 TO 100]"));
            searchHit.explanation(explanation);
        }
        TotalHits.Relation totalHitsRelation = randomFrom(TotalHits.Relation.values());
        TotalHits totalHits = new TotalHits(randomLongBetween(1, 1000), totalHitsRelation);
        final SortField[] sortFields = new SortField[] {
            new SortField("random-text-field-1", SortField.Type.INT, randomBoolean()),
            new SortField("random-text-field-2", SortField.Type.STRING, randomBoolean()) };
        SearchHits searchHits = new SearchHits(searchHitsWithoutSorting.getHits(), totalHits, maxScore, sortFields, null, null);

        SearchResponse searchResponse = getSearchResponse(searchHits);

        PipelineProcessingContext pipelineProcessingContext = new PipelineProcessingContext();
        Map<SearchShard, List<CombinedExplanationDetails>> combinedExplainDetails = getCombinedExplainDetails(searchHits);
        Map<ExplanationPayload.PayloadType, Object> explainPayload = Map.of(NORMALIZATION_PROCESSOR, combinedExplainDetails);
        ExplanationPayload explanationPayload = ExplanationPayload.builder().explainPayload(explainPayload).build();
        pipelineProcessingContext.setAttribute(EXPLANATION_RESPONSE_KEY, explanationPayload);

        // Act
        SearchResponse processedResponse = explanationResponseProcessor.processResponse(
            searchRequest,
            searchResponse,
            pipelineProcessingContext
        );

        // Assert
        assertOnExplanationResults(processedResponse, maxScore);
    }

    @SneakyThrows
    public void testParsingOfExplanations_whenScoreSortingAndExplanations_thenSuccessful() {
        // Setup
        ExplanationResponseProcessor explanationResponseProcessor = new ExplanationResponseProcessor(DESCRIPTION, PROCESSOR_TAG, false);
        SearchRequest searchRequest = mock(SearchRequest.class);

        float maxScore = 1.0f;

        SearchHits searchHits = getSearchHits(maxScore);

        SearchResponse searchResponse = getSearchResponse(searchHits);

        PipelineProcessingContext pipelineProcessingContext = new PipelineProcessingContext();

        Map<SearchShard, List<CombinedExplanationDetails>> combinedExplainDetails = getCombinedExplainDetails(searchHits);
        Map<ExplanationPayload.PayloadType, Object> explainPayload = Map.of(NORMALIZATION_PROCESSOR, combinedExplainDetails);
        ExplanationPayload explanationPayload = ExplanationPayload.builder().explainPayload(explainPayload).build();
        pipelineProcessingContext.setAttribute(EXPLANATION_RESPONSE_KEY, explanationPayload);

        // Act
        SearchResponse processedResponse = explanationResponseProcessor.processResponse(
            searchRequest,
            searchResponse,
            pipelineProcessingContext
        );

        // Assert
        assertOnExplanationResults(processedResponse, maxScore);
    }

    @SneakyThrows
    public void testProcessResponse_whenNullSearchHits_thenNoOp() {
        ExplanationResponseProcessor processor = new ExplanationResponseProcessor(DESCRIPTION, PROCESSOR_TAG, false);
        SearchRequest searchRequest = mock(SearchRequest.class);
        SearchResponse searchResponse = getSearchResponse(null);
        PipelineProcessingContext context = new PipelineProcessingContext();

        SearchResponse processedResponse = processor.processResponse(searchRequest, searchResponse, context);
        assertEquals(searchResponse, processedResponse);
    }

    @SneakyThrows
    public void testProcessResponse_whenEmptySearchHits_thenNoOp() {
        ExplanationResponseProcessor processor = new ExplanationResponseProcessor(DESCRIPTION, PROCESSOR_TAG, false);
        SearchRequest searchRequest = mock(SearchRequest.class);
        SearchHits emptyHits = new SearchHits(new SearchHit[0], new TotalHits(0, TotalHits.Relation.EQUAL_TO), 0.0f);
        SearchResponse searchResponse = getSearchResponse(emptyHits);
        PipelineProcessingContext context = new PipelineProcessingContext();

        SearchResponse processedResponse = processor.processResponse(searchRequest, searchResponse, context);
        assertEquals(searchResponse, processedResponse);
    }

    @SneakyThrows
    public void testProcessResponse_whenNullExplanation_thenSkipProcessing() {
        ExplanationResponseProcessor processor = new ExplanationResponseProcessor(DESCRIPTION, PROCESSOR_TAG, false);
        SearchRequest searchRequest = mock(SearchRequest.class);
        SearchHits searchHits = getSearchHits(1.0f);
        for (SearchHit hit : searchHits.getHits()) {
            hit.explanation(null);
        }
        SearchResponse searchResponse = getSearchResponse(searchHits);
        PipelineProcessingContext context = new PipelineProcessingContext();

        SearchResponse processedResponse = processor.processResponse(searchRequest, searchResponse, context);
        assertEquals(searchResponse, processedResponse);
    }

    @SneakyThrows
    public void testProcessResponse_whenInvalidExplanationPayload_thenHandleGracefully() {
        ExplanationResponseProcessor processor = new ExplanationResponseProcessor(DESCRIPTION, PROCESSOR_TAG, false);
        SearchRequest searchRequest = mock(SearchRequest.class);
        SearchHits searchHits = getSearchHits(1.0f);
        SearchResponse searchResponse = getSearchResponse(searchHits);
        PipelineProcessingContext context = new PipelineProcessingContext();

        // Set invalid payload
        Map<ExplanationPayload.PayloadType, Object> invalidPayload = Map.of(NORMALIZATION_PROCESSOR, "invalid payload");
        ExplanationPayload explanationPayload = ExplanationPayload.builder().explainPayload(invalidPayload).build();
        context.setAttribute(EXPLANATION_RESPONSE_KEY, explanationPayload);

        SearchResponse processedResponse = processor.processResponse(searchRequest, searchResponse, context);
        assertNotNull(processedResponse);
    }

    @SneakyThrows
    public void testProcessResponse_whenZeroScore_thenProcessCorrectly() {
        ExplanationResponseProcessor processor = new ExplanationResponseProcessor(DESCRIPTION, PROCESSOR_TAG, false);
        SearchRequest searchRequest = mock(SearchRequest.class);
        SearchHits searchHits = getSearchHits(0.0f);
        SearchResponse searchResponse = getSearchResponse(searchHits);
        PipelineProcessingContext context = new PipelineProcessingContext();

        Map<SearchShard, List<CombinedExplanationDetails>> combinedExplainDetails = getCombinedExplainDetails(searchHits);
        Map<ExplanationPayload.PayloadType, Object> explainPayload = Map.of(NORMALIZATION_PROCESSOR, combinedExplainDetails);
        ExplanationPayload explanationPayload = ExplanationPayload.builder().explainPayload(explainPayload).build();
        context.setAttribute(EXPLANATION_RESPONSE_KEY, explanationPayload);

        SearchResponse processedResponse = processor.processResponse(searchRequest, searchResponse, context);
        assertNotNull(processedResponse);
        assertEquals(0.0f, processedResponse.getHits().getMaxScore(), DELTA_FOR_SCORE_ASSERTION);
    }

    @SneakyThrows
    public void testProcessResponse_whenScoreIsNaN_thenExplanationUsesZero() {
        ExplanationResponseProcessor processor = new ExplanationResponseProcessor(DESCRIPTION, PROCESSOR_TAG, false);
        SearchRequest searchRequest = mock(SearchRequest.class);

        // Create SearchHits with NaN score
        SearchHits searchHits = getSearchHits(Float.NaN);
        SearchResponse searchResponse = getSearchResponse(searchHits);
        PipelineProcessingContext context = new PipelineProcessingContext();

        // Setup explanation payload
        Map<SearchShard, List<CombinedExplanationDetails>> combinedExplainDetails = getCombinedExplainDetails(searchHits);
        Map<ExplanationPayload.PayloadType, Object> explainPayload = Map.of(NORMALIZATION_PROCESSOR, combinedExplainDetails);
        ExplanationPayload explanationPayload = ExplanationPayload.builder().explainPayload(explainPayload).build();
        context.setAttribute(EXPLANATION_RESPONSE_KEY, explanationPayload);

        // Process response
        SearchResponse processedResponse = processor.processResponse(searchRequest, searchResponse, context);

        // Verify results
        assertNotNull(processedResponse);
        SearchHit[] hits = processedResponse.getHits().getHits();
        assertNotNull(hits);
        assertTrue(hits.length > 0);

        // Verify that the explanation uses 0.0f when input score was NaN
        Explanation explanation = hits[0].getExplanation();
        assertNotNull(explanation);
        assertEquals(0.0f, (float) explanation.getValue(), DELTA_FOR_FLOATS_ASSERTION);
    }

    @SneakyThrows
    public void testProcessResponseWithZeroScoreExplanations() {
        // Setup test data
        SearchHit searchHit = new SearchHit(1);
        SearchShardTarget shardTarget = new SearchShardTarget("node1", new ShardId("index", "_na_", 0), null, null);
        searchHit.shard(shardTarget);

        // Create original explanation with zero and non-zero scores
        Explanation originalExplanation = Explanation.match(
            1.0f,
            "original explanation",
            Arrays.asList(
                Explanation.match(0.0f, "zero score explanation"),
                Explanation.match(0.5f, "non-zero score explanation"),
                Explanation.match(0.0f, "another zero score explanation")
            )
        );
        searchHit.explanation(originalExplanation);

        // Create normalization details
        List<Pair<Float, String>> scoreDetails = Arrays.asList(
            Pair.of(0.0f, "normalized zero score"),
            Pair.of(0.8f, "normalized non-zero score"),
            Pair.of(0.0f, "normalized zero score 2")
        );
        ExplanationDetails normalizationExplanation = new ExplanationDetails(scoreDetails);

        // Setup response and context
        SearchHits searchHits = new SearchHits(new SearchHit[] { searchHit }, new TotalHits(1, TotalHits.Relation.EQUAL_TO), 1.0f);
        SearchResponse searchResponse = createSearchResponse(searchHits);
        PipelineProcessingContext context = createContextWithExplanations(searchHit.getShard(), normalizationExplanation);

        // Execute processor
        ExplanationResponseProcessor processor = new ExplanationResponseProcessor("test", "tag", false);
        SearchResponse processedResponse = processor.processResponse(new SearchRequest(), searchResponse, context);

        // Verify results
        SearchHit processedHit = processedResponse.getHits().getHits()[0];
        Explanation processedExplanation = processedHit.getExplanation();

        // Verify that only non-zero score explanations are included
        Explanation[] details = processedExplanation.getDetails();
        assertEquals(1, details.length);
        assertEquals(0.8f, details[0].getValue().floatValue(), 0.001f);
        assertEquals("normalized non-zero score", details[0].getDescription());
    }

    @SneakyThrows
    public void testProcessResponseWithAllZeroScores() {
        // Setup test data
        SearchHit searchHit = new SearchHit(1);
        SearchShardTarget shardTarget = new SearchShardTarget("node1", new ShardId("index", "_na_", 0), null, null);
        searchHit.shard(shardTarget);

        // Create original explanation with all zero scores
        Explanation originalExplanation = Explanation.match(
            0.0f,
            "original explanation",
            Arrays.asList(Explanation.match(0.0f, "zero score 1"), Explanation.match(0.0f, "zero score 2"))
        );
        searchHit.explanation(originalExplanation);

        // Create normalization details with all zeros
        List<Pair<Float, String>> scoreDetails = Arrays.asList(Pair.of(0.0f, "normalized zero 1"), Pair.of(0.0f, "normalized zero 2"));
        ExplanationDetails normalizationExplanation = new ExplanationDetails(scoreDetails);

        // Setup response and context
        SearchHits searchHits = new SearchHits(new SearchHit[] { searchHit }, new TotalHits(1, TotalHits.Relation.EQUAL_TO), 1.0f);
        SearchResponse searchResponse = createSearchResponse(searchHits);
        PipelineProcessingContext context = createContextWithExplanations(searchHit.getShard(), normalizationExplanation);

        // Execute processor
        ExplanationResponseProcessor processor = new ExplanationResponseProcessor("test", "tag", false);
        SearchResponse processedResponse = processor.processResponse(new SearchRequest(), searchResponse, context);

        // Verify results
        SearchHit processedHit = processedResponse.getHits().getHits()[0];
        Explanation processedExplanation = processedHit.getExplanation();

        // Verify that no details are included (all scores were zero)
        assertEquals(0, processedExplanation.getDetails().length);
    }

    public void testProcessResponseThrowsExceptionWhenExplanationLengthsMismatch() {
        ExplanationResponseProcessor processor = new ExplanationResponseProcessor("test description", "test tag", false);

        // Mock search request and response
        SearchRequest request = mock(SearchRequest.class);
        SearchResponse response = mock(SearchResponse.class);
        PipelineProcessingContext context = mock(PipelineProcessingContext.class);

        // Create actual SearchHit with explanation
        SearchHit searchHit = new SearchHit(1);
        ShardId shardId = new ShardId("index", "_na_", 0);
        SearchShardTarget shardTarget = new SearchShardTarget("node1", shardId, null, null);
        searchHit.shard(shardTarget);

        // Create explanation
        Explanation queryExplanation = Explanation.match(1.0f, "combined score", new Explanation[] { Explanation.match(0.5f, "exp1") });
        searchHit.explanation(queryExplanation);

        // Setup combined explanation details with mismatched lengths
        Map<SearchShard, List<CombinedExplanationDetails>> combinedDetails = new HashMap<>();
        SearchShard searchShard = new SearchShard(shardId.getIndexName(), shardId.getId(), shardTarget.getNodeId());

        // Create explanations with different lengths
        ExplanationDetails normalizationExplanation = new ExplanationDetails(
            List.of(
                Pair.of(0.5f, "norm1"),
                Pair.of(0.3f, "norm2") // Extra explanation
            )
        );

        ExplanationDetails originalExplanation = new ExplanationDetails(
            List.of(Pair.of(1.0f, "original"))  // Only one explanation
        );

        CombinedExplanationDetails combinedExplanationDetails = new CombinedExplanationDetails(
            normalizationExplanation,
            originalExplanation
        );

        combinedDetails.put(searchShard, List.of(combinedExplanationDetails));

        // Setup explanation payload
        Map<ExplanationPayload.PayloadType, Object> explainPayload = new HashMap<>();
        explainPayload.put(NORMALIZATION_PROCESSOR, combinedDetails);
        ExplanationPayload explanationPayload = new ExplanationPayload(explainPayload);

        // Setup response with actual SearchHits
        SearchHits searchHits = new SearchHits(new SearchHit[] { searchHit }, new TotalHits(1, TotalHits.Relation.EQUAL_TO), 1.0f);
        when(response.getHits()).thenReturn(searchHits);
        when(context.getAttribute(EXPLANATION_RESPONSE_KEY)).thenReturn(explanationPayload);

        // Verify the exception is thrown
        IllegalStateException exception = expectThrows(
            IllegalStateException.class,
            () -> processor.processResponse(request, response, context)
        );

        assertEquals("mismatch in number of query level explanations and normalization explanations", exception.getMessage());
    }

    public void testProcessResponseThrowsExceptionWhenNormalizedScoreDetailsIsNull() {
        ExplanationResponseProcessor processor = new ExplanationResponseProcessor("test description", "test tag", false);

        // Mock search request and response
        SearchRequest request = mock(SearchRequest.class);
        SearchResponse response = mock(SearchResponse.class);
        PipelineProcessingContext context = mock(PipelineProcessingContext.class);

        // Create actual SearchHit with explanation
        SearchHit searchHit = new SearchHit(1);
        ShardId shardId = new ShardId("index", "_na_", 0);
        SearchShardTarget shardTarget = new SearchShardTarget("node1", shardId, null, null);
        searchHit.shard(shardTarget);

        // Create explanation
        Explanation queryExplanation = Explanation.match(1.0f, "combined score", new Explanation[] { Explanation.match(0.5f, "exp1") });
        searchHit.explanation(queryExplanation);

        // Setup combined explanation details with ExplanationDetails that has a null Pair
        Map<SearchShard, List<CombinedExplanationDetails>> combinedDetails = new HashMap<>();

        // Use the same index and shard information as in the SearchHit
        SearchShard searchShard = new SearchShard(shardId.getIndexName(), shardId.getId(), shardTarget.getNodeId());

        // Create ExplanationDetails with a list containing a null Pair
        List<Pair<Float, String>> scoreDetails = new ArrayList<>();
        scoreDetails.add(null); // This will trigger the null check
        ExplanationDetails normalizationExplanation = new ExplanationDetails(scoreDetails);

        CombinedExplanationDetails combinedExplanationDetails = new CombinedExplanationDetails(
            normalizationExplanation,  // ExplanationDetails with a null Pair in scoreDetails
            new ExplanationDetails(List.of(Pair.of(1.0f, "combined")))
        );

        combinedDetails.put(searchShard, List.of(combinedExplanationDetails));

        // Setup explanation payload
        Map<ExplanationPayload.PayloadType, Object> explainPayload = new HashMap<>();
        explainPayload.put(NORMALIZATION_PROCESSOR, combinedDetails);
        ExplanationPayload explanationPayload = new ExplanationPayload(explainPayload);

        // Setup response with actual SearchHits
        SearchHits searchHits = new SearchHits(new SearchHit[] { searchHit }, new TotalHits(1, TotalHits.Relation.EQUAL_TO), 1.0f);
        when(response.getHits()).thenReturn(searchHits);
        when(context.getAttribute(EXPLANATION_RESPONSE_KEY)).thenReturn(explanationPayload);

        // Verify the exception is thrown
        IllegalStateException exception = expectThrows(
            IllegalStateException.class,
            () -> processor.processResponse(request, response, context)
        );

        assertEquals("normalized score details must not be null", exception.getMessage());
    }

    private PipelineProcessingContext createContextWithExplanations(
        SearchShardTarget shardTarget,
        ExplanationDetails normalizationExplanation
    ) {

        SearchShard searchShard = SearchShard.createSearchShard(shardTarget);

        CombinedExplanationDetails combinedDetails = new CombinedExplanationDetails(
            normalizationExplanation,
            new ExplanationDetails(Collections.singletonList(Pair.of(1.0f, "combined")))
        );

        Map<SearchShard, List<CombinedExplanationDetails>> explanationMap = new HashMap<>();
        explanationMap.put(searchShard, Collections.singletonList(combinedDetails));

        ExplanationPayload explanationPayload = new ExplanationPayload(Map.of(NORMALIZATION_PROCESSOR, explanationMap));

        PipelineProcessingContext context = new PipelineProcessingContext();
        context.setAttribute(EXPLANATION_RESPONSE_KEY, explanationPayload);

        return context;
    }

    private static SearchHits getSearchHits(float maxScore) {
        int numResponses = 1;
        int numIndices = 2;
        Iterator<Map.Entry<String, Index[]>> indicesIterator = randomRealisticIndices(numIndices, numResponses).entrySet().iterator();
        Map.Entry<String, Index[]> entry = indicesIterator.next();
        String clusterAlias = entry.getKey();
        Index[] indices = entry.getValue();

        int requestedSize = 2;
        PriorityQueue<SearchHit> priorityQueue = new PriorityQueue<>(new SearchHitComparator(null));
        TotalHits.Relation totalHitsRelation = randomFrom(TotalHits.Relation.values());
        // At least need 2 total hits since later in getCombinedExplainDetails we will try to process two hits.
        TotalHits totalHits = new TotalHits(randomLongBetween(2, 1000), totalHitsRelation);

        final int numDocs = totalHits.value() >= requestedSize ? requestedSize : (int) totalHits.value();
        int scoreFactor = randomIntBetween(1, numResponses);

        SearchHit[] searchHitArray = randomSearchHitArray(
            numDocs,
            numResponses,
            clusterAlias,
            indices,
            maxScore,
            scoreFactor,
            null,
            priorityQueue
        );
        for (SearchHit searchHit : searchHitArray) {
            Explanation explanation = Explanation.match(1.0f, "combined score of:", Explanation.match(1.0f, "field1:[0 TO 100]"));
            searchHit.explanation(explanation);
        }

        SearchHits searchHits = new SearchHits(searchHitArray, new TotalHits(numResponses, TotalHits.Relation.EQUAL_TO), maxScore);
        return searchHits;
    }

    private static SearchResponse getSearchResponse(SearchHits searchHits) {
        InternalSearchResponse internalSearchResponse = new InternalSearchResponse(
            searchHits,
            InternalAggregations.EMPTY,
            null,
            null,
            false,
            null,
            1
        );
        SearchResponse searchResponse = new SearchResponse(
            internalSearchResponse,
            null,
            1,
            1,
            0,
            1000,
            new ShardSearchFailure[0],
            SearchResponse.Clusters.EMPTY
        );
        return searchResponse;
    }

    private static Map<SearchShard, List<CombinedExplanationDetails>> getCombinedExplainDetails(SearchHits searchHits) {
        Map<SearchShard, List<CombinedExplanationDetails>> combinedExplainDetails = Map.of(
            SearchShard.createSearchShard(searchHits.getHits()[0].getShard()),
            List.of(
                CombinedExplanationDetails.builder()
                    .normalizationExplanations(new ExplanationDetails(List.of(Pair.of(1.0f, "min_max normalization of:"))))
                    .combinationExplanations(new ExplanationDetails(List.of(Pair.of(0.5f, "arithmetic_mean combination of:"))))
                    .build()
            ),
            SearchShard.createSearchShard(searchHits.getHits()[1].getShard()),
            List.of(
                CombinedExplanationDetails.builder()
                    .normalizationExplanations(new ExplanationDetails(List.of(Pair.of(0.5f, "min_max normalization of:"))))
                    .combinationExplanations(new ExplanationDetails(List.of(Pair.of(0.25f, "arithmetic_mean combination of:"))))
                    .build()
            )
        );
        return combinedExplainDetails;
    }

    private static void assertOnExplanationResults(SearchResponse processedResponse, float maxScore) {
        assertNotNull(processedResponse);
        Explanation hit1TopLevelExplanation = processedResponse.getHits().getHits()[0].getExplanation();
        assertNotNull(hit1TopLevelExplanation);
        assertEquals("arithmetic_mean combination of:", hit1TopLevelExplanation.getDescription());
        assertEquals(maxScore, (float) hit1TopLevelExplanation.getValue(), DELTA_FOR_SCORE_ASSERTION);

        Explanation[] hit1SecondLevelDetails = hit1TopLevelExplanation.getDetails();
        assertEquals(1, hit1SecondLevelDetails.length);
        assertEquals("min_max normalization of:", hit1SecondLevelDetails[0].getDescription());
        assertEquals(1.0f, (float) hit1SecondLevelDetails[0].getValue(), DELTA_FOR_SCORE_ASSERTION);

        assertNotNull(hit1SecondLevelDetails[0].getDetails());
        assertEquals(1, hit1SecondLevelDetails[0].getDetails().length);
        Explanation hit1ShardLevelExplanation = hit1SecondLevelDetails[0].getDetails()[0];

        assertEquals(1.0f, (float) hit1ShardLevelExplanation.getValue(), DELTA_FOR_SCORE_ASSERTION);
        assertEquals("field1:[0 TO 100]", hit1ShardLevelExplanation.getDescription());

        Explanation hit2TopLevelExplanation = processedResponse.getHits().getHits()[1].getExplanation();
        assertNotNull(hit2TopLevelExplanation);
        assertEquals("arithmetic_mean combination of:", hit2TopLevelExplanation.getDescription());
        assertEquals(0.0f, (float) hit2TopLevelExplanation.getValue(), DELTA_FOR_SCORE_ASSERTION);

        Explanation[] hit2SecondLevelDetails = hit2TopLevelExplanation.getDetails();
        assertEquals(1, hit2SecondLevelDetails.length);
        assertEquals("min_max normalization of:", hit2SecondLevelDetails[0].getDescription());
        assertEquals(.5f, (float) hit2SecondLevelDetails[0].getValue(), DELTA_FOR_SCORE_ASSERTION);

        assertNotNull(hit2SecondLevelDetails[0].getDetails());
        assertEquals(1, hit2SecondLevelDetails[0].getDetails().length);
        Explanation hit2ShardLevelExplanation = hit2SecondLevelDetails[0].getDetails()[0];

        assertEquals(1.0f, (float) hit2ShardLevelExplanation.getValue(), DELTA_FOR_SCORE_ASSERTION);
        assertEquals("field1:[0 TO 100]", hit2ShardLevelExplanation.getDescription());

        Explanation explanationHit2 = processedResponse.getHits().getHits()[1].getExplanation();
        assertNotNull(explanationHit2);
        assertEquals("arithmetic_mean combination of:", explanationHit2.getDescription());
        assertTrue(Range.of(0.0f, maxScore).contains((float) explanationHit2.getValue()));

    }

    private static Map<String, Index[]> randomRealisticIndices(int numIndices, int numClusters) {
        String[] indicesNames = new String[numIndices];
        for (int i = 0; i < numIndices; i++) {
            indicesNames[i] = randomAlphaOfLengthBetween(5, 10);
        }
        Map<String, Index[]> indicesPerCluster = new TreeMap<>();
        for (int i = 0; i < numClusters; i++) {
            Index[] indices = new Index[indicesNames.length];
            for (int j = 0; j < indices.length; j++) {
                String indexName = indicesNames[j];
                String indexUuid = frequently() ? randomAlphaOfLength(10) : indexName;
                indices[j] = new Index(indexName, indexUuid);
            }
            String clusterAlias;
            if (frequently() || indicesPerCluster.containsKey(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY)) {
                clusterAlias = randomAlphaOfLengthBetween(5, 10);
            } else {
                clusterAlias = RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY;
            }
            indicesPerCluster.put(clusterAlias, indices);
        }
        return indicesPerCluster;
    }

    private static SearchHit[] randomSearchHitArray(
        int numDocs,
        int numResponses,
        String clusterAlias,
        Index[] indices,
        float maxScore,
        int scoreFactor,
        SortField[] sortFields,
        PriorityQueue<SearchHit> priorityQueue
    ) {
        SearchHit[] hits = new SearchHit[numDocs];

        int[] sortFieldFactors = new int[sortFields == null ? 0 : sortFields.length];
        for (int j = 0; j < sortFieldFactors.length; j++) {
            sortFieldFactors[j] = randomIntBetween(1, numResponses);
        }

        for (int j = 0; j < numDocs; j++) {
            ShardId shardId = new ShardId(randomFrom(indices), randomIntBetween(0, 10));
            SearchShardTarget shardTarget = new SearchShardTarget(
                randomAlphaOfLengthBetween(3, 8),
                shardId,
                clusterAlias,
                OriginalIndices.NONE
            );
            SearchHit hit = new SearchHit(randomIntBetween(0, Integer.MAX_VALUE));

            float score = Float.NaN;
            if (!Float.isNaN(maxScore)) {
                score = (maxScore - j) * scoreFactor;
                hit.score(score);
            }

            hit.shard(shardTarget);
            if (sortFields != null) {
                Object[] rawSortValues = new Object[sortFields.length];
                DocValueFormat[] docValueFormats = new DocValueFormat[sortFields.length];
                for (int k = 0; k < sortFields.length; k++) {
                    SortField sortField = sortFields[k];
                    if (sortField == SortField.FIELD_SCORE) {
                        hit.score(score);
                        rawSortValues[k] = score;
                    } else {
                        rawSortValues[k] = sortField.getReverse() ? numDocs * sortFieldFactors[k] - j : j;
                    }
                    docValueFormats[k] = DocValueFormat.RAW;
                }
                hit.sortValues(rawSortValues, docValueFormats);
            }
            hits[j] = hit;
            priorityQueue.add(hit);
        }
        return hits;
    }

    private static final class SearchHitComparator implements Comparator<SearchHit> {

        private final SortField[] sortFields;

        SearchHitComparator(SortField[] sortFields) {
            this.sortFields = sortFields;
        }

        @Override
        public int compare(SearchHit a, SearchHit b) {
            if (sortFields == null) {
                int scoreCompare = Float.compare(b.getScore(), a.getScore());
                if (scoreCompare != 0) {
                    return scoreCompare;
                }
            } else {
                for (int i = 0; i < sortFields.length; i++) {
                    SortField sortField = sortFields[i];
                    if (sortField == SortField.FIELD_SCORE) {
                        int scoreCompare = Float.compare(b.getScore(), a.getScore());
                        if (scoreCompare != 0) {
                            return scoreCompare;
                        }
                    } else {
                        Integer aSortValue = (Integer) a.getRawSortValues()[i];
                        Integer bSortValue = (Integer) b.getRawSortValues()[i];
                        final int compare;
                        if (sortField.getReverse()) {
                            compare = Integer.compare(bSortValue, aSortValue);
                        } else {
                            compare = Integer.compare(aSortValue, bSortValue);
                        }
                        if (compare != 0) {
                            return compare;
                        }
                    }
                }
            }
            SearchShardTarget aShard = a.getShard();
            SearchShardTarget bShard = b.getShard();
            int shardIdCompareTo = aShard.getShardId().compareTo(bShard.getShardId());
            if (shardIdCompareTo != 0) {
                return shardIdCompareTo;
            }
            int clusterAliasCompareTo = aShard.getClusterAlias().compareTo(bShard.getClusterAlias());
            if (clusterAliasCompareTo != 0) {
                return clusterAliasCompareTo;
            }
            return Integer.compare(a.docId(), b.docId());
        }
    }

    private SearchResponse createSearchResponse(SearchHits searchHits) {
        return new SearchResponse(
            new SearchResponseSections(searchHits, null, null, false, false, null, 0),
            "_scrollId",
            1,
            1,
            0,
            1,
            new ShardSearchFailure[] {},
            SearchResponse.Clusters.EMPTY
        );
    }
}
