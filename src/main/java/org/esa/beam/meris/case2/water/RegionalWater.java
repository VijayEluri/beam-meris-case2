package org.esa.beam.meris.case2.water;

import org.esa.beam.framework.gpf.pointop.WritableSample;
import org.esa.beam.meris.case2.algorithm.KMin;


public class RegionalWater extends WaterAlgorithm {

    private final double tsmExponent;
    private final double tsmFactor;
    private final double chlExponent;
    private final double chlFactor;
    private boolean outputAPoc;

    public RegionalWater(boolean outputAllKds, boolean outputAPoc, double spectrumOutOfScopeThreshold,
                         double tsmExponent, double tsmFactor, double chlExponent, double chlFactor,
                         double averageSalinity, double averageTemperature) {
        super(outputAllKds, spectrumOutOfScopeThreshold, averageSalinity, averageTemperature);
        this.outputAPoc = outputAPoc;
        this.tsmExponent = tsmExponent;
        this.tsmFactor = tsmFactor;
        this.chlExponent = chlExponent;
        this.chlFactor = chlFactor;
    }

    @Override
    protected KMin createKMin(WritableSample[] targetSamples) {
        final double bTsm = targetSamples[TARGET_BB_SPM_INDEX].getDouble() / BTSM_TO_SPM_FACTOR;
        final double aPig = targetSamples[TARGET_A_PIGMENT_INDEX].getDouble();
        final double aGelbstoff = targetSamples[TARGET_A_GELBSTOFF_INDEX].getDouble();
        return new KMin(bTsm, aPig, aGelbstoff);
    }

    @Override
    protected double computeChiSquare(double[] forwardWaterOutnet, double[] logRLw_cut) {
        double chiSquare = 0.0;
        chiSquare += Math.pow(forwardWaterOutnet[0] - logRLw_cut[0], 2);
        chiSquare += Math.pow(forwardWaterOutnet[1] - logRLw_cut[1], 2);
        chiSquare += Math.pow(forwardWaterOutnet[2] - logRLw_cut[2], 2);
        chiSquare += Math.pow(forwardWaterOutnet[3] - logRLw_cut[3], 2);
        chiSquare += Math.pow(forwardWaterOutnet[4] - logRLw_cut[4], 2);
        chiSquare += Math.pow(forwardWaterOutnet[5] - logRLw_cut[5], 2);
        chiSquare += Math.pow(forwardWaterOutnet[6] - logRLw_cut[6], 2);
        // pay attention these are mixed up in the forward output net
        chiSquare += Math.pow(forwardWaterOutnet[7] - logRLw_cut[8], 2);
        chiSquare += Math.pow(forwardWaterOutnet[8] - logRLw_cut[7], 2);
        chiSquare += Math.pow(forwardWaterOutnet[9] - logRLw_cut[9], 2);
        return chiSquare;
    }

    @Override
    protected double[] getForwardWaterInnet(double solzen, double satzen, double azi_diff_deg,
                                            double averageTemperature, double averageSalinity, double[] waterOutnet) {
        double[] forwardWaterInnet = new double[12];
        forwardWaterInnet[0] = solzen;
        forwardWaterInnet[1] = satzen;
        forwardWaterInnet[2] = azi_diff_deg;
        forwardWaterInnet[3] = averageTemperature;
        forwardWaterInnet[4] = averageSalinity;
        forwardWaterInnet[5] = waterOutnet[0];
        forwardWaterInnet[6] = waterOutnet[1];
        forwardWaterInnet[7] = waterOutnet[2];
        forwardWaterInnet[8] = waterOutnet[3];
        forwardWaterInnet[9] = waterOutnet[4];
        forwardWaterInnet[10] = waterOutnet[5];
        forwardWaterInnet[11] = waterOutnet[6];
        return forwardWaterInnet;

    }

    @Override
    protected void fillOutput(double[] waterOutnet, WritableSample[] targetSamples) {
        double chlConc = Math.exp(waterOutnet[0]);

        targetSamples[TARGET_CHL_CONC_INDEX].set(chlConc);

        double aPart = Math.exp(waterOutnet[1]);
        double aGelbstoff = Math.exp(waterOutnet[2]);
        double aPig = Math.exp(waterOutnet[3]);
        targetSamples[TARGET_A_GELBSTOFF_INDEX].set(aGelbstoff);
        targetSamples[TARGET_A_PIGMENT_INDEX].set(aPig);
        targetSamples[TARGET_A_TOTAL_INDEX].set(aPig + aGelbstoff + aPart);

        double bTsm = Math.exp(waterOutnet[4]);
        targetSamples[TARGET_BB_SPM_INDEX].set(bTsm * BTSM_TO_SPM_FACTOR);
        targetSamples[TARGET_TSM_INDEX].set(Math.exp(Math.log(tsmFactor) + waterOutnet[4] * tsmExponent));

        if (outputAPoc) {
            // todo - How to compute a_poc_443?
            targetSamples[TARGET_A_POC_INDEX].set(0.0);
        }
    }

    @Override
    protected double[] getWaterInnet(double solzen, double satzen, double azi_diff_deg, double averageSalinity,
                                     double averageTemperature, double[] logRLw) {
        double[] waterInnet = new double[14];
        waterInnet[0] = solzen;
        waterInnet[1] = satzen;
        waterInnet[2] = azi_diff_deg;
        waterInnet[3] = averageTemperature;
        waterInnet[4] = averageSalinity;
        waterInnet[5] = logRLw[0]; // 412 reflec_1
        waterInnet[6] = logRLw[1]; // 443 reflec_2
        waterInnet[7] = logRLw[2]; // 490 reflec_3
        waterInnet[8] = logRLw[3]; // 510 reflec_4
        waterInnet[9] = logRLw[4]; // 560 reflec_5
        waterInnet[10] = logRLw[5]; // 620 reflec_6
        waterInnet[11] = logRLw[6]; // 665 reflec_7
        waterInnet[12] = logRLw[8]; // 708 reflec_9
        waterInnet[13] = logRLw[9]; // 753 reflec_10
        return waterInnet;
    }

}
