package com.jonasschnell.quickscope.data

import com.jonasschnell.quickscope.logic.OfflinePack
import com.jonasschnell.quickscope.logic.parseOfflinePack
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Lokaler Datenspeicher fuer den Offline-Betrieb: kleine JSON-Dateien in einem eigenen Ordner, atomar geschrieben
 * (temporaere Datei, dann Umbenennen), mit Versionsfeld. Nicht lesbare oder fremde Versionen zaehlen als "nichts da".
 * Beim Abmelden und Konto loeschen wird alles entfernt ([clear]); kein Backup (siehe Manifest).
 */
class LocalData(private val dir: File) {
    private val lock = Any()

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    private fun file(name: String) = File(dir, safe(name) + ".json")

    fun put(name: String, data: JsonElement) = synchronized(lock) {
        dir.mkdirs()
        val tmp = File(dir, safe(name) + ".tmp")
        tmp.writeText(JsonObject(mapOf("v" to JsonPrimitive(VERSION), "d" to data)).toString(), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file(name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file(name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    }

    fun get(name: String): JsonElement? = synchronized(lock) {
        val f = file(name)
        if (!f.exists()) return null
        val root = runCatching { AppJson.parseToJsonElement(f.readText(Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return null
        if ((root["v"] as? JsonPrimitive)?.intOrNull != VERSION) return null
        root["d"]
    }

    fun delete(name: String) = synchronized(lock) { file(name).delete(); Unit }

    fun clear() = synchronized(lock) { dir.deleteRecursively(); Unit }

    private fun <T> putTyped(name: String, s: KSerializer<T>, value: T) = put(name, AppJson.encodeToJsonElement(s, value))
    private fun <T> getTyped(name: String, s: KSerializer<T>): T? = get(name)?.let { runCatching { AppJson.decodeFromJsonElement(s, it) }.getOrNull() }

    fun saveMe(me: Me) = putTyped("me", Me.serializer(), me)
    fun me(): Me? = getTyped("me", Me.serializer())

    fun saveExams(list: List<Exam>) = putTyped("exams", ListSerializer(Exam.serializer()), list)
    fun exams(): List<Exam>? = getTyped("exams", ListSerializer(Exam.serializer()))?.takeIf { it.isNotEmpty() }

    fun saveForecast(examId: String, f: Forecast) = putTyped("forecast_$examId", Forecast.serializer(), f)
    fun forecast(examId: String): Forecast? = getTyped("forecast_$examId", Forecast.serializer())

    fun saveTopics(examId: String, list: List<Topic>) = putTyped("topics_$examId", ListSerializer(Topic.serializer()), list)
    fun topics(examId: String): List<Topic> = getTyped("topics_$examId", ListSerializer(Topic.serializer())).orEmpty()

    /** Das Paket wird als Rohtext des Servers abgelegt und beim Lesen neu geparst (Parser bleibt austauschbar). */
    fun savePack(examId: String, rawJson: String, days: Int, etag: String?) {
        put("pack_$examId", AppJson.parseToJsonElement(rawJson))
        put("packmeta_$examId", JsonObject(mapOf("days" to JsonPrimitive(days), "etag" to JsonPrimitive(etag.orEmpty()), "at" to JsonPrimitive(System.currentTimeMillis()))))
    }

    /** ETag des gespeicherten Pakets fuer If-None-Match (nur, wenn das Paket selbst noch da ist). */
    fun packEtag(examId: String): String? =
        if (get("pack_$examId") == null) null else ((get("packmeta_$examId") as? JsonObject)?.get("etag") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    fun pack(examId: String): OfflinePack? = get("pack_$examId")?.let { parseOfflinePack(it.toString()) }

    /** Tage des bereitliegenden Pakets (kleine Datei, ohne das Paket zu parsen) oder null. */
    fun packDays(examId: String): Int? = (get("packmeta_$examId") as? JsonObject)?.get("days")?.let { (it as? JsonPrimitive)?.intOrNull }

    fun doneKeys(examId: String): Set<String> = getTyped("done_$examId", SetSerializer(String.serializer())).orEmpty()

    fun markDone(examId: String, key: String) = synchronized(lock) {
        putTyped("done_$examId", SetSerializer(String.serializer()), doneKeys(examId) + key)
    }

    fun clearDone(examId: String) = delete("done_$examId")

    companion object {
        const val VERSION = 1
    }
}
