package com.ghurtchu

import cats.data.{EitherT, OptionT}
import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s.{host, port}
import com.ghurtchu.domain.domain.{Contributor, PublicRepos, RepositoryName}
import org.http4s.Header.Raw
import org.http4s.client.Client
import org.http4s.dsl.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder

import scala.collection.parallel.CollectionConverters.*
import org.http4s.{EntityDecoder, Header, HttpRoutes, Method, ParseFailure, Request, Response, Uri}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import play.api.libs.json.*
import cats.syntax.all.*


object Main extends IOApp.Simple {

  case class Token(value: String) extends AnyVal

  // TODO: either load from config or store as env variable
  implicit val token: Token = Token("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")

  implicit val _: Logger[IO] = Slf4jLogger.getLogger[IO]

  override val run: IO[Unit] =
    (for {
      client <- EmberClientBuilder.default[IO].build
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(host"localhost")
        .withPort(port"9000")
        .withHttpApp(routes(client).orNotFound)
        .build
    } yield ())
      .useForever

  def publicReposUrl(orgName: String) =
    s"https://api.github.com/orgs/$orgName"

  def contributorsUrl(repoName: String, orgName: String, page: Int): String =
    s"https://api.github.com/repos/$orgName/$repoName/contributors?per_page=100&page=$page"

  def repoPerPageUrl(orgName: String, page: Int): String =
    s"https://api.github.com/orgs/$orgName/repos?per_page=100&page=$page"

  def req(uri: Uri)(implicit token: Token) = Request[IO](Method.GET, uri)
      .putHeaders(Raw(CIString("Authorization"), s"Bearer ${token.value}"))

  def uri(url: String): Either[ParseFailure, Uri] = Uri.fromString(url)

  def routes(client: Client[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root => Ok("hi :)")

    case GET -> Root / "org" / orgName => {
      val logic: IO[Either[ParseFailure, Response[IO]]] =
        uri(publicReposUrl(orgName))
        .traverse { publicReposUri =>
          for {
            before <- IO.realTime
            publicRepos <- client.expect[String](req(publicReposUri)) // expect String request body
              .onError(IO.println) // log any shit that went wrong
              .map(_.into[PublicRepos]) // parse json string to PublicRepos
            _ <- IO.println(s"Number of public repos for $orgName: ${publicRepos.value}, list below:\n$publicRepos")
            nParRequests = (1 to (publicRepos.value / 100) + 1).toVector
            repositories <- nParRequests.parFlatTraverse { pageNumber =>
              uri(repoPerPageUrl(orgName, pageNumber))
                .traverse { repoPerPageUri =>
                  client.expect[String](req(repoPerPageUri))
                    .onError(IO.println)
                    .map(_.into[Vector[RepositoryName]])
                }.map(_.getOrElse(Vector.empty))
            }
            contributors <- repositories.parUnorderedFlatTraverse { repo =>
              def getContributors(
                page: Int,
                contributors: Vector[Contributor],
                isEmpty: Boolean = false
              ): IO[Vector[Contributor]] = {
                if (page > 1 && contributors.size % 100 != 0) IO.pure(contributors)
                else if (isEmpty) IO.pure(contributors)
                else {
                  val repoUrl = contributorsUrl(repo.value, orgName, page)
                  println(s"sending request to: $repoUrl")
                  uri(repoUrl)
                    .traverse { repoUri =>
                      for {
                        resp <- client.expect[String](req(repoUri)).handleError(_ => "")
                        _ <- IO.println(s"received from $repoUrl")
                        newContributors = scala.util.Try(resp.into[Vector[Contributor]]).getOrElse(Vector.empty)
                        _ = println(newContributors)
                        result <- getContributors(
                          page = page + 1,
                          contributors = contributors ++ newContributors,
                          isEmpty = newContributors.isEmpty
                        )
                      } yield result
                  }.map(_.getOrElse(Vector.empty))
                }
              }

              getContributors(1, Vector.empty)
            }.map {
              _.groupMapReduce(_.login)(_.contributions)(_ + _)
                .toVector
                .map(Contributor.fromTuple)
                .sortWith(_.contributions > _.contributions)
            }
            now <- IO.realTime
            _ = println((before - now).toSeconds)
            _ = println("---" * 50)
            resp <- Ok(contributors.toString)
          } yield resp
        }

      for {
        r <- logic.flatMap(IO.fromEither)
      } yield r


      //      val req = Request[IO](Method.GET, org.http4s.Uri.unsafeFromString(publicReposUrl(orgName)))
      //        .putHeaders(Raw(CIString("Authorization"), s"Bearer ${token.value}"))
      //
      //      for {
      //        before <- IO.realTime
      //        response <- client.expect[String](req).onError {
      //          case err => IO.println(err.getMessage)
      //        }
      //        publicRepos = response.into[PublicRepos]
      //        nParRequests = (publicRepos.value / 100) + 1
      //        _ = println(publicRepos)
      //        _ = println("=" * 100)
      //        repos <- (1 to nParRequests).toVector.parFlatTraverse { page =>
      //          val url = repoPerPageUrl(orgName, page)
      //          val request: Request[IO] = Request[IO](Method.GET, org.http4s.Uri.unsafeFromString(url))
      //            .putHeaders(Raw(CIString("Authorization"), s"Bearer ${token.value}"))
      //
      //          for {
      //            js <- client.expect[String](request)
      //          } yield js.into[Vector[RepositoryName]]
      //        }
      //
      //        _ = println(repos)
      //
      //        contributors <- repos.parUnorderedFlatTraverse { repo =>
      //          def getContributors(page: Int, contributors: Vector[Contributor], isEmpty: Boolean = false): IO[Vector[Contributor]] = {
      //            if (page > 1 && contributors.size % 100 != 0) IO.pure(contributors)
      //            else if (isEmpty) IO.pure(contributors)
      //            else {
      //              val repoUrl = contributorsUrl(repo.value, orgName, page)
      //              println(s"sending request to: $repoUrl")
      //              val repoEndpoint = org.http4s.Uri.unsafeFromString(repoUrl)
      //              val request: Request[IO] = Request[IO](Method.GET, repoEndpoint)
      //                .putHeaders(Raw(CIString("Authorization"), s"Bearer ${token.value}"))
      //
      //              for {
      //                resp <- client.expect[String](request).handleError(_ => "")
      //                _ <- IO.println(s"received from $repoUrl")
      //                newContributors = scala.util.Try(resp.into[Vector[Contributor]]).getOrElse(Vector.empty)
      //                _ = println(newContributors)
      //                result <- getContributors(
      //                  page = page + 1,
      //                  contributors = contributors ++ newContributors,
      //                  isEmpty = newContributors.isEmpty
      //                )
      //              } yield result
      //            }
      //          }
      //
      //          getContributors(1, Vector.empty)
      //        }.map {
      //          _.groupMapReduce(_.login)(_.contributions)(_ + _)
      //            .toVector
      //            .map(Contributor.fromTuple)
      //            .sortWith(_.contributions > _.contributions)
      //        }
      //        now <- IO.realTime
      //        _ = println((before - now).toSeconds)
      //        _ = println("---" * 50)
      //
      //        resp <- Ok(contributors.toString)
      //      } yield resp

    }

  }

  implicit class StringJsonOps(self: String) extends AnyVal {
    def into[A: Reads]: A = (Json parse self).as[A]
  }


}
