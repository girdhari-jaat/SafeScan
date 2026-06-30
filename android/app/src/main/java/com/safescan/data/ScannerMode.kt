package com.safescan.data

/**
 * Scanner modes used throughout the app.
 *
 * PAPER:
 *  - A4, Letter, receipts, invoices, books.
 *  - Optimized for large document detection.
 *
 * CARD:
 *  - CNIC
 *  - Passport
 *  - Driving License
 *  - Business Card
 *  - Optimized for small rectangular documents.
 */
enum class ScannerMode(
    val title: String,
    val minAspectRatio: Float,
    val maxAspectRatio: Float,
    val minAreaPercent: Float
) {

    PAPER(
        title = "Paper",
        minAspectRatio = 0.60f,
        maxAspectRatio = 1.60f,
        minAreaPercent = 0.18f
    ),

    CARD(
        title = "Card",
        minAspectRatio = 1.40f,
        maxAspectRatio = 1.75f,
        minAreaPercent = 0.05f
    );

    fun isPaper(): Boolean = this == PAPER

    fun isCard(): Boolean = this == CARD

    fun toggle(): ScannerMode =
        if (this == PAPER) CARD else PAPER
}