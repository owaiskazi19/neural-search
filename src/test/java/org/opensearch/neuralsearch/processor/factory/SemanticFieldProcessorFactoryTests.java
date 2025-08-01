/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor.factory;

import org.junit.Before;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.env.Environment;
import org.opensearch.index.analysis.AnalysisRegistry;
import org.opensearch.neuralsearch.mapper.SemanticFieldMapper;
import org.opensearch.neuralsearch.ml.MLCommonsClientAccessor;
import org.opensearch.neuralsearch.processor.semantic.SemanticFieldProcessor;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.client.OpenSearchClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.opensearch.neuralsearch.constants.MappingConstants.DOC;
import static org.opensearch.neuralsearch.constants.MappingConstants.PROPERTIES;
import static org.opensearch.neuralsearch.constants.MappingConstants.TYPE;
import static org.opensearch.neuralsearch.processor.TextChunkingProcessorTests.getAnalysisRegistry;
import static org.opensearch.neuralsearch.settings.NeuralSearchSettings.SEMANTIC_INGEST_BATCH_SIZE;
import static org.opensearch.plugins.IngestPlugin.SystemIngestPipelineConfigKeys.INDEX_MAPPINGS;
import static org.opensearch.plugins.IngestPlugin.SystemIngestPipelineConfigKeys.INDEX_SETTINGS;
import static org.opensearch.plugins.IngestPlugin.SystemIngestPipelineConfigKeys.INDEX_TEMPLATE_MAPPINGS;
import static org.opensearch.plugins.IngestPlugin.SystemIngestPipelineConfigKeys.INDEX_TEMPLATE_SETTINGS;

public class SemanticFieldProcessorFactoryTests extends OpenSearchTestCase {
    @Mock
    private MLCommonsClientAccessor mlClientAccessor;
    @Mock
    private Environment environment;
    @Mock
    private ClusterService clusterService;
    @Mock
    private OpenSearchClient openSearchClient;

    private SemanticFieldProcessorFactory factory;

    @Before
    public void setup() {
        MockitoAnnotations.openMocks(this);
        // analysisRegistry is a final class so use a real one
        AnalysisRegistry analysisRegistry = getAnalysisRegistry();
        factory = new SemanticFieldProcessorFactory(mlClientAccessor, environment, clusterService, analysisRegistry, openSearchClient);
    }

    public void testNewProcessor_noMappings_thenReturnNull() {
        final SemanticFieldProcessor processor = (SemanticFieldProcessor) factory.newProcessor(null, null, Collections.emptyMap());
        assertNull(processor);
    }

    public void testNewProcessor_indexMappingsWithoutProperties_thenReturnNull() {
        Map<String, Object> processorConfig = Map.of(INDEX_MAPPINGS, Map.of(DOC, Map.of()));
        final SemanticFieldProcessor processor = (SemanticFieldProcessor) factory.newProcessor(null, null, processorConfig);
        assertNull(processor);
    }

    public void testNewProcessor_templateMappingsWithoutProperties_thenReturnNull() {
        Map<String, Object> processorConfig = Map.of(INDEX_TEMPLATE_MAPPINGS, List.of(Map.of(DOC, Map.of())));
        final SemanticFieldProcessor processor = (SemanticFieldProcessor) factory.newProcessor(null, null, processorConfig);
        assertNull(processor);
    }

    public void testNewProcessor_indexMappingsWithSemanticField_thenCreateProcessor() {
        Map<String, Object> processorConfig = Map.of(
            INDEX_MAPPINGS,
            Map.of(DOC, Map.of(PROPERTIES, Map.of("semantic_field", Map.of(TYPE, SemanticFieldMapper.CONTENT_TYPE))))
        );
        final SemanticFieldProcessor processor = (SemanticFieldProcessor) factory.newProcessor(null, null, processorConfig);
        assertNotNull(processor);
        assertEquals(10, processor.getBatchSize());
    }

    public void testNewProcessor_indexMappingsWithSemanticFieldAndCustomBatchSize_thenCreateProcessor() {
        Map<String, Object> processorConfig = Map.of(
            INDEX_MAPPINGS,
            Map.of(DOC, Map.of(PROPERTIES, Map.of("semantic_field", Map.of(TYPE, SemanticFieldMapper.CONTENT_TYPE)))),
            INDEX_SETTINGS,
            Settings.builder().put(SEMANTIC_INGEST_BATCH_SIZE.getKey(), 1).build()
        );
        final SemanticFieldProcessor processor = (SemanticFieldProcessor) factory.newProcessor(null, null, processorConfig);
        assertNotNull(processor);
        assertEquals(1, processor.getBatchSize());
    }

    public void testNewProcessor_templateIndexMappingsWithSemanticFieldAndCustomBatchSize_thenCreateProcessor() {
        Map<String, Object> processorConfig = Map.of(
            INDEX_TEMPLATE_MAPPINGS,
            List.of(Map.of(DOC, Map.of(PROPERTIES, Map.of("semantic_field", Map.of(TYPE, SemanticFieldMapper.CONTENT_TYPE))))),
            INDEX_TEMPLATE_SETTINGS,
            List.of(Settings.builder().build(), Settings.builder().put(SEMANTIC_INGEST_BATCH_SIZE.getKey(), 100).build())
        );
        final SemanticFieldProcessor processor = (SemanticFieldProcessor) factory.newProcessor(null, null, processorConfig);
        assertNotNull(processor);
        assertEquals(100, processor.getBatchSize());
    }
}
