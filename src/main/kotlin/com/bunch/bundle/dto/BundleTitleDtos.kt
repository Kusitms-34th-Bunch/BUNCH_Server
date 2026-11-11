package com.bunch.bundle.dto

data class BundleTitleCrdtRequest(val state: ByteArray)

data class BundleTitleState(
    val title: String,
    val titleCrdtState: ByteArray,
    val titleVersion: Long,
    val titleTooLong: Boolean
)

data class TitleMergeResult(val state: ByteArray, val value: String, val tooLong: Boolean)

data class TitleDocument(val value: String, val tooLong: Boolean)
