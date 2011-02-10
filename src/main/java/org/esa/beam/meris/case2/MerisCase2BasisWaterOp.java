package org.esa.beam.meris.case2;

import org.esa.beam.atmosphere.operator.MerisFlightDirection;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Mask;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.ProductNodeGroup;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.experimental.PixelOperator;
import org.esa.beam.jai.ResolutionLevel;
import org.esa.beam.jai.VirtualBandOpImage;
import org.esa.beam.meris.case2.fit.ChiSquareFitting;
import org.esa.beam.meris.case2.util.nn.NNffbpAlphaTabFast;
import org.esa.beam.meris.case2.water.WaterAlgorithm;
import org.esa.beam.util.ProductUtils;

import java.awt.Color;
import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import static org.esa.beam.dataio.envisat.EnvisatConstants.*;
import static org.esa.beam.meris.case2.water.WaterAlgorithm.*;

@SuppressWarnings({"UnusedDeclaration"})
public abstract class MerisCase2BasisWaterOp extends PixelOperator {

    // todo move to EnivsatConstants
    private static final String MERIS_ZONAL_WIND_DS_NAME = "zonal_wind";
    private static final String MERIS_MERID_WIND_DS_NAME = "merid_wind";

    @SuppressWarnings({"PointlessBitwiseExpression"})
    private static final int WLR_OOR = 0x0001 << WLR_OOR_BIT_INDEX;         // WLR out of scope
    private static final int CONC_OOR = 0x01 << CONC_OOR_BIT_INDEX;         // concentration out of range
    private static final int OOTR = 0x01 << OOTR_BIT_INDEX;                 // out of training range == chi2 of measured and fwNN spectrum above threshold
    private static final int WHITECAPS = 0x01 << WHITECAPS_BIT_INDEX;       // risk for white caps
    private static final int FIT_FAILED = 0x01 << FIT_FAILED_INDEX;          // fit failed
    private static final int INVALID = 0x01 << INVALID_BIT_INDEX;           // not a usable water pixel

    private static final String BAND_NAME_A_GELBSTOFF = "a_gelbstoff";
    private static final String BAND_NAME_A_PIGMENT = "a_pig";
    private static final String BAND_NAME_A_TOTAL = "a_total";
    private static final String BAND_NAME_B_TSM = "b_tsm";
    private static final String BAND_NAME_TSM = "tsm";
    private static final String BAND_NAME_CHL_CONC = "chl_conc";
    private static final String BAND_NAME_CHI_SQUARE = "chiSquare";
    private static final String BAND_NAME_K_MIN = "K_min";
    private static final String BAND_NAME_Z90_MAX = "Z90_max";
    private static final String BAND_NAME_KD_490 = "Kd_490";
    private static final String BAND_NAME_TURBIDITY_INDEX = "turbidity_index";
    private static final String BAND_NAME_CASE2_FLAGS = "case2_flags";
    private static final String BAND_NAME_A_GELBSTOFF_FIT = "a_gelbstoffFit";
    private static final String BAND_NAME_A_GELBSTOFF_FIT_MAX = "a_gelbstoffFit_max";
    private static final String BAND_NAME_A_GELBSTOFF_FIT_MIN = "a_gelbstoffFit_min";
    private static final String BAND_NAME_A_PIG_FIT = "a_pigFit";
    private static final String BAND_NAME_A_PIG_FIT_MAX = "a_pigFit_max";
    private static final String BAND_NAME_A_PIG_FIT_MIN = "a_pigFit_min";
    private static final String BAND_NAME_B_TSM_FIT = "b_tsmFit";
    private static final String BAND_NAME_B_TSM_FIT_MAX = "b_tsmFit_max";
    private static final String BAND_NAME_B_TSM_FIT_MIN = "b_tsmFit_min";
    private static final String BAND_NAME_TSM_FIT = "tsmFit";
    private static final String BAND_NAME_CHL_CONC_FIT = "chl_concFit";
    private static final String BAND_NAME_CHI_SQUARE_FIT = "chiSquareFit";
    private static final String BAND_NAME_N_ITER = "nIter";
    private static final String BAND_NAME_PARAM_CHANGE = "paramChange";

    private static final double WINDSPEED_THRESHOLD = 12.0;

    @Parameter(defaultValue = "4.0", description = "Threshold to indicate Spectrum is Out of Scope")
    private double spectrumOutOfScopeThreshold;

    @Parameter(defaultValue = "agc_flags.INVALID",
               description = "Expression defining pixels not considered for processing")
    private String invalidPixelExpression;

    @Parameter(label = "Inverse water neural net (optional)",
               description = "The file of the inverse water neural net to be used instead of the default.")
    private File inverseWaterNnFile;

    @Parameter(label = "Forward water neural net (optional)",
               description = "The file of the forward water neural net to be used instead of the default.")
    private File forwardWaterNnFile;

    @Parameter(label = "Perform Chi-Square fitting", defaultValue = "false",
               description = "Whether or not to perform the Chi-Square fitting.")
    private boolean performChiSquareFit;

    private int centerPixel;
    private boolean isFullResolution;
    private org.esa.beam.meris.case2.water.WaterAlgorithm waterAlgorithm;
    private String inverseWaterNnString;
    private String forwardWaterNnString;
    private ThreadLocal<NNffbpAlphaTabFast> threadLocalInverseWaterNet;
    private ThreadLocal<NNffbpAlphaTabFast> threadLocalForwardWaterNet;
    private VirtualBandOpImage invalidOpImage;

    @Override
    protected void configureTargetProduct(Product targetProduct) {
        final Product sourceProduct = getSourceProduct();

        addTargetBands(targetProduct);


        // copy bands of FRS products
        ProductUtils.copyBand(MERIS_AMORGOS_L1B_CORR_LATITUDE_BAND_NAME, sourceProduct, targetProduct);
        ProductUtils.copyBand(MERIS_AMORGOS_L1B_CORR_LONGITUDE_BAND_NAME, sourceProduct, targetProduct);
        ProductUtils.copyBand(MERIS_AMORGOS_L1B_ALTIUDE_BAND_NAME, sourceProduct, targetProduct);

        ProductUtils.copyFlagBands(sourceProduct, targetProduct);
        final Band[] sourceBands = sourceProduct.getBands();
        for (Band sourceBand : sourceBands) {
            if (sourceBand.isFlagBand()) {
                final Band targetBand = targetProduct.getBand(sourceBand.getName());
                targetBand.setSourceImage(sourceBand.getSourceImage());
            }
        }

        ProductUtils.copyMetadata(sourceProduct, targetProduct);
        targetProduct.setProductType(getProductType());
        addFlagsAndMasks(targetProduct);
    }

    protected void addTargetBands(Product targetProduct) {
        addTargetBand(targetProduct, BAND_NAME_A_GELBSTOFF, "m^-1",
                      "Gelbstoff (yellow substance) absorption  at 442 nm", true, ProductData.TYPE_FLOAT32);
        addTargetBand(targetProduct, BAND_NAME_A_PIGMENT, "m^-1",
                      "Pigment absorption at band 2 ", true, ProductData.TYPE_FLOAT32);
        addTargetBand(targetProduct, BAND_NAME_A_TOTAL, "m^-1",
                      "Absorption at 443 nm of all water constituents", false, ProductData.TYPE_FLOAT32);
        addTargetBand(targetProduct, BAND_NAME_B_TSM, "m^-1",
                      "Total suspended matter scattering", true, ProductData.TYPE_FLOAT32);
        addTargetBand(targetProduct, BAND_NAME_TSM, "g m^-3",
                      "Total suspended matter dry weight concentration", true, ProductData.TYPE_FLOAT32);
        addTargetBand(targetProduct, BAND_NAME_CHL_CONC, "mg m^-3",
                      "Chlorophyll concentration", true, ProductData.TYPE_FLOAT32);
        addTargetBand(targetProduct, BAND_NAME_CHI_SQUARE, null,
                      "Chi Square Out of Scope", true, ProductData.TYPE_FLOAT32);
        addTargetBand(targetProduct, BAND_NAME_K_MIN, "m^-1",
                      "Minimum downwelling irradiance attenuation coefficient", false, ProductData.TYPE_FLOAT32);
        addTargetBand(targetProduct, BAND_NAME_Z90_MAX, "m",
                      "Maximum signal depth", false, ProductData.TYPE_FLOAT32);
        addTargetBand(targetProduct, BAND_NAME_KD_490, "m^-1",
                      "Downwelling irradiance attenuation coefficient at wavelength 490", false,
                      ProductData.TYPE_FLOAT32);
        addTargetBand(targetProduct, BAND_NAME_TURBIDITY_INDEX, "FNU",
                      "Turbidity index in FNU (Formazine Nephelometric Unit) ", false, ProductData.TYPE_FLOAT32);
        if (performChiSquareFit) {
            addTargetBand(targetProduct, BAND_NAME_A_GELBSTOFF_FIT, null, null, true, ProductData.TYPE_FLOAT32);
            addTargetBand(targetProduct, BAND_NAME_A_GELBSTOFF_FIT_MAX, null, null, true, ProductData.TYPE_FLOAT32);
            addTargetBand(targetProduct, BAND_NAME_A_GELBSTOFF_FIT_MIN, null, null, true, ProductData.TYPE_FLOAT32);

            addTargetBand(targetProduct, BAND_NAME_A_PIG_FIT, null, null, true, ProductData.TYPE_FLOAT32);
            addTargetBand(targetProduct, BAND_NAME_A_PIG_FIT_MAX, null, null, true, ProductData.TYPE_FLOAT32);
            addTargetBand(targetProduct, BAND_NAME_A_PIG_FIT_MIN, null, null, true, ProductData.TYPE_FLOAT32);

            addTargetBand(targetProduct, BAND_NAME_B_TSM_FIT, null, null, true, ProductData.TYPE_FLOAT32);
            addTargetBand(targetProduct, BAND_NAME_B_TSM_FIT_MAX, null, null, true, ProductData.TYPE_FLOAT32);
            addTargetBand(targetProduct, BAND_NAME_B_TSM_FIT_MIN, null, null, true, ProductData.TYPE_FLOAT32);

            addTargetBand(targetProduct, BAND_NAME_TSM_FIT, null, null, true, ProductData.TYPE_FLOAT32);
            addTargetBand(targetProduct, BAND_NAME_CHL_CONC_FIT, null, null, true, ProductData.TYPE_FLOAT32);
            addTargetBand(targetProduct, BAND_NAME_CHI_SQUARE_FIT, null, null, true, ProductData.TYPE_FLOAT32);
            addTargetBand(targetProduct, BAND_NAME_N_ITER, null, null, false, ProductData.TYPE_INT32);
            addTargetBand(targetProduct, BAND_NAME_PARAM_CHANGE, "1", "Parameter change in last fit step", false,
                          ProductData.TYPE_FLOAT32);
        }
    }

    @Override
    protected void configureTargetSamples(Configurator configurator) {
        configurator.defineSample(TARGET_A_GELBSTOFF_INDEX, BAND_NAME_A_GELBSTOFF);
        configurator.defineSample(TARGET_A_PIGMENT_INDEX, BAND_NAME_A_PIGMENT);
        configurator.defineSample(TARGET_A_TOTAL_INDEX, BAND_NAME_A_TOTAL);
        configurator.defineSample(TARGET_B_TSM_INDEX, BAND_NAME_B_TSM);
        configurator.defineSample(TARGET_TSM_INDEX, BAND_NAME_TSM);
        configurator.defineSample(TARGET_CHL_CONC_INDEX, BAND_NAME_CHL_CONC);
        configurator.defineSample(TARGET_CHI_SQUARE_INDEX, BAND_NAME_CHI_SQUARE);
        configurator.defineSample(TARGET_K_MIN_INDEX, BAND_NAME_K_MIN);
        configurator.defineSample(TARGET_Z90_MAX_INDEX, BAND_NAME_Z90_MAX);
        configurator.defineSample(TARGET_KD_490_INDEX, BAND_NAME_KD_490);
        configurator.defineSample(TARGET_TURBIDITY_INDEX_INDEX, BAND_NAME_TURBIDITY_INDEX);
        configurator.defineSample(TARGET_FLAG_INDEX, BAND_NAME_CASE2_FLAGS);
        if (performChiSquareFit) {
            configurator.defineSample(TARGET_A_GELBSTOFF_FIT_INDEX, BAND_NAME_A_GELBSTOFF_FIT);
            configurator.defineSample(TARGET_A_GELBSTOFF_FIT_MAX_INDEX, BAND_NAME_A_GELBSTOFF_FIT_MAX);
            configurator.defineSample(TARGET_A_GELBSTOFF_FIT_MIN_INDEX, BAND_NAME_A_GELBSTOFF_FIT_MIN);

            configurator.defineSample(TARGET_A_PIG_FIT_INDEX, BAND_NAME_A_PIG_FIT);
            configurator.defineSample(TARGET_A_PIG_FIT_MAX_INDEX, BAND_NAME_A_PIG_FIT_MAX);
            configurator.defineSample(TARGET_A_PIG_FIT_MIN_INDEX, BAND_NAME_A_PIG_FIT_MIN);

            configurator.defineSample(TARGET_B_TSM_FIT_INDEX, BAND_NAME_B_TSM_FIT);
            configurator.defineSample(TARGET_B_TSM_FIT_MAX_INDEX, BAND_NAME_B_TSM_FIT_MAX);
            configurator.defineSample(TARGET_B_TSM_FIT_MIN_INDEX, BAND_NAME_B_TSM_FIT_MIN);

            configurator.defineSample(TARGET_TSM_FIT_INDEX, BAND_NAME_TSM_FIT);
            configurator.defineSample(TARGET_CHL_CONC_FIT_INDEX, BAND_NAME_CHL_CONC_FIT);
            configurator.defineSample(TARGET_CHI_SQUARE_FIT_INDEX, BAND_NAME_CHI_SQUARE_FIT);
            configurator.defineSample(TARGET_N_ITER_FIT_INDEX, BAND_NAME_N_ITER);
            configurator.defineSample(TARGET_PARAM_CHANGE_FIT_INDEX, BAND_NAME_PARAM_CHANGE);
        }
    }

    @Override
    protected void configureSourceSamples(Configurator configurator) {
        final Product sourceProduct = getSourceProduct();
        configurator.defineSample(SOURCE_REFLEC_1_INDEX, MERIS_L2_REFLEC_1_BAND_NAME);
        configurator.defineSample(SOURCE_REFLEC_2_INDEX, MERIS_L2_REFLEC_2_BAND_NAME);
        configurator.defineSample(SOURCE_REFLEC_3_INDEX, MERIS_L2_REFLEC_3_BAND_NAME);
        configurator.defineSample(SOURCE_REFLEC_4_INDEX, MERIS_L2_REFLEC_4_BAND_NAME);
        configurator.defineSample(SOURCE_REFLEC_5_INDEX, MERIS_L2_REFLEC_5_BAND_NAME);
        configurator.defineSample(SOURCE_REFLEC_6_INDEX, MERIS_L2_REFLEC_6_BAND_NAME);
        configurator.defineSample(SOURCE_REFLEC_7_INDEX, MERIS_L2_REFLEC_7_BAND_NAME);
        configurator.defineSample(SOURCE_REFLEC_9_INDEX, MERIS_L2_REFLEC_9_BAND_NAME);
        configurator.defineSample(SOURCE_REFLEC_10_INDEX, MERIS_L2_REFLEC_10_BAND_NAME);
        configurator.defineSample(SOURCE_REFLEC_12_INDEX, MERIS_L2_REFLEC_12_BAND_NAME);
        configurator.defineSample(SOURCE_REFLEC_13_INDEX, MERIS_L2_REFLEC_13_BAND_NAME);
        configurator.defineSample(SOURCE_SOLAZI_INDEX, MERIS_SUN_AZIMUTH_DS_NAME);
        configurator.defineSample(SOURCE_SOLZEN_INDEX, MERIS_SUN_ZENITH_DS_NAME);
        configurator.defineSample(SOURCE_SATAZI_INDEX, MERIS_VIEW_AZIMUTH_DS_NAME);
        configurator.defineSample(SOURCE_SATZEN_INDEX, MERIS_VIEW_ZENITH_DS_NAME);
        configurator.defineSample(SOURCE_ZONAL_WIND_INDEX, MERIS_ZONAL_WIND_DS_NAME);
        configurator.defineSample(SOURCE_MERID_WIND_INDEX, MERIS_MERID_WIND_DS_NAME);

        invalidOpImage = VirtualBandOpImage.createMask(invalidPixelExpression,
                                                       getSourceProduct(),
                                                       ResolutionLevel.MAXRES);

        centerPixel = MerisFlightDirection.findNadirColumnIndex(sourceProduct);

        isFullResolution = !sourceProduct.getProductType().contains("RR");
        waterAlgorithm = createAlgorithm();
        inverseWaterNnString = readNeuralNetString(getDefaultInverseWaterNetResourcePath(), inverseWaterNnFile);
        forwardWaterNnString = readNeuralNetString(getDefaultForwardWaterNetResourcePath(), forwardWaterNnFile);
        threadLocalInverseWaterNet = new ThreadLocal<NNffbpAlphaTabFast>() {
            @Override
            protected NNffbpAlphaTabFast initialValue() {
                try {
                    return new NNffbpAlphaTabFast(inverseWaterNnString);
                } catch (IOException e) {
                    throw new OperatorException("Not able to init neural net", e);
                }
            }
        };
        threadLocalForwardWaterNet = new ThreadLocal<NNffbpAlphaTabFast>() {
            @Override
            protected NNffbpAlphaTabFast initialValue() {
                try {
                    return new NNffbpAlphaTabFast(forwardWaterNnString);
                } catch (IOException e) {
                    throw new OperatorException("Not able to init neural net", e);
                }
            }
        };
    }

    @Override
    protected void computePixel(int x, int y, Sample[] sourceSamples, WritableSample[] targetSamples) {
        final double solazi = sourceSamples[SOURCE_SOLAZI_INDEX].getDouble();
        final double satazi = sourceSamples[SOURCE_SATAZI_INDEX].getDouble();
        double azi_diff_deg = getAzimuthDifference(satazi, solazi);
        double solzen = sourceSamples[SOURCE_SOLZEN_INDEX].getDouble();
        double satzen = sourceSamples[SOURCE_SATZEN_INDEX].getDouble();
        satzen = correctViewAngle(satzen, x, centerPixel, isFullResolution);
        double zonalWind = sourceSamples[SOURCE_ZONAL_WIND_INDEX].getDouble();
        double meridWind = sourceSamples[SOURCE_MERID_WIND_INDEX].getDouble();
        double windspeed = Math.sqrt(zonalWind * zonalWind + meridWind * meridWind);

        if (invalidOpImage.getData(new Rectangle(x, y, 1, 1)).getSample(x, y, 0) != 0) {
            targetSamples[TARGET_FLAG_INDEX].set(INVALID_BIT_INDEX, true);
            return;
        }

        if (windspeed > WINDSPEED_THRESHOLD) {
            targetSamples[TARGET_FLAG_INDEX].set(WHITECAPS_BIT_INDEX, true);
        }

        NNffbpAlphaTabFast inverseWaterNet = threadLocalInverseWaterNet.get();
        NNffbpAlphaTabFast forwardWaterNet = threadLocalForwardWaterNet.get();
        double[] RLw_cut = waterAlgorithm.perform(inverseWaterNet, forwardWaterNet,
                                                  solzen, satzen, azi_diff_deg, sourceSamples, targetSamples);
        if (performChiSquareFit) {
            final ChiSquareFitting fitting = createChiSquareFitting();
            fitting.perform(forwardWaterNet, RLw_cut, solzen, satzen, azi_diff_deg, targetSamples);
        }
    }

    protected abstract String getDefaultForwardWaterNetResourcePath();

    protected abstract String getDefaultInverseWaterNetResourcePath();

    protected abstract WaterAlgorithm createAlgorithm();

    protected abstract ChiSquareFitting createChiSquareFitting();

    protected abstract String getProductTypeSuffix();

    private String getProductType() {
        final String type = getSourceProduct().getProductType().substring(0, 7);
        return type + getProductTypeSuffix();
    }

    private double correctViewAngle(double satelliteZenith, int pixelX, int nadirPixelX, boolean isFullResolution) {
        final double ang_coef_1 = -0.004793;
        final double ang_coef_2 = isFullResolution ? 0.0093247 / 4 : 0.0093247;
        satelliteZenith = satelliteZenith + Math.abs(pixelX - nadirPixelX) * ang_coef_2 + ang_coef_1;
        return satelliteZenith;
    }

    /**
     * Computes the difference of solar and satellite azimuth in degree.
     *
     * @param satelliteAzimuth the satellite azimuth angle in degree
     * @param solarAzimuth     the solar azimuth angle in degree
     * @return azimuth difference in degree
     */
    private static double getAzimuthDifference(double satelliteAzimuth, double solarAzimuth) {
        double azi_diff_deg = Math.abs(satelliteAzimuth - solarAzimuth); /* azimuth difference */
        /* reverse azi difference */
        if (azi_diff_deg > 180.0) {
            azi_diff_deg = 360.0 - azi_diff_deg;
        }
        azi_diff_deg = 180.0 - azi_diff_deg; /* different definitions in MERIS data and MC /HL simulation */
        return azi_diff_deg;
    }

    private void addFlagsAndMasks(Product targetProduct) {
        final FlagCoding case2FlagCoding = new FlagCoding(BAND_NAME_CASE2_FLAGS);
        case2FlagCoding.addFlag("WLR_OOR", WLR_OOR, "WLR out of scope");
        case2FlagCoding.addFlag("CONC_OOR", CONC_OOR, "Concentration out of training range");
        case2FlagCoding.addFlag("OOTR", OOTR, "RLw out of training range");
        case2FlagCoding.addFlag("WHITECAPS", WHITECAPS, "Whitecaps pixels");
        case2FlagCoding.addFlag("FIT_FAILED", FIT_FAILED, "Fit failed");
        case2FlagCoding.addFlag("INVALID", INVALID, "not valid");
        targetProduct.getFlagCodingGroup().add(case2FlagCoding);
        final Band case2Flags = targetProduct.addBand(BAND_NAME_CASE2_FLAGS, ProductData.TYPE_UINT8);
        case2Flags.setSampleCoding(case2FlagCoding);

        final ProductNodeGroup<Mask> maskGroup = targetProduct.getMaskGroup();
        addMask(maskGroup, "case2_wlr_oor", "WLR out of scope", "case2_flags.WLR_OOR", Color.CYAN, 0.5f);
        addMask(maskGroup, "case2_conc_oor", "Concentration out of training range", "case2_flags.CONC_OOR",
                Color.DARK_GRAY, 0.5f);
        addMask(maskGroup, "case2_ootr", "RLw out of training range", "case2_flags.OOTR", Color.ORANGE, 0.5f);
        addMask(maskGroup, "case2_whitecaps", "Whitecaps pixels", "case2_flags.WHITECAPS", Color.PINK, 0.5f);
        addMask(maskGroup, "case2_fit_failed", "Fit failed", "case2_flags.FIT_FAILED", Color.MAGENTA, 0.5f);
        addMask(maskGroup, "case2_invalid", "invalid case2 pixel", "case2_flags.INVALID", Color.RED, 0.0f);
    }

    private static void addMask(ProductNodeGroup<Mask> maskGroup, String name, String description,
                                String expression, Color color, float transparency) {
        maskGroup.add(Mask.BandMathsType.create(name, description,
                                                maskGroup.getProduct().getSceneRasterWidth(),
                                                maskGroup.getProduct().getSceneRasterHeight(),
                                                expression, color, transparency));
    }

    protected final void addTargetBand(Product targetProduct, String bandName, String unit, String description,
                                       boolean log10Scaled, int dataType) {
        final Band band = targetProduct.addBand(bandName, dataType);
        band.setDescription(description);
        band.setUnit(unit);
        band.setLog10Scaled(log10Scaled);
        band.setValidPixelExpression("!case2_flags.INVALID");
    }

    private String readNeuralNetString(String resourceNetName, File neuralNetFile) {
        InputStream neuralNetStream;
        if (neuralNetFile == null) {
            neuralNetStream = getClass().getResourceAsStream(resourceNetName);
        } else {
            try {
                //noinspection IOResourceOpenedButNotSafelyClosed
                neuralNetStream = new FileInputStream(neuralNetFile);
            } catch (FileNotFoundException e) {
                throw new OperatorException(e);
            }
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(neuralNetStream));
        try {
            String line = reader.readLine();
            final StringBuilder sb = new StringBuilder();
            while (line != null) {
                // have to append line terminator, cause it's not included in line
                sb.append(line).append('\n');
                line = reader.readLine();
            }
            return sb.toString();
        } catch (IOException ioe) {
            throw new OperatorException("Could not initialize neural net", ioe);
        } finally {
            try {
                reader.close();
            } catch (IOException ignore) {
            }
        }
    }

    protected double getSpectrumOutOfScopeThreshold() {
        return spectrumOutOfScopeThreshold;
    }
}