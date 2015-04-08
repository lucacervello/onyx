(ns ^:no-doc onyx.messaging.protocol-aeron
  (:require [taoensso.timbre :as timbre])
  (:import [java.util UUID]
           [uk.co.real_logic.agrona.concurrent UnsafeBuffer]
           [uk.co.real_logic.agrona DirectBuffer MutableDirectBuffer]))

;;;;;;
;; Constants

;; id uuid 
(def completion-msg-length (int 16))

;; id uuid
(def retry-msg-length (int 16))

;; id uuid, completion-id uuid, ack-val long
(def ack-msg-length (int 40))

;; message length without nippy segments
;; id (uuid), acker-id (uuid), completion-id (uuid), ack-val (long)
(def message-base-length (int 56))

;; messages with 0 messages in it
(def messages-base-length (int 4))

(defn write-uuid [^MutableDirectBuffer buf offset ^UUID uuid]
  (.putLong buf offset (.getMostSignificantBits uuid))
  (.putLong buf (+ 8 offset) (.getLeastSignificantBits uuid)))

(defn get-uuid [^UnsafeBuffer buf offset]
  (let [msb (.getLong buf offset)
        lsb (.getLong buf (+ 8 offset))]
    (java.util.UUID. msb lsb)))

(defn build-acker-message [^UUID id ^UUID completion-id ^long ack-val]
  (let [buf (UnsafeBuffer. (byte-array ack-msg-length))]
    (write-uuid buf 0 id)
    (write-uuid buf 16 completion-id)
    (.putLong buf 32 ack-val)
    buf))

(defn read-acker-message [^UnsafeBuffer buf offset]
  (let [id (get-uuid buf offset)
        completion-id (get-uuid buf (+ offset 16))
        ack-val (.getLong buf (+ offset 32))]
    {:id id
     :completion-id completion-id
     :ack-val ack-val}))

(defn read-completion [^UnsafeBuffer buf offset]
  (get-uuid buf offset))

(defn read-retry [^UnsafeBuffer buf offset]
  (get-uuid buf offset))

(defn build-completion-msg-buf [id] 
  (let [buf (UnsafeBuffer. (byte-array completion-msg-length))] 
    (write-uuid buf 0 id)
    buf))

(defn build-retry-msg-buf [id]
  (let [buf (UnsafeBuffer. (byte-array retry-msg-length))] 
    (write-uuid buf 0 id)
    buf))

(defn write-message-meta [^MutableDirectBuffer buf offset {:keys [id acker-id completion-id ack-val]}]
  (write-uuid buf offset id)
  (write-uuid buf (+ offset 16) acker-id)
  (write-uuid buf (+ offset 32) completion-id)
  (.putLong buf (+ offset 48) ack-val))

(defn read-message-meta [^UnsafeBuffer buf offset]
  (let [id (get-uuid buf offset)
        acker-id (get-uuid buf (+ offset 16))
        completion-id (get-uuid buf (+ offset 32))
        ack-val (.getLong buf (+ offset 48))]
    {:id id 
     :acker-id acker-id 
     :completion-id completion-id 
     :ack-val ack-val}))

(def message-count-size 4)

(defn meta-message-offsets [start-pos cnt]
  (reductions + start-pos (repeat cnt message-base-length)))

(defn build-messages-msg-buf [compress-f messages]
  (let [meta-offsets (meta-message-offsets message-count-size (count messages))
        message-payloads (compress-f (map :message messages))
        buf-size (+ (last meta-offsets) 
                    (alength message-payloads))
        buf (UnsafeBuffer. (byte-array buf-size))] 
    (.putInt buf 0 (int (count messages))) ; number of messages
    (doseq [[msg offset] (map vector messages meta-offsets)]
      (write-message-meta buf offset msg))
    (.putBytes buf (last meta-offsets) message-payloads)
    [buf-size buf]))

(defn read-messages-buf [decompress-f ^UnsafeBuffer buf offset length]
  (let [message-count (.getInt buf offset)
        meta-offsets (meta-message-offsets (+ message-count-size offset) message-count)
        metas (doall (map (partial read-message-meta buf)
                          (butlast meta-offsets)))
        segments-size  (- (+ offset length) (last meta-offsets))
        message-payload-bytes (byte-array segments-size)
        _ (.getBytes buf (last meta-offsets) message-payload-bytes)
        message-payloads (decompress-f message-payload-bytes)]
    (map (fn [m message]
           (assoc m :message message))
         metas
         message-payloads)))