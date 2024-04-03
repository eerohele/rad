(ns rad.dev.proxy
  (:require [rad.impl.concurrent :refer [async] :as concurrent])
  (:import (java.io InputStream OutputStream)
           (java.net InetAddress ServerSocket Socket SocketException)
           (java.util.concurrent Executors)))

(defmacro ^:private until-interruption
  "Given a body, execute the body until the current thread is interrupted."
  [& body]
  `(while (not (.isInterrupted (Thread/currentThread)))
     (do ~@body)))

(defn run
  [upstream-host upstream-port
   & {:keys [port] :or {port 9736}}]
   (let [acceptor (Executors/newVirtualThreadPerTaskExecutor)
         worker (Executors/newFixedThreadPool 2 (concurrent/thread-factory :worker))
         server-socket (ServerSocket. port 0 (InetAddress/getLoopbackAddress))
         upstream-socket (Socket. ^String upstream-host ^long upstream-port)]
     (async acceptor
       (try
         (until-interruption
           (let [client-socket (.accept server-socket)]
             (async worker
               (with-open [^OutputStream proxy->client (.getOutputStream client-socket)
                           upstream->proxy (.getInputStream upstream-socket)]
                 (try
                   (.transferTo upstream->proxy proxy->client)
                   (finally
                     (.close client-socket)
                     (.close upstream-socket)))))

             (async worker
               (with-open [proxy->upstream (.getOutputStream upstream-socket)
                           ^InputStream client->proxy (.getInputStream client-socket)]
                 (.transferTo client->proxy proxy->upstream)))))
         (catch SocketException _)))
     (fn []
       (.close upstream-socket)
       (.close server-socket)
       (.shutdownNow worker)
       (.shutdownNow acceptor)
       :stopped)))

(comment
  (def proxy (run "localhost" 6379))
  (proxy)
  ,,,)
