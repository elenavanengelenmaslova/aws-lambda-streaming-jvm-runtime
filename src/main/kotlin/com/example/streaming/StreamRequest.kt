package com.example.streaming

/**
 * The minimal domain request produced by the [RequestParser] from the API Gateway
 * proxy event: the requested file name (1–1024 characters).
 */
data class StreamRequest(val fileName: String)
