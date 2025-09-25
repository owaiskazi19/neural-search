/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.query.ext;

import java.io.IOException;
import java.util.Objects;

import lombok.AllArgsConstructor;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.search.SearchExtBuilder;

import lombok.Getter;

/**
 * SearchExtBuilder for agent steps summary in agentic search responses
 */
@AllArgsConstructor
public class AgentStepsSearchExtBuilder extends SearchExtBuilder {

    public final static String PARAM_FIELD_NAME = "agent_steps_summary";
    @Getter
    protected String agentStepsSummary;

    public AgentStepsSearchExtBuilder(StreamInput in) throws IOException {
        agentStepsSummary = in.readString();
    }

    @Override
    public String getWriteableName() {
        return PARAM_FIELD_NAME;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(agentStepsSummary);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.field(PARAM_FIELD_NAME, agentStepsSummary);
        return builder;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getClass(), this.agentStepsSummary);
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof AgentStepsSearchExtBuilder)
            && Objects.equals(agentStepsSummary, ((AgentStepsSearchExtBuilder) obj).agentStepsSummary);
    }

    public static AgentStepsSearchExtBuilder fromXContent(XContentParser parser) throws IOException {
        String agentSteps = null;
        if (parser.currentToken() == XContentParser.Token.FIELD_NAME) {
            parser.nextToken();
        }
        if (parser.currentToken() == XContentParser.Token.VALUE_STRING) {
            agentSteps = parser.text();
        } else if (parser.currentToken() == XContentParser.Token.VALUE_NULL) {
            agentSteps = null;
        }
        return new AgentStepsSearchExtBuilder(agentSteps);
    }
}
