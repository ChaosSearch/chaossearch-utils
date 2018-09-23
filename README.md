# chaossearch-utils
CHAOSSEARCH Utilities

Written in Scala.

* chaossearch.utils.io.UnixDomain.UDSClientTransport - A ClientTransport pattern for AKKA HTTP to allow HTTP transport over a Unix Domain Socket.

# To Build
```
./gradlew :jar
```

This will render the `jar` file:
```
build/libs/chaossearch-utils.jar
```

# Examples
## `chaossearch.utils.io.UnixDomain`
### `UDSClientTransport`

At ChaosSearch we use this `Akka-Http` `ClientTransport` pattern to access the `Docker Engine` Unix Domain socket for our swarm manager nodes.

The following snippets of code should give you a flavor of how to use this `ClientTransport` pattern to create a connection pool to the `Docker Engine`.

Typically the `Docker Engine` socket is created at `/var/run/docker.sock`. So first we need to create some transport settings for the connection pool.

```
  def transportSettings = ConnectionPoolSettings(system)
    .withConnectionSettings(ClientConnectionSettings(system))
    .withTransport(DomainSocket.createClientTransport("/var/run/docker.sock"))
    .withIdleTimeout(Duration.Inf)
    .withMaxConnections(1)
    .withMaxOpenRequests(1)
    .withPipeliningLimit(1)
```
In this use case we are assuming an `Actor` will be able to ensure ordely and atomic access to the socket. So we can confidently set the `MaxConnections`, `MaxOpenRequests` and `PipeliningLimit` to `1`.

We can now use the `transportSettings` to create the connection pool.

```
  val maxQueueDepth = 1024
  val queue = Source.queue[(HttpRequest,Promise[HttpResponse])](maxQueueDepth, OverflowStrategy.backpressure)
    .via(
      Http().newHostConnectionPool[Promise[HttpResponse]](
        host     = "chaossearch.domain.socket",
        port     = 80,
        settings = transportSettings
      )
    )
    .to(Sink.foreach {
      case (res,p) => p.complete(res)
    }).run()
```

Some things to note here, the `host` and `port` are needed by the API but will not be used by the underlying `clientTransport`. In fact if you dig on to the code you will see we set the local and remote addresses to nonsense values. There is no TCP involvd here and are hence ignored.

Now we can create an `HttpRequest`.

[The `Docker Engine` has a test interface in their REST API to verify in the engine is operational].(https://docs.docker.com/engine/api/v1.35/#operation/SystemPing)

In this example we are going to use it.

```
  val pingUri = Uri(
    scheme      = "http",
    authority   = Uri.Authority(host=Uri.Host("chaossearch.domain.socket"), port=80, userinfo=""),
    path        = Uri.Path("/v1.35/_ping")
  )
  val request = HttpRequest(uri = pingUri)
```

Now we can queue the `request` to the connection pool `queue`. We create a `Promise` to return an `HttpResponse`
```
  val p = Promise[HttpResponse]
```

Now we can offer up the `request`.
```
  val response = queue.offer((request,p))
    .flatMap { _ => p.future }
    .flatMap { case response =>
        response.entity.toStrict(5.seconds).map { case entity => response.withEntity(entity) }
    }
```

The response is a flattened `Future` of an `HttpResponse`. You can then map this. For example:
```
  response.map {
    case HttpResponse(StatusCodes.OK, _, entity, _) => {
      // If we got here then the Docker Engine is functional.
      entity.discardBytes()
      StatusCodes.OK
    }
    case HttpResponse(code, _, entity, _)  => ... // Handle the error case
  }
```
