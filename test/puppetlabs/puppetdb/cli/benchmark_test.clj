(ns puppetlabs.puppetdb.cli.benchmark-test
  (:require [clojure.pprint]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.cli.benchmark :as benchmark]
            [puppetlabs.puppetdb.client :as client]
            [puppetlabs.puppetdb.lint :refer [ignore-value]]
            [puppetlabs.puppetdb.nio :refer [copts copt-replace get-path]]
            [puppetlabs.trapperkeeper.config :as config]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.cli :refer [get-nodes example-catalog
                                                       example-report example-facts
                                                       example-certname]]
            [puppetlabs.puppetdb.time :as time]
            [puppetlabs.puppetdb.utils :as utils :refer [with-captured-throw]]
            [puppetlabs.kitchensink.core :as ks])
  (:import
   [clojure.lang ExceptionInfo]
   [java.nio.file Files]))

(defn mock-submit-record-fn [submitted-records entity]
  (fn [base-url _certname version payload-string ssl-opts]
    (swap! submitted-records conj
           {:entity entity
            :base-url base-url
            :version version
            :payload-string payload-string
            :payload (keywordize-keys payload-string)
            :ssl-opts ssl-opts})))

(defn call-with-benchmark-status
  [config cli-args f]
  (let [submitted-records (atom [])]
    (with-redefs [client/submit-catalog (mock-submit-record-fn submitted-records
                                                               :catalog)
                  client/submit-report (mock-submit-record-fn submitted-records
                                                              :report)
                  client/submit-facts (mock-submit-record-fn submitted-records
                                                             :factset)
                  config/load-config (fn [_] config)
                  ;; This normally calls System/exit on a cli error;
                  ;; we'd rather have the exception.
                  utils/try-process-cli (fn ([f] (f)) ([f _] (f)))
                  benchmark/benchmark-shutdown-timeout tu/default-timeout-ms
                  ;; disable catalog/reports submission delay to avoid slowing down tests
                  benchmark/random-cmd-delay (constantly 0)]
      (f submitted-records (benchmark/benchmark-wrapper cli-args)))))

(deftest progressing-timestamp-nummsgs
  (doseq [end-in [0 -3 3 14]]
    (let [now (time/now)
          end-in-days (time/days end-in)
          get-timestamp (benchmark/progressing-timestamp 1 (* 14 48) 30 end-in-days)
          initial-timestamp (get-timestamp)]
      (is (time/before? (-> end-in-days
                            time/from-now
                            (time/minus (time/days 14))) initial-timestamp))
      (is (time/after? (-> now
                           (time/plus (time/days end-in))
                           (time/minus (time/days 14))
                           (time/plus (time/minutes 31))) initial-timestamp))
      ;; start at 2 because of first and last invocations
      (doseq [_ (range 2 (* 14 48))]
        (get-timestamp))
      (let [final-timestamp (get-timestamp)
            before-time (time/plus now end-in-days)
            after-time (time/plus (time/now) end-in-days)]
        (is (or (time/equal? before-time final-timestamp)
                (time/before? before-time final-timestamp)))
        (is (or (time/equal? after-time final-timestamp)
                (time/after? after-time final-timestamp)))))))

(defn benchmark-nummsgs
  [config & cli-args]
  ;; Assumes cli-args does not indicate a --runinterval) run
  (call-with-benchmark-status config cli-args
                              (fn [submitted {:keys [join]}]
                                (is (= true (join tu/default-timeout-ms)))
                                @submitted)))

(deftest config-is-required
  (let [x (with-captured-throw (benchmark-nummsgs {}))]
    (is (= ExceptionInfo (class x)))
    (when (= ExceptionInfo (class x))
      (is (= ::ks/cli-error (:kind (ex-data x))))
      (is (str/includes? (:msg (ex-data x))
                         "Missing required argument '--config'")))))

(deftest numhosts-is-required
  (let [x (with-captured-throw (benchmark-nummsgs {} "--config" "anything.ini"))]
    (is (= ExceptionInfo (class x)))
    (when (= ExceptionInfo (class x))
      (is (= ::ks/cli-error (:kind (ex-data x))))
      (is (str/includes? (:msg (ex-data x))
                         "Missing required argument '--numhosts'")))))

(deftest nummsgs-or-runinterval-is-required
  (let [x (with-captured-throw (benchmark-nummsgs {}
                                                  "--config" "anything.ini"
                                                  "--numhosts" "42"))]
    (is (= ExceptionInfo (class x)))
    (when (= ExceptionInfo (class x))
      (is (= ::ks/cli-error (:kind (ex-data x))))
      (is (str/includes? (:msg (ex-data x))
                         "Error: Either -N/--nummsgs or -i/--runinterval is required.")))))

(deftest runs-with-runinterval
  (call-with-benchmark-status
   {}
   ["--config" "anything.ini" "--numhosts" "333" "--runinterval" "1"]
   (fn [submitted {:keys [stop]}]
     (let [enough-records (* 3 42)
           finished (promise)
           watch-key (Object.)
           watcher (fn [_k _ref _old new]
                     (when (>= (count new) enough-records)
                       (deliver finished true)))]
       (add-watch submitted watch-key watcher)
       (when-not (>= (count @submitted) enough-records) ; avoid add-watch race
         (deref finished tu/default-timeout-ms nil))
       (is (>= (count @submitted) enough-records))
       (stop)))))

(deftest multiple-messages-and-hosts
  (let [numhosts 2
        nummsgs 3
        submitted (benchmark-nummsgs {}
                                     "--config" "anything.ini"
                                     "--numhosts" (str numhosts)
                                     "--nummsgs" (str nummsgs))]
    (is (= (* numhosts nummsgs 3) (count submitted)))))

(deftest toggle-catalog-edges
  (testing "default: no catalog edges included"
    (let [submitted (benchmark-nummsgs {}
                                       "--config" "anything.ini"
                                       "--numhosts" "2"
                                       "--nummsgs" "3")
          catalogs (filter #(= :catalog (:entity %))
                           submitted)]

      (is (every? (fn [c] (zero? (count (get-in c [:payload :edges]))))
                  catalogs))))

  (testing "catalog edges included when requested"
    (let [submitted (benchmark-nummsgs {}
                                       "--config" "anything.ini"
                                       "--numhosts" "2"
                                       "--nummsgs" "3"
                                       "--include-catalog-edges")
          catalogs (filter #(= :catalog (:entity %))
                           submitted)]

      (is (every? (fn [c] (pos? (count (get-in c [:payload :edges]))))
                  catalogs)))))

(deftest archive-flag-works
  (let [export-out-file (.getPath (tu/temp-file "benchmark-test" ".tar.gz"))]
    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (is (empty? (get-nodes)))

       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname "replace catalog" 8 example-catalog)
       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname "store report" 7 example-report)
       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname "replace facts" 4 example-facts)

       (let [r (svc-utils/get (svc-utils/admin-url-str "/archive")
                              {:as :stream :decompress-body false})]
         (if-not (= 200 (:status r))
           (do
             (binding [*out* *err*]
               (println "Unable to retrieve pdb archive:")
               (clojure.pprint/pprint r))
             (is (= 200 (:status r))))
           (do
             (ignore-value
              (Files/copy ^java.io.InputStream (:body r)
                          (get-path export-out-file)
                          (copts [copt-replace])))
             (let [numhosts 2
                   nummsgs 3
                   submitted (benchmark-nummsgs {}
                                                "--config" "anything.ini"
                                                "--numhosts" (str numhosts)
                                                "--nummsgs" (str nummsgs)
                                                "--archive" export-out-file)]
               (is (= (* numhosts nummsgs 3) (count submitted)))))))))))

(deftest consecutive-reports-are-distinct
  (let [submitted (benchmark-nummsgs {}
                                     "--config" "anything.ini"
                                     "--numhosts" "1"
                                     "--nummsgs" "10")
        reports (->> submitted
                     (filter #(= :report (:entity %)))
                     (map :payload))]
    (is (= 10 (->> reports (map :configuration_version) distinct count)))
    (is (= 10 (->> reports (map :start_time) distinct count)))
    (is (= 10 (->> reports (map :end_time) distinct count)))))

(deftest randomize-catalogs-and-factsets
  (let [submitted (benchmark-nummsgs {}
                                     "--config" "anything.ini"
                                     "--numhosts" "1"
                                     "--nummsgs" "10"
                                     "--rand-perc" "100")
        catalog-hashes (->> submitted
                            (filter #(= :catalog (:entity %)))
                            (map :payload)
                            (map hash))
        factset-hashes (->> submitted
                            (filter #(= :factset (:entity %)))
                            (map :payload)
                            (map hash))]
    (is (= 10 (count (distinct catalog-hashes))))
    (is (= 10 (count (distinct factset-hashes))))))

(deftest all-hosts-are-present
  (let [submitted (benchmark-nummsgs {}
                                     "--config" "anything.ini"
                                     "--numhosts" "100"
                                     "--nummsgs" "3")
        catalogs-per-host (->> submitted
                               (filter #(= :catalog (:entity %)))
                               (map :payload)
                               (group-by :certname)
                               (#(update-vals % count)))]
    (is (= 100 (count catalogs-per-host)))
    ; We should see at least 2 catalogs for every host accounting for potential
    ; jitter in the simulation timer.
    (is (every? #(>= % 2) (vals catalogs-per-host)))))

(deftest benchmark-runs-at-correct-rate
  (call-with-benchmark-status
   {}
   ["--config" "anything.ini"
    "--numhosts" "600"
    "--runinterval" "1"]
   ;; This should generate 30 messages per second (10 hosts per second, 3
   ;; messages per host).
   (fn [submitted {:keys [stop]}]
     (let [start (System/currentTimeMillis)
           enough-records (* 3 30) ; 3 seconds
           finished (promise)
           watch-key (Object.)
           watcher (fn [_k _ref _old new]
                     (when (>= (count new) enough-records)
                       (deliver finished true)))]
       (add-watch submitted watch-key watcher)
       (when-not (>= (count @submitted) enough-records) ; avoid add-watch race
         (deref finished tu/default-timeout-ms nil))
       ;; Allow a ~33% margin of error to account for jitter in the simulation
       ;; timer.
       (let [elapsed (/ (- (System/currentTimeMillis) start) 1000.0)]
         (is (<= 2 elapsed 4)))
       (stop)))))

(deftest rand-catalog-mutation-keys
  (let [catalog {"certname"           "host-1"
                 "catalog_uuid"       "512d24ae-8999-4f12-bda0-1e5d57c0b5cc"
                 "producer"           "puppet-primary-1"
                 "hash"               "a923c88272cbf195c4c1f3138090a1c6a64712a3"
                 "transaction_uuid"   "65998cdc-a66f-40a9-b4fd-2b4c51384764"
                 "producer_timestamp" "2023-07-20T17:00:11.947Z"
                 "environment"        "production"
                 "code_id"            "100891c7504e899c86bad9ce60c27b29ae5c21ec"
                 "version"            "1690231634"
                 "resources"          [{"type"  "Class"
                                        "title" "aclass"
                                        "file"  "thing"
                                        "line"  123
                                        "tags"  ["one" "two"]
                                        "parameters" {"a" "one"
                                                      "b" "two"}}
                                       {"type"  "Atype"
                                        "title" "atypetitle"
                                        "file"  "otherthing"
                                        "line"  456
                                        "tags"  ["three" "four"]
                                        "parameters" {"c" "one"
                                                      "d" "two"}}]
                 "edges"              [{"source" {"type"  "Class"
                                                  "title" "aclass"}
                                        "target" {"type"  "Atype"
                                                  "title" "atypetitle"}
                                        "relationship" "contains"}]
                 "job_id"             nil}
        mutated (benchmark/rand-catalog-mutation catalog true)
        checked-keys (clojure.walk/walk (fn [[k _]] [k (string? k)]) identity mutated)
        symbol-keys (filter (fn [[_ is-string]] (not is-string)) checked-keys)]
    (is (empty? symbol-keys) "Mutating a catalog unexpectedly produced these keys as symbols instead of strings.")))
