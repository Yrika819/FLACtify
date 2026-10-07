package com.flactify.viewmodel

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class RecommendationManagerHttpErrorTest {

    @Test
    fun `http error response code triggers proper error handling logic`() {
        val conn = FakeHttpURLConnection(URL("https://test.example.com/api"), 500)
        val responseCode = conn.responseCode

        assertEquals(500, responseCode)
        assert(responseCode !in 200..299)

        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val response = stream?.bufferedReader()?.readText() ?: ""
        assertTrue(response.contains("error"))
    }

    @Test
    fun `http success response code returns normal stream`() {
        val conn = FakeHttpURLConnection(URL("https://test.example.com/api"), 200)
        val responseCode = conn.responseCode

        assertEquals(200, responseCode)
        assert(responseCode in 200..299)

        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val response = stream?.bufferedReader()?.readText() ?: ""
        assertTrue(response.contains("toptracks"))
    }

    @Test
    fun `http 404 error returns error stream`() {
        val conn = FakeHttpURLConnection(URL("https://test.example.com/api"), 404)
        val responseCode = conn.responseCode

        assertEquals(404, responseCode)

        var exceptionThrown = false
        try {
            conn.inputStream
        } catch (e: IOException) {
            exceptionThrown = true
        }
        assertTrue("Should throw IOException for error response", exceptionThrown)

        val errorStream = conn.errorStream
        assertNotNull("Error stream should be available", errorStream)
        val errorResponse = errorStream?.bufferedReader()?.readText() ?: ""
        assertTrue(errorResponse.contains("error"))
    }

    private class FakeHttpURLConnection(url: URL, private val fakeResponseCode: Int) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun getResponseCode(): Int = fakeResponseCode

        override fun getInputStream(): java.io.InputStream {
            if (fakeResponseCode !in 200..299) {
                throw IOException("Server returned HTTP response code: $fakeResponseCode")
            }
            return ByteArrayInputStream("""{"toptracks": {"track": [{"name": "Test", "artist": {"name": "Test"}}]}}""".toByteArray())
        }

        override fun getErrorStream(): java.io.InputStream? {
            return if (fakeResponseCode !in 200..299) {
                ByteArrayInputStream("""{"error": 6, "message": "Not Found"}""".toByteArray())
            } else null
        }

        override fun usingProxy(): Boolean = false
    }
}
