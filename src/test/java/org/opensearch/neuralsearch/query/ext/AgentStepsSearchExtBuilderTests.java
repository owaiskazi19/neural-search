/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.query.ext;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;

import static org.opensearch.common.xcontent.XContentFactory.jsonBuilder;

public class AgentStepsSearchExtBuilderTests extends OpenSearchTestCase {

    public void testConstructor() {
        String agentSteps = "Step 1: Analysis\nStep 2: Execution";
        AgentStepsSearchExtBuilder builder = new AgentStepsSearchExtBuilder(agentSteps);

        assertEquals(agentSteps, builder.getAgentStepsSummary());
        assertEquals(AgentStepsSearchExtBuilder.PARAM_FIELD_NAME, builder.getWriteableName());
    }

    public void testConstructorWithEmptySteps() {
        String agentSteps = "";
        AgentStepsSearchExtBuilder builder = new AgentStepsSearchExtBuilder(agentSteps);
        assertEquals(agentSteps, builder.getAgentStepsSummary());
    }

    public void testSerialization() throws IOException {
        String agentSteps = "Step 1: Query parsing\nStep 2: Index search\nStep 3: Result ranking";
        AgentStepsSearchExtBuilder original = new AgentStepsSearchExtBuilder(agentSteps);

        // Serialize
        BytesStreamOutput output = new BytesStreamOutput();
        original.writeTo(output);

        // Deserialize
        StreamInput input = output.bytes().streamInput();
        AgentStepsSearchExtBuilder deserialized = new AgentStepsSearchExtBuilder(input);

        assertEquals(original.getAgentStepsSummary(), deserialized.getAgentStepsSummary());
    }

    public void testToXContent() throws IOException {
        String agentSteps = "Step 1: Understanding query\nStep 2: Generating response";
        AgentStepsSearchExtBuilder builder = new AgentStepsSearchExtBuilder(agentSteps);

        XContentBuilder xContentBuilder = jsonBuilder();
        xContentBuilder.startObject();
        builder.toXContent(xContentBuilder, ToXContent.EMPTY_PARAMS);
        xContentBuilder.endObject();

        String json = xContentBuilder.toString();
        assertTrue(json.contains("\"agent_steps_summary\""));
        assertTrue(json.contains("Step 1: Understanding query"));
        assertTrue(json.contains("Step 2: Generating response"));
    }

    public void testToXContentWithEmptySteps() throws IOException {
        AgentStepsSearchExtBuilder builder = new AgentStepsSearchExtBuilder("");

        XContentBuilder xContentBuilder = jsonBuilder();
        xContentBuilder.startObject();
        builder.toXContent(xContentBuilder, ToXContent.EMPTY_PARAMS);
        xContentBuilder.endObject();

        String json = xContentBuilder.toString();
        assertTrue(json.contains("\"agent_steps_summary\":\"\""));
    }

    public void testFromXContent() throws IOException {
        String json = "{\"agent_steps_summary\":\"Step 1: Process query\\nStep 2: Return results\"}";

        XContentParser parser = createParser(XContentType.JSON.xContent(), json);
        parser.nextToken(); // START_OBJECT
        parser.nextToken(); // FIELD_NAME

        AgentStepsSearchExtBuilder builder = AgentStepsSearchExtBuilder.fromXContent(parser);

        assertEquals("Step 1: Process query\nStep 2: Return results", builder.getAgentStepsSummary());
    }

    public void testFromXContentWithEmptyValue() throws IOException {
        String json = "{\"agent_steps_summary\":\"\"}";

        XContentParser parser = createParser(XContentType.JSON.xContent(), json);
        parser.nextToken(); // START_OBJECT
        parser.nextToken(); // FIELD_NAME

        AgentStepsSearchExtBuilder builder = AgentStepsSearchExtBuilder.fromXContent(parser);

        assertEquals("", builder.getAgentStepsSummary());
    }

    public void testEquals() {
        String agentSteps = "Step 1: Test";
        AgentStepsSearchExtBuilder builder1 = new AgentStepsSearchExtBuilder(agentSteps);
        AgentStepsSearchExtBuilder builder2 = new AgentStepsSearchExtBuilder(agentSteps);
        AgentStepsSearchExtBuilder builder3 = new AgentStepsSearchExtBuilder("Different steps");

        assertEquals(builder1, builder2);
        assertNotEquals(builder1, builder3);
        assertNotEquals(builder1, null);
        assertNotEquals(builder1, "not a builder");
    }

    public void testHashCode() {
        String agentSteps = "Step 1: Test";
        AgentStepsSearchExtBuilder builder1 = new AgentStepsSearchExtBuilder(agentSteps);
        AgentStepsSearchExtBuilder builder2 = new AgentStepsSearchExtBuilder(agentSteps);

        assertEquals(builder1.hashCode(), builder2.hashCode());
    }
}
