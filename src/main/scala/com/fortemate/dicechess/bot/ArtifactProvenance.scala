package com.fortemate.dicechess.bot

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.HexFormat
import java.io.InputStream

final private[bot] case class ArtifactProvenance(id: String, sha256: String)

private[bot] object ArtifactProvenance:

  private val Sha256 = "^[0-9a-fA-F]{64}$".r

  def inspect(pathValue: String, expectedValue: Option[String], configuredId: Option[String]): ArtifactProvenance =
    val expected = expectedValue.map { raw =>
      val normalized = raw.stripPrefix("sha256:")
      if !Sha256.matches(normalized) then
        sys.error(s"invalid expected SHA-256 for '$pathValue' (expected 64 hexadecimal characters)")
      normalized
    }
    val path = Path.of(pathValue)
    if !Files.isRegularFile(path) then sys.error(s"artifact is not a regular file: '$pathValue'")
    val actual = digest(path)
    expected.foreach { expectedSha256 =>
      if !actual.equalsIgnoreCase(expectedSha256) then
        sys.error(s"SHA-256 mismatch for '$pathValue' (expected ${expectedSha256.toLowerCase}, got $actual)")
    }
    val id = configuredId.filter(_.nonEmpty).getOrElse(path.getFileName.toString)
    ArtifactProvenance(id, actual)

  def inspectResource(resourceName: String, id: String): ArtifactProvenance =
    val resourcePath = "/" + resourceName.stripPrefix("/")
    val input        = Option(getClass.getResourceAsStream(resourcePath))
      .getOrElse(sys.error(s"bundled artifact '$resourcePath' is missing"))
    val actual = try digest(input)
    finally input.close()
    ArtifactProvenance(id, actual)

  private def digest(path: Path): String =
    val input = Files.newInputStream(path)
    try digest(input)
    finally input.close()

  private def digest(input: InputStream): String =
    val md     = MessageDigest.getInstance("SHA-256")
    val buffer = new Array[Byte](64 * 1024)
    var read   = input.read(buffer)
    while read >= 0 do
      if read > 0 then md.update(buffer, 0, read)
      read = input.read(buffer)
    HexFormat.of().formatHex(md.digest())
