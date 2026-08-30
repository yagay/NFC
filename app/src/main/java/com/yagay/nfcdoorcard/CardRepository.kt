package com.yagay.nfcdoorcard

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Small persistence boundary for saved card metadata. */
class CardRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("cards", 0)
    private val gson = Gson()
    private val listType = object : TypeToken<List<CardModel>>() {}.type

    fun load(): List<CardModel> {
        var json = prefs.getString("list", null)
        if (json == null) {
            val legacy = appContext.getSharedPreferences("saved_cards", 0)
                .getString("cards_list", null)
            if (!legacy.isNullOrBlank()) {
                json = legacy
                prefs.edit().putString("list", legacy).apply()
            }
        }
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<CardModel>>(json, listType) ?: emptyList() }
            .getOrDefault(emptyList())
    }

    fun save(cards: List<CardModel>) {
        prefs.edit().putString("list", gson.toJson(cards)).apply()
    }
}
