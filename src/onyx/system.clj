(ns onyx.system
  (:require [clojure.core.async :refer [chan close!]]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :refer [fatal info]]
            [onyx.static.logging-configuration :as logging-config]
            [onyx.peer.virtual-peer :refer [virtual-peer]]
            [onyx.peer.task-lifecycle :refer [task-lifecycle new-task-information]]
            ;[onyx.messaging.aeron :as am]
            [onyx.messaging.atom-messenger :as atom-messenger]
            [onyx.peer.peer-group-manager :as pgm]
            [onyx.monitoring.no-op-monitoring]
            [onyx.monitoring.custom-monitoring]
            [onyx.log.zookeeper :refer [zookeeper]]
            [onyx.state.bookkeeper :refer [multi-bookie-server]]
            [onyx.state.log.bookkeeper]
            [onyx.state.log.none]
            [onyx.state.filter.set]
            [onyx.state.filter.rocksdb]
            [onyx.log.commands.prepare-join-cluster]
            [onyx.log.commands.accept-join-cluster]
            [onyx.log.commands.abort-join-cluster]
            [onyx.log.commands.notify-join-cluster]
            [onyx.log.commands.exhaust-input]
            [onyx.log.commands.signal-ready]
            [onyx.log.commands.set-replica]
            [onyx.log.commands.group-leave-cluster]
            [onyx.log.commands.leave-cluster]
            [onyx.log.commands.submit-job]
            [onyx.log.commands.kill-job]
            [onyx.log.commands.gc]
            [onyx.log.commands.compact-bookkeeper-log-ids]
            [onyx.log.commands.assign-bookkeeper-log-id]
            [onyx.log.commands.deleted-bookkeeper-log-ids]
            [onyx.log.commands.add-virtual-peer]
            [onyx.scheduling.greedy-job-scheduler]
            [onyx.scheduling.balanced-job-scheduler]
            [onyx.scheduling.percentage-job-scheduler]
            [onyx.scheduling.balanced-task-scheduler]
            [onyx.scheduling.percentage-task-scheduler]
            [onyx.scheduling.colocated-task-scheduler]
            [onyx.windowing.units]
            [onyx.windowing.window-extensions]
            [onyx.windowing.aggregation]
            [onyx.triggers]
            [onyx.refinements]
            [onyx.compression.nippy]
            [onyx.plugin.onyx-plugin]
            [onyx.plugin.onyx-input]
            [onyx.plugin.onyx-output]
            [onyx.plugin.core-async]
            [onyx.extensions :as extensions]
            [onyx.interop]))

(def development-components [:monitoring :logging-config :log :bookkeeper])

(def peer-group-components [:logging-config :monitoring :messaging-group :peer-group-manager])

(def peer-components [:messenger :acking-daemon :virtual-peer])

(def client-components [:monitoring :log])

(def task-components
  [:task-lifecycle :task-information :task-monitoring :messenger])

(defn rethrow-component [f]
  (try
    (f)
    (catch Throwable e
      (fatal e)
      (throw (.getCause e)))))

(defrecord OnyxDevelopmentEnv []
  component/Lifecycle
  (start [this]
    (rethrow-component
     #(component/start-system this development-components)))
  (stop [this]
    (rethrow-component
     #(component/stop-system this development-components))))

(defn onyx-development-env
  ([peer-config]
     (onyx-development-env peer-config {:monitoring :no-op}))
  ([peer-config monitoring-config]
     (map->OnyxDevelopmentEnv
      {:monitoring (extensions/monitoring-agent monitoring-config)
       :logging-config (logging-config/logging-configuration peer-config)
       :bookkeeper (component/using (multi-bookie-server peer-config) [:log])
       :log (component/using (zookeeper peer-config) [:monitoring :logging-config])})))


(defrecord OnyxClient []
  component/Lifecycle
  (start [this]
    (rethrow-component
     #(component/start-system this client-components)))
  (stop [this]
    (rethrow-component
     #(component/stop-system this client-components))))

(defrecord OnyxTask [peer-site peer-state task-state]
  component/Lifecycle
  (start [component]
    (rethrow-component
     #(component/start-system component task-components)))
  (stop [component]
    (rethrow-component
     #(component/stop-system component task-components))))

(defn onyx-task
  [peer task]
  (map->OnyxTask
   {:peer peer
    :task task
    :task-information (component/using (new-task-information peer task) [])
    :task-monitoring (component/using (:monitoring peer) [:task-information])
    ;:task-pipeline (component/using (task-pipeline peer task)) [:task-information :task-monitoring :messenger]
    ;:task-lifecycle (component/using (task-lifecycle) [:task-pipeline :task-monitoring :messenger])
    :task-lifecycle (component/using (task-lifecycle peer task) [:task-information :task-monitoring :messenger])
    :messenger (component/using (atom-messenger/atom-messenger) [:peer])}))

(def peer-components
  [:monitoring :log :virtual-peer])

(defrecord OnyxPeer []
  component/Lifecycle
  (start [this]
    (rethrow-component
     #(component/start-system this peer-components)))
  (stop [this]
    (rethrow-component
     #(component/stop-system this peer-components))))

(defn onyx-peer
  ([peer-group]
     (onyx-peer peer-group {:monitoring :no-op}))
  ([{:keys [config] :as peer-group} monitoring-config]
     (map->OnyxPeer
      {:monitoring (extensions/monitoring-agent monitoring-config)
       :log (component/using (zookeeper config) [:monitoring])
       ;:messenger (component/using (am/aeron-messenger peer-group) [:monitoring])
       :virtual-peer (component/using (virtual-peer config peer-group onyx-task) [:monitoring :log ;:messenger
                                                                       ])})))

(def peer-group-components [:logging-config :messaging-group])

(defrecord OnyxPeerGroup []
  component/Lifecycle
  (start [this]
    (rethrow-component
     #(component/start-system this peer-group-components)))
  (stop [this]
    (rethrow-component
     #(component/stop-system this peer-group-components))))

(defn onyx-peer-group
  [config]
  (map->OnyxPeerGroup
   {:config config
    :logging-config (logging-config/logging-configuration config)
    :messaging-group (component/using (atom-messenger/atom-peer-group config) [:logging-config])
    ;:messaging-group (component/using (am/aeron-peer-group config) [:logging-config])
    }))

(defn onyx-client
  ([peer-config]
   (onyx-client peer-config {:monitoring :no-op}))
  ([peer-config monitoring-config]
   (map->OnyxClient
    {:monitoring (extensions/monitoring-agent monitoring-config)
     :log (component/using (zookeeper peer-config) [:monitoring])})))

(defn onyx-task
  [peer-state task-state]
  (map->OnyxTask
   {:logging-config (:logging-config peer-state)
    :peer-state peer-state
    :task-state task-state
    :task-information (new-task-information peer-state task-state)
    :task-monitoring (component/using
                      (:monitoring peer-state)
                      [:logging-config :task-information])
    :messenger (component/using (atom-messenger/atom-messenger) [:task-monitoring])
    :task-lifecycle (component/using
                     (task-lifecycle peer-state task-state)
                     [:task-information :task-monitoring])}))

(defn onyx-vpeer-system
  [group-ch outbox-ch peer-config messaging-group monitoring log group-id vpeer-id]
   (map->OnyxPeer
    {:group-id group-id
     :messaging-group messaging-group
     :logging-config (logging-config/logging-configuration peer-config)
     :monitoring monitoring 
     ; :messenger (component/using
     ;             (am/aeron-messenger peer-config messaging-group)
     ;             [:monitoring])
     :virtual-peer (component/using
                    (virtual-peer group-ch outbox-ch log peer-config onyx-task vpeer-id)
                    [:group-id :messaging-group :monitoring :logging-config])}))

(defn onyx-peer-group
  ([peer-config]
   (onyx-peer-group peer-config {:monitoring :no-op}))
  ([peer-config monitoring-config]
   (map->OnyxPeerGroup
    {:config peer-config
     :logging-config (logging-config/logging-configuration peer-config)
     :monitoring (component/using (extensions/monitoring-agent monitoring-config) [:logging-config])
     ;:messaging-group (component/using (am/aeron-peer-group peer-config) [:logging-config])
     :messaging-group (component/using (atom-messenger/atom-peer-group peer-config) [:logging-config])
     :peer-group-manager (component/using (pgm/peer-group-manager peer-config onyx-vpeer-system) 
                                          [:logging-config :monitoring :messaging-group])})))

(defrecord OnyxPeerGroupManager []
  component/Lifecycle
  (start [component]
    (rethrow-component
     #(component/start-system component task-components)))
  (stop [component]
    (rethrow-component
     #(component/stop-system component task-components))))

(defmethod clojure.core/print-method OnyxPeer
  [system ^java.io.Writer writer]
  (.write writer "#<Onyx Peer>"))

(defmethod clojure.core/print-method OnyxPeerGroup
  [system ^java.io.Writer writer]
  (.write writer "#<Onyx Peer Group>"))

(defmethod clojure.core/print-method OnyxTask
  [system ^java.io.Writer writer]
  (.write writer "#<Onyx Task>"))
