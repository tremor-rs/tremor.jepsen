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
             [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [knossos.model :as model]
            [slingshot.slingshot :refer [try+]]))



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
             :chdir   dir}
            binary
            :cluster]
           (if is-first
             [:init]
             [:join :--leader (api-url first-node)])
           [:--db-dir "/opt/tremor/db"
            :--rpc  (rpc-url node)
            :--api (api-url node)]))
         (Thread/sleep 5000))))
    (teardown! [_ test node]
      (info node "tearing down /opt/tremor")
      (cu/stop-daemon! binary pidfile)
      (c/su (cu/grepkill! :tremor)
            (c/exec :rm :-rf "/opt/tremor/db")))))

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
        r (http/post endpoint {:body body
                               :as :json
                               :content-type :json
                               :accept :json})]
    (info "=> " (:body r))
    {:body (decode-body (:body r))
     :status (:status r)}))

(defn tremor-put [url key val]
  (let [endpoint (str url "api/write")
        val (json/write-str val)
        body (json/write-str {:Set {:key key :value val}})
        _ (info "POST: " endpoint body)
        r (http/post
           endpoint
           {:body body
            :as :json
            :content-type :json
            :accept :json})]
    (info "=> " (:body r))
    {; we currently don't return a body
     ;:body (decode-body (:body r))
     :status (:status r)}))


(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :url (client-url node)))

  (setup! [this test])

  (invoke! [this test op]
    (case (:f op)
      :read (let [value (:body (tremor-get (:url this) "snot"))
                  _ (info "value:" value)]
              (assoc
               op
               :type :ok
               :value value))
      :write (do
               (tremor-put (:url this) "snot" (:value op))
               (assoc op :type :ok))))

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
          :db   (db "0.12.4")
          :client (Client. nil)
          :checker         (checker/linearizable
                            {:model     (model/cas-register)
                             :algorithm :linear})
          :generator       (->> (gen/mix [r w])
                                (gen/stagger 0.1)
                                (gen/nemesis nil)
                                (gen/time-limit 30))}))

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


(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn tremor-test})
                   (cli/serve-cmd))
            args))

