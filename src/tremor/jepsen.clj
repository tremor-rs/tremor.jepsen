(ns tremor.jepsen
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [clj-http.client :as http]
            [jepsen [cli :as cli]
             [client :as client]
             [control :as c]
             [db :as db]
             [generator :as gen]
             [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(def dir "/opt/tremor")
(def binary "bin/tremor")
(def logfile (str dir "/tremor.log"))
(def pidfile (str dir "/etcd.pid"))

(defn node-url
  "An HTTP url for connecting to a node on a particular port."
  [node port]
  (str node ":" port))

(defn peer-url
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
          [url (str "file:///var/packages/" version "/tremor-" version "-x86_64-unknown-linux-gnu.tar.gz")]
           (cu/install-archive! url dir))
         (apply
          cu/start-daemon!
          (concat
           [{:logfile logfile
             :pidfile pidfile
             :chdir   dir}
            binary
            :--instance node-id
            :server
            :run
            :-p pidfile
            :--cluster-host  (peer-url node)
            :--api-host (api-url node)]
           (map (fn [node] [:--cluster-peer (peer-url node)])
                (filter #(not= node %1) (:nodes test)))
           (if is-first
             [:--cluster-bootstrap]
             [:--cluster-peer (peer-url first-node)]))))
        (if (not is-first)
          (do
            (c/exec "/bin/sleep" (* 2 node-id)) ;; FIXME this is really bad :tm:
            (info node "running" (str "/usr/bin/curl -vv -XPOST" (str  " http://" (api-url first-node) "/cluster/" node-id)))
            (c/exec "/usr/bin/curl" :-vv  :-XPOST (str  "http://" (api-url first-node) "/cluster/" node-id)))
          ())))
    (teardown! [_ test node]
      (info node "tearing down /opt/tremor"))))

(defn client-url
  "The HTTP url clients use to talk to a node."
  [node]
  (str "http://" (node-url node 9898) "/"))


(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})


(defn decode-body [body]
  (if (= body "not found")
    nil
    (Long/parseLong body)))

(defn tremor-get [url path]
  (let [endpoint (str url "kv/" path)
        r (http/get endpoint {:accept :json :as :json})]
    (info "GET: " endpoint)
    {:body (decode-body (:body r))
     :status (:status r)}))

(defn tremor-put [url path val]
  (let [endpoint (str url "kv/" path)
        r (http/post
           endpoint
           {:form-params {:value (str val)}
            :as :json
            :content-type :json
            :accept :json})]
    (info "POST: " endpoint)
    {:body (decode-body (:body r))
     :status (:status r)}))


(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :url (client-url node)))

  (setup! [this test])

  (invoke! [this test op]
    (case (:f op)
      :read (assoc
             op
             :type :ok
             :value (:body (tremor-get (:url this) "snot")))
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
          :db   (db "0.9.5-rc.2")
          :client (Client. nil)
          :generator       (->> (gen/mix [r w])
                                (gen/stagger 1)
                                (gen/nemesis nil)
                                (gen/time-limit 15))}))

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

