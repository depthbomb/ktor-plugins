| Name | Description |
| --- | --- |
| [RateLimiter](ratelimiter/README.md) | Share a request quota across routes, charge different costs, or acquire tokens from application code. |
| [CSRF tokens](csrf/README.md) | Issue a session-bound token when rendering a form and validate it before a state change. |
| [Signed URLs](signedurls/README.md) | Issue a link for a specific path and query, optionally set an expiration, and validate it in a route. |
| [Abort](abort/README.md) | Stop a route by throwing an HTTP exception with a status and an optional message. |

## Build

The project uses Kotlin 2.4.21, Ktor 3.6.2, and a JDK 21 toolchain. Run `./gradlew build` from the project root, or
`.\gradlew.bat build` in PowerShell. To test one plugin, run `./gradlew :ratelimiter:test`, `./gradlew :csrf:test`,
`./gradlew :signedurls:test`, or `./gradlew :abort:test`.
