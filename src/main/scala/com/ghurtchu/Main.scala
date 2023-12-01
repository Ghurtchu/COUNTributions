package com.ghurtchu

import cats.effect.{IO, IOApp}
import org.http4s.Header.Raw
import org.http4s.client.Client
import org.http4s.dsl.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.{EntityDecoder, Header, HttpRoutes, Method, ParseFailure, Request, Response, Uri}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import cats.syntax.all.*
import org.typelevel.log4cats.syntax.LoggerInterpolator
import com.ghurtchu.Main.syntax.*
import com.ghurtchu.Main.domain.*
import play.api.libs.json.*
import play.api.libs.*
import cats.implicits.*
import com.comcast.ip4s.*
import pureconfig._
import pureconfig.generic.derivation.default._

object Main extends IOApp.Simple {

  final case class Token(token: String) derives ConfigReader {
    override def toString: String = token
  }

  given logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  override val run: IO[Unit] =
    (for {
      _ <- info"starting server".toResource
      token <- IO.delay(ConfigSource.default.loadOrThrow[Token]).toResource
      client <- EmberClientBuilder.default[IO].build
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(host"localhost")
        .withPort(port"9000")
        .withHttpApp(routes(client, token).orNotFound)
        .build
    } yield ()).useForever

  def publicRepos(orgName: String) = s"https://api.github.com/orgs/$orgName"

  def contributorsUrl(repoName: String, orgName: String, page: Int): String =
    s"https://api.github.com/repos/$orgName/$repoName/contributors?per_page=100&page=$page"

  def repos(orgName: String, page: Int): String =
    s"https://api.github.com/orgs/$orgName/repos?per_page=100&page=$page"

  def req(uri: Uri)(using token: Token) = Request[IO](Method.GET, uri)
    .putHeaders(Raw(CIString("Authorization"), s"Bearer ${token.token}"))

  def uri(url: String): IO[Uri] =
    IO.fromEither(Uri.fromString(url))

  def fetch[A: Reads](uri: Uri, client: Client[IO], default: => A)(using token: Token): IO[A] =
    client
      .expect[String](req(uri))
      .map(_.into[A])
      .onError(IO.println)
      .handleError(_ => default)

  def routes(client: Client[IO], token: Token): HttpRoutes[IO] = {
    given tk: Token = token

    HttpRoutes.of[IO] {

      case GET -> Root => Ok("hi :)")

      case GET -> Root / "org" / orgName =>
        for {
          start <- IO.realTime
          response <- uri(publicRepos(orgName))
            .flatMap { publicReposUri =>
              for {
                publicRepos <- fetch[PublicRepos](publicReposUri, client, PublicRepos.Emptpy)
                pages = (1 to (publicRepos.value / 100) + 1).toVector
                repositories <- pages.parUnorderedFlatTraverse { page =>
                  uri(repos(orgName, page))
                    .flatMap(fetch[Vector[RepoName]](_, client, Vector.empty))
                }
                contributors <- repositories
                  .parUnorderedFlatTraverse { repoName =>
                    def getContributors(
                      page: Int,
                      contributors: Vector[Contributor],
                      isEmpty: Boolean = false,
                    ): IO[Vector[Contributor]] =
                      if ((page > 1 && contributors.size % 100 != 0) || isEmpty) IO.pure(contributors)
                      else {
                        uri(contributorsUrl(repoName.value, orgName, page))
                          .flatMap { repoUri =>
                            for {
                              newContributors <- fetch[Vector[Contributor]](repoUri, client, Vector.empty)
                              next <- getContributors(
                                page = page + 1,
                                contributors = contributors ++ newContributors,
                                isEmpty = newContributors.isEmpty,
                              )
                            } yield next
                          }
                      }

                    getContributors(page = 1, contributors = Vector.empty)
                  }
                  .map {
                    _.groupMapReduce(_.login)(_.contributions)(_ + _).toVector
                      .map(Contributor(_, _))
                      .sortWith(_.contributions > _.contributions)
                  }
                result <- Ok(Contributions(contributors.size, contributors).toJson)
              } yield result
            }
          _ <- IO.realTime.flatTap(now => IO.println(s"operation took ${(now - start).toSeconds} seconds"))
        } yield response
    }
  }

  object syntax {
    extension (self: String) def into[A](using r: Reads[A]): A = Json.parse(self).as[A]
    extension [A](self: A) def toJson(using w: Writes[A]): String = Json.prettyPrint(w.writes(self))
  }

  object domain {

    import Reads.{IntReads, StringReads}
    import play.api.libs.json._

    opaque type PublicRepos = Int
    object PublicRepos {
      val Emptpy: PublicRepos = PublicRepos.apply(0)
      def apply(value: Int): PublicRepos = value
    }
    given ReadsPublicRepos: Reads[PublicRepos] = (__ \ "public_repos").read[Int].map(PublicRepos.apply)
    extension (repos: PublicRepos) def value: Int = repos

    opaque type RepoName = String
    object RepoName {
      def apply(value: String): RepoName = value
    }
    given ReadsRepo: Reads[RepoName] = (__ \ "name").read[String].map(RepoName.apply)
    extension (repoName: RepoName) def value: String = repoName

    final case class Contributor(login: String, contributions: Long)
    given ReadsContributor: Reads[Contributor] = json =>
      (
        (json \ "login").asOpt[String],
        (json \ "contributions").asOpt[Long],
      ).tupled.fold(JsError("parse failure"))((lo, co) => JsSuccess(Contributor(lo, co)))
    given WritesContributor: Writes[Contributor] = Json.writes[Contributor]

    final case class Contributions(count: Long, contributors: Vector[Contributor])
    given WritesContributions: Writes[Contributions] = Json.writes[Contributions]
  }
}
