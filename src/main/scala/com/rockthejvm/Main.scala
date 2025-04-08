package com.rockthejvm

import caching.*
import config.*
import domain.*
import uris.*
import serde.syntax.*
import serde.given
import org.http4s.Header.Raw
import org.http4s.client.Client
import org.http4s.{EntityDecoder, Header, HttpRoutes, Method, ParseFailure, Request, Response, StaticFile, Status, Uri}
import org.typelevel.ci.CIString
import pureconfig.*
import pureconfig.generic.derivation.default.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.dsl.*
import org.http4s.*
import org.http4s.dsl.io.*
import fs2.io.file.Path
import cats.syntax.all.*
import cats.instances.all.*
import play.api.libs.json.*
import play.api.libs.*
import cats.implicits.*
import cats.effect.{IO, IOApp, Ref, Resource}
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.syntax.LoggerInterpolator
import com.comcast.ip4s.*
import com.rockthejvm.Main.getContributorsPerRepo
import com.rockthejvm.caching.Cache.Value
import org.http4s.server.middleware.CORS

import java.time.Instant
import scala.concurrent.duration.*
import scala.concurrent.Future

object Main extends IOApp.Simple {

  given logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  override val run: IO[Unit] =
    (for {
      _ <- info"starting server".toResource
      appConfig <- IO.delay(ConfigSource.default.loadOrThrow[AppConfig]).toResource
      cache <- Cache.make(appConfig.cacheExpirationInMillis)
      client <- EmberClientBuilder.default[IO].build
      _ <- startServer(appConfig, cache, client)
      _ <- cleanCachePeriodically(cache, appConfig.cacheExpirationInMillis.millis)
    } yield ()).useForever

  private def startServer(appConfig: AppConfig, cache: Cache, client: Client[IO]) =
    EmberServerBuilder
      .default[IO]
      .withHost(appConfig.serverConfig.host)
      .withPort(appConfig.serverConfig.port)
      .withHttpApp(CORS(routes(cache, client, appConfig.token)).orNotFound)
      .build

  private def cleanCachePeriodically(cache: Cache, cacheExpiration: FiniteDuration): Resource[IO, Unit] =
    (for {
      _ <- IO.sleep(cacheExpiration)
      _ <- info"running cache cleanup..."
      now <- IO.realTimeInstant
      _ <- cache.clear(now)
    } yield ()).foreverM.background.void

  private def fetch[A: Reads](uri: Uri, client: Client[IO], default: => A)(using token: Token): IO[A] =
    client
      .expect[String] {
        Request[IO](Method.GET, uri)
          .putHeaders(Raw(CIString("Authorization"), s"Bearer $token"))
      }
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
      uri(uris.contributors(repoName.value, orgName, page))
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

  private def routes(cache: Cache, client: Client[IO], token: Token): HttpRoutes[IO] = {
    given tk: Token = token

    HttpRoutes.of[IO] {
      case request @ GET -> Root =>
        StaticFile
          .fromPath(Path("src/main/resources/index.html"), Some(request))
          .getOrElseF(NotFound())
      case GET -> Root / "health" => Ok("I am alive!")
      case GET -> Root / "org" / orgName =>
        for {
          cachedValue <- cache.getOpt(orgName)
          response <- cachedValue match {
            case Some(cached) =>
              for {
                response <- Ok(cached.contributions)
                _ <- info"returning cached contributions for $orgName"
              } yield response
            case None =>
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
                contributions = Contributions(contributors.size, contributors).toJson
                now <- IO.realTimeInstant
                _ <- info"caching contributions for $orgName"
                _ <- cache.put(orgName, Value(now, contributions)).start
                response <- Ok(contributions)
                end <- IO.realTime
                _ <- info"aggregation took ${(end - start).toSeconds} seconds"
              } yield response
          }
        } yield response
    }
  }
}
