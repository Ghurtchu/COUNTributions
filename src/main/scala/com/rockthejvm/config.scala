package com.rockthejvm

import com.comcast.ip4s.*
import pureconfig.ConfigReader
import pureconfig.error.{CannotConvert, ConvertFailure}
import pureconfig.generic.derivation.default.*

object config {

  opaque type Token = String

  object Token {
    def apply(value: String): Token = value
  }

  case class ServerConfig(
    host: Host,
    port: Port,
  ) derives ConfigReader

  given HostReader: ConfigReader[Host] =
    ConfigReader[String].emap { host =>
      Host
        .fromString(host)
        .toRight(CannotConvert(host, "Host", "incorrect host"))
    }

  given PortReader: ConfigReader[Port] =
    ConfigReader[String].emap { port =>
      Port
        .fromString(port)
        .toRight(CannotConvert(port, "Post", "incorrect port"))
    }

  case class AppConfig(
    token: Token,
    cacheExpirationInMillis: Long,
    serverConfig: ServerConfig,
  ) derives ConfigReader

}
