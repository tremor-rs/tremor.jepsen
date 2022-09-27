(ns tremor.jepsen
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [jepsen [checker :as checker]
             [cli :as cli]
             [client :as client]
             [control :as c]
             [db :as db]
             [generator :as gen]
             [independent :as independent]
             [nemesis :as nemesis]
             [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [jepsen.checker.timeline :as timeline]
            [knossos.model :as model]
            [slingshot.slingshot :refer [try+ throw+]]
            [jepsen.independent :as independent]))



;; (ns tremor.jepsen
;;   (:require [clojure.tools.logging :refer :all]
;;             [clojure.string :as str]
;;             [clj-http.client :as http]
;;             [jepsen
;;              [checker :as checker]
;;              [cli :as cli]
;;              [client :as client]
;;              [control :as c]
;;              [db :as db]
;;              [generator :as gen]
;;              [tests :as tests]]
;;             [jepsen.control.util :as cu]
;;             [knossos.model :as model]
;;             [jepsen.os.debian :as debian]))

(def dir "/opt/tremor")
(def binary "bin/tremor")
(def logfile (str dir "/tremor.log"))
(def pidfile (str dir "/etcd.pid"))

(defn node-url
  "An HTTP url for connecting to a node on a particular port."
  [node port]
  (str node ":" port))

(defn rpc-url
  "The HTTP url for other peers to talk to a node."
  [node]
  (node-url node 8080))

(defn api-url
  "The HTTP url for other peers to talk to a node."
  [node]
  (node-url node 9898))

(defn db
  "Tremor for a particular version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (let
       [node-id (+ (.indexOf (:nodes test) node) 1)
        is-first (= node-id 1)
        first-node (first (:nodes test))]
        (info node "installing /opt/tremor" version)
        (c/su
         (let
         ;[url (str "https://github.com/tremor-rs/tremor-runtime/releases/download/v" version "/tremor-" version "-x86_64-unknown-linux-gnu.tar.gz")]
          [url (str "file:///var/packages/" "tremor-" version "-x86_64-unknown-linux-gnu.tar.gz")]
           (cu/install-archive! url dir))
         ; stagger the joins
         (Thread/sleep (* (- node-id 1) 1000))
         (apply
          cu/start-daemon!
          (concat
           [{:logfile logfile
             :pidfile pidfile
             :chdir   dir
             :env {:RUST_LOG "info,tarpc=warn"}}
            binary
            :cluster]
           (if is-first
             [:bootstrap]
             [:start :--join (api-url first-node)])
           [:--db-dir "/opt/tremor/db"
            :--rpc  (rpc-url node)
            :--api (api-url node)]))
         (Thread/sleep 5000))))
    (teardown! [_ test node]
      (info node "tearing down /opt/tremor")
      (cu/stop-daemon! binary pidfile)
      (c/su (cu/grepkill! :tremor)
            (c/exec :rm :-rf "/opt/tremor/db")))
    db/LogFiles
    (log-files [_ test node]
      [logfile])))

(defn client-url
  "The HTTP url clients use to talk to a node."
  [node]
  (str "http://" (api-url node) "/"))


(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})


(defn decode-body [body]
  (info "body:" body)
  (if (= body nil)
    nil
    (Long/parseLong body)))


(defn tremor-get [url key]
  (let [endpoint (str url "api/consistent_read")
        ; endpoint (str url "api/read")
        body (json/write-str key)
        _ (info "POST: " endpoint body)
        r (try+ (let [response (http/post endpoint {:body body
                                                    :as :json
                                                    :content-type :json
                                                    :accept :json
                                                    :socket-timeout 5000
                                                    :connection-timeout 5000})] {:type :ok :value (decode-body (:body response))})
                (catch [:status 503] {:keys [request-time body]}
                  (error "503 Service Unavailable" request-time body)
                  {:type :fail :body body :status 503 :error :no-quorum}) ; this is only thrown when the node knows it doesn't have a leader/quorum
                (catch [:status 500] {:keys [request-time body]}
                  (error "500 Internal Server Error" request-time body)
                  {:type :fail :body body :status 500 :error :server-error})
                (catch Exception x (error "Error reading a value" x) {:type :fail :error (. x toString)}))]
    (info "=> " r)
    r))

(defn tremor-put [url key val]
  (let [endpoint (str url "api/write")
        val (json/write-str val)
        body (json/write-str {:key key :value val})
        _ (info "POST: " endpoint body)
        r (try+ (let [response (http/post
                                endpoint
                                {:body body
                                 :as :json
                                 :content-type :json
                                 :accept :json
                                 :socket-timeout 5000      ;; in milliseconds
                                 :connection-timeout 5000  ;; in milliseconds
                                 })]{:type :ok :body (:body response)})
                (catch [:status 503] {:keys [request-time body]}
                  (error "503 Service Unavailable" request-time body)
                  {:type :fail :body body :status 503}) ; this is only thrown when the node knows it doesn't have a leader/quorum
                (catch [:status 500] {:keys [request-time body]}
                  (error "500 Internal Server Error" request-time body)
                  {:type :info :body body :status 503}) ; we don't know if the write took place or not
                (catch java.net.SocketTimeoutException _
                  (error "Write Timed out")
                  {:type :info :error :timeout}) ; we don't know if the write took place or not
                (catch java.net.ConnectException _
                  (error "Connection refused")
                  {:type :fail :error :conn}) ; we know the write didnt take place
                (catch Exception x
                  (error "Error writing a value" x)
                  (throw+)))] ; we cannot know if the write succeeded or not
    (info "=> " r)
    r))


(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :url (client-url node) :node node))

  (setup! [this test])

  (invoke! [this test op]
    (let [[key value] (:value op)]
      (case (:f op)
        :read (let [res (tremor-get (:url this) (str key))
                    value (:value res)
                    indep_res (merge res {:value (independent/tuple key value) :node (:node this)})]
                (merge op indep_res))
        :write (do
                 (let [result (tremor-put (:url this) (str key) value)]
                   (merge op result {:node (:node this)}))))))

  (teardown! [this test])

  (close! [_ test]))

(defn tremor-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         opts
         {:pure-generators true
          :name "tremor"
          :os   debian/os
          :db   (db "0.13.0-rc.2")
          :client (Client. nil)
          :checker         (checker/compose {:perf (checker/perf)
                                             :indep (independent/checker
                                                     (checker/compose
                                                      {:linear (checker/linearizable {:model     (model/register)
                                                                                      :algorithm :linear})
                                                       :timeline (timeline/html)}))})
          :generator       (->> (independent/concurrent-generator
                                 10
                                 (range)
                                 (fn [key] (->> (gen/mix [r w])
                                                (gen/stagger (/ (:rate opts)))
                                                (gen/limit (:ops-per-key opts)))))
                                (gen/nemesis
                                 (cycle [(gen/sleep 5)
                                         {:type :info :f :start}
                                         (gen/sleep 5)
                                         {:type :info :f :stop}]))
                                (gen/time-limit (:time-limit opts)))
          :nemesis    (nemesis/partition-random-halves)}))

(defn node-url
  "An HTTP url for connecting to a node on a particular port."
  [node port]
  (str node ":" port))


;; (defn initial-cluster
;;   "Constructs an initial cluster string for a test, like
;;   \"foo=foo:2380,bar=bar:2380,...\""
;;   [this-node test]
;;   (str "--cluster-peer " (first (:nodes test)))
;;   (->>  (:nodes test)
;;         (map (fn [node]
;;                (str node "=" (peer-url node))))
;;         (str/join ",")))

(def cli-opts
  "tremor-test command line options"
  [["-r" "--rate HZ" "approximate number of requests per second, per thread" :default 10 :parse-fn read-string :validate [#(and (number? %) (pos? %)) "Must be a positive number"]]
   [nil "--ops-per-key NUM" "Maximum number of operations on any given key." :default 100 :parse-fn parse-long :validate [pos? "Must be a positive integer."]]])

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn tremor-test :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))

