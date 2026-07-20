package com.mongodb.demo.gridfs.domain;

/** Coarse bucket used by the UI to decide how to render / play a file. */
public enum FileCategory {
    DOCUMENT,   // pdf, docx, xlsx, pptx, txt, md, html, csv, epub
    MEDIA,      // audio + video -> streamed with HTTP Range
    IMAGE,      // png, jpg, gif, webp, svg
    OTHER;

    public static FileCategory fromContentType(String contentType) {
        if (contentType == null) return OTHER;
        String ct = contentType.toLowerCase();
        if (ct.startsWith("video/") || ct.startsWith("audio/")) return MEDIA;
        if (ct.startsWith("image/")) return IMAGE;
        if (ct.startsWith("text/")
                || ct.contains("pdf")
                || ct.contains("officedocument")
                || ct.contains("msword")
                || ct.contains("ms-excel")
                || ct.contains("ms-powerpoint")
                || ct.contains("opendocument")
                || ct.contains("epub")
                || ct.contains("rtf")) return DOCUMENT;
        return OTHER;
    }
}
