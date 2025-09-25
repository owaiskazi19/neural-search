/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.ml;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.core.action.ActionListener;
import org.opensearch.neuralsearch.query.AgenticSearchQueryBuilder;
import org.opensearch.ml.client.MachineLearningNodeClient;
import org.opensearch.ml.common.output.model.ModelTensor;
import org.opensearch.ml.common.output.model.ModelTensorOutput;
import org.opensearch.ml.common.output.model.ModelTensors;
import org.opensearch.ml.common.transport.execute.MLExecuteTaskResponse;
import org.opensearch.test.OpenSearchTestCase;

public class MLCommonsClientAccessorTests extends OpenSearchTestCase {

    @Mock
    private ActionListener<List<List<Number>>> resultListener;

    @Mock
    private ActionListener<List<Number>> singleSentenceResultListener;

    @Mock
    private ActionListener<List<Float>> similarityResultListener;

    @Mock
    private MachineLearningNodeClient client;

    @InjectMocks
    private MLCommonsClientAccessor accessor;

    @Before
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    public void testExecuteAgent_Success() throws Exception {
        final String agentId = "test-agent-id";
        final ActionListener<AgentExecutionDTO> listener = mock(ActionListener.class);
        final String expectedDslQuery = "{\"query\":{\"match\":{\"field\":\"value\"}}}";
        final String expectedAgentSteps = "Step 1: Analysis\nStep 2: Query generation";

        SearchRequest mockRequest = mock(SearchRequest.class);
        when(mockRequest.indices()).thenReturn(new String[] { "test-index" });

        AgenticSearchQueryBuilder mockQuery = mock(AgenticSearchQueryBuilder.class);
        when(mockQuery.getQueryText()).thenReturn("test query");
        when(mockQuery.getQueryFields()).thenReturn(List.of("field1", "field2"));

        Mockito.doAnswer(invocation -> {
            final ActionListener actionListener = invocation.getArgument(2);
            MLExecuteTaskResponse mockResponse = mock(MLExecuteTaskResponse.class);
            when(mockResponse.getOutput()).thenReturn(createConversationalAgentResponse());
            actionListener.onResponse(mockResponse);
            return null;
        }).when(client).execute(any(), any(), any());

        AgentInfoDTO agentInfo = new AgentInfoDTO("conversational", false, false);

        accessor.executeAgent(
            mockRequest,
            mockQuery,
            agentId,
            agentInfo,
            mock(org.opensearch.core.xcontent.NamedXContentRegistry.class),
            listener
        );

        verify(client).execute(any(), any(), any());
        ArgumentCaptor<AgentExecutionDTO> resultCaptor = ArgumentCaptor.forClass(AgentExecutionDTO.class);
        verify(listener).onResponse(resultCaptor.capture());

        AgentExecutionDTO result = resultCaptor.getValue();
        assertEquals(expectedDslQuery, result.getDslQuery());
        assertEquals("test summary", result.getAgentStepsSummary());
        Mockito.verifyNoMoreInteractions(listener);
    }

    public void testExecuteAgent_Failure() throws Exception {
        final String agentId = "test-agent-id";
        final ActionListener<AgentExecutionDTO> listener = mock(ActionListener.class);
        final RuntimeException exception = new RuntimeException("Agent execution failed");

        SearchRequest mockRequest = mock(SearchRequest.class);
        when(mockRequest.indices()).thenReturn(new String[] { "test-index" });

        AgenticSearchQueryBuilder mockQuery = mock(AgenticSearchQueryBuilder.class);
        when(mockQuery.getQueryText()).thenReturn("test query");

        Mockito.doAnswer(invocation -> {
            final ActionListener actionListener = invocation.getArgument(2);
            actionListener.onFailure(exception);
            return null;
        }).when(client).execute(any(), any(), any());

        AgentInfoDTO agentInfo = new AgentInfoDTO("conversational", false, false);

        accessor.executeAgent(
            mockRequest,
            mockQuery,
            agentId,
            agentInfo,
            mock(org.opensearch.core.xcontent.NamedXContentRegistry.class),
            listener
        );

        verify(client).execute(any(), any(), any());
        verify(listener).onFailure(exception);
        Mockito.verifyNoMoreInteractions(listener);
    }

    private ModelTensorOutput createConversationalAgentResponse() {
        final List<ModelTensors> tensorsList = new ArrayList<>();
        final List<ModelTensor> mlModelTensorList = new ArrayList<>();

        String responseJson = "{\"dsl_query\":{\"query\":{\"match\":{\"field\":\"value\"}}},\"agent_steps_summary\":\"test summary\"}";
        final ModelTensor tensor = new ModelTensor("response", null, null, null, null, null, Map.of("response", responseJson));
        mlModelTensorList.add(tensor);
        final ModelTensors modelTensors = new ModelTensors(mlModelTensorList);
        tensorsList.add(modelTensors);
        return new ModelTensorOutput(tensorsList);
    }
}
