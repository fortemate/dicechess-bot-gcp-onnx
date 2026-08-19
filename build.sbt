// =============================================================================
// Project metadata
// =============================================================================
ThisBuild / organization         := "com.fortemate"
ThisBuild / organizationName     := "Fortemate"
ThisBuild / organizationHomepage := Some(uri("https://fortemate.com"))
ThisBuild / homepage             := Some(uri("https://fortemate.com"))
ThisBuild / startYear            := Some(2026)
ThisBuild / version              := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion         := "3.8.4"

ThisBuild / description :=
  "Dice Chess webhook bot for Google Cloud Run, powered by ONNX 2-ply expectimax search and an opening book."
ThisBuild / licenses      := List(License("AGPL-3.0", uri("https://www.gnu.org/licenses/agpl-3.0.txt")))
ThisBuild / versionScheme := Some("early-semver")

ThisBuild / scmInfo := Some(
  ScmInfo(
    uri("https://github.com/fortemate/dicechess-bot-gcp-onnx"),
    "scm:git@github.com:fortemate/dicechess-bot-gcp-onnx.git"
  )
)

ThisBuild / developers := List(
  Developer(
    id = "fortemate",
    name = "Fortemate",
    email = "contact@fortemate.com",
    url = uri("https://fortemate.com")
  )
)

// Both Fortemate libraries are public Maven Central artifacts; no repository credentials are required.
val DiceChessEngineVersion     = "0.4.0"
val DiceChessBotRuntimeVersion = "1.0.0"
val CirceVersion               = "0.14.16"
val MunitVersion               = "1.3.5"

lazy val testAll = taskKey[Unit]("Run every test, while allowing an empty bootstrap project")

lazy val root = (project in file("."))
  .settings(
    name                := "dicechess-bot-gcp-onnx",
    publish / skip      := true,
    Test / exportJars   := false,
    Compile / mainClass := Some("com.fortemate.dicechess.bot.Main"),
    semanticdbEnabled   := true,
    semanticdbVersion   := scalafixSemanticdb.revision,
    scalacOptions ++= Seq(
      "-Werror",
      "-Wunused:all",
      "-language:strictEquality",
      "-Yexplicit-nulls",
      "-explain",
      "-feature",
      "-deprecation"
    ),
    libraryDependencies ++= Seq(
      "com.fortemate" %% "dicechess-engine"      % DiceChessEngineVersion,
      "com.fortemate"  % "dicechess-bot-runtime" % DiceChessBotRuntimeVersion,
      "io.circe"      %% "circe-parser"          % CirceVersion % Test,
      "org.scalameta" %% "munit"                 % MunitVersion % Test
    ),
    coverageExcludedFiles    := ".*Main\\.scala",
    coverageMinimumStmtTotal := 90,
    coverageFailOnMinimum    := true,
    testAll                  := Def.taskDyn {
      val tests = (Test / definedTests).value
      if (tests.isEmpty)
        Def.task(streams.value.log.info("No tests discovered; bootstrap validation is compile-only"))
      else
        Def.task {
          (Test / testOnly).toTask(" *").value
          ()
        }
    }.value,
    assembly / mainClass             := Some("com.fortemate.dicechess.bot.Main"),
    assembly / assemblyJarName       := "dicechess-bot-gcp-onnx.jar",
    assembly / assemblyOutputPath    := target.value / "dicechess-bot-gcp-onnx.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) if xs.nonEmpty && {
            val name = xs.last.toLowerCase
            name.endsWith(".sf") || name.endsWith(".dsa") || name.endsWith(".rsa")
          } =>
        MergeStrategy.discard
      case PathList("META-INF", "MANIFEST.MF")        => MergeStrategy.discard
      case PathList("META-INF", "services", _ @_*)    => MergeStrategy.concat
      case path if path.endsWith("module-info.class") => MergeStrategy.discard
      case _                                          => MergeStrategy.first
    }
  )
