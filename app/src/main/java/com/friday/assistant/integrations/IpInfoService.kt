package com.friday.assistant.integrations

import android.content.Context
import com.friday.assistant.ai.SecureApiKeyStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class IpInfoService(context: Context) {
    private val keys=SecureApiKeyStore(context)
    fun configureToken(token:String)=keys.saveNamed("ipinfo_token",token)
    fun isConfigured()=!keys.readNamed("ipinfo_token").isNullOrBlank()
    fun currentLocation():String=runCatching{
        val token=keys.readNamed("ipinfo_token")
        val url=if(token.isNullOrBlank())"https://ipinfo.io/json" else "https://ipinfo.io/json?token="+token
        val c=(URL(url).openConnection() as HttpURLConnection).apply{requestMethod="GET";connectTimeout=8000;readTimeout=12000}
        try{
            val code=c.responseCode
            val body=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            if(code !in 200..299)error("HTTP "+code)
            val o=JSONObject(body)
            val place=listOf(o.optString("city"),o.optString("region"),o.optString("country")).filter{it.isNotBlank()}.joinToString(", ")
            "Approximate network location: "+place+". This is IP-based, not exact GPS."
        }finally{c.disconnect()}
    }.getOrElse{"Boss, IP location abhi available nahi hai. Main guess nahi karungi."}
}
