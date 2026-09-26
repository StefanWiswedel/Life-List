package dk.lifelist.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The URL the one network call in this app builds.
 *
 * Small, and worth a test for exactly that reason: a wrong extension here is a 404 on 18,957
 * species and the only symptom is that the species page never gets sharper, which is a thing
 * nobody reports because nothing looks broken.
 */
class LargePhotosTest {

    @Test
    fun `it asks the bucket the bundled photographs came from`() {
        val url = LargePhotos.urlFor(787560, "jpg")
        assertEquals(
            "https://inaturalist-open-data.s3.amazonaws.com/photos/787560/large.jpg",
            url,
        )
    }

    @Test
    fun `large, because that is the size worth the megabyte`() {
        // 1024 px and about 110 KB. `medium` is 500 px, which is what the model's own species
        // already ship and would be no upgrade at all; `original` is megabytes (§83).
        assertTrue(LargePhotos.urlFor(1, "jpg").endsWith("/large.jpg"))
    }

    @Test
    fun `a leading dot on the extension is not a second dot in the URL`() {
        // iNaturalist's manifest writes both `jpg` and `.jpg` depending on the row.
        assertEquals(LargePhotos.urlFor(9, "jpg"), LargePhotos.urlFor(9, ".jpg"))
    }

    @Test
    fun `png stays png`() {
        // The S3 keys are exact: asking for a jpg that is a png is a 404, not a wrong picture.
        assertTrue(LargePhotos.urlFor(9, "png").endsWith("large.png"))
    }

    @Test
    fun `a missing extension falls back to what the bucket mostly holds`() {
        assertTrue(LargePhotos.urlFor(9, "").endsWith("large.jpeg"))
        assertTrue(LargePhotos.urlFor(9, "   ").endsWith("large.jpeg"))
    }
}
