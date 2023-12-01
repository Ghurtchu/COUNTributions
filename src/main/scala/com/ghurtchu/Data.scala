package com.ghurtchu

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import play.api.libs.json.{Format, JsError, JsSuccess, Json}

case class Data (
                  x: Int,
                  y: Option[Int],
                  v: Vector[String] = Vector.empty,
                  m: Map[String, String] = Map.empty
                )

// implicit val format: Format[Data]= Json.format[Data]

implicit val format: Format[Data]= Json.using[Json.WithDefaultValues].format[Data]

object app extends IOApp.Simple {


  val opt: Option[Int] = Some(1)
  val op2: Option[Int] = None

  val program = opt.flatTraverse { int => IO(Some(int)) }
  val program2 = op2.traverse { int => IO(Some(int)) }

  override val run =
    for {
      p1 <- program
      _ <- IO.println(p1)
      p2 <- program2
      _ <- IO.println(p2)
    } yield ()

}
