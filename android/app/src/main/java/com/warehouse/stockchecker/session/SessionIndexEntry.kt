package com.warehouse.stockchecker.session

/**
 * Snapshot of one saved session.
 *
 * @param id stable identifier (also the JSON filename stem).
 * @param displayName user-visible name shown in the Sessions list.
 * @param createdAtMillis wall-clock millis when the session was saved.
 * @param anchorCount how many anchors were captured in this session.
 * @param inferenceTimeMs inference time of the burst that produced the anchors.
 * @param relativePath where the JSON was written, relative to the public Downloads folder
 *   (e.g. "StockCheck/Session_2026-08-31_11-08-07.json"). The repository normalises this for the
 *   FileProvider on older devices.
 */
data class SessionIndexEntry(
    val id: String,
    val displayName: String,
    val createdAtMillis: Long,
    val anchorCount: Int,
    val inferenceTimeMs: Long,
    val relativePath: String
)
