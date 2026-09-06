package com.fortemate.dicechess.bot

import java.nio.file.Files

class ArtifactProvenanceSuite extends munit.FunSuite:

  test("computes and verifies a configured SHA-256 without retaining file contents"):
    val path = Files.createTempFile("artifact-provenance", ".bin")
    try
      Files.writeString(path, "dicechess")
      val expected = "14080a0f99a3e4a4bb8056eee6dcdca3b8d18eefe46d6f716fb5f0329a59847e"
      val result   = ArtifactProvenance.inspect(path.toString, Some(s"sha256:$expected"), Some("fixture"))
      assertEquals(result, ArtifactProvenance("fixture", expected))
    finally Files.deleteIfExists(path)

  test("fails closed on a digest mismatch"):
    val path = Files.createTempFile("artifact-provenance", ".bin")
    try
      Files.writeString(path, "dicechess")
      val expected = "0" * 64
      val error    = intercept[RuntimeException] {
        ArtifactProvenance.inspect(path.toString, Some(expected), None)
      }
      assert(error.getMessage.startsWith(s"SHA-256 mismatch for '${path.toString}'"))
    finally Files.deleteIfExists(path)

  test("rejects malformed expected digests before accessing the artifact"):
    val path = Files.createTempFile("artifact-provenance", ".bin")
    try
      Files.delete(path)
      val error = intercept[RuntimeException] {
        ArtifactProvenance.inspect(path.toString, Some("not-a-digest"), None)
      }
      assertEquals(
        error.getMessage,
        s"invalid expected SHA-256 for '${path.toString}' (expected 64 hexadecimal characters)"
      )
    finally Files.deleteIfExists(path)

  test("fails closed when a configured artifact is missing"):
    val missing = Files.createTempDirectory("artifact-provenance-missing").resolve("missing.onnx")
    try
      val error = intercept[RuntimeException] {
        ArtifactProvenance.inspect(missing.toString, None, None)
      }
      assertEquals(error.getMessage, s"artifact is not a regular file: '${missing.toString}'")
    finally Files.deleteIfExists(missing.getParent)

  test("fingerprints the bundled opening-book sample"):
    assertEquals(
      ArtifactProvenance.inspectResource("opening_book.tsv", "bundled-sample"),
      ArtifactProvenance(
        "bundled-sample",
        "635b95bba574df2f3d43df3b27b4242c1e3b8627b27e5335c636aafcde40a7af"
      )
    )

  test("accepts upper-case expected digest and returns lower-case actual digest"):
    val path = Files.createTempFile("artifact-provenance", ".bin")
    try
      Files.writeString(path, "dicechess")
      val expectedLower = "14080a0f99a3e4a4bb8056eee6dcdca3b8d18eefe46d6f716fb5f0329a59847e"
      val expectedUpper = expectedLower.toUpperCase
      val result        = ArtifactProvenance.inspect(path.toString, Some(expectedUpper), Some("fixture"))
      assertEquals(result, ArtifactProvenance("fixture", expectedLower))
    finally Files.deleteIfExists(path)

  test("reports expected digest in lower case on mismatch even when configured in upper case"):
    val path = Files.createTempFile("artifact-provenance", ".bin")
    try
      Files.writeString(path, "dicechess")
      val actualLower = "14080a0f99a3e4a4bb8056eee6dcdca3b8d18eefe46d6f716fb5f0329a59847e"
      val wrongUpper  = "A" * 64
      val wrongLower  = wrongUpper.toLowerCase
      val error       = intercept[RuntimeException] {
        ArtifactProvenance.inspect(path.toString, Some(wrongUpper), None)
      }
      assert(
        error.getMessage.startsWith(
          s"SHA-256 mismatch for '${path.toString}' (expected $wrongLower, got $actualLower)"
        )
      )
    finally Files.deleteIfExists(path)

  test("falls back to file name when configuredId is None or empty"):
    val path = Files.createTempFile("artifact-provenance", ".bin")
    try
      Files.writeString(path, "dicechess")
      val expectedId  = path.getFileName.toString
      val resultNone  = ArtifactProvenance.inspect(path.toString, None, None)
      val resultEmpty = ArtifactProvenance.inspect(path.toString, None, Some(""))
      assertEquals(resultNone.id, expectedId)
      assertEquals(resultEmpty.id, expectedId)
    finally Files.deleteIfExists(path)

  test("handles missing and slash-prefixed resource paths"):
    val missingError = intercept[RuntimeException] {
      ArtifactProvenance.inspectResource("does-not-exist.onnx", "x")
    }
    assertEquals(missingError.getMessage, "bundled artifact '/does-not-exist.onnx' is missing")
    assertEquals(
      ArtifactProvenance.inspectResource("/opening_book.tsv", "s"),
      ArtifactProvenance.inspectResource("opening_book.tsv", "s")
    )
