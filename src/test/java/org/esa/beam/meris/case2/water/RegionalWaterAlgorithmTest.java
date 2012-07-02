package org.esa.beam.meris.case2.water;

import org.esa.beam.atmosphere.operator.ReflectanceEnum;
import org.esa.beam.framework.datamodel.RasterDataNode;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.pointop.Sample;
import org.esa.beam.framework.gpf.pointop.WritableSample;
import org.esa.beam.meris.case2.RegionalWaterOp;
import org.esa.beam.nn.NNffbpAlphaTabFast;
import org.esa.beam.util.BitSetter;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.*;

public class RegionalWaterAlgorithmTest {

    private NNffbpAlphaTabFast inverseNet;
    private NNffbpAlphaTabFast forwardNet;

    @Before
    public void before() throws Exception {
        inverseNet = new NNffbpAlphaTabFast(getClass().getResourceAsStream("regional_inverse_test.net"));
        forwardNet = new NNffbpAlphaTabFast(getClass().getResourceAsStream("regional_forward_test.net"));
    }

    private ThreadLocal<NNffbpAlphaTabFast> createNeuralNet(final String resourceNetName) {
        final InputStream inputStream = getClass().getResourceAsStream(resourceNetName);
        return new ThreadLocal<NNffbpAlphaTabFast>() {
            @Override
            protected NNffbpAlphaTabFast initialValue() {
                try {

                    return new NNffbpAlphaTabFast(inputStream);
                } catch (IOException e) {
                    throw new OperatorException("Not able to init neural net", e);
                }
            }
        };
    }

    @Test
    public void testComputation() throws Exception {
//        final WaterAlgorithm regionalAlgo = new WaterAlgorithm(false, false, 4.0, 1.0, 1.73,
//                                                               ReflectanceEnum.RADIANCE_REFLECTANCES, threadLocalForwardIopNet, threadLocalInverseIopNet, threadLocalInverseKdNet);
        final double aziDiff = RegionalWaterOp.getAzimuthDifference(89.83, 283.79);
        final Sample[] sourceSamples = createSourceSamples();
        final WritableSample[] targetSamples = createTargetSamples();
//        regionalAlgo.perform(inverseNet, forwardNet, 23.255, 16.845, aziDiff, sourceSamples, targetSamples, 35.0, 15.0);
        assertFalse(Double.isNaN(targetSamples[WaterAlgorithm.TARGET_A_GELBSTOFF_INDEX].getDouble()));
        assertFalse(Double.isNaN(targetSamples[WaterAlgorithm.TARGET_A_PIGMENT_INDEX].getDouble()));
        assertFalse(Double.isNaN(targetSamples[WaterAlgorithm.TARGET_A_TOTAL_INDEX].getDouble()));
        assertFalse(Double.isNaN(targetSamples[WaterAlgorithm.TARGET_BB_SPM_INDEX].getDouble()));
        assertFalse(Double.isNaN(targetSamples[WaterAlgorithm.TARGET_TSM_INDEX].getDouble()));
        assertFalse(Double.isNaN(targetSamples[WaterAlgorithm.TARGET_CHL_CONC_INDEX].getDouble()));
        assertFalse(Double.isNaN(targetSamples[WaterAlgorithm.TARGET_CHI_SQUARE_INDEX].getDouble()));
        assertFalse(Double.isNaN(targetSamples[WaterAlgorithm.TARGET_K_MIN_INDEX].getDouble()));
        assertFalse(Double.isNaN(targetSamples[WaterAlgorithm.TARGET_Z90_MAX_INDEX].getDouble()));
        assertFalse(Double.isNaN(targetSamples[WaterAlgorithm.TARGET_KD_490_INDEX].getDouble()));
        assertFalse(Double.isNaN(targetSamples[WaterAlgorithm.TARGET_TURBIDITY_INDEX_INDEX].getDouble()));
        assertEquals(0, targetSamples[WaterAlgorithm.TARGET_FLAG_INDEX].getInt(), 1.0e-3);
    }

    private Sample[] createSourceSamples() {
        final Sample[] sourceSamples = new Sample[18];
        sourceSamples[WaterAlgorithm.SOURCE_REFLEC_1_INDEX] = new TestSample(0.015459167);
        sourceSamples[WaterAlgorithm.SOURCE_REFLEC_2_INDEX] = new TestSample(0.015351999);
        sourceSamples[WaterAlgorithm.SOURCE_REFLEC_3_INDEX] = new TestSample(0.016962104);
        sourceSamples[WaterAlgorithm.SOURCE_REFLEC_4_INDEX] = new TestSample(0.013087227);
        sourceSamples[WaterAlgorithm.SOURCE_REFLEC_5_INDEX] = new TestSample(0.0091405315);
        sourceSamples[WaterAlgorithm.SOURCE_REFLEC_6_INDEX] = new TestSample(0.0020359613);
        sourceSamples[WaterAlgorithm.SOURCE_REFLEC_7_INDEX] = new TestSample(0.0011729593);
        sourceSamples[WaterAlgorithm.SOURCE_REFLEC_8_INDEX] = new TestSample(0.0011168025);
        sourceSamples[WaterAlgorithm.SOURCE_REFLEC_9_INDEX] = new TestSample(5.6830555E-4);
        sourceSamples[WaterAlgorithm.SOURCE_REFLEC_10_INDEX] = new TestSample(6.6830555E-4);
        sourceSamples[WaterAlgorithm.SOURCE_REFLEC_12_INDEX] = new TestSample(6.6830555E-4);
        sourceSamples[WaterAlgorithm.SOURCE_REFLEC_13_INDEX] = new TestSample(6.6830555E-4);
        sourceSamples[WaterAlgorithm.SOURCE_SOLAZI_INDEX] = new TestSample(89.832535);
        sourceSamples[WaterAlgorithm.SOURCE_SOLZEN_INDEX] = new TestSample(23.25591);
        sourceSamples[WaterAlgorithm.SOURCE_SATAZI_INDEX] = new TestSample(283.79846);
        sourceSamples[WaterAlgorithm.SOURCE_SATZEN_INDEX] = new TestSample(16.845516);
        sourceSamples[WaterAlgorithm.SOURCE_ZONAL_WIND_INDEX] = new TestSample(-2.6816404);
        sourceSamples[WaterAlgorithm.SOURCE_MERID_WIND_INDEX] = new TestSample(-0.71250004);
        return sourceSamples;
    }

    private WritableSample[] createTargetSamples() {
        final WritableSample[] targetSamples = new WritableSample[13];
        for (int i = 0; i < targetSamples.length; i++) {
            targetSamples[i] = new TestSample(Double.NaN);
        }
        return targetSamples;
    }

    private static class TestSample implements WritableSample {

        private double value;

        private TestSample(double value) {
            this.value = value;
        }

        @Override
        public double getDouble() {
            return value;
        }

        @Override
        public void set(double v) {
            value = v;
        }

        @Override
        public void set(int bitIndex, boolean condition) {
            long flagValues = BitSetter.setFlag(Double.doubleToLongBits(value), bitIndex, condition);
            value = Double.longBitsToDouble(flagValues);
        }

        @Override
        public void set(boolean v) {
            throw new IllegalStateException("Not implemented!");

        }

        @Override
        public void set(int v) {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public void set(float v) {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public RasterDataNode getNode() {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public int getIndex() {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public int getDataType() {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public boolean getBit(int bitIndex) {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public boolean getBoolean() {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public int getInt() {
            return (int) Double.doubleToLongBits(value);
        }

        @Override
        public float getFloat() {
            throw new IllegalStateException("Not implemented!");
        }

    }
}
