val scala213Version = "2.13.12"

ThisBuild / scalaVersion := scala213Version
ThisBuild / crossScalaVersions := Seq(scala213Version, "3.3.0")
ThisBuild / organization := "io.github.valdemargr"

ThisBuild / tlBaseVersion := "0.1"
ThisBuild / tlUntaggedAreSnapshots := false
ThisBuild / tlSonatypeUseLegacyHost := true

ThisBuild / licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / developers := List(
  Developer("valdemargr", "Valdemar Grange", "randomvald0069@gmail.com", url("https://github.com/valdemargr"))
)
ThisBuild / headerLicense := Some(HeaderLicense.Custom("Copyright (c) 2024 Valdemar Grange"))
ThisBuild / headerEmptyLine := false

lazy val sharedSettings = Seq(
  organization := "io.github.valdemargr",
  organizationName := "Valdemar Grange",
  autoCompilerPlugins := true,
  tlCiMimaBinaryIssueCheck := false,
  tlMimaPreviousVersions := Set.empty,
  mimaReportSignatureProblems := false,
  mimaFailOnProblem := false,
  mimaPreviousArtifacts := Set.empty,
  scalacOptions ++= {
    if (scalaVersion.value.startsWith("2")) {
      Seq(
        "-Wunused:-nowarn",
        "-Wconf:cat=unused-nowarn:s",
        "-Ywarn-unused:-nowarn"
      )
    } else Seq.empty // Seq("-explain")
  },
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect" % "3.5.2",
    // "org.typelevel" %% "cats-mtl" % "1.3.1",
    "org.typelevel" %% "cats-core" % "2.9.0",
    // "org.typelevel" %% "cats-free" % "2.9.0",
    "org.typelevel" %% "vault" % "3.5.0",
    "org.tpolecat" %% "sourcepos" % "1.1.0",
    "org.scalameta" %% "munit" % "1.0.0-M10" % Test,
    "org.typelevel" %% "munit-cats-effect" % "2.0.0-M3" % Test
  )
)

lazy val core = project
  .in(file("modules/core"))
  .settings(sharedSettings)
  .settings(name := "catch-effect")

lazy val readme = project
  .in(file("modules/readme"))
  .settings(
    moduleName := "catch-effect-readme",
    mdocIn := file("readme/README.md"),
    mdocOut := file("./README.md"),
    mdocVariables ++= Map(
      "VERSION" -> tlLatestVersion.value.getOrElse(version.value)
    ),
    tlFatalWarnings := false
  )
  .dependsOn(core)
  .enablePlugins(MdocPlugin, NoPublishPlugin)
