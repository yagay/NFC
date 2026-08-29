package com.example.nfcdoorcard

data class CardModel(
    val name: String,
    val uid: String, // Hex string
    val sak: String, // Hex string
    val atqa: String // Hex string
)
