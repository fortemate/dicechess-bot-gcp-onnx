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

  test("rejects malformed expected digests before startup"):
    val path = Files.createTempFile("artifact-provenance", ".bin")
    try
      val error = intercept[RuntimeException] {
        ArtifactProvenance.inspect(path.toString, Some("not-a-digest"), None)
      }
      assert(error.getMessage.contains("expected 64 hexadecimal characters"))
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
