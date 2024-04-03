# Architecture

![A diagram describing Rad's high-level architecture.](architecture.svg "Rad architecture diagram.")

This is a description of how data flows inside Rad and between Rad and Redis.

1. The user creates a Rad client via `rad.api/client`.

   Rad establishes a new, persistent TCP connection to Redis and starts two threads:

   - **Reader**: a thread that continuously reads from the socket connected to Redis.
   - **Writer**: a thread that continuously writes everything it receives into the socket connected to Redis.

   Reader and Writer use queues to communicate with the rest of Rad.

   Redis sends clients two kinds of messages: **command responses** and [**push events**](https://redis.io/docs/latest/develop/reference/protocol-spec/#pushes). If Reader receives a command response, it puts it into the **receive queue** (`recvq`). If Reader receives a push event, it calls all callbacks registered for the push event in a separate (virtual) thread.

   Writer continuously takes commands from the **send queue** (`sendq`) and writes them into the socket that's connected to Redis.

1. The user calls the Rad client function (created via `rad.api/client`), passing x command vectors as arguments.
1. Rad spawns a [`CompletableFuture`](https://docs.oracle.com/en%2Fjava%2Fjavase%2F21%2Fdocs%2Fapi%2F%2F/java.base/java/util/concurrent/CompletableFuture.html) that takes x items from the receive queue and hands that `CompletableFuture` to the user.
1. Rad puts the commands the user gave it into the send queue.
1. Writer takes the commands from the send queue and writes them into the socket that's connected to Redis.
1. Reader reads the responses to the commands from the socket and puts them into the receive queue.
1. The user (presumably, eventually) dereferences the `CompletableFuture` to acquire the responses the commands they sent.

 If Redis sends Rad a push event instead of a command response, Reader calls the callback functions (possibly) associated with the event.
