import sbtcrossproject.CrossPlugin.autoImport.crossProject

//scalaVersion in ThisBuild := "2.11.12"
scalaVersion in ThisBuild := "3.0.1"
crossScalaVersions in ThisBuild := Seq("2.11.12", "2.12.13", "2.13.6", "3.0.1")
//scalaJSUseRhino in ThisBuild := true

lazy val scalatest = Def.setting("org.scalatest" %%% "scalatest" % "3.2.9")
lazy val specs2 = Def.setting("org.specs2" %%% "specs2-core" % (if (scalaVersion.value startsWith "2.") "4.10.6" else "5.0.0-RC-03"))

val commonSettings = Defaults.coreDefaultSettings ++ Seq(
  unmanagedSourceDirectories in Compile ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2L, _)) =>
        Some(baseDirectory.value.getParentFile / "shared/src/main/scala-2")
      case Some((3L, _)) =>
        Some(baseDirectory.value.getParentFile / "shared/src/main/scala-3")
      case _ =>
        None
    }
  },
  scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-Xcheckinit", "-target:jvm-1.8", "-rewrite", "-source", "3.0-migration")
)

lazy val scalamock = crossProject(JSPlatform, JVMPlatform) in file(".") settings(
    commonSettings,
    name := "scalamock",
    publishArtifact in (Compile, packageBin) := true,
    publishArtifact in (Compile, packageDoc) := true,
    publishArtifact in (Compile, packageSrc) := true,
    publishArtifact in Test := false,
    scalacOptions in (Compile, doc) ++= Opts.doc.title("ScalaMock") ++
      Opts.doc.version(version.value) ++ Seq("-doc-root-content", "rootdoc.txt", "-version"),
    libraryDependencies ++= Seq(
      scalatest.value % Optional,
      specs2.value % Optional
    ) ++ (if (scalaVersion.value startsWith "2.") Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value) else Nil)
  )

lazy val `scalamock-js` = scalamock.js
lazy val `scalamock-jvm` = scalamock.jvm

lazy val examples = project in file("examples") settings(
  commonSettings,
  name := "ScalaMock Examples",
  skip in publish := true,
  libraryDependencies ++= Seq(
    scalatest.value % Test,
    specs2.value % Test
  )
) dependsOn scalamock.jvm
