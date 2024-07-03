package com.example.currencyconvert

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path

interface ExchangeRateApi {
    @GET("{currency}")
    fun getRates(@Path("currency") currency: String): Call<CurrencyResponse>
}

