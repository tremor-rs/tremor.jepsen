(defproject tremor.jepsen "0.1.0-SNAPSHOT"
  :description "Tremor jepsen tests"
  :url "https://tremor.rs"
  :license {:name "ASL-2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [jepsen "0.2.7"]]
  :main tremor.jepsen
  :repl-options {:init-ns tremor.jepsen})
