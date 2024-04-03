# Rād

Rad is a Redis client for Clojure.

## Getting started

To print "Hello, world!" using Rad:

1. Have a Redis server listening on `localhost:6379`.
1. Add Rad as a dependency of your project.
1. In the REPL, do:

    ```clojure
    user=> (require '[rad.api :as rad])
    nil
    user=> (def redis (rad/client :decode rad/bytes->str))
    #'user/redis
    user=> @(redis [:ECHO "Hello, world!"])
    "Hello, world!"
    ```

## Sending commands to Redis

To send commands to Redis, call the client function with one or more command vectors.

For example:

```clojure
;; One command vector
user=> @(redis [:ECHO "Hello, world!"])
"Hello, world!"
;; Multiple command vectors (pipeline)
user=> @(redis [:INCR "n"] [:DECR "n"])
[1 0]
```

Every argument to the client function must be a vector. The client function does not support lists, sets, lazy seqs, or any other collection types.

The command name **must** be an upper-case simple keyword. That is, Rad only accepts `:HELLO`. Rad does **not** accept `:hello`, `:WORLD/HELLO`, `::HELLO`, `HELLO` (symbol), or `"HELLO"`, for example.

## Using spec validation

To avoid giving Rad bad inputs, I recommend enabling [clojure.spec assertion checks](https://clojure.org/guides/spec#_using_spec_for_validation) during development:

```clojure
user=> (require '[clojure.spec.alpha :as spec])
nil
user=> (spec/check-asserts true)
true
;; Rad does not support pub/sub commands in pipelines.
;;
;; When assert checking is enabled, this form yields an execution error.
user=> (redis [:PING] [:SUBSCRIBE "channel-1"] [:PING])
```

Conversely, I strongly recommend disabling clojure.spec assertion checks in production to prevent them from affecting Rad's performance.

You might also want to consider using a tool such as [Expound](https://github.com/bhb/expound) with Rad.

## Decoding Redis responses

With `:decode rad/bytes->str`, Rad decodes every [byte array](https://github.com/redis/redis-specifications/blob/master/protocol/RESP3.md#simple-types) Redis sends it into a UTF-8 string. Without the `:decode` option, Rad gives you byte arrays:

```clojure
user=> (def redis (rad/client))
#'user/redis
user=> @(redis [:ECHO "Hello, world!"])
#object["[B" 0x14d916ef "[B@14d916ef"]
```

If you want to use Rad to work with binary data, this is necessary. If you omit `:decode`, Rad will not decode *any* of the byte arrays that Redis sends it, though -- even ones that actually are UTF-8 strings.

Unfortunately, `#object["[B" 0x14d916ef "[B@14d916ef"]` is quite hard to decipher. If you want to work with binary data *and* be able to read the data Rad gets from Redis, you can use this arcane incantation:

```clojure
;; You probably only want to use this (quick and dirty) method at development-
;; time.
(defmethod print-method (Class/forName "[B")
  [^bytes v ^java.io.Writer w]
  (.write w "#b ")
  (binding [*print-readably* true]
    (print-method (String. v "UTF-8") w)))
```

This tells Clojure to print byte arrays as UTF-8 strings. After evaluating this `defmethod`, Rad yields a more readable response:

```clojure
user=> @(redis [:ECHO "Hello, world!"])
#b "Hello, world!"
```

A string representation of a UTF-8 byte array is not always useful, but in this case, it is almost always more useful than the default representation.

## Using Rad asynchronously

Rad is asynchronous. Every Rad response is a [`CompletableFuture`](https://docs.oracle.com/en%2Fjava%2Fjavase%2F21%2Fdocs%2Fapi%2F%2F/java.base/java/util/concurrent/CompletableFuture.html).

You can [`deref`](https://clojure.github.io/clojure/clojure.core-api.html#clojure.core/deref) a `CompletableFuture`. That means you can, if you want, just forget about `CompletableFuture`s, imagine Rad returns [promises](https://clojure.github.io/clojure/clojure.core-api.html#clojure.core/promise), and use [`@`](https://clojure.org/reference/reader#_deref) every time you need a value.

Or, if you need to, you can take advantage of the CompletableFuture API:

```clojure
;; Until functional interface interop lands in Clojure 1.12.
user=> (defn as-fn
         [f]
         (reify java.util.function.Function
           (apply [_ arg]
             (f arg))))
#'user/as-fn
;; Have Redis increment a number, then Clojure -- asynchronously, of course,
;; because that's extremely useful.
user=> (-> (redis [:INCR "n"]) (.thenApplyAsync (as-fn inc)) (.get))
2
```

Or, if you don't want asynchrony, you can make a "synchronous" client instead:

```clojure
user=> (def redis (comp deref (rad/client)))
#'user/redis
user=> (redis [:ECHO "Hello, world!"])
#b "Hello, world!"
```

## Using pipelining

To use [Redis pipelining](https://redis.io/docs/latest/develop/use/pipelining/), give the client more than one command vector:

```clojure
;; Send multiple commands to the Redis server, then wait for it to send
;; responses to all commands.
user=> @(redis [:ECHO "Hello, "] [:ECHO "world!"])
["Hello, " "world!"]
```

## Serialization

Rad does not automatically serialize Clojure data structures you give it, nor does it presume the byte arrays Redis sends it to be in any particular format.

If you want to store e.g. [Transit-encoded](https://github.com/cognitect/transit-format) data in Rad, you must encode and decode the data yourself. See [`transit.repl`](../examples/transit.repl) for an example on how to do that. Adapting the Transit example to another serialization format (e.g. Nippy) should be straightforward.

## Blocking commands

Every Rad client works over a single TCP connection to the Redis server. This means that if you run commands that block the connection (such as `BLPOP`), you block the entire client:

```clojure
user=> (time
         (do
           (redis [:BLPOP "none" 2])
           @(redis [:PING])))
"Elapsed time: 2020.623541 msecs"
"PONG"
```

If you need to run many blocking commands frequently, you might want to consider using a separate Rad client for those. You also might be able to use a generic connection pooling library (such as [Apache Commons Pool](https://commons.apache.org/proper/commons-pool/)) with Rad, but I haven't tried. Alternatively, you might want to use a Redis client library that has built-in support for connection pooling, such as [Carmine](https://github.com/taoensso/carmine) or [Jedis](https://github.com/redis/jedis).

## Logging

> [!CAUTION]
> Rad's logging implementation is unfinished and subject to change.

Rad logs using [java.util.logging](https://docs.oracle.com/en/java/javase/21/core/java-logging-overview.html). The current implementation is unfinished, however. I'm thinking users can elect to route logs e.g. to SLF4J using [jul-to-slf4j bridge](https://www.slf4j.org/legacy.html#jul-to-slf4j), but I haven't really thought the whole thing through.

To just get some logs to show up, do e.g. `(rad.log/console-logging! :level :fine)`, I dunno.

(Logging seems to be by far the most complicated part of writing a library like this. I'm open to suggestions.)

## Pub/Sub

Rad does everything over a single TCP connection, including pub/sub.

For examples on how Rad's pub/sub support works, see [`pubsub.repl`](../examples/pubsub.repl).

For an example on how to route Redis pub/sub messages into a [core.async](https://github.com/clojure/core.async) channel, see [`pubsub_core_async.repl`](../examples/pubsub_core_async.repl).

> [!NOTE]
> Rad does not support subscribe or unsubscribe commands (e.g. `:SUBSCRIBE`, `:UNSUBSCRIBE`) as part of a [pipeline](#using-pipelining).
