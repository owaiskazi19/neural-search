/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.ml;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * DTO containing agent information
 */
@AllArgsConstructor
@Getter
public class AgentInfoDTO {
    private final String type;
    private final boolean hasSystemPrompt;
    private final boolean hasUserPrompt;
}
