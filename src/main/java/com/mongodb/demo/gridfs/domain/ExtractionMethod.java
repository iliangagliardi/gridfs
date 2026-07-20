package com.mongodb.demo.gridfs.domain;

/** How the text in {@code metadata.extractedText} was obtained. */
public enum ExtractionMethod {
    /** Apache Tika parsed a real text layer out of the document. */
    TIKA,
    /** Tesseract OCR recognised text from pixels (scanned pages, photos). */
    OCR,
    /** Tika found a text layer and OCR added more on top of it. */
    TIKA_AND_OCR,
    /** No text was obtained. */
    NONE
}
