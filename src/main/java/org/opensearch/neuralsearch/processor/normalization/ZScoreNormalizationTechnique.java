/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor.normalization;

import lombok.ToString;
import org.opensearch.neuralsearch.processor.CompoundTopDocs;
import org.opensearch.neuralsearch.processor.NormalizeScoresDTO;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;

import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.opensearch.neuralsearch.processor.CompoundTopDocs;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import com.google.common.primitives.Floats;
import org.opensearch.neuralsearch.processor.combination.ArithmeticMeanScoreCombinationTechnique;
import org.opensearch.neuralsearch.processor.combination.GeometricMeanScoreCombinationTechnique;
import org.opensearch.neuralsearch.processor.combination.HarmonicMeanScoreCombinationTechnique;
import org.opensearch.neuralsearch.processor.combination.ScoreCombinationTechnique;
import org.opensearch.neuralsearch.processor.explain.DocIdAtSearchShard;
import org.opensearch.neuralsearch.processor.explain.ExplainableTechnique;
import org.opensearch.neuralsearch.processor.explain.ExplanationDetails;

import java.util.List;
import java.util.Objects;

import static org.opensearch.neuralsearch.processor.explain.ExplanationUtils.getDocIdAtQueryForNormalization;

/**
 * Abstracts normalization of scores based on z score method
 */
@ToString(onlyExplicitlyIncluded = true)
public class ZScoreNormalizationTechnique implements ScoreNormalizationTechnique, ExplainableTechnique {
    @ToString.Include
    public static final String TECHNIQUE_NAME = "z_score";
    private static final float SINGLE_RESULT_SCORE = 1.0f;
    private static final float MIN_SCORE = 0.001f;

    /**
     * Z-score normalization transforms the data based on its mean and standard deviation, making it more robust to outliers.
     * This technique preserves the shape of the original distribution while ensuring that all features contribute equally to the analysis,
     * regardless of their original scales.
     * Z-score calculation: score - mean // standard_deviation
     * mean = sum of all scores / number of scores
     * standard_deviation = square root of (sum of all scores - mean) / number of scores
     * Main algorithm steps:
     * - calculate mean and standard deviation for each sub query
     * - iterate over each result and update score as per formula above where "score" is raw score returned by Hybrid query
     *
     * @param normalizeScoresDTO is a data transfer object that contains queryTopDocs
     * original query results from multiple shards and multiple sub-queries, ScoreNormalizationTechnique,
     * and nullable rankConstant that is only used in RRF technique
     */
    @Override
    public void normalize(NormalizeScoresDTO normalizeScoresDTO) {
        List<CompoundTopDocs> queryTopDocs = normalizeScoresDTO.getQueryTopDocs();

        ZScores zscores = getZScoreResults(queryTopDocs);

        // do normalization using actual score and z-scores for corresponding sub query
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
                        zscores.stdPerSubquery[j],
                        zscores.meanPerSubQuery[j],
                        zscores.maxPerSubQuery[j],
                        zscores.minPerSubQuery[j]
                    );
                }
            }
        }
    }

    @Override
    public void validateCombinationTechnique(ScoreCombinationTechnique combinationTechnique) throws IllegalArgumentException {
        switch (combinationTechnique.techniqueName()) {
            case ArithmeticMeanScoreCombinationTechnique.TECHNIQUE_NAME:
                // This is the supported technique, so we do nothing
                break;
            case GeometricMeanScoreCombinationTechnique.TECHNIQUE_NAME:
                throw new IllegalArgumentException("Z Score does not support geometric mean combination technique");
            case HarmonicMeanScoreCombinationTechnique.TECHNIQUE_NAME:
                throw new IllegalArgumentException("Z Score does not support harmonic mean combination technique");
            default:
                throw new IllegalArgumentException(
                    "Z Score does not support the provided combination technique {}: Supported technique is arithmetic_mean"
                        + combinationTechnique.techniqueName()
                );
        }
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "%s", TECHNIQUE_NAME);
    }

    @Override
    public Map<DocIdAtSearchShard, ExplanationDetails> explain(final List<CompoundTopDocs> queryTopDocs) {
        ZScoreNormalizationTechnique.ZScores zScores = getZScoreResults(queryTopDocs);

        Map<DocIdAtSearchShard, List<Float>> normalizedScores = new HashMap<>();
        for (CompoundTopDocs compoundQueryTopDocs : queryTopDocs) {
            if (Objects.isNull(compoundQueryTopDocs)) {
                continue;
            }
            List<TopDocs> topDocsPerSubQuery = compoundQueryTopDocs.getTopDocs();
            int numberOfSubQueries = topDocsPerSubQuery.size();
            for (int subQueryIndex = 0; subQueryIndex < numberOfSubQueries; subQueryIndex++) {
                TopDocs subQueryTopDoc = topDocsPerSubQuery.get(subQueryIndex);
                for (ScoreDoc scoreDoc : subQueryTopDoc.scoreDocs) {
                    DocIdAtSearchShard docIdAtSearchShard = new DocIdAtSearchShard(scoreDoc.doc, compoundQueryTopDocs.getSearchShard());
                    float normalizedScore = normalizeSingleScore(
                        scoreDoc.score,
                        zScores.stdPerSubquery[subQueryIndex],
                        zScores.meanPerSubQuery[subQueryIndex],
                        zScores.maxPerSubQuery[subQueryIndex],
                        zScores.minPerSubQuery[subQueryIndex]
                    );
                    ScoreNormalizationUtil.setNormalizedScore(
                        normalizedScores,
                        docIdAtSearchShard,
                        subQueryIndex,
                        numberOfSubQueries,
                        normalizedScore
                    );
                    scoreDoc.score = normalizedScore;
                }
            }
        }
        return getDocIdAtQueryForNormalization(normalizedScores, this);
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

    /*private static float[] calculateScoreSumPerSubQuery(final List<CompoundTopDocs> queryTopDocs, final int numOfScores) {
        final float[] sumOfScorePerSubQuery = new float[numOfScores];
        Arrays.fill(sumOfScorePerSubQuery, 0);
        for (CompoundTopDocs compoundQueryTopDocs : queryTopDocs) {
            if (Objects.isNull(compoundQueryTopDocs)) {
                continue;
            }
            List<TopDocs> topDocsPerSubQuery = compoundQueryTopDocs.getTopDocs();
            int subQueryIndex = 0;
            for (TopDocs topDocs : topDocsPerSubQuery) {
                sumOfScorePerSubQuery[subQueryIndex++] += sumScoreDocsArray(topDocs.scoreDocs);
            }
        }

        return sumOfScorePerSubQuery;
    }*/

    private static float[] calculateMaxScorePerSubquery(final List<CompoundTopDocs> queryTopDocs, final int numOfScores) {
        DescriptiveStatistics[] statsPerSubquery = calculateStatsPerSubquery(queryTopDocs, numOfScores);

        float[] maxPerSubQuery = new float[numOfScores];
        for (int i = 0; i < numOfScores; i++) {
            maxPerSubQuery[i] = (float) statsPerSubquery[i].getMax();
        }

        return maxPerSubQuery;
    }

    private static float[] calculateMinScorePerSubquery(final List<CompoundTopDocs> queryTopDocs, final int numOfScores) {
        DescriptiveStatistics[] statsPerSubquery = calculateStatsPerSubquery(queryTopDocs, numOfScores);

        float[] minPerSubQuery = new float[numOfScores];
        for (int i = 0; i < numOfScores; i++) {
            minPerSubQuery[i] = (float) statsPerSubquery[i].getMin();
        }

        return minPerSubQuery;
    }

    /*private static long[] calculateNumberOfElementsPerSubQuery(final List<CompoundTopDocs> queryTopDocs, final int numOfScores) {
        final long[] numberOfElementsPerSubQuery = new long[numOfScores];
        Arrays.fill(numberOfElementsPerSubQuery, 0);
        for (CompoundTopDocs compoundQueryTopDocs : queryTopDocs) {
            if (Objects.isNull(compoundQueryTopDocs)) {
                continue;
            }
            List<TopDocs> topDocsPerSubQuery = compoundQueryTopDocs.getTopDocs();
            int subQueryIndex = 0;
            for (TopDocs topDocs : topDocsPerSubQuery) {
                numberOfElementsPerSubQuery[subQueryIndex++] += topDocs.totalHits.value();
            }
        }

        return numberOfElementsPerSubQuery;
    }*/

    private static DescriptiveStatistics[] calculateStatsPerSubquery(final List<CompoundTopDocs> queryTopDocs, final int numOfScores) {
        DescriptiveStatistics[] statsPerSubquery = new DescriptiveStatistics[numOfScores];
        for (int i = 0; i < numOfScores; i++) {
            statsPerSubquery[i] = new DescriptiveStatistics();
        }

        for (CompoundTopDocs compoundQueryTopDocs : queryTopDocs) {
            if (Objects.isNull(compoundQueryTopDocs)) {
                continue;
            }
            List<TopDocs> topDocsPerSubQuery = compoundQueryTopDocs.getTopDocs();
            for (int subQueryIndex = 0; subQueryIndex < topDocsPerSubQuery.size(); subQueryIndex++) {
                TopDocs topDocs = topDocsPerSubQuery.get(subQueryIndex);
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    statsPerSubquery[subQueryIndex].addValue(scoreDoc.score);
                }
            }
        }

        return statsPerSubquery;
    }

    private static float[] calculateMeanPerSubquery(final List<CompoundTopDocs> queryTopDocs, final int numOfScores) {
        DescriptiveStatistics[] statsPerSubquery = calculateStatsPerSubquery(queryTopDocs, numOfScores);

        float[] meanPerSubQuery = new float[numOfScores];
        for (int i = 0; i < numOfScores; i++) {
            meanPerSubQuery[i] = (float) statsPerSubquery[i].getMean();
        }

        return meanPerSubQuery;
    }

    private static float[] calculateStandardDeviationPerSubquery(final List<CompoundTopDocs> queryTopDocs, final int numOfScores) {
        DescriptiveStatistics[] statsPerSubquery = calculateStatsPerSubquery(queryTopDocs, numOfScores);

        float[] stdPerSubQuery = new float[numOfScores];
        for (int i = 0; i < numOfScores; i++) {
            stdPerSubQuery[i] = (float) statsPerSubquery[i].getStandardDeviation();
        }

        return stdPerSubQuery;
    }

    /*private static float sumScoreDocsArray(final ScoreDoc[] scoreDocs) {
        float sum = 0;
        for (ScoreDoc scoreDoc : scoreDocs) {
            sum += scoreDoc.score;
        }

        return sum;
    }
    */
    private ZScores getZScoreResults(final List<CompoundTopDocs> queryTopDocs) {
        int numOfSubqueries = getNumOfSubqueries(queryTopDocs);

        // to be done for each subquery
        // float[] sumPerSubquery = calculateScoreSumPerSubQuery(queryTopDocs, numOfSubqueries);
        float[] maxPerSubquery = calculateMaxScorePerSubquery(queryTopDocs, numOfSubqueries);
        float[] minPerSubquery = calculateMinScorePerSubquery(queryTopDocs, numOfSubqueries);
        // long[] elementsPerSubquery = calculateNumberOfElementsPerSubQuery(queryTopDocs, numOfSubqueries);
        float[] meanPerSubQuery = calculateMeanPerSubquery(queryTopDocs, numOfSubqueries);
        float[] stdPerSubquery = calculateStandardDeviationPerSubquery(queryTopDocs, numOfSubqueries);
        return new ZScores(meanPerSubQuery, stdPerSubquery, maxPerSubquery, minPerSubquery);
    }

    private static float normalizeSingleScore(
        final float score,
        final float standardDeviation,
        final float mean,
        final float maxScore,
        final float minScore
    ) {
        // edge case when there is only one score and z scores are same
        if (Floats.compare(mean, score) == 0) {
            return maxScore;
        }
        // Case when sd is 0
        if (Floats.compare(standardDeviation, 0.0f) == 0) {
            return minScore;
        }
        float normalizedScore = (score - mean) / standardDeviation;

        return normalizedScore <= 0.0f ? MIN_SCORE : normalizedScore;
    }

    /**
     * Record to hold mean, std dev, max and min scores for each sub query
     */
    private record ZScores(float[] meanPerSubQuery, float[] stdPerSubquery, float[] maxPerSubQuery, float[] minPerSubQuery) {
    }
}
