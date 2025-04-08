package com.rockthejvm

import cats.effect.{IO, Ref, Resource}
import com.rockthejvm.domain.Contributions

import java.time.Instant

object caching {

  import Cache.*

  final case class Cache(underlying: Ref[IO, State], cacheExpiration: Long) {
    def put(key: Key, value: Value): IO[Unit] =
      underlying.update(_.updated(key, value))

    def clear(now: Instant): IO[Unit] =
      underlying.update {
        _.filter { case (_, cached) =>
          java.time.temporal.ChronoUnit.MILLIS
            .between(cached.cachedAt, now) <= cacheExpiration
        }
      }

    def getOpt(key: String): IO[Option[Value]] =
      underlying.get.map(_.get(key))
  }

  object Cache {

    type Key = String
    type Json = String

    final case class Value(
      cachedAt: Instant,
      contributions: Json, // cached as String to avoid additional serialization
    )

    type State = Map[Key, Value]

    def make(cacheExpiration: Long): Resource[IO, Cache] = Resource.eval {
      for {
        ref <- IO.ref(Map.empty[String, Value])
      } yield Cache(ref, cacheExpiration)
    }
  }

}
