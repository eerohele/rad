(ns ^:no-doc rad.impl.connection
  (:require [cognitect.anomalies :as-alias anomalies]
            [rad.impl.anomaly :as anomaly :refer [anomaly!]]
            [rad.impl.resilience :as resilience :refer [with-retry]]
            [rad.log :refer [log]]
            [rad.resp :as resp])
  (:import (java.io Closeable BufferedInputStream BufferedOutputStream)
           (java.net InetSocketAddress Socket)))

(set! *warn-on-reflection* true)

(defprotocol ^:private Connection
  (input [this])
  (output [this])
  (shutdown-output [this])
  (output-shutdown? [this])
  (try-restore [this]))

(defn ^:private -establish
  [host port {:keys [tcp-no-delay] :or {tcp-no-delay false}}]
  (try
    (let [address (InetSocketAddress. ^String host ^long port)
          socket (doto (Socket.)
                   (.setKeepAlive true)
                   ;; https://brooker.co.za/blog/2024/05/09/nagle.html
                   (.setTcpNoDelay tcp-no-delay))
          _ (.connect socket address)
          out (-> socket .getOutputStream BufferedOutputStream.)
          in (-> socket .getInputStream BufferedInputStream.)
          ^InetSocketAddress remote-addr (.getRemoteSocketAddress socket)
          ^InetSocketAddress local-addr (.getLocalSocketAddress socket)]

      (log :info :connection-established
        {:remote {:host (.getHostName remote-addr)
                  :port (.getPort remote-addr)}
         :local {:host (.getHostName local-addr)
                 :port (.getPort local-addr)}})

      (resp/write ["HELLO" "3" "SETNAME" "rad.api"] out)

      (.flush out)

      (let [response (resp/read in resp/bytes->str)]
        (assert (= 3 (get response "proto"))
          (format "Unexpected response to HELLO: %s" response)))

      {:socket socket
       :in in
       :out out})
    (catch Exception ex
      (anomaly! (ex-message ex) ::anomalies/unavailable {:ex ex}))))

(defn establish
  [host port & {:keys [options retry-options]}]
  (let [connection (atom
                     (with-retry retry-options
                       (-establish host port options)))]
    (reify
      Connection
      (input [_] (:in @connection))
      (output [_] (:out @connection))
      (shutdown-output [_]
        (.shutdownOutput ^Socket (:socket @connection)))

      (output-shutdown? [_]
        (.isOutputShutdown ^Socket (:socket @connection)))

      (try-restore [_]
        (with-retry retry-options
          (let [new-connection (-establish host port options)]
            (swap! connection
              (fn [{:keys [socket]}]
                (some-> ^Closeable socket .close)
                new-connection)))))

      Closeable
      (close [_]
        (.close ^Socket (:socket @connection))))))
