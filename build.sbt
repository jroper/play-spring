def commonSettings: Seq[Setting[_]] = Seq(
  version := "1.0-SNAPSHOT",
  organization := "com.typesafe.play",
  scalaVersion := "2.10.4",
  crossScalaVersions := Seq("2.10.4", "2.11.2")
)

lazy val root = (project in file("."))
  .aggregate(playSpring)

lazy val playSpring = (project in file("core"))
  .settings(commonSettings: _*)
  .settings(
    name := "play-spring",
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play" % "2.4-SNAPSHOT",
      "org.springframework" % "spring-context" % "4.0.6.RELEASE"
    )
  )
