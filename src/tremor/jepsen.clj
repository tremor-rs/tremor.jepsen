(ns tremor.jepsen
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen [cli :as cli]
             [control :as c]
             [db :as db]
             [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(def dir "/opt/tremor")

(defn db
  "Tremor for a particular version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "installing /opt/tremor" version)
      (c/su
       (let [url (str "https://github.com/tremor-rs/tremor-runtime/releases/download/v" version "/tremor-" version "-x86_64-unknown-linux-gnu.tar.gz")]
         (cu/install-archive! url dir))))
    (teardown! [_ test node]
      (info node "tearing down /opt/tremor"))))


(defn tremor-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         opts
         {:name "tremor"
          :os   debian/os
          :db   (db "0.9.5-rc.2")
          :pure-generators true}))

(defn node-url
  "An HTTP url for connecting to a node on a particular port."
  [node port]
  (str "http://" node ":" port))

(defn peer-url
  "The HTTP url for other peers to talk to a node."
  [node]
  (node-url node 8080))

(defn client-url
  "The HTTP url clients use to talk to a node."
  [node]
  (node-url node 8080))

(defn initial-cluster
  "Constructs an initial cluster string for a test, like
  \"foo=foo:2380,bar=bar:2380,...\""
  [test]
  (->> (:nodes test)
       (map (fn [node]
              (str node "=" (peer-url node))))
       (str/join ",")))

(def dir     "/opt/tremor")
(def binary "tremor")
(def logfile (str dir "/var/log/tremor/tremor.log"))
(def pidfile (str dir "/etcd.pid"))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn tremor-test})
                   (cli/serve-cmd))
            args))

