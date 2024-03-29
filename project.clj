(defproject tremor.jepsen "0.1.0-SNAPSHOT"
  :description "Tremor jepsen tests"
  :url "https://tremor.rs"
  :license {:name "ASL-2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/data.json "2.4.0"]
                 [org.clojure/core.match "1.0.1"]
                 [org.clojure/tools.logging "1.2.4"]
                 [jepsen "0.3.2"]
                 [clj-http "3.12.1"]
                 [cheshire "5.9.0"]]
  :main tremor.jepsen
  :repl-options {:init-ns tremor.jepsen})
