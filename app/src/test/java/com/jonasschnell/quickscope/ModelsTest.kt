package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.data.AppJson
import com.jonasschnell.quickscope.data.LiveChange
import com.jonasschnell.quickscope.data.Me
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsTest {
    @Test fun decodesMeWithMissingFields() {
        val me = AppJson.decodeFromString(Me.serializer(), """{"id":"u1","email":"a@b.de","unbekannt":1}""")
        assertEquals("u1", me.id)
        assertEquals("a@b.de", me.email)
        assertNull(me.name)
        assertNull(me.pictureUrl)
    }

    @Test fun decodesLiveChange() {
        val change = AppJson.decodeFromString(LiveChange.serializer(), """{"kind":"me","by":"u2"}""")
        assertEquals("me", change.kind)
        assertEquals("u2", change.by)
    }
}
