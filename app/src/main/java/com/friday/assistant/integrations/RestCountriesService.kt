package com.friday.assistant.integrations

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class RestCountriesService {
    fun countryInfo(query: String): String = runCatching {
        val q = query.trim()
        val url = "https://restcountries.com/v3.1/name/" + URLEncoder.encode(q, "UTF-8") + "?fullText=false"
        val c = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod="GET"; connectTimeout=8000; readTimeout=12000 }
        try {
            val code=c.responseCode
            val body=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            if(code !in 200..299) error("HTTP "+code)
            val item=JSONArray(body).optJSONObject(0) ?: error("not found")
            val name=item.optJSONObject("name")?.optString("common").orEmpty()
            val official=item.optJSONObject("name")?.optString("official").orEmpty()
            val capital=item.optJSONArray("capital")?.optString(0).orEmpty()
            val region=item.optString("region")
            val pop=item.optLong("population")
            val currencyObj=item.optJSONObject("currencies")
            val currencyCode=currencyObj?.keys()?.asSequence()?.firstOrNull()
            val currency=if(currencyCode!=null) currencyObj.optJSONObject(currencyCode)?.optString("name")+" ("+currencyCode+")" else "N/A"
            val langs=item.optJSONObject("languages")
            val languages=if(langs!=null) langs.keys().asSequence().map{langs.optString(it)}.joinToString(", ") else "N/A"
            "Country: "+name+". Official name: "+official+". Capital: "+capital.ifBlank{"N/A"}+". Region: "+region+". Population: "+pop+". Currency: "+currency+". Languages: "+languages+"."
        } finally { c.disconnect() }
    }.getOrElse { "Boss, country information nahi mil paayi. Main guess nahi karungi." }
}
