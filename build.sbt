ThisBuild / scalaVersion := "3.3.6"
ThisBuild / organization := "io.github.aoprisan"
// version is derived from git tags by sbt-dynver (see project/plugins.sbt):
// a `vX.Y.Z` tag releases X.Y.Z, anything else publishes a snapshot.

ThisBuild / homepage := Some(url("https://github.com/aoprisan/typesafe-ai-scala-sdk"))
ThisBuild / licenses := List("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/aoprisan/typesafe-ai-scala-sdk"),
    "scm:git@github.com:aoprisan/typesafe-ai-scala-sdk.git"
  )
)
ThisBuild / developers := List(
  Developer("aoprisan", "Andrei", "", url("https://github.com/aoprisan"))
)

lazy val root = project
  .in(file("."))
  .settings(
    name        := "typesafe-sdk-scala",
    description := "Zero-dependency Scala 3 client for the TypeSafe AI System One API",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-Wunused:all",
      "-Xfatal-warnings",
      "-release", "17"
    ),
    // Scaladoc runs the same options; don't fail the release jar on a doc warning.
    Compile / doc / scalacOptions -= "-Xfatal-warnings",
    // No runtime dependencies: the JDK HttpClient and an internal JSON AST.
    libraryDependencies += "org.scalameta" %% "munit" % "1.1.1" % Test,
    Test / fork := true,
    Test / parallelExecution := false
  )
