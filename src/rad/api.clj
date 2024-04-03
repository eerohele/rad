(ns rad.api
  "A Redis client."
  {:author "Eero Helenius"
   :license "MIT"}
  (:require [cognitect.anomalies :as-alias anomalies]
            [clojure.spec.alpha :as spec]
            [rad.impl.anomaly :as anomaly :refer [anomaly!]]
            [rad.impl.concurrent :as concurrent :refer [async with-lock]]
            [rad.impl.connection :as conn]
            [rad.log :refer [log]]
            [rad.resp :as resp]
            [rad.specs :as specs])
  (:import (clojure.lang BigInt Named)
           (java.io Closeable IOException EOFException OutputStream)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util.concurrent ArrayBlockingQueue Executor Executors TimeUnit)
           (java.util.concurrent.locks ReentrantLock)))

(set! *unchecked-math* :warn-on-boxed)
(set! *warn-on-reflection* true)

(defn bytes->str
  "Decode a byte array into a UTF-8 string."
  ^String [^bytes bs]
  (String. bs StandardCharsets/UTF_8))

(defmacro ^:private until-interruption
  "Given a body, execute the body until the current thread is interrupted."
  [& body]
  `(while (not (.isInterrupted (Thread/currentThread)))
     (do ~@body)))

(defmacro ^:private submit
  "Submit a body into the given java.util.concurrent.ExecutorService."
  [executor & body]
  `(.submit ~executor ^Callable (^:once fn* [] (do ~@body))))

(defmacro ^:private execute
  "Execute a body in the given java.util.concurrent.Executor."
  [executor & body]
  `(.execute ~executor (^:once fn* [] (do ~@body))))

(defmacro ^:private execute-fns
  "Given an executor, a coll of fns, and any number of args to each fn, execute
  each function using the executor, passing the args to each function call."
  [executor fns & args]
  `(run!
     (fn [f#]
       (.execute ~executor
         (^:once fn* []
          (try
            (f# ~@args)
            (catch Exception ex#
              (log :severe :pub-sub/callback-error ex#))))))
     ~fns))

;; Could make this protocol public, e.g. to serialize
;; clojure.lang.IPersistentCollection using Transit, if necessary.
(defprotocol ^:private Argument
  "A Redis command argument."
  (^:private serialize [this]))

(extend-protocol Argument
  ;; Byte array must be the first type. Otherwise, you'll get:
  ;;
  ;; Don't know how to create ISeq from: java.lang.Character
  (Class/forName "[B") (serialize [this] this)
  BigInt (serialize [this] (-> this .toBigInteger .toString))
  BigInteger (serialize [this] (-> this .toString))
  Byte (serialize [this] (-> this Byte/toString))
  Double (serialize [this] (-> this Double/toString))
  Float (serialize [this] (-> this Float/toString))
  Integer (serialize [this] (-> this Integer/toString))
  Long (serialize [this] (-> this Long/toString))
  Named (serialize [this] (-> this name))
  Short (serialize [this] (-> this Short/toString))
  String (serialize [this] this))

(defn ^:private -attach-callback
  [chan->cbs cb channels]
  ;; When user calls [:SUBSCRIBE ...] without attaching :cb meta, cb is nil.
  ;; We only want to register a callback when cb is not nil.
  (when cb
    (swap! chan->cbs
      (fn [ch->cbs]
        (reduce
          (fn [ch->cbs ch]
            (update ch->cbs ch (fnil conj #{}) cb))
          ch->cbs
          channels)))))

(defn attach-callback
  "Given a client, a callback function, and any number of channels, attach a
  callback function to each channel.

  When a message is published into any of the channels, Rad calls the callback
  function with the message."
  [client cb & channels]
  (-attach-callback (-> client meta :chan->cbs) cb channels)
  nil)

(defn ^:private -detach-callback
  [chan->cbs cb channels]
  (when cb
    (swap! chan->cbs
      (fn [ch->cbs]
        (reduce
          (fn [ch->cbs ch]
            (update ch->cbs ch disj cb))
          ch->cbs
          channels)))))

(defn detach-callback
  "Given a client, a callback function, and any number of channels, detach the
  callback function from each channel."
  [client cb & channels]
  (-detach-callback (-> client meta :chan->cbs) cb channels)
  nil)

(defn ^:private -detach-callbacks
  [chan->cbs chs]
  (swap! chan->cbs
    (fn [ch->cbs]
      (reduce
        (fn [ch->cbs ch]
          (dissoc ch->cbs ch))
        ch->cbs
        ;; If the channel list is empty, unsubscribe from all channels.
        (or (not-empty chs) (keys ch->cbs))))))

(defn ^:private update-pub-sub-state!
  "Given an atom that contains a map of Redis pub/sub channel name to callback
  function and a command vector, update the atom to attach or detach callback
  functions per the command vector.

  Returns true if pub/sub state was updated, else false."
  [chan->cbs cmdvec]
  (case (nth cmdvec 0)
    (:SUBSCRIBE :PSUBSCRIBE :SSUBSCRIBE)
    (let [chs (subvec cmdvec 1)
          ;; Pick up callback function from cmdvec meta.
          {:keys [cb]} (meta cmdvec)]
      (-attach-callback chan->cbs cb chs)
      true)

    (:UNSUBSCRIBE :PUNSUBSCRIBE :SUNSUBSCRIBE)
    ;; Can't detach callbacks here. If we did, Rad wouldn't call the callback
    ;; function with the unsubscription confirmation message.
    true

    false))

(defn ^:private handle-push-event!
  [^Executor executor chan->cbs {:keys [type data] :as event}]
  (case type
    ("subscribe" "psubscribe")
    (let [channel (nth data 0)
          cbs (some-> chan->cbs deref (get channel))
          subs (nth data 1)]
      (log :fine :pub-sub/subscribe {:channel channel :subs subs})
      ;; Execute callbacks in parallel.
      ;;
      ;; cbs is a hash set of functions, so the order in which Rad calls the
      ;; callbacks is not deterministic.
      (execute-fns executor cbs event))

    ("unsubscribe" "punsubscribe")
    (let [channel (nth data 0)
          cbs (some-> chan->cbs deref (get channel))
          subs (nth data 1)]
      (log :fine :pub-sub/unsubscribe {:channel channel :subs subs})
      (execute-fns executor cbs event)

      ;; :UNSUBSCRIBE detaches all callbacks from the channel.
      (-detach-callbacks chan->cbs channel))

    "message"
    (let [channel (nth data 0)
          cbs (some-> chan->cbs deref (get channel))]
      (log :finest :pub-sub/message {:channel channel})
      (execute-fns executor cbs event))

    "pmessage"
    (let [pattern (nth data 0)
          channel (nth data 1)
          cbs (some-> chan->cbs deref (get pattern))]
      (log :finest :pub-sub/pmessage {:pattern pattern :channel channel})
      (execute-fns executor cbs event))

    (log :warning :unknown-push-event {:message event})))

(defn client
  "Connect to a Redis server.

  Options:

    :host (string, default: \"localhost\")
      The hostname of the Redis server to connect to.

    :port (long, default: 6379)
      The port number of the Redis server to connect to.

    :decode (function, default: identity)
      A function of one argument that accepts a byte array and returns any
      value.

      Rad uses this function to decode all binary data that Redis sends it.

      To tell Rad to decode all byte arrays Redis sends it into UTF-8 strings,
      use rad.api/bytes->str.

    :sendq-capacity (long, default: 128)
      The maximum capacity of Rad's send queue.

      If this queue is full, calling the function this function returns blocks
      until there's room in the queue.

    :send-timeout (java.time.Duration, default: \"PT1M\")
      The duration to wait for availability in the send queue.

      If this duration expires, you're probably sending commands faster than
      Rad or Redis can handle them.

  Returns a Redis client.

  When called with zero arguments, closes the client and all resources it holds
  on to.

  When called with one or more arguments, executes Redis commands.

  Blocks iff send queue is full."
  [& {:keys [^String host
             ^long port
             decode
             ^Duration send-timeout
             sendq-capacity]
      :or {host "localhost"
           port 6379
           decode identity
           send-timeout (Duration/ofSeconds 60)
           sendq-capacity 128}}]

  (let [send-timeout-ms (.toMillis send-timeout)

        conn (conn/establish host port :retry-options {:max-retries 50})

        ;; The reason we use virtual threads here is that IO operations on
        ;; java.net.Socket are interruptiple if the thread doing the IO
        ;; is a virtual thread.
        exec (Executors/newVirtualThreadPerTaskExecutor)
        exec-handler (Executors/newSingleThreadExecutor (concurrent/thread-factory :exec-handler))

        sendq (ArrayBlockingQueue. sendq-capacity)
        recvq (ArrayBlockingQueue. 32)
        suspq (ArrayBlockingQueue. 1)

        lock (ReentrantLock.)

        chan->cbs (atom {})

        reader
        ;; Use .submit to support .cancel.
        (submit exec
          (until-interruption
            (try
              (let [msg (try
                          (resp/read (conn/input conn) decode)
                          ;; Rethrow IOException and EOFException as retryable exceptions.
                          (catch IOException ex
                            (anomaly! "IO exception when reading from socket" ::anomalies/unavailable {:ex ex}))
                          (catch EOFException ex
                            (anomaly! "Unexpected EOF when reading from socket" ::anomalies/unavailable {:ex ex})))]
                (cond
                  ;; Blocking queues don't permit nils. If Redis sends a nil
                  ;; response (e.g. to a GET on a key that doesn't exist), put
                  ;; ::nil into recvq and convert it back to nil before handing
                  ;; it back to the user.
                  (nil? msg)
                  (.put recvq ::nil)

                  (resp/push-event? msg)
                  (execute exec (handle-push-event! exec chan->cbs msg))

                  :else
                  (.put recvq msg)))
              (catch Exception ex
                ;; If reading from the socket fails, tell the writer to stop
                ;; writing, too.
                (conn/shutdown-output conn)

                (log :warning :read-error ex)

                (if (anomaly/retryable? ex)
                  (do
                    (conn/try-restore conn)
                    ;; If we successfully reconnected to the Redis server,
                    ;; signal to the writer that it is OK to continue writing
                    ;; to the socket.
                    (assert (zero? (.size suspq)))
                    (.put suspq ::ok))
                  ;; If the error we encounter is not retryable, tell the
                  ;; writer to abort, then rethrow.
                  (do
                    (log :severe :unrecoverable-connection-error ex)
                    (assert (zero? (.size suspq)))
                    (.put suspq ::abort)
                    (throw ex)))))))

        writer
        (submit exec
          (until-interruption
            (let [commands (.take sendq)]
              (run!
                (fn [cmdvec]
                  ;; If the reader receives an end of stream it shuts down
                  ;; the socket output.
                  ;;
                  ;; This way, the writer won't attempt to write into a dead
                  ;; socket.
                  ;;
                  ;; (Writing to a dead socket will not, understandably,
                  ;; throw an exception. After all, the writer has no way of
                  ;; knowing whether someone is actually reading the bytes it
                  ;; sends.)
                  (when (conn/output-shutdown? conn)
                    ;; Wait for the reader's signal that it is OK to
                    ;; proceed writing.
                    ;;
                    ;; If the reader sends an ::abort, stop writing.
                    (when (identical? ::abort (.take suspq))
                      (anomaly! (format "Unable to connect to Redis server (%s:%d)" host port)
                        ::anomalies/unavailable)))

                  (try
                    (resp/write (mapv serialize cmdvec) (conn/output conn))

                    (catch IOException ex
                      (log :severe :unrecoverable-connection-error ex)
                      (throw ex))))
                commands)

              (.flush ^OutputStream (conn/output conn)))))

        close
        (fn []
          (-detach-callbacks chan->cbs nil)
          (.cancel writer true)
          (.cancel reader true)
          (.shutdown exec)
          (.shutdown exec-handler)
          ;; TODO: Should these be configurable?
          (.awaitTermination exec 1 TimeUnit/MINUTES)
          (.awaitTermination exec-handler 1 TimeUnit/MINUTES)
          (.close ^Closeable conn))

        client
        (fn
          ([] (close))
          ([& commands]
           (spec/assert ::specs/commands commands)

           ;; If the user gives us a command that warrants an update of
           ;; the pub/sub state of the client (e.g. :SUBSCRIBE or
           ;; :UNSUBSCRIBE), update the state now.
           ;;
           ;; Rad does not support :SUBSCRIBE or :UNSUBSCRIBE in
           ;; pipelines. We can therefore just grab the first command
           ;; here and ignore the rest.
           (when (update-pub-sub-state! chan->cbs (nth commands 0))
             ;; Redis doesn't send responses to SUBSCRIBE or UNSUBSCRIBE.
             ;; Rad requires every command to have a response, though, so
             ;; we'll emulate one here.
             (.put recvq ::nil))

           (let [cmd-count (count commands)]
             ;; This lock is necessary to prevent one thread from spawning a
             ;; response handler for another thread's request.
             ;;
             ;; That is, this lock ensures that the thread that spawns the
             ;; response handler is the same thread that puts the commands into
             ;; the send queue.
             (with-lock lock
               ;; Asynchronously wait for the result of this command.
               (let [cf (async exec-handler
                          (loop [responses [] n 0]
                            (cond
                              (= n cmd-count)
                              ;; If the requests consists of a single command,
                              ;; unwrap the response to that command from the
                              ;; response vector.
                              (cond-> responses (= 1 cmd-count) peek)

                              :else
                              ;; Can't have a timeout on the take here; it would
                              ;; mess up response order (e.g. with [:BLPOP 2] and
                              ;; timeout of 1).
                              (let [response (.take recvq)
                                    response (if (identical? ::nil response) nil response)]
                                (recur (conj responses response) (inc n))))))]

                 (when-not (.offer sendq commands send-timeout-ms TimeUnit/MILLISECONDS)
                   (.cancel cf true)
                   (anomaly! "Send queue congested; aborting" ::anomalies/busy))

                 cf)))))]

    ;; Use metadata to store client state that other internal functions need to
    ;; be able to access.
    (with-meta client {:chan->cbs chan->cbs})))

(spec/fdef client :args ::specs/options :ret ::specs/client)

(comment
  (def ᚱ (client :decode bytes->str)) #_(ᚱ)

  @(ᚱ [:PING])
  @(ᚱ [:FLUSHDB])

  @(ᚱ
     [:ZADD "racer_scores" 10 "Norem"]
     [:ZADD "racer_scores" 12 "Castilla"]
     [:ZADD "racer_scores" 8 "Sam-Bodden" 10 "Royce" 6 "Ford" 14 "Prickett"]
     [:ZRANGE "racer_scores" 0 -1]
     [:ZREVRANGE "racer_scores" 0 -1])
  ,,,)
