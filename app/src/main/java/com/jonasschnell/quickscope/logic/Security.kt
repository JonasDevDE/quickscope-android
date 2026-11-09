package com.jonasschnell.quickscope.logic

/** IV und Chiffretext fuer die Ablage in einem Wert zusammenfuegen (IV vorne, Laenge im ersten Byte). */
fun packSecret(iv: ByteArray, cipherText: ByteArray): ByteArray = byteArrayOf(iv.size.toByte()) + iv + cipherText

/** Gegenstueck zu [packSecret]; null bei ungueltigen Daten. */
fun unpackSecret(data: ByteArray): Pair<ByteArray, ByteArray>? {
    if (data.isEmpty()) return null
    val n = data[0].toInt() and 0xFF
    if (n <= 0 || data.size <= 1 + n) return null
    return data.copyOfRange(1, 1 + n) to data.copyOfRange(1 + n, data.size)
}

/** Alter Klartextwert vorhanden: muss verschluesselt abgelegt und der Klartext geloescht werden. */
fun needsSecretMigration(plain: String?): Boolean = plain != null
