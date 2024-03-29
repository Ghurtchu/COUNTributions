package com.rockthejvm

import org.http4s.Header.Raw
import org.http4s.client.Client
import org.http4s.{EntityDecoder, Header, HttpRoutes, Method, ParseFailure, Request, Response, Status, Uri}
import org.typelevel.ci.CIString
import com.rockthejvm.Main.syntax.*
import com.rockthejvm.Main.domain.*
import pureconfig.*
import pureconfig.generic.derivation.default.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.dsl.*
import cats.syntax.all.*
import cats.instances.all.*
import play.api.libs.json.*
import play.api.libs.*
import cats.implicits.*
import cats.effect.{IO, IOApp}
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.syntax.LoggerInterpolator
import com.comcast.ip4s.*
import com.rockthejvm.Main.getContributorsPerRepo

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

  def uri(url: String): IO[Uri] = IO.fromEither(Uri.fromString(url))

  def fetch[A: Reads](uri: Uri, client: Client[IO], default: => A)(using token: Token): IO[A] =
    client
      .expect[String](req(uri))
      .map(_.into[A])
      .onError {
        case org.http4s.client.UnexpectedStatus(Status.Unauthorized, _, _) =>
          error"GitHub token is either expired or absent, please check `token` key in src/main/resources/application.conf"
        case other => error"$other"
      }
      .handleErrorWith(_ => warn"returning default value: $default for $uri due to unexpected error" as default)

  private def getContributorsPerRepo(
    client: Client[IO],
    repoName: RepoName,
    orgName: String,
    contributors: Vector[Contributor] = Vector.empty[Contributor],
    page: Int = 1,
    isEmpty: Boolean = false,
  )(using token: Token): IO[Vector[Contributor]] =
    if ((page > 1 && contributors.size % 100 != 0) || isEmpty) IO.pure(contributors)
    else {
      uri(contributorsUrl(repoName.value, orgName, page))
        .flatMap { contributorUri =>
          for {
            _ <- info"requesting page: $page of $repoName contributors"
            newContributors <- fetch[Vector[Contributor]](contributorUri, client, Vector.empty)
            _ <- info"fetched ${newContributors.size} contributors on page: $page for $repoName"
            next <- getContributorsPerRepo(
              client = client,
              repoName = repoName,
              orgName = orgName,
              contributors = contributors ++ newContributors,
              page = page + 1,
              isEmpty = newContributors.isEmpty,
            )
          } yield next
        }
    }

  def routes(client: Client[IO], token: Token): HttpRoutes[IO] = {
    given tk: Token = token

    HttpRoutes.of[IO] {
      case GET -> Root => Ok("hi :)")
      case GET -> Root / "org" / orgName =>
        for {
          start <- IO.realTime
          _ <- info"accepted $orgName"
          publicReposUri <- uri(publicRepos(orgName))
          _ <- info"fetching the amount of available repositories for $orgName"
          publicRepos <- fetch[PublicRepos](publicReposUri, client, PublicRepos.Empty)
          _ <- info"amount of public repositories for $orgName: $publicRepos"
          pages = (1 to (publicRepos.value / 100) + 1).toVector
          repositories <- pages.parUnorderedFlatTraverse { page =>
            uri(repos(orgName, page))
              .flatMap(fetch[Vector[RepoName]](_, client, Vector.empty[RepoName]))
          }
          _ <- info"$publicRepos repositories were collected for $orgName"
          _ <- info"starting to fetch contributors for each repository"
          contributors <- repositories
            .parUnorderedFlatTraverse { repoName =>
              for {
                _ <- info"fetching contributors for $repoName"
                contributors <- getContributorsPerRepo(client, repoName, orgName)
                _ <- info"fetched amount of contributors for $repoName: ${contributors.size}"
              } yield contributors
            }
            .map {
              _.groupMapReduce(_.login)(_.contributions)(_ + _).toVector
                .map(Contributor(_, _))
                .sortWith(_.contributions > _.contributions)
            }
          _ <- info"returning aggregated & sorted contributors for $orgName"
          response <- Ok(Contributions(contributors.size, contributors).toJson)
          end <- IO.realTime
          _ <- info"aggregation took ${(end - start).toSeconds} seconds"
        } yield response
    }
  }

  object syntax {
    extension (self: String) def into[A](using r: Reads[A]): A = Json.parse(self).as[A]
    extension [A](self: A) def toJson(using w: Writes[A]): String = Json.prettyPrint(w.writes(self))
  }

  object domain {

    import play.api.libs.json.*
    import play.api.libs.*
    import Reads.{IntReads, StringReads}

    opaque type PublicRepos = Int
    object PublicRepos {
      val Empty: PublicRepos = PublicRepos.apply(0)
      def apply(value: Int): PublicRepos = value
    }
    extension (repos: PublicRepos) def value: Int = repos
    given ReadsPublicRepos: Reads[PublicRepos] = (__ \ "public_repos").read[Int].map(PublicRepos.apply)

    opaque type RepoName = String
    object RepoName {
      def apply(value: String): RepoName = value
    }
    extension (repoName: RepoName) def value: String = repoName
    given ReadsRepo: Reads[RepoName] = (__ \ "name").read[String].map(RepoName.apply)

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
