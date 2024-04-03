(ns ^:no-doc rad.impl.concurrent
  (:require [cognitect.anomalies :as-alias anomalies]
            [rad.log :refer [log]])
  (:import (java.util.concurrent CompletableFuture ThreadFactory)
           (java.util.function Supplier)))

(def ^:private logging-exception-handler
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ thread ex]
      (log :severe
        :uncaught-exception-in-thread
        {:thread (.getName thread) :ex ex}))))

(defn thread-factory
  [name-suffix]
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto
        (Thread. runnable (format "me.flowthing/rad-%s" (name name-suffix)))
        (.setUncaughtExceptionHandler logging-exception-handler)))))

(defmacro with-lock
  "Given a java.util.concurrent.locks.Lock and a body, execute the body while
  holding the given lock."
  [lock & body]
  `(do
     (.lock ~lock)
     (try
       (do ~@body)
       (finally
         (.unlock ~lock)))))

(defmacro async
  "Given a java.util.concurrent.Executor and a body, execute the body in the
  executor and return a java.util.concurrent.CompletableFuture representing
  its result."
  [executor & body]
  `(CompletableFuture/supplyAsync
     (reify
       Supplier
       ~(list 'get '[this] (cons 'do body)))
     ~executor))
