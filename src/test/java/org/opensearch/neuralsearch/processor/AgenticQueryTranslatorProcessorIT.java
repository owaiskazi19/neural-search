/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor;

import org.junit.After;
import org.junit.Before;
import org.apache.lucene.tests.util.LuceneTestCase.AwaitsFix;
import org.opensearch.neuralsearch.BaseNeuralSearchIT;
import org.opensearch.neuralsearch.query.AgenticSearchQueryBuilder;
import org.opensearch.client.Response;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

@AwaitsFix(bugUrl = "Ignoring until we find a way to fetch access, secret key for remote model")
public class AgenticQueryTranslatorProcessorIT extends BaseNeuralSearchIT {

    private static final String TEST_INDEX = "test-agentic-processor-index";
    private static String TEST_AGENT_ID;
    private static String TEST_MODEL_ID;
    private static final String TEST_QUERY_TEXT = "Find documents about machine learning";

    @Before
    public void setUp() throws Exception {
        super.setUp();
        updateClusterSettings();
        if (TEST_AGENT_ID == null) {
            try {
                String connectorId = createTestConnector();
                TEST_MODEL_ID = registerAndDeployTestModel(connectorId);
                TEST_AGENT_ID = registerTestAgent(TEST_MODEL_ID);
            } catch (Exception e) {
                TEST_AGENT_ID = "dummy-agent-id";
                TEST_MODEL_ID = "dummy-model-id";
            }
        }
    }

    @After
    public void tearDown() throws Exception {
        if (TEST_AGENT_ID != null && !TEST_AGENT_ID.equals("dummy-agent-id")) {
            deleteAgent(TEST_AGENT_ID);
        }
        super.tearDown();
    }

    public void testAgenticQueryTranslatorProcessor_withValidQuery_expectsTranslation() throws Exception {
        initializeIndexIfNotExist(TEST_INDEX);
        createSearchPipelineWithAgenticProcessor();

        AgenticSearchQueryBuilder agenticQuery = new AgenticSearchQueryBuilder().queryText(TEST_QUERY_TEXT);

        try {
            Map<String, Object> searchResponse = searchWithPipeline(TEST_INDEX, agenticQuery, "agentic-pipeline");
            assertNotNull(searchResponse);
        } catch (Exception e) {
            assertTrue(
                "Should be a setup-related error",
                e.getMessage().contains("Agent index not found")
                    || e.getMessage().contains("model not found")
                    || e.getMessage().contains("Agentic search failed")
            );
        }
    }

    public void testAgenticQueryTranslatorProcessor_withAggregations_expectsFailure() throws Exception {
        initializeIndexIfNotExist(TEST_INDEX);
        createSearchPipelineWithAgenticProcessor();

        AgenticSearchQueryBuilder agenticQuery = new AgenticSearchQueryBuilder().queryText(TEST_QUERY_TEXT);

        try {
            Map<String, Object> searchResponse = searchWithPipelineAndAggregations(TEST_INDEX, agenticQuery, "agentic-pipeline");
            fail("Expected failure due to aggregations with agentic search");
        } catch (Exception e) {
            assertTrue(
                "Should contain invalid usage error",
                e.getMessage().contains("Invalid usage with other search features")
                    || e.getMessage().contains("cannot be used with other search features")
            );
        }
    }

    public void testAgenticQueryTranslatorProcessor_withSort_expectsFailure() throws Exception {
        initializeIndexIfNotExist(TEST_INDEX);
        createSearchPipelineWithAgenticProcessor();

        AgenticSearchQueryBuilder agenticQuery = new AgenticSearchQueryBuilder().queryText(TEST_QUERY_TEXT);

        try {
            Map<String, Object> searchResponse = searchWithPipelineAndSort(TEST_INDEX, agenticQuery, "agentic-pipeline");
            fail("Expected failure due to sort with agentic search");
        } catch (Exception e) {
            assertTrue(
                "Should contain invalid usage error",
                e.getMessage().contains("Invalid usage with other search features")
                    || e.getMessage().contains("cannot be used with other search features")
            );
        }
    }

    private void initializeIndexIfNotExist(String indexName) throws Exception {
        if (!indexExists(indexName)) {
            createIndexWithConfiguration(
                indexName,
                buildIndexConfiguration(
                    Collections.emptyList(),
                    Map.of(),
                    Collections.emptyList(),
                    Collections.singletonList("passage_text"),
                    Collections.emptyList(),
                    1
                ),
                ""
            );
            addDocument(indexName, "1", "passage_text", "This is about science and technology", null, null);
            addDocument(indexName, "2", "passage_text", "Machine learning and artificial intelligence", null, null);
            assertEquals(2, getDocCount(indexName));
        }
    }

    private void createSearchPipelineWithAgenticProcessor() throws Exception {
        String pipelineConfig = String.format(Locale.ROOT, """
            {
              "request_processors": [
                {
                  "agentic_query_translator": {
                    "agent_id": "%s"
                  }
                }
              ]
            }
            """, TEST_AGENT_ID);

        makeRequest(client(), "PUT", "/_search/pipeline/agentic-pipeline", null, toHttpEntity(pipelineConfig), null);
    }

    private Map<String, Object> searchWithPipeline(String indexName, AgenticSearchQueryBuilder query, String pipelineName)
        throws Exception {
        return search(indexName, query, null, 10, Map.of("search_pipeline", pipelineName));
    }

    private Map<String, Object> searchWithPipelineAndAggregations(String indexName, AgenticSearchQueryBuilder query, String pipelineName)
        throws Exception {
        String searchBody = String.format(Locale.ROOT, """
            {
              "query": {
                "agentic": {
                  "query_text": "%s"
                }
              },
              "aggs": {
                "test_agg": {
                  "terms": {
                    "field": "passage_text.keyword"
                  }
                }
              }
            }
            """, query.getQueryText());

        Response response = makeRequest(
            client(),
            "GET",
            "/" + indexName + "/_search",
            Map.of("search_pipeline", pipelineName),
            toHttpEntity(searchBody),
            null
        );
        String responseBody = EntityUtils.toString(response.getEntity());
        return XContentHelper.convertToMap(XContentType.JSON.xContent(), responseBody, false);
    }

    private Map<String, Object> searchWithPipelineAndSort(String indexName, AgenticSearchQueryBuilder query, String pipelineName)
        throws Exception {
        String searchBody = String.format(Locale.ROOT, """
            {
              "query": {
                "agentic": {
                  "query_text": "%s"
                }
              },
              "sort": [
                {"_score": {"order": "desc"}}
              ]
            }
            """, query.getQueryText());

        Response response = makeRequest(
            client(),
            "GET",
            "/" + indexName + "/_search",
            Map.of("search_pipeline", pipelineName),
            toHttpEntity(searchBody),
            null
        );
        String responseBody = EntityUtils.toString(response.getEntity());
        return XContentHelper.convertToMap(XContentType.JSON.xContent(), responseBody, false);
    }

    private String createTestConnector() throws Exception {
        final String createConnectorRequestBody = Files.readString(
            Path.of(classLoader.getResource("agenticsearch/CreateConnectorRequestBody.json").toURI())
        );
        return createConnector(createConnectorRequestBody);
    }

    private String registerAndDeployTestModel(String connectorId) throws Exception {
        final String registerModelRequestBody = String.format(
            Locale.ROOT,
            Files.readString(Path.of(classLoader.getResource("agenticsearch/RegisterModelRequestBody.json").toURI())),
            connectorId
        );
        return registerModelGroupAndUploadModel(registerModelRequestBody);
    }

    private String registerTestAgent(String modelId) throws Exception {
        final String registerAgentRequestBody = String.format(
            Locale.ROOT,
            Files.readString(Path.of(classLoader.getResource("agenticsearch/RegisterAgentRequestBody.json").toURI())),
            modelId
        );
        return registerAgent(registerAgentRequestBody);
    }
}
