/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.core.ParseField;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.index.query.MatchAllQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.neuralsearch.ml.AgentInfoDTO;
import org.opensearch.neuralsearch.ml.MLCommonsClientAccessor;
import org.opensearch.neuralsearch.ml.AgentExecutionDTO;
import org.opensearch.neuralsearch.query.AgenticSearchQueryBuilder;
import org.opensearch.neuralsearch.stats.events.EventStatsManager;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.pipeline.PipelineProcessingContext;
import org.opensearch.core.action.ActionListener;
import org.opensearch.test.OpenSearchTestCase;

import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.neuralsearch.util.NeuralSearchClusterUtil;
import org.opensearch.neuralsearch.settings.NeuralSearchSettingsAccessor;
import org.opensearch.neuralsearch.util.TestUtils;

import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;

public class AgenticQueryTranslatorProcessorTests extends OpenSearchTestCase {

    private static final String AGENT_ID = "test-agent";
    private static final String QUERY_TEXT = "find red cars";

    private MLCommonsClientAccessor mockMLClient;
    private NamedXContentRegistry mockXContentRegistry;
    private AgenticQueryTranslatorProcessor processor;
    private PipelineProcessingContext mockContext;
    private NeuralSearchSettingsAccessor mockSettingsAccessor;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        TestUtils.initializeEventStatsManager();
        mockMLClient = mock(MLCommonsClientAccessor.class);
        mockXContentRegistry = mock(NamedXContentRegistry.class);
        mockContext = mock(PipelineProcessingContext.class);
        mockSettingsAccessor = mock(NeuralSearchSettingsAccessor.class);
        when(mockSettingsAccessor.isAgenticSearchEnabled()).thenReturn(true);
        EventStatsManager.instance().initialize(mockSettingsAccessor);

        ClusterService mockClusterService = mock(ClusterService.class);
        ClusterState mockClusterState = mock(ClusterState.class);
        Metadata mockMetadata = mock(Metadata.class);
        when(mockClusterService.state()).thenReturn(mockClusterState);
        when(mockClusterState.metadata()).thenReturn(mockMetadata);
        when(mockMetadata.index(any(String.class))).thenReturn(null);
        NeuralSearchClusterUtil.instance().initialize(mockClusterService, null);

        AgenticQueryTranslatorProcessor.Factory factory = new AgenticQueryTranslatorProcessor.Factory(
            mockMLClient,
            mockXContentRegistry,
            mockSettingsAccessor
        );
        Map<String, Object> config = new HashMap<>();
        config.put("agent_id", AGENT_ID);
        processor = factory.create(null, "test-tag", "test-description", false, config, null);
    }

    public void testProcessRequestAsync_withAgenticQuery_success() throws IOException {
        List<NamedXContentRegistry.Entry> entries = new ArrayList<>();
        entries.add(new NamedXContentRegistry.Entry(QueryBuilder.class, new ParseField("match_all"), MatchAllQueryBuilder::fromXContent));
        NamedXContentRegistry registry = new NamedXContentRegistry(entries);

        AgenticQueryTranslatorProcessor.Factory factory = new AgenticQueryTranslatorProcessor.Factory(
            mockMLClient,
            registry,
            mockSettingsAccessor
        );
        Map<String, Object> config = new HashMap<>();
        config.put("agent_id", AGENT_ID);
        AgenticQueryTranslatorProcessor testProcessor = factory.create(null, "test-tag", "test-description", false, config, null);

        AgenticSearchQueryBuilder agenticQuery = new AgenticSearchQueryBuilder().queryText(QUERY_TEXT);
        SearchRequest request = new SearchRequest("test-index");
        request.source(new SearchSourceBuilder().query(agenticQuery));

        ActionListener<SearchRequest> listener = mock(ActionListener.class);

        AgentInfoDTO agentInfo = new AgentInfoDTO("conversational", false, false);
        doAnswer(invocation -> {
            ActionListener<AgentInfoDTO> agentInfoListener = invocation.getArgument(1);
            agentInfoListener.onResponse(agentInfo);
            return null;
        }).when(mockMLClient).getAgentDetails(eq(AGENT_ID), any(ActionListener.class));

        String validAgentResponse = "{\"query\": {\"match_all\": {}}}";
        String agentSteps = "Step 1: Analysis\nStep 2: Query generation";
        AgentExecutionDTO validResult = new AgentExecutionDTO(validAgentResponse, agentSteps);

        doAnswer(invocation -> {
            ActionListener<AgentExecutionDTO> agentListener = invocation.getArgument(5);
            agentListener.onResponse(validResult);
            return null;
        }).when(mockMLClient)
            .executeAgent(
                any(SearchRequest.class),
                any(AgenticSearchQueryBuilder.class),
                eq(AGENT_ID),
                eq(agentInfo),
                any(NamedXContentRegistry.class),
                any(ActionListener.class)
            );

        testProcessor.processRequestAsync(request, mockContext, listener);

        verify(mockMLClient).getAgentDetails(eq(AGENT_ID), any(ActionListener.class));
        verify(mockMLClient).executeAgent(
            any(SearchRequest.class),
            any(AgenticSearchQueryBuilder.class),
            eq(AGENT_ID),
            eq(agentInfo),
            any(NamedXContentRegistry.class),
            any(ActionListener.class)
        );
        verify(listener).onResponse(request);
        verify(mockContext).setAttribute("agent_steps_summary", agentSteps);

        assertNotNull(request.source());
    }

    public void testGetType() {
        assertEquals("agentic_query_translator", processor.getType());
    }
}
