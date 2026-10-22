/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.artifacts

import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Part
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/** Unit tests for [FileArtifactService]. */
class FileArtifactServiceTest {

  private lateinit var baseDir: File
  private lateinit var service: FileArtifactService

  @BeforeTest
  fun setUp() {
    baseDir = Files.createTempDirectory("file-artifacts").toFile()
    service = FileArtifactService(baseDir.path)
  }

  @AfterTest
  fun tearDown() {
    baseDir.deleteRecursively()
  }

  @Test
  fun saveArtifact_firstVersion_returnsZero() = runTest {
    val version = service.saveArtifact(SESSION_KEY, FILENAME, part("hello"))
    assertEquals(0, version)
  }

  @Test
  fun saveArtifact_successiveVersions_increment() = runTest {
    val v0 = service.saveArtifact(SESSION_KEY, FILENAME, part("a"))
    val v1 = service.saveArtifact(SESSION_KEY, FILENAME, part("b"))
    assertEquals(0, v0)
    assertEquals(1, v1)
  }

  @Test
  fun loadArtifact_noVersion_loadsLatest() = runTest {
    val unused0 = service.saveArtifact(SESSION_KEY, FILENAME, part("content 1"))
    val unused1 = service.saveArtifact(SESSION_KEY, FILENAME, part("content 2"))

    val loaded = service.loadArtifact(SESSION_KEY, FILENAME)

    assertEquals(part("content 2"), loaded)
  }

  @Test
  fun loadArtifact_explicitVersion_loadsThatVersion() = runTest {
    val unused0 = service.saveArtifact(SESSION_KEY, FILENAME, part("content 1"))
    val unused1 = service.saveArtifact(SESSION_KEY, FILENAME, part("content 2"))

    val loaded = service.loadArtifact(SESSION_KEY, FILENAME, version = 0)

    assertEquals(part("content 1"), loaded)
  }

  @Test
  fun loadArtifact_preservesMimeType() = runTest {
    val unused =
      service.saveArtifact(
        SESSION_KEY,
        FILENAME,
        Part(inlineData = Blob(data = "x".toByteArray(), mimeType = "image/png")),
      )

    val loaded = service.loadArtifact(SESSION_KEY, FILENAME)

    assertEquals("image/png", loaded?.inlineData?.mimeType)
  }

  @Test
  fun loadArtifact_nullMimeType_roundTripsToNull() = runTest {
    val unused =
      service.saveArtifact(
        SESSION_KEY,
        FILENAME,
        Part(inlineData = Blob(data = "x".toByteArray(), mimeType = null)),
      )

    val loaded = service.loadArtifact(SESSION_KEY, FILENAME)

    assertNull(loaded?.inlineData?.mimeType)
    assertContentEquals("x".toByteArray(), loaded?.inlineData?.data)
  }

  @Test
  fun loadArtifact_preservesDisplayName() = runTest {
    val unused =
      service.saveArtifact(
        SESSION_KEY,
        FILENAME,
        Part(
          inlineData =
            Blob(data = "x".toByteArray(), mimeType = "text/plain", displayName = "My Report"),
        ),
      )

    val loaded = service.loadArtifact(SESSION_KEY, FILENAME)

    assertEquals("My Report", loaded?.inlineData?.displayName)
  }

  @Test
  fun loadArtifact_unknownArtifact_returnsNull() = runTest {
    assertNull(service.loadArtifact(SESSION_KEY, "missing.txt"))
  }

  @Test
  fun loadArtifact_unknownVersion_returnsNull() = runTest {
    val unused = service.saveArtifact(SESSION_KEY, FILENAME, part("a"))
    assertNull(service.loadArtifact(SESSION_KEY, FILENAME, version = 99))
  }

  @Test
  fun loadArtifact_payloadFileMissing_returnsNull() = runTest {
    val unused = service.saveArtifact(SESSION_KEY, FILENAME, part("a"))
    val payload =
      File(baseDir, "users/$USER_ID/sessions/$SESSION_ID/artifacts/$FILENAME/versions/0/$FILENAME")
    assertTrue(payload.delete())

    assertNull(service.loadArtifact(SESSION_KEY, FILENAME))
  }

  @Test
  fun saveArtifact_withoutInlineData_throws() = runTest {
    assertFailsWith<IllegalArgumentException> {
      service.saveArtifact(SESSION_KEY, FILENAME, Part(text = "not an artifact"))
    }
  }

  @Test
  fun saveArtifact_emptyData_throws() = runTest {
    assertFailsWith<IllegalArgumentException> {
      service.saveArtifact(SESSION_KEY, FILENAME, Part(inlineData = Blob(data = ByteArray(0))))
    }
  }

  @Test
  fun saveArtifact_reservedMetadataBasenames_areRejectedBeforeWriting() = runBlocking {
    val filenames =
      listOf(
        "metadata.json",
        "Metadata.Json",
        "METADATA.JSON",
        "nested/metadata.json",
        "nested\\metadata.json",
        "nested/../metadata.json",
        "metadata.json/child/..",
        "user:metadata.json",
        "user:nested/Metadata.Json",
        "metadata.json.",
        "metadata.json...",
        "metadata.json . . ",
      )

    for (key in listOf(SESSION_KEY, SESSION_KEY.copy(id = null))) {
      for (filename in filenames) {
        val context = "filename=$filename, session=${key.id}"
        val failure =
          assertFailsWith<IllegalArgumentException>(context) {
            service.saveArtifact(key, filename, part("user payload"))
          }
        assertContains(failure.message.orEmpty(), "reserved", message = context)
        assertContentEquals(emptyList(), baseDir.listFiles().orEmpty().toList(), context)
      }
    }
  }

  @Test
  fun saveArtifact_reservedVersionsPathComponents_areRejectedBeforeWriting() = runBlocking {
    val filenames =
      listOf(
        "report/versions/0/x.txt",
        "versions",
        "VERSIONS",
        "versions/x.txt",
        "report/Versions/x.txt",
        "report/versions./x.txt",
        "report/versions . ./x.txt",
        "versions . . ",
        "report\\versions\\0\\x.txt",
        "user:versions/x.txt",
        "user:report/Versions/x.txt",
        "nested/../versions/x.txt",
        "report/child/../versions/x.txt",
      )

    for (key in listOf(SESSION_KEY, SESSION_KEY.copy(id = null))) {
      for (filename in filenames) {
        val context = "filename=$filename, session=${key.id}"
        val failure =
          assertFailsWith<IllegalArgumentException>(context) {
            service.saveArtifact(key, filename, part("user payload"))
          }
        assertContains(failure.message.orEmpty(), "reserved", message = context)
        assertContentEquals(emptyList(), baseDir.listFiles().orEmpty().toList(), context)
      }
    }
  }

  @Test
  fun saveArtifact_reservedNames_omitCallerFilenameFromErrors() = runBlocking {
    for (filename in listOf("private-name/metadata.json", "private-name/versions/x.txt")) {
      val failure =
        assertFailsWith<IllegalArgumentException>("filename=$filename") {
          service.saveArtifact(SESSION_KEY, filename, part("user payload"))
        }
      assertContains(failure.message.orEmpty(), "reserved", message = "filename=$filename")
      assertFalse(failure.message.orEmpty().contains("private-name"), "filename=$filename")
    }
  }

  @Test
  fun saveAndReloadArtifact_reservedNames_areRejectedBeforeWriting() = runBlocking {
    for (filename in listOf("metadata.json", "report/versions/0/x.txt")) {
      assertFailsWith<IllegalArgumentException>("filename=$filename") {
        service.saveAndReloadArtifact(SESSION_KEY, filename, part("user payload"))
      }
      assertContentEquals(
        emptyList(),
        baseDir.listFiles().orEmpty().toList(),
        "filename=$filename",
      )
    }
  }

  @Test
  fun saveArtifact_similarNamesAndMetadataParentDirectory_roundTrip() = runBlocking {
    for (filename in
      listOf(
        "report.json",
        "metadata.json.bak",
        "metadata.json/report.txt",
        "versions.txt",
        "myversions/report.txt",
        "versions/../normalized.txt",
      )) {
      assertEquals(0, service.saveArtifact(SESSION_KEY, filename, part("user payload")), filename)
      assertEquals(part("user payload"), service.loadArtifact(SESSION_KEY, filename), filename)
      assertContains(service.listArtifactKeys(SESSION_KEY), filename, filename)
    }
  }

  @Test
  fun saveArtifact_versionsInStorageRootAndScopeIdentifiers_roundTrip() = runBlocking {
    val scopedService = FileArtifactService(File(baseDir, "versions").path)
    val key = SESSION_KEY.copy(userId = "versions", id = "versions")

    assertEquals(0, scopedService.saveArtifact(key, FILENAME, part("user payload")))
    assertEquals(part("user payload"), scopedService.loadArtifact(key, FILENAME))
    assertEquals(listOf(FILENAME), scopedService.listArtifactKeys(key))
  }

  @Test
  fun existingMetadataNamedArtifact_remainsReadableAndDeletable() = runBlocking {
    val artifactDir = File(baseDir, "users/$USER_ID/sessions/$SESSION_ID/artifacts/metadata.json")
    val versionDir = File(artifactDir, "versions/0")
    assertTrue(versionDir.mkdirs())
    val metadata =
      """
      {"version":0,"fileName":"metadata.json","canonicalUri":"file:///legacy",
       "createTime":0.0,"mimeType":"application/json"}
      """
        .trimIndent()
        .toByteArray()
    File(versionDir, "metadata.json").writeBytes(metadata)

    assertFailsWith<IllegalArgumentException> {
      service.saveArtifact(SESSION_KEY, "metadata.json", part("replacement"))
    }
    assertContentEquals(
      metadata,
      service.loadArtifact(SESSION_KEY, "metadata.json")?.inlineData?.data,
    )
    assertEquals(listOf(0), service.listVersions(SESSION_KEY, "metadata.json"))
    assertEquals(listOf("metadata.json"), service.listArtifactKeys(SESSION_KEY))

    service.deleteArtifact(SESSION_KEY, "metadata.json")

    assertFalse(artifactDir.exists())
    assertNull(service.loadArtifact(SESSION_KEY, "metadata.json"))
  }

  @Test
  fun existingVersionsPathArtifact_remainsReadableAndDeletable() = runBlocking {
    val filename = "report/versions/0/x.txt"
    val artifactDir = File(baseDir, "users/$USER_ID/sessions/$SESSION_ID/artifacts/$filename")
    val versionDir = File(artifactDir, "versions/0")
    assertTrue(versionDir.mkdirs())
    File(versionDir, "x.txt").writeText("legacy payload")
    File(versionDir, "metadata.json")
      .writeText(
        """
        {"version":0,"fileName":"$filename","canonicalUri":"file:///legacy",
         "createTime":0.0,"mimeType":"text/plain"}
        """
          .trimIndent(),
      )

    assertFailsWith<IllegalArgumentException> {
      service.saveArtifact(SESSION_KEY, filename, part("replacement"))
    }
    assertEquals(part("legacy payload"), service.loadArtifact(SESSION_KEY, filename))
    assertEquals(listOf(0), service.listVersions(SESSION_KEY, filename))

    service.deleteArtifact(SESSION_KEY, filename)

    assertFalse(artifactDir.exists())
    assertNull(service.loadArtifact(SESSION_KEY, filename))
  }

  @Test
  fun saveArtifact_traversalFilename_throws() = runTest {
    assertFailsWith<IllegalArgumentException> {
      service.saveArtifact(SESSION_KEY, "../../escape.txt", part("x"))
    }
  }

  @Test
  fun saveArtifact_posixAbsoluteFilename_throws() = runTest {
    assertFailsWith<IllegalArgumentException> {
      service.saveArtifact(SESSION_KEY, "/etc/passwd", part("x"))
    }
  }

  @Test
  fun saveArtifact_windowsRootedAbsoluteFilename_throws() = runTest {
    assertFailsWith<IllegalArgumentException> {
      service.saveArtifact(SESSION_KEY, """C:\\etc\\passwd""", part("x"))
    }
  }

  @Test
  fun saveArtifact_windowsRootRelativeFilename_throws() = runTest {
    assertFailsWith<IllegalArgumentException> {
      service.saveArtifact(SESSION_KEY, """\etc\passwd""", part("x"))
    }
  }

  @Test
  fun saveArtifact_windowsDriveRelativeFilename_throws() = runTest {
    assertFailsWith<IllegalArgumentException> {
      service.saveArtifact(SESSION_KEY, """C:etc\passwd""", part("x"))
    }
  }

  @Test
  fun saveArtifact_emptyUserId_throws() = runTest {
    val key = SESSION_KEY.copy(userId = "")
    assertFailsWith<IllegalArgumentException> { service.saveArtifact(key, FILENAME, part("x")) }
  }

  @Test
  fun saveArtifact_userIdWithSeparator_throws() = runTest {
    val key = SESSION_KEY.copy(userId = "bad/user")
    assertFailsWith<IllegalArgumentException> { service.saveArtifact(key, FILENAME, part("x")) }
  }

  @Test
  fun saveArtifact_sessionIdTraversalSegment_throws() = runTest {
    val key = SESSION_KEY.copy(id = "..")
    assertFailsWith<IllegalArgumentException> { service.saveArtifact(key, FILENAME, part("x")) }
  }

  @Test
  fun saveArtifact_userIdWithNullByte_throws() = runTest {
    val key = SESSION_KEY.copy(userId = "bad\u0000user")
    assertFailsWith<IllegalArgumentException> { service.saveArtifact(key, FILENAME, part("x")) }
  }

  @Test
  fun saveAndReloadArtifact_returnsFileUriAndMimeType() = runTest {
    val reloaded =
      service.saveAndReloadArtifact(
        SESSION_KEY,
        FILENAME,
        Part(inlineData = Blob(data = "bytes".toByteArray(), mimeType = "text/plain")),
      )

    assertEquals("text/plain", reloaded.fileData?.mimeType)
    assertTrue(reloaded.fileData?.fileUri?.startsWith("file:") == true)
  }

  @Test
  fun saveAndReloadArtifact_nullMimeType_defaultsToOctetStream() = runTest {
    val reloaded =
      service.saveAndReloadArtifact(
        SESSION_KEY,
        FILENAME,
        Part(inlineData = Blob(data = "bytes".toByteArray(), mimeType = null)),
      )

    assertEquals("application/octet-stream", reloaded.fileData?.mimeType)
  }

  @Test
  fun listArtifactKeys_returnsSortedSessionFilenames() = runTest {
    val unused0 = service.saveArtifact(SESSION_KEY, "b.txt", part("x"))
    val unused1 = service.saveArtifact(SESSION_KEY, "a.txt", part("x"))

    assertContentEquals(listOf("a.txt", "b.txt"), service.listArtifactKeys(SESSION_KEY))
  }

  @Test
  fun listVersions_returnsAllVersionsSorted() = runTest {
    val unused0 = service.saveArtifact(SESSION_KEY, FILENAME, part("a"))
    val unused1 = service.saveArtifact(SESSION_KEY, FILENAME, part("b"))
    val unused2 = service.saveArtifact(SESSION_KEY, FILENAME, part("c"))

    assertContentEquals(listOf(0, 1, 2), service.listVersions(SESSION_KEY, FILENAME))
  }

  @Test
  fun deleteArtifact_removesArtifact() = runTest {
    val unused = service.saveArtifact(SESSION_KEY, FILENAME, part("a"))

    service.deleteArtifact(SESSION_KEY, FILENAME)

    assertNull(service.loadArtifact(SESSION_KEY, FILENAME))
    assertContentEquals(emptyList(), service.listVersions(SESSION_KEY, FILENAME))
  }

  @Test
  fun deleteArtifact_missingArtifact_isNoOp() = runTest {
    service.deleteArtifact(SESSION_KEY, "never-saved.txt")

    assertNull(service.loadArtifact(SESSION_KEY, "never-saved.txt"))
  }

  @Test
  fun userNamespacedArtifact_isVisibleAcrossSessionsForSameUser() = runTest {
    val userFile = "user:profile.json"
    val unused = service.saveArtifact(SESSION_KEY, userFile, part("shared"))

    val otherSession = SESSION_KEY.copy(id = "another-session")
    val loaded = service.loadArtifact(otherSession, userFile)

    assertEquals(part("shared"), loaded)
  }

  @Test
  fun nullSessionId_isTreatedAsUserScoped() = runTest {
    val key = SESSION_KEY.copy(id = null)
    val unused = service.saveArtifact(key, "doc.txt", part("scoped-to-user"))

    val loaded = service.loadArtifact(key, "doc.txt")

    assertEquals(part("scoped-to-user"), loaded)
  }

  @Test
  fun sessionIdEqualsUser_doesNotCollideWithUserScope() = runTest {
    val key = SESSION_KEY.copy(id = "user")
    val unused0 = service.saveArtifact(key, "report.txt", part("session-data"))
    val unused1 = service.saveArtifact(key, "user:report.txt", part("user-data"))

    assertEquals(part("session-data"), service.loadArtifact(key, "report.txt"))
    assertEquals(part("user-data"), service.loadArtifact(key, "user:report.txt"))
  }

  @Test
  fun nestedFilename_roundTrips() = runTest {
    val unused = service.saveArtifact(SESSION_KEY, "images/photo.png", part("pixels"))

    assertEquals(part("pixels"), service.loadArtifact(SESSION_KEY, "images/photo.png"))
  }

  @Test
  fun listArtifactKeys_includesUserNamespacedKeysWithPrefix() = runTest {
    val unused0 = service.saveArtifact(SESSION_KEY, "session.txt", part("x"))
    val unused1 = service.saveArtifact(SESSION_KEY, "user:global.txt", part("x"))

    assertContentEquals(
      listOf("session.txt", "user:global.txt"),
      service.listArtifactKeys(SESSION_KEY),
    )
  }

  @Test
  fun listArtifactKeys_returnsOriginalFilenameFromMetadata() = runTest {
    // The stored fileName (the caller's original) differs from the normalized on-disk path
    // ("report.txt"), so this pins that listing reports metadata.fileName, not the reconstructed
    // path.
    val unused = service.saveArtifact(SESSION_KEY, "sub/../report.txt", part("x"))

    assertContentEquals(listOf("sub/../report.txt"), service.listArtifactKeys(SESSION_KEY))
  }

  @Test
  fun saveArtifact_sessionScoped_usesCrossLanguageLayout() = runTest {
    val unused = service.saveArtifact(SESSION_KEY, FILENAME, part("a"))

    val payload =
      File(baseDir, "users/$USER_ID/sessions/$SESSION_ID/artifacts/$FILENAME/versions/0/$FILENAME")
    val metadata =
      File(
        baseDir,
        "users/$USER_ID/sessions/$SESSION_ID/artifacts/$FILENAME/versions/0/metadata.json",
      )
    assertTrue(payload.exists())
    assertTrue(metadata.exists())
  }

  @Test
  fun saveArtifact_userScoped_stripsPrefixAndOmitsSessionFromLayout() = runTest {
    val unused = service.saveArtifact(SESSION_KEY, "user:global.txt", part("a"))

    val payload = File(baseDir, "users/$USER_ID/artifacts/global.txt/versions/0/global.txt")
    assertTrue(payload.exists())
  }

  @Test
  fun artifacts_persistAcrossServiceInstancesOnSameDir() = runTest {
    val unused = service.saveArtifact(SESSION_KEY, FILENAME, part("durable"))

    val reopened = FileArtifactService(baseDir.path)

    assertEquals(part("durable"), reopened.loadArtifact(SESSION_KEY, FILENAME))
  }

  private fun part(content: String): Part =
    Part(inlineData = Blob(data = content.toByteArray(), mimeType = "text/plain"))

  companion object {
    private const val APP_NAME = "test-app"
    private const val USER_ID = "test-user"
    private const val SESSION_ID = "test-session"
    private const val FILENAME = "test-file.txt"
    private val SESSION_KEY = SessionKey(appName = APP_NAME, userId = USER_ID, id = SESSION_ID)
  }
}
