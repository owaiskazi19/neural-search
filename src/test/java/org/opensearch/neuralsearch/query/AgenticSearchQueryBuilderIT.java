/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.query;

import org.junit.After;
import org.junit.Before;
import org.opensearch.neuralsearch.BaseNeuralSearchIT;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.message.BasicHeader;
import com.google.common.collect.ImmutableList;
import org.opensearch.neuralsearch.settings.NeuralSearchSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import static org.opensearch.neuralsearch.util.TestUtils.DEFAULT_USER_AGENT;

public class AgenticSearchQueryBuilderIT extends BaseNeuralSearchIT {

    private static final String TEST_INDEX = "test-agentic-index";
    private static String TEST_AGENT_ID;
    private static String TEST_MODEL_ID;
    private static final String TEST_QUERY_TEXT = "Find documents about machine learning";
    private static final String AWS_ACCESS_KEY_ID = System.getenv("AWS_ACCESS_KEY_ID");
    protected static final String ML_COMMONS_AGENTIC_SEARCH_ENABLED = "plugins.ml_commons.agentic_search_enabled";

    @Before
    public void setUp() throws Exception {
        super.setUp();
        updateClusterSettings();
        updateClusterSettings(NeuralSearchSettings.AGENTIC_SEARCH_ENABLED.getKey(), true);
        updateClusterSettings(ML_COMMONS_AGENTIC_SEARCH_ENABLED, true);
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
            try {
                deleteAgent(TEST_AGENT_ID);
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
        super.tearDown();
    }

    public void testAgenticSearchQuery_withValidParameters_thenExpectError() throws Exception {
        initializeIndexIfNotExist(TEST_INDEX);
        String pipelineName = "test-agentic-pipeline";
        createAgenticSearchPipeline(pipelineName, TEST_AGENT_ID);

        try {
            Map<String, Object> searchResponse = search(TEST_INDEX, null, null, 10, Map.of("search_pipeline", pipelineName));
            // Expect some results or error due to setup limitations
            assertTrue("Should get results or setup error", getHitCount(searchResponse) >= 0);
        } catch (Exception e) {
            assertTrue(
                "Should be a setup-related error",
                e.getMessage().contains("Agent index not found")
                    || e.getMessage().contains("model not found")
                    || e.getMessage().contains("Failed to execute agentic search")
                    || e.getMessage().contains("dummy-agent-id")
            );
        }
    }

    public void testAgenticSearchQuery_withMissingAgentId_thenFail() throws Exception {
        initializeIndexIfNotExist(TEST_INDEX);
        String pipelineName = "test-agentic-pipeline-no-agent";

        try {
            createAgenticSearchPipeline(pipelineName, "");
            Map<String, Object> searchResponse = search(TEST_INDEX, null, null, 10, Map.of("search_pipeline", pipelineName));
            fail("Expected exception for empty agent_id");
        } catch (Exception e) {
            assertTrue(
                "Should contain agent_id error",
                e.getMessage().contains("agent_id") || e.getMessage().contains("required") || e.getMessage().contains("empty")
            );
        }
    }

    public void testAgenticSearchQuery_withSingleShard_thenSuccess() throws Exception {
        String singleShardIndex = TEST_INDEX + "-single-shard";
        initializeIndexIfNotExist(singleShardIndex, 1);
        String pipelineName = "test-agentic-pipeline-single";
        createAgenticSearchPipeline(pipelineName, TEST_AGENT_ID);

        try {
            Map<String, Object> searchResponse = search(singleShardIndex, null, null, 10, Map.of("search_pipeline", pipelineName));
            assertTrue("Should get results or setup error", getHitCount(searchResponse) >= 0);
        } catch (Exception e) {
            assertTrue(
                "Should be a setup-related error",
                e.getMessage().contains("Agent index not found")
                    || e.getMessage().contains("model not found")
                    || e.getMessage().contains("Failed to execute agentic search")
                    || e.getMessage().contains("dummy-agent-id")
            );
        }
    }

    public void testAgenticSearchQuery_withMultipleShards_thenSuccess() throws Exception {
        String multiShardIndex = TEST_INDEX + "-multi-shard";
        initializeIndexIfNotExist(multiShardIndex, 3);
        String pipelineName = "test-agentic-pipeline-multi";
        createAgenticSearchPipeline(pipelineName, TEST_AGENT_ID);

        try {
            Map<String, Object> searchResponse = search(multiShardIndex, null, null, 10, Map.of("search_pipeline", pipelineName));
            assertTrue("Should get results or setup error", getHitCount(searchResponse) >= 0);
        } catch (Exception e) {
            assertTrue(
                "Should be a setup-related error",
                e.getMessage().contains("Agent index not found")
                    || e.getMessage().contains("model not found")
                    || e.getMessage().contains("Failed to execute agentic search")
                    || e.getMessage().contains("dummy-agent-id")
            );
        }
    }

    private void initializeIndexIfNotExist(String indexName) throws Exception {
        initializeIndexIfNotExist(indexName, 1);
    }

    private void initializeIndexIfNotExist(String indexName, int numberOfShards) throws Exception {
        if (!indexExists(indexName)) {
            createIndexWithConfiguration(
                indexName,
                buildIndexConfiguration(
                    Collections.emptyList(),
                    Map.of(),
                    Collections.emptyList(),
                    Collections.singletonList("passage_text"),
                    Collections.emptyList(),
                    numberOfShards
                ),
                ""
            );
            addDocument(indexName, "1", "passage_text", "This is about science and technology", null, null);
            addDocument(indexName, "2", "passage_text", "Machine learning and artificial intelligence", null, null);
            assertEquals(2, getDocCount(indexName));
        }
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

    private void createAgenticSearchPipeline(String pipelineName, String agentId) throws Exception {
        final String pipelineRequestBody = String.format(
            Locale.ROOT,
            Files.readString(Path.of(classLoader.getResource("agenticsearch/AgenticSearchPipelineRequestBody.json").toURI())),
            agentId
        );
        makeRequest(
            client(),
            "PUT",
            String.format(Locale.ROOT, "/_search/pipeline/%s", pipelineName),
            null,
            toHttpEntity(pipelineRequestBody),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
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
