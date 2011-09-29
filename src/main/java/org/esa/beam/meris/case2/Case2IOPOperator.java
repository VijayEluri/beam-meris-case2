/*
 * Copyright (C) 2010 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.beam.meris.case2;

import org.esa.beam.atmosphere.operator.GlintCorrectionOperator;
import org.esa.beam.atmosphere.operator.ReflectanceEnum;
import org.esa.beam.framework.datamodel.MetadataElement;
import org.esa.beam.framework.datamodel.PixelGeoCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.gpf.operators.standard.MergeOp;
import org.esa.beam.util.ProductUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;


@OperatorMetadata(alias = "Meris.Case2Regional",
                  description = "Performs IOP retrieval on L1b MERIS products, including radiometric correction and atmospheric correction.",
                  authors = "Roland Doerffer (GKSS); Marco Peters (Brockmann Consult)",
                  copyright = "(c) 2011 by Brockmann Consult",
                  version = "1.6.5-CC3")
public class Case2IOPOperator extends Operator {

    @SourceProduct(alias = "source", label = "Name", description = "The source product.")
    private Product sourceProduct;

    ///////////  GlintCorrectionOperator  ///////////////////////////
    ///////////

    @Parameter(defaultValue = "true", label = "Perform atmospheric correction",
               description = "Whether or not to perform atmospheric correction.")
    private boolean doAtmosphericCorrection;

    @Parameter(label = "Alternative atm. corr. neural net (optional)",
               description = "The file of the atmospheric net to be used instead of the default neural net.")
    private File atmoNetFile;

    @Parameter(defaultValue = "true",
               label = "Perform SMILE correction",
               description = "Whether to perform SMILE correction.")
    private boolean doSmileCorrection;

    @Parameter(defaultValue = "false", label = "Output TOSA reflectance",
               description = "Toggles the output of TOSA reflectance.")
    private boolean outputTosa;

    @Parameter(defaultValue = "true", label = "Output water leaving reflectance",
               description = "Toggles the output of water leaving reflectance.")
    private boolean outputReflec;

    @Parameter(defaultValue = "RADIANCE_REFLECTANCES", valueSet = {"RADIANCE_REFLECTANCES", "IRRADIANCE_REFLECTANCES"},
               label = "Output water leaving reflectance as",
               description = "Select if reflectances shall be written as radiances or irradiances. " +
                             "The irradiances are compatible with standard MERIS product.")
    private ReflectanceEnum outputReflecAs;

    @Parameter(defaultValue = "true", label = "Output path reflectance",
               description = "Toggles the output of water leaving path reflectance.")
    private boolean outputPath;

    @Parameter(defaultValue = "false", label = "Output transmittance",
               description = "Toggles the output of downwelling irradiance transmittance.")
    private boolean outputTransmittance;

    @Parameter(defaultValue = "false", label = "Output Kd spectrum",
               description = "Toggles the output of downwelling irradiance attenuation coefficients. " +
                             "If disabled only Kd_490 is added to the output.")
    private boolean outputKdSpectrum;

    @Parameter(defaultValue = "false",
               label = "Output normalised bidirectional reflectances",
               description = "Toggles the output of normalised reflectances.")
    private boolean outputNormReflec;

    @Parameter(defaultValue = "false", label = "Output A_Poc",
               description = "Toggles the output of absorption by particulate organic matter.")
    private boolean outputAPoc;

    @Parameter(defaultValue = "toa_reflec_10 > toa_reflec_6 AND toa_reflec_13 > 0.0475",
               label = "Land detection expression",
               description = "The arithmetic expression used for land detection.",
               notEmpty = true, notNull = true)
    private String landExpression;

    @Parameter(defaultValue = "toa_reflec_14 > 0.2",
               label = "Cloud/Ice detection expression",
               description = "The arithmetic expression used for cloud/ice detection.",
               notEmpty = true, notNull = true)
    private String cloudIceExpression;

    ///////////  Case2WaterOp  ///////////////////////////
    ///////////


    @Parameter(label = "Average salinity", defaultValue = "35", unit = "PSU", description = "The salinity of the water")
    private double averageSalinity;

    @Parameter(label = "Average temperature", defaultValue = "15", unit = "°C", description = "The Water temperature")
    private double averageTemperature;

    @Parameter(label = "Tsm conversion exponent",
               defaultValue = "1.0",
               description = "Exponent for conversion from TSM to B_TSM.")
    private Double tsmConversionExponent;

    @Parameter(label = "Tsm conversion factor",
               defaultValue = "1.73",
               description = "Factor for conversion from TSM to B_TSM.")
    private Double tsmConversionFactor;

    @Parameter(label = "Chl conversion exponent",
               defaultValue = "1.04",
               description = "Exponent for conversion from A_PIG to CHL_CONC. ")
    private Double chlConversionExponent;

    @Parameter(label = "Chl conversion factor",
               defaultValue = "21.0",
               description = "Factor for conversion from A_PIG to CHL_CONC. ")
    private Double chlConversionFactor;

    @Parameter(defaultValue = "4.0", description = "Threshold to indicate Spectrum is Out of Scope.")
    private double spectrumOutOfScopeThreshold;

    @Parameter(defaultValue = "agc_flags.INVALID",
               description = "Expression defining pixels not considered for processing.")
    private String invalidPixelExpression;

    @Parameter(label = "Alternative inverse water neural net (optional)",
               description = "The file of the inverse water neural net to be used instead of the default.")
    private File inverseWaterNnFile;

    @Parameter(label = "Alternative forward water neural net (optional)",
               description = "The file of the forward water neural net to be used instead of the default.")
    private File forwardWaterNnFile;

    @Override
    public void initialize() throws OperatorException {
        Product inputProduct = sourceProduct;

        if (doAtmosphericCorrection) {
            Operator atmoCorOp = new GlintCorrectionOperator();
            atmoCorOp.setParameter("doSmileCorrection", doSmileCorrection);
            if (atmoNetFile != null) {
                atmoCorOp.setParameter("atmoNetMerisFile", atmoNetFile);
            }
            atmoCorOp.setParameter("outputReflec", true);
            atmoCorOp.setParameter("outputReflecAs", outputReflecAs);
            atmoCorOp.setParameter("outputTosa", outputTosa);
            atmoCorOp.setParameter("outputNormReflec", outputNormReflec);
            atmoCorOp.setParameter("outputPath", outputPath);
            atmoCorOp.setParameter("outputTransmittance", outputTransmittance);
            atmoCorOp.setParameter("averageSalinity", averageSalinity);
            atmoCorOp.setParameter("averageTemperature", averageTemperature);
            atmoCorOp.setParameter("landExpression", landExpression);
            atmoCorOp.setParameter("cloudIceExpression", cloudIceExpression);
            atmoCorOp.setSourceProduct("merisProduct", inputProduct);
            inputProduct = atmoCorOp.getTargetProduct();
        }

        Operator case2Op = new RegionalWaterOp();

        case2Op.setParameter("averageSalinity", averageSalinity);
        case2Op.setParameter("averageTemperature", averageTemperature);
        case2Op.setParameter("inputReflecAre", outputReflecAs);
        case2Op.setParameter("spectrumOutOfScopeThreshold", spectrumOutOfScopeThreshold);
        case2Op.setParameter("invalidPixelExpression", invalidPixelExpression);
        case2Op.setParameter("inverseWaterNnFile", inverseWaterNnFile);
        case2Op.setParameter("forwardWaterNnFile", forwardWaterNnFile);
        case2Op.setParameter("outputKdSpectrum", outputKdSpectrum);
        case2Op.setParameter("outputAPoc", outputAPoc);
        case2Op.setSourceProduct("acProduct", inputProduct);
        final Product case2Product = case2Op.getTargetProduct();

        List<MergeOp.BandDesc> bandDescList = new ArrayList<MergeOp.BandDesc>();
        final String[] names = inputProduct.getBandNames();
        for (String name : names) {
            if (name.contains("flags") || name.contains("b_tsm") || name.contains("a_tot")) {
                continue;
            }
            if (!outputReflec && name.startsWith("reflec")) {
                continue;
            }
            if (case2Product.containsBand(name)) {
                continue;
            }
            final MergeOp.BandDesc bandDesc = new MergeOp.BandDesc();
            bandDesc.setProduct("inputProduct");
            bandDesc.setNamePattern(name);
            bandDescList.add(bandDesc);
        }

        final String[] case2names = case2Product.getBandNames();
        for (String name : case2names) {
            if (inputProduct.getGeoCoding() instanceof PixelGeoCoding &&
                (name.startsWith("corr_") || name.startsWith("l1_flags"))) {
                continue;
            }
            final MergeOp.BandDesc bandDesc = new MergeOp.BandDesc();
            bandDesc.setProduct("case2Product");
            bandDesc.setNamePattern(name);
            bandDescList.add(bandDesc);
        }


        final MergeOp mergeOp = new MergeOp();
        mergeOp.setSourceProduct("inputProduct", inputProduct);
        mergeOp.setSourceProduct("case2Product", case2Product);
        mergeOp.setParameter("productName", case2Product.getName());
        mergeOp.setParameter("productType", case2Product.getProductType());
        mergeOp.setParameter("copyGeoCodingFrom", "case2Product");
        mergeOp.setParameter("bands", bandDescList.toArray(new MergeOp.BandDesc[bandDescList.size()]));
        final Product targetProduct = mergeOp.getTargetProduct();
        final MetadataElement metadataRoot = targetProduct.getMetadataRoot();
        removeAllMetadata(metadataRoot);
        ProductUtils.copyMetadata(case2Product, targetProduct);
        setTargetProduct(targetProduct);
    }

    private void removeAllMetadata(MetadataElement metadataRoot) {
        final MetadataElement[] elements = metadataRoot.getElements();
        for (MetadataElement element : elements) {
            metadataRoot.removeElement(element);
        }
    }

    public static class Spi extends OperatorSpi {

        public Spi() {
            super(Case2IOPOperator.class);
        }
    }

}
