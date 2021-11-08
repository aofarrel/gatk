package org.broadinstitute.hellbender.tools.walkers.sv.GqRecalibrator;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

class FilterLoss implements Comparable<FilterLoss> {
    final double inheritanceLoss;
    final double truthLoss;
    final double inheritanceWeight;
    final double truthWeight;
    final String label;
    static final double targetPrecision = MinGqVariantFilterBase.targetPrecision;

    FilterLoss(final double inheritanceLoss, final double truthLoss,
               final double inheritanceWeight, final double truthWeight, final String label) {
        if(inheritanceWeight < 0) {
            throw new IllegalArgumentException("inheritanceWeight (" + inheritanceWeight + ") < 0");
        }
        if(truthWeight < 0) {
            throw new IllegalArgumentException("truthWeight (" + truthWeight + ") < 0");
        }
        this.inheritanceLoss = inheritanceLoss;
        this.truthLoss = truthLoss;
        this.inheritanceWeight = inheritanceWeight;
        this.truthWeight = truthWeight;
        this.label = label;
    }


    FilterLoss(final FilterSummary filterSummary) {
        this(1.0 - getInheritanceF1(filterSummary),
             1.0 - getTruthF1(filterSummary),
             filterSummary.inheritanceWeight * inheritanceAlleleFrequencyWeight(filterSummary),
             filterSummary.truthWeight,
             filterSummary.toTableLine());
    }

    FilterLoss(final FilterLoss copyLoss, final String label) {
        this(copyLoss.inheritanceLoss, copyLoss.truthLoss, copyLoss.inheritanceWeight, copyLoss.truthWeight, label);
    }

    FilterLoss(final Collection<FilterSummary> filterSummaries, final List<String> labelColumns) {
        this(
            filterSummaries.stream()
                .map(FilterLoss::new)
                .reduce(FilterLoss::add).orElse(EMPTY),
            getHeader(labelColumns) + "\n" +
                filterSummaries.stream()
                    .filter(FilterSummary::isNotEmpty)
                    .map(FilterLoss::new)
                    .map(FilterLoss::toString)
                    .collect(Collectors.joining("\n"))
                    + "\n" + filterSummaries.stream()
                        .filter(FilterSummary::isNotEmpty)
                        .reduce(FilterSummary::add).orElse(FilterSummary.EMPTY)
                        .setLabel(getOverallLabel(labelColumns))
                        .toTableLine()
        );
    }

    protected static String getHeader(final List<String> labelColumns){
        return FilterSummary.tableHeader(labelColumns) + "\tinheritLoss\ttruthLoss";
    }

    protected static String getOverallLabel(final List<String> labelColumns) {
        return labelColumns.stream()
            .map(col -> MinGqVariantFilterBase.padWidth("all", col.length()))
            .collect(Collectors.joining("\t"));
    }

    protected static double inheritanceAlleleFrequencyWeight(final FilterSummary filterSummary) {
        return filterSummary.isLargeAlleleFrequency ? 1e-6 : 1.0;
    }

    protected static double getInheritanceF1(final FilterSummary filterSummary) {
        // calculate f1 score:
        //     recall = numPassedAlleleCount / maxPassedAlleleCount
        //     precision = numMendelian / numDiscoverableTrios
        //     f1 = 2.0 / (1.0 / recall + 1.0 / precision)
        // No data to filter? -> no loss -> f1 = 1.0
        if(filterSummary.numDiscoverableTrios == 0) {
            // No way to score this if no Mendelian trios are discoverable
            return Double.NaN;
        } else if(filterSummary.numMendelian == 0) {
            return 0.0;  // Discovered nothing -> get bad score even if you passed nothing
        }
        final double precision = filterSummary.numMendelian / (double)filterSummary.numDiscoverableTrios;
        if(precision > targetPrecision) { // in high-precision regime, maximize f1
            final double recall = filterSummary.numPassed / (double)filterSummary.numVariants;
            final double f1 = 2.0 / (1.0 / recall + 1.0 / precision);
            return f1 * (1.0 - targetPrecision) + targetPrecision;  // join continuously to low-precision regime
        } else {
            return precision; // in low-precision regime, just maximize precision
        }
    }

    protected static double getTruthF1(final FilterSummary filterSummary) {
        // NOTE: calls are compared against list of true variants and false variants
        // calculate f1 score:
        //     f1 = 2.0 / (1.0 / recall + 1.0 / precision)
        //     recall = numTruePositive / (numTruePositive + numFalseNegative)
        //     precision = numTruePositive / (numTruePositive + numFalsePositive)
        //     -> f1 = 2.0 * numTruePositive / (2 * numTruePositive + numFalseNegative + numFalsePositive)
        // No data to filter? -> no loss -> f1 = 1.0
        final long numTrue = filterSummary.numTruePositive + filterSummary.numFalseNegative;
        final long numFalse = filterSummary.numTrueNegative + filterSummary.numFalsePositive;
        if(numFalse == 0) {
            // no "false" variants in truth set, may have recall but no precision. This is dangerous to optimize,
            // so don't try
            return Double.NaN;
        } else if(numTrue == 0) {
            // no "true" variants in truth set, but we can optimize for correctly rejecting garbage
            final double negativePrecision = filterSummary.numTrueNegative / (double) numFalse;
            // if "negative recall" is good enough, borrow recall information from inheritance
            final double passRatio = filterSummary.numPassed / (double)(filterSummary.numVariants);
            return negativePrecision <= targetPrecision ? negativePrecision : targetPrecision + (1.0 - targetPrecision) * passRatio;
        }
        final long numClassifiedPositive = filterSummary.numTruePositive + filterSummary.numFalsePositive;
        if(numClassifiedPositive == 0) {
            // we know there is truth data, so this is just a crappy classifier
            return 0.0;
        }
        // this is guaranteed to be finite now
        final double precision = filterSummary.numTruePositive / (double) numClassifiedPositive;
        if(precision <= targetPrecision) {
            return precision;  // haven't met target precision, just optimize that
        }
        final double recall = filterSummary.numTruePositive / (double)numTrue;
        final double f1 = 2.0 / (1.0 / recall + 1.0 / precision);
        return f1 * (1.0 - targetPrecision) + targetPrecision;
    }


    static FilterLoss add(final FilterLoss lossA, final FilterLoss lossB) {
        return new FilterLoss(addLosses(lossA.inheritanceLoss, lossA.inheritanceWeight,
                                        lossB.inheritanceLoss, lossB.inheritanceWeight),
                              addLosses(lossA.truthLoss, lossA.truthWeight,
                                        lossB.truthLoss, lossB.truthWeight),
                              lossA.inheritanceWeight + lossB.inheritanceWeight,
                              lossA.truthWeight + lossB.truthWeight,
                              lossA.label == null ? lossB.label : lossA.label);
    }

    static private double addLosses(final double lossA, final double weightA, final double lossB, final double weightB) {
        final double weight = (weightA + weightB);
        if(weight <= 0) {
            return Double.NaN;
        }
        if(Double.isFinite(lossA)) {
            if(Double.isFinite(lossB)) {
                return (lossA * weightA + lossB * weightB) / weight;
            } else {
                return lossA * weightA / weight;
            }
        } else {
            return lossB * weightB / weight;
        }
    }

    public boolean ge(final FilterLoss other) {
        return toDouble() >= other.toDouble();
    }

    public boolean gt(final FilterLoss other) {
        return toDouble() > other.toDouble();
    }

    public boolean le(final FilterLoss other) {
        return toDouble() <= other.toDouble();
    }

    public boolean lt(final FilterLoss other) {
        return toDouble() < other.toDouble();
    }

    @Override
    public int compareTo(final @NotNull FilterLoss other) {
        if (this.lt(other)) {
            return -1;
        } else if (this.gt(other)) {
            return 1;
        } else {
            return 0;
        }
    }

    private static String getDescription(final double inheritanceLoss, final double truthLoss) {
        return String.format("%11.9f\t11%.9f", inheritanceLoss, truthLoss);
    }

    @Override
    public String toString() {
        return label == null ?
                getDescription(inheritanceLoss, truthLoss) :
                label + "\t" + getDescription(inheritanceLoss, truthLoss);
    }

    double toDouble() {
        final double weightedInheritanceLoss = Double.isFinite(inheritanceLoss) ?
            inheritanceWeight * inheritanceLoss :
            0.0;
        final double weightedTruthLoss = Double.isFinite(truthLoss) ?
            truthWeight * truthLoss :
            0.0;
        return weightedInheritanceLoss + weightedTruthLoss;
    }

    float toFloat() {
        return (float) toDouble();
    }

    static final FilterLoss EMPTY = new FilterLoss(FilterSummary.EMPTY);
}
