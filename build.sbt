val Http4sVersion = "0.23.23"
val CirceVersion = "0.14.5"

lazy val root = (project in file("."))
  .settings(
    organization := "com.rockthejvm",
    name := "contrib",
    scalaVersion := "3.3.0",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % Http4sVersion,
      "org.http4s" %% "http4s-ember-client" % Http4sVersion,
      "org.http4s" %% "http4s-dsl" % Http4sVersion,
      "com.typesafe.play" %% "play-json" % "2.10.3",
      "com.github.pureconfig" %% "pureconfig-core" % "0.17.4",
      "ch.qos.logback" % "logback-classic" % "1.4.12" % Runtime,
    ),
  )
