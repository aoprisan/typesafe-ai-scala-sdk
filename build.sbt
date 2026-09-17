ThisBuild / scalaVersion := "3.3.6"
ThisBuild / organization := "io.github.aoprisan"
ThisBuild / version      := "0.1.0-SNAPSHOT"

ThisBuild / homepage := Some(url("https://github.com/aoprisan/typesafe-scala"))
ThisBuild / licenses := List("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / scmInfo := Some(
  ScmInfo(url("https://github.com/aoprisan/typesafe-scala"), "scm:git@github.com:aoprisan/typesafe-scala.git")
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
    // No runtime dependencies: the JDK HttpClient and an internal JSON AST.
    libraryDependencies += "org.scalameta" %% "munit" % "1.1.1" % Test,
    Test / fork := true,
    Test / parallelExecution := false
  )
