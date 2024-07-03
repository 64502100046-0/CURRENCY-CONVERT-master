package com.example.currencyconvert

data class CurrencyResponse(
    val base: String,
    val date: String,
    val rates: Map<String, Double>
)
