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

val munitVersion           = "1.1.1"
val catsEffectVersion      = "3.7.1"
val fs2Version             = "3.14.0"
val munitCatsEffectVersion = "2.2.1"
val monixVersion           = "3.5.0"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-Wunused:all",
    "-Xfatal-warnings",
    "-release", "17"
  ),
  // Scaladoc runs the same options; don't fail the release jar on a doc warning.
  Compile / doc / scalacOptions -= "-Xfatal-warnings",
  libraryDependencies += "org.scalameta" %% "munit" % munitVersion % Test,
  Test / fork := true,
  Test / parallelExecution := false
)

lazy val root = project
  .in(file("."))
  .aggregate(core, catsEffect, fs2, monix)
  .settings(
    name := "typesafe-sdk-scala-root",
    publish / skip := true
  )

lazy val core = project
  .in(file("core"))
  .settings(commonSettings)
  .settings(
    name        := "typesafe-sdk-scala",
    description := "Zero-dependency Scala 3 client for the TypeSafe AI System One API"
    // No runtime dependencies: the JDK HttpClient and an internal JSON AST.
  )

lazy val catsEffect = project
  .in(file("cats-effect"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name        := "typesafe-sdk-scala-cats-effect",
    description := "Cats Effect binding for the TypeSafe AI System One client",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"      % catsEffectVersion,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test
    )
  )

lazy val fs2 = project
  .in(file("fs2"))
  .dependsOn(catsEffect, core % "test->test")
  .settings(commonSettings)
  .settings(
    name        := "typesafe-sdk-scala-fs2",
    description := "fs2 streaming for the TypeSafe AI System One client",
    libraryDependencies ++= Seq(
      "co.fs2"        %% "fs2-core"          % fs2Version,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test
    )
  )

lazy val monix = project
  .in(file("monix"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name        := "typesafe-sdk-scala-monix",
    description := "Monix Task binding for the TypeSafe AI System One client",
    // Monix 3.x is built on Cats Effect 2; keep it off the Cats Effect 3 modules' classpath.
    libraryDependencies += "io.monix" %% "monix-eval" % monixVersion
  )
