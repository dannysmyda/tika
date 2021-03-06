/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.parser.microsoft.ooxml;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.io.input.CloseShieldInputStream;
import org.apache.poi.ooxml.POIXMLDocument;
import org.apache.poi.ooxml.extractor.ExtractorFactory;
import org.apache.poi.ooxml.extractor.POIXMLTextExtractor;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.InvalidOperationException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationshipCollection;
import org.apache.poi.util.LocaleUtil;
import org.apache.poi.xslf.extractor.XSLFPowerPointExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFRelation;
import org.apache.poi.xslf.usermodel.XSLFSlideShow;
import org.apache.poi.xssf.extractor.XSSFBEventBasedExcelExtractor;
import org.apache.poi.xssf.extractor.XSSFEventBasedExcelExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRelation;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.IOUtils;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.EmptyParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.chm.core.ChmExtractor;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.parser.microsoft.ooxml.xps.XPSExtractorDecorator;
import org.apache.tika.parser.microsoft.ooxml.xps.XPSTextExtractor;
import org.apache.tika.parser.microsoft.ooxml.xslf.XSLFEventBasedPowerPointExtractor;
import org.apache.tika.parser.microsoft.ooxml.xwpf.XWPFEventBasedWordExtractor;
import org.apache.tika.parser.pkg.ZipContainerDetector;
import org.apache.xmlbeans.XmlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * Figures out the correct {@link OOXMLExtractor} for the supplied document and
 * returns it.
 */
public class OOXMLExtractorFactory {

    private static final Logger LOG = LoggerFactory.getLogger(OOXMLExtractorFactory.class);

    public static void parse(
            InputStream stream, ContentHandler baseHandler,
            Metadata metadata, ParseContext context)
            throws IOException, SAXException, TikaException {
        Locale locale = context.get(Locale.class, LocaleUtil.getUserLocale());
        ExtractorFactory.setThreadPrefersEventExtractors(true);

        //if there's a problem opening the zip file;
        //create a tmp file, and copy what you can read of it.
        File tmpRepairedCopy = null;

        OPCPackage pkg = null;
        try {
            OOXMLExtractor extractor = null;

            // Locate or Open the OPCPackage for the file
            TikaInputStream tis = TikaInputStream.cast(stream);
            if (tis != null && tis.getOpenContainer() instanceof OPCPackage) {
                pkg = (OPCPackage) tis.getOpenContainer();
            } else if (tis != null && tis.hasFile()) {
                try {
                    pkg = OPCPackage.open(tis.getFile().getPath(), PackageAccess.READ);
                } catch (InvalidOperationException e) {
                    tmpRepairedCopy = File.createTempFile("tika-ooxml-repair", "");
                    repairCopy(tis.getFile(), tmpRepairedCopy);
                    pkg = OPCPackage.open(tmpRepairedCopy, PackageAccess.READ);
                }
                tis.setOpenContainer(pkg);
            } else {
                InputStream shield = new CloseShieldInputStream(stream);
                pkg = OPCPackage.open(shield);
            }

            // Get the type, and ensure it's one we handle
            MediaType type = ZipContainerDetector.detectOfficeOpenXML(pkg);
            if (type == null) {
                type = ZipContainerDetector.detectXPSOPC(pkg);
            }

            if (type == null || OOXMLParser.UNSUPPORTED_OOXML_TYPES.contains(type)) {
                // Not a supported type, delegate to Empty Parser
                EmptyParser.INSTANCE.parse(stream, baseHandler, metadata, context);
                return;
            }
            metadata.set(Metadata.CONTENT_TYPE, type.toString());
            // Have the appropriate OOXML text extractor picked
            POIXMLTextExtractor poiExtractor = null;
            // This has already been set by OOXMLParser's call to configure()
            // We can rely on this being non-null.
            OfficeParserConfig config = context.get(OfficeParserConfig.class);
            if (config.getUseSAXDocxExtractor()) {
                poiExtractor = trySXWPF(pkg);
            }
            if (poiExtractor == null) {
                poiExtractor = tryXSLF(pkg, config.getUseSAXPptxExtractor());
            }
            if (type.equals(OOXMLParser.XPS)) {
                poiExtractor = new XPSTextExtractor(pkg);
            }

            if (poiExtractor == null) {
                poiExtractor = (POIXMLTextExtractor) ExtractorFactory.createExtractor(pkg);
            }

            POIXMLDocument document = poiExtractor.getDocument();
            if (poiExtractor instanceof XSSFBEventBasedExcelExtractor) {
                extractor = new XSSFBExcelExtractorDecorator(context, poiExtractor, locale);
            } else if (poiExtractor instanceof XSSFEventBasedExcelExtractor) {
                extractor = new XSSFExcelExtractorDecorator(
                        context, poiExtractor, locale);
            } else if (poiExtractor instanceof XWPFEventBasedWordExtractor) {
                extractor = new SXWPFWordExtractorDecorator(metadata, context,
                        (XWPFEventBasedWordExtractor) poiExtractor);
                metadata.add("X-Parsed-By", XWPFEventBasedWordExtractor.class.getCanonicalName());
            } else if (poiExtractor instanceof XSLFEventBasedPowerPointExtractor) {
                extractor = new SXSLFPowerPointExtractorDecorator(metadata, context,
                        (XSLFEventBasedPowerPointExtractor) poiExtractor);
                metadata.add("X-Parsed-By", XSLFEventBasedPowerPointExtractor.class.getCanonicalName());
            } else if (poiExtractor instanceof XPSTextExtractor) {
                extractor = new XPSExtractorDecorator(context, poiExtractor);
            } else if (document == null) {
                throw new TikaException(
                        "Expecting UserModel based POI OOXML extractor with a document, but none found. " +
                                "The extractor returned was a " + poiExtractor
                );
            } else if (document instanceof XMLSlideShow) {
                extractor = new XSLFPowerPointExtractorDecorator(
                        context, (org.apache.poi.xslf.extractor.XSLFPowerPointExtractor) poiExtractor);
            } else if (document instanceof XWPFDocument) {
                extractor = new XWPFWordExtractorDecorator( metadata,
                        context, (XWPFWordExtractor) poiExtractor);
            } else {
                extractor = new POIXMLTextExtractorDecorator(context, poiExtractor);
            }


            // Get the bulk of the metadata first, so that it's accessible during
            //  parsing if desired by the client (see TIKA-1109)
            extractor.getMetadataExtractor().extract(metadata);

            // Extract the text, along with any in-document metadata
            extractor.getXHTML(baseHandler, metadata, context);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null &&
                    e.getMessage().startsWith("No supported documents found")) {
                throw new TikaException(
                        "TIKA-418: RuntimeException while getting content"
                                + " for thmx and xps file types", e);
            } else {
                throw new TikaException("Error creating OOXML extractor", e);
            }
        } catch (InvalidFormatException e) {
            throw new TikaException("Error creating OOXML extractor", e);
        } catch (OpenXML4JException e) {
            throw new TikaException("Error creating OOXML extractor", e);
        } catch (XmlException e) {
            throw new TikaException("Error creating OOXML extractor", e);
        } finally {
            if (tmpRepairedCopy != null) {
                if (pkg != null) {
                    try {
                        pkg.close();
                    } catch (IOException e) {
                        LOG.warn("problem closing pkg file");
                    }
                }
                boolean deleted = tmpRepairedCopy.delete();
                if (! deleted) {
                    LOG.warn("failed to delete tmp (repair) file: "+tmpRepairedCopy.getAbsolutePath());
                }
            }
        }
    }

    private static void repairCopy(File brokenZip, File fixedZip) {
        try (ZipArchiveOutputStream outputStream = new ZipArchiveOutputStream(fixedZip)) {
            try (InputStream is = new FileInputStream(brokenZip)) {
                ZipArchiveInputStream zipArchiveInputStream = new ZipArchiveInputStream(is);
                ZipArchiveEntry zae = zipArchiveInputStream.getNextZipEntry();
                while (zae != null) {
                    try {
                        if (!zae.isDirectory() && zipArchiveInputStream.canReadEntryData(zae)) {
                            outputStream.putArchiveEntry(zae);
                            //this will copy an incomplete stream...so there
                            //could be truncation of the xml, but the zip file
                            //should be intact.
                            boolean successfullyCopied = false;
                            try {
                                IOUtils.copy(zipArchiveInputStream, outputStream);
                                successfullyCopied = true;
                            } catch (IOException e) {
                                //this can hit a "truncated ZipFile" IOException
                            }
                            outputStream.flush();
                            outputStream.closeArchiveEntry();
                            if (!successfullyCopied) {
                                break;
                            }
                        }
                        zae = zipArchiveInputStream.getNextZipEntry();
                    } catch (EOFException e) {
                        break;
                    }

                }
                outputStream.flush();
                outputStream.finish();
                outputStream.close();
            }
        } catch (IOException e) {
            LOG.warn("problem fixing zip", e);
        }
    }

    private static POIXMLTextExtractor trySXWPF(OPCPackage pkg) throws XmlException, OpenXML4JException, IOException {
        PackageRelationshipCollection packageRelationshipCollection = pkg.getRelationshipsByType("http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument");
        if (packageRelationshipCollection.size() == 0) {
            packageRelationshipCollection = pkg.getRelationshipsByType("http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument");
        }

        if (packageRelationshipCollection.size() == 0) {
            return null;
        }
        PackagePart corePart = pkg.getPart(packageRelationshipCollection.getRelationship(0));
        String targetContentType = corePart.getContentType();
        for (XWPFRelation relation : XWPFWordExtractor.SUPPORTED_TYPES) {
            if (targetContentType.equals(relation.getContentType())) {
                return new XWPFEventBasedWordExtractor(pkg);
            }
        }
        return null;
    }

    private static POIXMLTextExtractor tryXSLF(OPCPackage pkg, boolean eventBased) throws XmlException, OpenXML4JException, IOException {

        PackageRelationshipCollection packageRelationshipCollection = pkg.getRelationshipsByType("http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument");
        if (packageRelationshipCollection.size() == 0) {
            packageRelationshipCollection = pkg.getRelationshipsByType("http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument");
        }

        if (packageRelationshipCollection.size() == 0) {
            return null;
        }
        PackagePart corePart = pkg.getPart(packageRelationshipCollection.getRelationship(0));
        String targetContentType = corePart.getContentType();

        XSLFRelation[] xslfRelations = org.apache.poi.xslf.extractor.XSLFPowerPointExtractor.SUPPORTED_TYPES;

        for (int i = 0; i < xslfRelations.length; i++) {
            XSLFRelation xslfRelation = xslfRelations[i];
            if (xslfRelation.getContentType().equals(targetContentType)) {
                if (eventBased) {
                    return new XSLFEventBasedPowerPointExtractor(pkg);
                } else {
                    return new XSLFPowerPointExtractor(new XSLFSlideShow(pkg));
                }
            }
        }

        if (XSLFRelation.THEME_MANAGER.getContentType().equals(targetContentType)) {
            if (eventBased) {
                return new XSLFEventBasedPowerPointExtractor(pkg);
            } else {
                return new XSLFPowerPointExtractor(new XSLFSlideShow(pkg));
            }
        }
        return null;
    }


}
