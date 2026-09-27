package com.friday.assistant.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class OpenRouterProvider(context: Context) {
    private val keys=SecureApiKeyStore(context)
    private val model="openai/gpt-4o-mini"
    fun configureApiKey(key:String)=keys.saveNamed("openrouter_key",key)
    fun clearApiKey(){keys.saveNamed("openrouter_key","")}
    fun isConfigured()=!keys.readNamed("openrouter_key").isNullOrBlank()
    fun ask(prompt:String,history:List<Pair<String,String>>=emptyList()):Result<String>=runCatching{
        val key=keys.readNamed("openrouter_key")?:error("OpenRouter key missing")
        val messages=JSONArray().put(JSONObject().put("role","system").put("content","You are FRIDAY, a personal Android AI assistant. Understand Hindi, Hinglish and English. Be concise for voice. Never claim a phone action succeeded unless verified."))
        history.takeLast(12).forEach{(role,text)->messages.put(JSONObject().put("role",if(role=="assistant")"assistant" else "user").put("content",text))}
        messages.put(JSONObject().put("role","user").put("content",prompt))
        val body=JSONObject().put("model",model).put("messages",messages).put("temperature",0.35).put("max_tokens",1200)
        val c=(URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection).apply{requestMethod="POST";connectTimeout=12000;readTimeout=30000;doOutput=true;setRequestProperty("Content-Type","application/json");setRequestProperty("Authorization","Bearer "+key);setRequestProperty("X-Title","FRIDAY Android Assistant")}
        try{
            c.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}
            val code=c.responseCode
            val response=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            if(code !in 200..299)error("OpenRouter HTTP "+code)
            JSONObject(response).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()?.takeIf{it.isNotBlank()}?:error("No response")
        }finally{c.disconnect()}
    }
}
