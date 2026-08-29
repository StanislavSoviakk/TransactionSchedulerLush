package org.example.domain

import java.time.LocalDateTime

data class Transaction(
    val id: String,
    val market: Market,
    val shopId: String,
    val shopSubdivision: String?,
    val localTimestamp: LocalDateTime
)