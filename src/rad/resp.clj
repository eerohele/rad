(ns rad.resp
  "RESP3 wire protocol serialization.

  For more information on RESP3, see:

  https://redis.io/docs/latest/develop/reference/protocol-spec/"
  ;; This implementation assumes that the other communicating party is a Redis
  ;; server. It is not particularly good at informing users about syntax
  ;; errors.
  {:author "Eero Helenius"
   :license "MIT"}
  (:refer-clojure :exclude [read read-string])
  (:require [cognitect.anomalies :as-alias anomalies]
            [rad.impl.anomaly :refer [anomaly!]])
  (:import (clojure.lang BigInt Sequential Named IPersistentMap IPersistentSet)
           (java.io ByteArrayInputStream ByteArrayOutputStream InputStream OutputStream)
           (java.nio.charset StandardCharsets)))

(set! *unchecked-math* :warn-on-boxed)
(set! *warn-on-reflection* true)

;; Write

(defn bytes->str
  "Decode a byte array into a UTF-8 string."
  ^String [^bytes bs]
  (String. bs StandardCharsets/UTF_8))

(defmacro ^:private str->bytes
  "Given a string, return the UTF-8 bytes of the string."
  ^bytes [^String s]
  `(.getBytes ~s StandardCharsets/UTF_8))

(defn ^:private write-etb
  [^OutputStream out]
  (.write out 13 #_\return)
  (.write out 10 #_\newline))

(defrecord PushEvent [type data])

(defn push-event?
  "Return true if x is a PushEvent."
  [x]
  (instance? PushEvent x))

(defrecord VerbatimString [encoding data])

(defn verbatim-string?
  "Return true if x is a VerbatimString."
  [x]
  (instance? VerbatimString x))

(defprotocol ^:private Writable
  "An object that can be serialized using the RESP3 protocol."
  (write [this out]
    "Write a RESP3 type into a java.io.OutputStream."))

(defn ^:private write-num
  [^OutputStream out n]
  (.write out (-> n str str->bytes)))

(defn ^:private write-bytes
  [^OutputStream out ^long prefix ^bytes bs]
  (.write out prefix)

  (let [len (alength bs)]
    (write-num out len)
    (write-etb out)
    (.write out bs))

  (write-etb out))

(defn ^:private write-coll
  [^OutputStream out ^long prefix this]
  (let [len (count this)]
    (.write out prefix)
    (write-num out len)
    (write-etb out)
    (run! (fn [item] (write item out)) this)))

(extend-protocol Writable
  nil
  (write [_ ^OutputStream out]
    (.write out 95) #__
    (write-etb out))

  Boolean
  (write [this ^OutputStream out]
    (.write out 35) #_\#

    (case this
      true (.write out 116) #_\t
      false (.write out 102) #_\f)

    (write-etb out))

  String
  (write [this ^OutputStream out]
    (write (str->bytes this) out))

  Long
  (write [this ^OutputStream out]
    (.write out 58) #_\:
    (write-num out this)
    (write-etb out))

  Double
  (write [this ^OutputStream out]
    (.write out 44) #_\,

    (if (NaN? this)
      (.write out (str->bytes "nan"))
      (case this
        ##Inf (.write out (str->bytes "inf"))
        ##-Inf (.write out (str->bytes "-inf"))
        (write-num out this)))

    (write-etb out))

  BigInt
  (write [this ^OutputStream out]
    (.write out 40) #_\(
    (write-num out this)
    (write-etb out))

  Named
  (write [this ^OutputStream out]
    (write (name this) out))

  IPersistentMap
  (write [this ^OutputStream out]
    (let [len (count this)]
      (.write out 37) #_\%
      (write-num out len)
      (write-etb out)

      (when (seq this)
        (run! (fn [map-entry]
                (write (key map-entry) out)
                (write (val map-entry) out))
          this))))

  IPersistentSet
  (write [this out]
    (write-coll out 126 #_\~ (seq this)))

  Sequential
  (write [this out]
    (write-coll out 42 #_\* this))

  Exception
  (write [this ^OutputStream out]
    (.write out 45 #_\-)

    (let [^String s (ex-message this)
          len (.length s)]
      (loop [n 0]
        (when (< n len)
          (.write out (int (.charAt s n)))
          (recur (inc n)))))

    (write-etb out))

  VerbatimString
  (write [this out]
    (let [s (format "%s:%s" (:encoding this) (:data this))
          bs (str->bytes s)]
      (write-bytes out 61 #_\= bs)))

  PushEvent
  (write [this out]
    (let [xs (concat [(:type this)] (:data this))]
      (write-coll out 62 #_\> xs)))

  Object
  (write [this _]
    (anomaly! (format "Unsupported input of type: %s %s" (class this) this)
      ::anomalies/unsupported)))

(comment (write-string (->VerbatimString "foo" "bar")) ,,,)

(extend-protocol Writable
  (Class/forName "[B")
  (write [this ^OutputStream out]
    (write-bytes out 36 #_\$ this)))

;; Read

(declare read)

(defn ^:private eos!
  []
  (anomaly! "Unexpected end of stream" ::anomalies/unavailable))

(defn ^:private read-number
  ^long [^InputStream in]
  (loop [n 0 sign 1]
    (let [b (.read in)]
      (case b
        -1 (eos!)

        ;; Minus sign
        45 (recur n -1)

        13
        (do
          (.skipNBytes in 1)
          (* sign n))

        (recur (+ (* 10 n) (- b 48)) sign)))))

(defn ^:private read-big-number
  [^StringBuilder sb ^InputStream in]
  (loop []
    (let [b (.read in)]
      (case b
        -1 (eos!)

        13
        (do
          (.skipNBytes in 1) #_\newline
          (let [n (bigint (str sb))]
            (.setLength sb 0)
            n))

        (do
          (.append sb (char b))
          (recur))))))

(defn ^:private read-double
  [^StringBuilder sb ^InputStream in]
  (loop []
    (let [b (.read in)]
      (case b
        -1 (eos!)

        13
        (do
          (.skipNBytes in 1) #_\newline
          (let [s (str sb)]
            (.setLength sb 0)
            (case s
              "nan" ##NaN
              "inf" ##Inf
              "-inf" ##-Inf
              (parse-double s))))

        (do
          (.append sb (char b))
          (recur))))))

(defn ^:private read-array
  [sb ^InputStream in decode]
  (let [len (read-number in)]
    (loop [idx 0 ary (transient [])]
      (if (< idx len)
        (let [x (read sb in decode)]
          (recur (inc idx) (conj! ary x)))
        (persistent! ary)))))

(defn ^:private read-blob-string
  ([in decode] (read-blob-string in (read-number in) decode))
  ([^InputStream in ^long len decode]
   (case len
     -1 (eos!)
     (let [bs (.readNBytes in len)]
       (.skipNBytes in 2)
       (decode bs)))))

(defn write-string
  "Encode an object using the RESP3 protocol, return a string representation of
  the result."
  [x]
  (with-open [out (ByteArrayOutputStream.)]
    (write x out)
    (bytes->str (.toByteArray out))))

(comment
  (read-string "$0\r\n\r\n")
  (read-string "$5\r\nhello\r\n")
  (read-string "$6\r\nhello\r\n") ; bad
  (read-string "$5\r\nhell\r\n") ; bad
  ,,,)

(defn ^:private read-simple-string
  [^StringBuilder sb ^InputStream in]
  (loop []
    (let [n (.read in)]
      (case n
        1 (eos!)

        13
        (do
          (.skipNBytes in 1) ; \newline
          (let [s (str sb)]
            (.setLength sb 0)
            s))

        (do
          (.append sb (char n))
          (recur))))))

(defn ^:private read-simple-error
  [sb ^InputStream in]
  (ex-info (read-simple-string sb in) {}))

(defn ^:private read-null
  [^InputStream in]
  (.skipNBytes in 2)
  nil)

(defn ^:private read-boolean
  [^InputStream in]
  (let [b (.read in)]
    (.skipNBytes in 2)
    (case b
      116 true
      102 false
      (anomaly! (format "Expected \\t or \\f, got \\%s" (char b)) ::anomalies/fault))))

(comment
  (-> true write-string read-string)
  (-> false write-string read-string)
  (read-string "#x\r\n")
  ,,,)

(defn ^:private read-map
  "https://github.com/redis/redis-specifications/blob/master/protocol/RESP3.md#map-type"
  [sb ^InputStream in decode]
  (let [len (read-number in)]
    (loop [n 0 m (transient {})]
      (if (< n len)
        (let [k (read sb in decode)
              v (read sb in decode)]
          (recur (inc n) (assoc! m k v)))
        (persistent! m)))))

(defn ^:private read-set
  [sb ^InputStream in decode]
  (let [len (read-number in)]
    (loop [n 0 s (transient #{})]
      (if (< n len)
        (let [x (read sb in decode)]
          (recur (inc n) (conj! s x)))
        (persistent! s)))))

(comment
  (read-string "~5\r\n+orange\r\n+apple\r\n#t\r\n:100\r\n:999\r\n")
  ,,,)

(defn ^:private meta?
  [x]
  (instance? clojure.lang.IMeta x))

(defn ^:private read-attribute
  [sb ^InputStream in decode]
  (let [m (read-map sb in decode)
        x (read sb in decode)]
    ;; Ignore attributes if x can't hold meta.
    (cond-> x (meta? x) (with-meta m))))

(comment
  ((juxt identity meta) (read-string "|1\r\n+key-popularity\r\n%2\r\n$1\r\na\r\n,0.1923\r\n$1\r\nb\r\n,0.0012\r\n*2\r\n:2039123\r\n:9543892\r\n"))
  ((juxt identity meta) (read-string "|1\r\n+key-popularity\r\n%2\r\n$1\r\na\r\n,0.1923\r\n$1\r\nb\r\n,0.0012\r\n:1\r\n"))
  ,,,)

(defn ^:private read-blob-error
  [in]
  (let [^String s (read-blob-string in bytes->str)
        parts (.split s " " 2)]
    (ex-info (aget parts 1) {:code (aget parts 0)})))

(comment
  (read-string "!21\r\nSYNTAX invalid syntax\r\n")
  ,,,)

(defn ^:private read-verbatim-string
  [^InputStream in decode]
  (let [len (read-number in)
        encoding (decode (.readNBytes in 3))]
    (.skipNBytes in 1)
    (let [len (- len 4)
          data (read-blob-string in len decode)]
      (->VerbatimString encoding data))))

(comment
  (read-string "=15\r\ntxt:Some string\r\n" bytes->str)
  ,,,)

(defn ^:private read-push-event
  [sb in decode]
  (let [v (read-array sb in identity)
        ;; Regardless of how the user wants do decode byte arrays, we always
        ;; want to decode push event types as strings.
        ;;
        ;; This way, we can match on the push event type the same way,
        ;; regardless of which decoder the user uses.
        type (-> v (nth 0) bytes->str)
        data (subvec v 1)]
    ;; Similarly, we always want to handle pub/sub channel names as strings.
    (case type
      "message"
      (->PushEvent type
        [(-> data (nth 0) bytes->str)
         (-> data (nth 1) decode)])

      "pmessage"
      (->PushEvent type
        [(-> data (nth 0) bytes->str)
         (-> data (nth 1) bytes->str)
         (-> data (nth 2) decode)])

      ("subscribe"
       "unsubscribe"
       "psubscribe"
       "punsubscribe"
       "ssubscribe"
       "ssunsubscribe")
      (->PushEvent type
        [(-> data (nth 0) bytes->str)
         (-> data (nth 1))])

      (->PushEvent type (mapv decode data)))))

(defn read
  "Read a RESP3 type from a java.io.InputStream."
  ([in] (read (StringBuilder.) in identity))
  ([in decode] (read (StringBuilder.) in decode))
  ([sb ^InputStream in decode]
   (let [type (.read in)]
     (case type
       -1 (eos!)
       33 (read-blob-error in)
       35 (read-boolean in)
       36 (read-blob-string in decode)
       37 (read-map sb in decode)
       40 (read-big-number sb in)
       42 (read-array sb in decode)
       43 (read-simple-string sb in)
       44 (read-double sb in)
       45 (read-simple-error sb in)
       58 (read-number in)
       61 (read-verbatim-string in decode)
       62 (read-push-event sb in decode)
       95 (read-null in)
       124 (read-attribute sb in decode)
       126 (read-set sb in decode)
       ;; TODO:
       ;; - https://github.com/redis/redis-specifications/blob/master/protocol/RESP3.md#streamed-strings
       ;; - https://github.com/redis/redis-specifications/blob/master/protocol/RESP3.md#streamed-aggregated-data-types
       (anomaly! "Not implemented" ::anomalies/unsupported {:prefix (char type)})))))

(defn read-string
  "Read a RESP3 type from a string."
  ([s] (read-string s bytes->str))
  ([^String s decode]
   (with-open [in (ByteArrayInputStream. (str->bytes s))]
     (read in decode))))
