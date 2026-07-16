package com.aura.evolution

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object EvolutionTypeConverters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    @JvmStatic
    fun fromStringList(value: List<kotlin.String>): kotlin.String =
        json.encodeToString(ListSerializer(String.serializer()), value)

    @TypeConverter
    @JvmStatic
    fun toStringList(value: kotlin.String): List<kotlin.String> =
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), value) }.getOrDefault(emptyList())
}
