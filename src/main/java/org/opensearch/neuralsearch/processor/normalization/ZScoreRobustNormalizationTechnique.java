/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.neuralsearch.processor.normalization;

import org.opensearch.neuralsearch.processor.CompoundTopDocs;

import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import com.google.common.primitives.Floats;

public class ZScoreRobustNormalizationTechnique implements ScoreNormalizationTechnique{

    public static final String TECHNIQUE_NAME = "z_score_robust";
    private static final float SINGLE_RESULT_SCORE = 1.0f;
    private static final float MAD_CONSTANT = 1.4826f;
    private static final float MIN_SCORE = 0.001f;

    @Override
    public void normalize(List<CompoundTopDocs> queryTopDocs) {
        int numOfSubqueries = getNumOfSubqueries(queryTopDocs);

        long[] elementsPerSubquery = findNumberOfElementsPerSubQuery(queryTopDocs, numOfSubqueries);
        float[][] scoresPerSubQuery = findScoresPerSubQuery(queryTopDocs, numOfSubqueries, elementsPerSubquery);
        float[] medianPerSubQuery = findMedianPerSubquery(scoresPerSubQuery, numOfSubqueries);
        float[] madPerSubquery = findMADPerSubquery(scoresPerSubQuery, medianPerSubQuery, numOfSubqueries);

        // do normalization using actual score and robust z-scores for corresponding sub query
        for (CompoundTopDocs compoundQueryTopDocs : queryTopDocs) {
            if (Objects.isNull(compoundQueryTopDocs)) {
                continue;
            }
            List<TopDocs> topDocsPerSubQuery = compoundQueryTopDocs.getTopDocs();
            for (int j = 0; j < topDocsPerSubQuery.size(); j++) {
                TopDocs subQueryTopDoc = topDocsPerSubQuery.get(j);
                for (ScoreDoc scoreDoc : subQueryTopDoc.scoreDocs) {
                    scoreDoc.score = normalizeSingleScore(
                            scoreDoc.score,
                            madPerSubquery[j],
                            medianPerSubQuery[j]
                    );
                }
            }
        }
    }

    private int getNumOfSubqueries(final List<CompoundTopDocs> queryTopDocs) {
        return queryTopDocs.stream()
                .filter(Objects::nonNull)
                .filter(topDocs -> !topDocs.getTopDocs().isEmpty())
                .findAny()
                .get()
                .getTopDocs()
                .size();
    }

    static private long[] findNumberOfElementsPerSubQuery(final List<CompoundTopDocs> queryTopDocs, final int numOfScores) {
        final long[] numberOfElementsPerSubQuery = new long[numOfScores];
        Arrays.fill(numberOfElementsPerSubQuery, 0);
        for (CompoundTopDocs compoundQueryTopDocs : queryTopDocs) {
            if (Objects.isNull(compoundQueryTopDocs)) {
                continue;
            }
            List<TopDocs> topDocsPerSubQuery = compoundQueryTopDocs.getTopDocs();
            int subQueryIndex = 0;
            for (TopDocs topDocs : topDocsPerSubQuery) {
                numberOfElementsPerSubQuery[subQueryIndex++] += topDocs.totalHits.value;
            }
        }

        return numberOfElementsPerSubQuery;
    }

    static private float[][] findScoresPerSubQuery(final List<CompoundTopDocs> queryTopDocs, final int numOfScores, final long[] elementsPerSubquery) {
        float[][] scoresPerSubQuery = new float[numOfScores][];
        for (int i = 0; i < numOfScores; i++) {
            scoresPerSubQuery[i] = new float[(int) elementsPerSubquery[i]];
        }

        int[] indices = new int[numOfScores];
        for (CompoundTopDocs compoundQueryTopDocs : queryTopDocs) {
            if (Objects.isNull(compoundQueryTopDocs)) {
                continue;
            }
            List<TopDocs> topDocsPerSubQuery = compoundQueryTopDocs.getTopDocs();
            for (int i = 0; i < topDocsPerSubQuery.size(); i++) {
                for (ScoreDoc scoreDoc : topDocsPerSubQuery.get(i).scoreDocs) {
                    scoresPerSubQuery[i][indices[i]++] = scoreDoc.score;
                }
            }
        }

        return scoresPerSubQuery;
    }

    static private float[] findMedianPerSubquery(final float[][] scoresPerSubQuery, final int numOfScores) {
        final float[] medianPerSubQuery = new float[numOfScores];
        for (int i = 0; i < numOfScores; i++) {
            if (scoresPerSubQuery[i].length > 0) {
                Arrays.sort(scoresPerSubQuery[i]);
                int middle = scoresPerSubQuery[i].length / 2;
                if (scoresPerSubQuery[i].length % 2 == 0) {
                    medianPerSubQuery[i] = (scoresPerSubQuery[i][middle - 1] + scoresPerSubQuery[i][middle]) / 2.0f;
                } else {
                    medianPerSubQuery[i] = scoresPerSubQuery[i][middle];
                }
            }
        }
        return medianPerSubQuery;
    }

    static private float[] findMADPerSubquery(
            final float[][] scoresPerSubQuery,
            final float[] medianPerSubQuery,
            final int numOfScores
    ) {
        final float[] madPerSubQuery = new float[numOfScores];
        for (int i = 0; i < numOfScores; i++) {
            if (scoresPerSubQuery[i].length > 0) {
                float[] deviations = new float[scoresPerSubQuery[i].length];
                for (int j = 0; j < scoresPerSubQuery[i].length; j++) {
                    deviations[j] = Math.abs(scoresPerSubQuery[i][j] - medianPerSubQuery[i]);
                }
                Arrays.sort(deviations);
                int middle = deviations.length / 2;
                float medianDeviation;
                if (deviations.length % 2 == 0) {
                    medianDeviation = (deviations[middle - 1] + deviations[middle]) / 2.0f;
                } else {
                    medianDeviation = deviations[middle];
                }
                madPerSubQuery[i] = medianDeviation * MAD_CONSTANT;
            }
        }
        return madPerSubQuery;
    }

    private static float normalizeSingleScore(final float score, final float mad, final float median) {
        // edge case when there is only one score and min and max scores are same
        if (Floats.compare(median, score) == 0) {
            return SINGLE_RESULT_SCORE;
        }
        float normalizedScore = (score - median) / mad;
        return normalizedScore < 0.0f ? MIN_SCORE : normalizedScore;
    }
}
