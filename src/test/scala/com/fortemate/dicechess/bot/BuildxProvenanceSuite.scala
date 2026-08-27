package com.fortemate.dicechess.bot

import scala.sys.process.*

class BuildxProvenanceSuite extends munit.FunSuite:

  private val jqScript =
    """
      .[$platform].SLSA as $slsa |
      ($slsa != null) and
      ($slsa.buildDefinition.buildType == "https://mobyproject.org/buildkit@v1" or
       $slsa.buildDefinition.buildType == "https://github.com/moby/buildkit/blob/master/docs/attestations/slsa-definitions.md") and
      (($slsa.buildDefinition.resolvedDependencies | length) > 0) and
      all(
        $slsa.buildDefinition.resolvedDependencies[];
        (.uri | type == "string" and length > 0) and
        (.digest.sha256 | type == "string" and test("^[0-9a-f]{64}$"))
      )
    """

  private def validateProvenance(json: String, platform: String): Boolean =
    val process = Process(Seq("jq", "--exit-status", "--arg", "platform", platform, jqScript))
    val io      = new ProcessIO(
      in => {
        in.write(json.getBytes("UTF-8"))
        in.close()
      },
      _ => (),
      _ => ()
    )
    process.run(io).exitValue() == 0

  private def sampleProvenanceJson(buildType: String, sha256: String = "a" * 64): String =
    s"""
    {
      "linux/amd64": {
        "SLSA": {
          "buildDefinition": {
            "buildType": "$buildType",
            "resolvedDependencies": [
              {
                "uri": "pkg:docker/docker/dockerfile@1",
                "digest": {
                  "sha256": "$sha256"
                }
              }
            ]
          }
        }
      },
      "linux/arm64": {
        "SLSA": {
          "buildDefinition": {
            "buildType": "$buildType",
            "resolvedDependencies": [
              {
                "uri": "pkg:docker/docker/dockerfile@1",
                "digest": {
                  "sha256": "$sha256"
                }
              }
            ]
          }
        }
      }
    }
    """

  test("accepts mobyproject.org buildType URI"):
    val json = sampleProvenanceJson("https://mobyproject.org/buildkit@v1")
    assert(validateProvenance(json, "linux/amd64"))
    assert(validateProvenance(json, "linux/arm64"))

  test("accepts GitHub moby buildkit slsa-definitions URI"):
    val json =
      sampleProvenanceJson("https://github.com/moby/buildkit/blob/master/docs/attestations/slsa-definitions.md")
    assert(validateProvenance(json, "linux/amd64"))
    assert(validateProvenance(json, "linux/arm64"))

  test("rejects unknown buildType URIs"):
    val json = sampleProvenanceJson("https://example.com/unknown-builder@v1")
    assert(!validateProvenance(json, "linux/amd64"))
    assert(!validateProvenance(json, "linux/arm64"))

  test("rejects missing or empty resolvedDependencies"):
    val json =
      """
      {
        "linux/amd64": {
          "SLSA": {
            "buildDefinition": {
              "buildType": "https://mobyproject.org/buildkit@v1",
              "resolvedDependencies": []
            }
          }
        }
      }
      """
    assert(!validateProvenance(json, "linux/amd64"))

  test("rejects malformed sha256 digests in resolvedDependencies"):
    val json = sampleProvenanceJson("https://mobyproject.org/buildkit@v1", sha256 = "invalid-sha")
    assert(!validateProvenance(json, "linux/amd64"))

  test("rejects missing platform provenance"):
    val json = sampleProvenanceJson("https://mobyproject.org/buildkit@v1")
    assert(!validateProvenance(json, "linux/s390x"))
