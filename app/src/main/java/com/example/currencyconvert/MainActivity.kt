package com.example.currencyconvert

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity : AppCompatActivity() {
    private lateinit var amountEditText: EditText
    private lateinit var fromCurrencySpinner: Spinner
    private lateinit var toCurrencySpinner: Spinner
    private lateinit var convertButton: Button
    private lateinit var ocrButton: Button
    private lateinit var resultTextView: TextView

    private val api: ExchangeRateApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.exchangerate-api.com/v4/latest/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ExchangeRateApi::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        amountEditText = findViewById(R.id.amountEditText)
        fromCurrencySpinner = findViewById(R.id.fromCurrencySpinner)
        toCurrencySpinner = findViewById(R.id.toCurrencySpinner)
        convertButton = findViewById(R.id.convertButton)
        ocrButton = findViewById(R.id.ocrButton)
        resultTextView = findViewById(R.id.resultTextView)

        val currencies = listOf("USD", "EUR", "GBP", "JPY", "THB")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, currencies)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fromCurrencySpinner.adapter = adapter
        toCurrencySpinner.adapter = adapter

        convertButton.setOnClickListener {
            convertCurrency()
        }

        ocrButton.setOnClickListener {
            val intent = Intent(this, OcrActivity::class.java)
            startActivity(intent)
        }
    }

    private fun convertCurrency() {
        val amountString = amountEditText.text.toString()
        if (amountString.isEmpty()) {
            resultTextView.text = "Please enter an amount"
            return
        }

        try {
            val amount = amountString.toDouble()
            val fromCurrency = fromCurrencySpinner.selectedItem?.toString()
            val toCurrency = toCurrencySpinner.selectedItem?.toString()

            if (fromCurrency == null || toCurrency == null) {
                resultTextView.text = "Please select both currencies"
                return
            }

            // ใช้ Coroutines เพื่อทำการเรียก API แบบ Asynchronous
            GlobalScope.launch(Dispatchers.Main) {
                try {
                    val response = withContext(Dispatchers.IO) {
                        api.getRates(fromCurrency).execute()
                    }

                    if (response.isSuccessful) {
                        val rates = response.body()?.rates
                        val rate = rates?.get(toCurrency)
                        if (rate != null) {
                            val result = amount * rate
                            resultTextView.text = result.toString()
                        } else {
                            resultTextView.text = "Currency not found"
                        }
                    } else {
                        resultTextView.text = "Error: ${response.message()}"
                    }
                } catch (e: Exception) {
                    resultTextView.text = "Failure: ${e.message}"
                }
            }
        } catch (e: NumberFormatException) {
            resultTextView.text = "Invalid amount"
        }
    }
}
