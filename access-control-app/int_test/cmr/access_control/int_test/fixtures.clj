(ns cmr.access-control.int-test.fixtures
  (:require [cmr.transmit.access-control :as ac]
            [clojure.test :as ct]
            [cmr.transmit.config :as config]
            [cmr.transmit.metadata-db2 :as mdb]
            [cmr.access-control.system :as system]
            [cmr.access-control.config :as access-control-config]
            [cmr.access-control.test.util :refer [conn-context]]
            [cmr.elastic-utils.test-util :as elastic-test-util]
            [cmr.metadata-db.system :as mdb-system]
            [cmr.mock-echo.system :as mock-echo-system]
            [cmr.mock-echo.client.mock-echo-client :as mock-echo-client]
            [cmr.mock-echo.client.mock-urs-client :as mock-urs-client]
            [cmr.mock-echo.client.echo-util :as e]
            [cmr.common-app.test.client-util :as common-client-test-util]
            [cmr.metadata-db.system :as mdb-system]
            [cmr.metadata-db.config :as mdb-config]
            [cmr.metadata-db.data.memory-db :as memory]
            [cmr.message-queue.queue.memory-queue :as mem-queue]
            [cmr.message-queue.config :as rmq-conf]
            [cmr.message-queue.test.queue-broker-wrapper :as queue-broker-wrapper]
            [cmr.message-queue.test.queue-broker-side-api :as qb-side-api]
            [cmr.common.jobs :as jobs]))

(defn queue-config
  []
  (rmq-conf/merge-configs (mdb-config/rabbit-mq-config)
                          (access-control-config/rabbit-mq-config)))

(defn create-memory-queue-broker
  []
  (mem-queue/create-memory-queue-broker (queue-config)))

(defn create-mdb-system
  "Creates an in memory version of metadata db."
  ([]
   (create-mdb-system false))
  ([use-external-db]
   (let [mdb-sys (mdb-system/create-system)]
     (merge mdb-sys
            {:scheduler (jobs/create-non-running-scheduler)}
            (when-not use-external-db
              {:db (memory/create-db)})))))


(defn int-test-fixtures
  "Returns test fixtures for starting the access control application and its external dependencies.
   The test fixtures only start up applications and side APIs if it detects the applications are not
   already running on the ports requested. This allows the tests to be run in different scenarios
   and still work. The applications may already be running in dev-system or through user.clj If they
   are running these fixtures won't do anything. If it isn't running these fixtures will start up the
   applications and the test will work."
  []
  (let [queue-broker (queue-broker-wrapper/create-queue-broker-wrapper (create-memory-queue-broker))]
    (ct/join-fixtures
      [elastic-test-util/run-elastic-fixture
       (common-client-test-util/run-app-fixture
         conn-context
         :access-control
         (assoc (system/create-system) :queue-broker queue-broker)
         system/start
         system/stop)

       ;; Create a side API that will allow waiting for the queue broker terminal states to be achieved.
       (common-client-test-util/side-api-fixture
         (fn [_]
           (qb-side-api/build-routes queue-broker))
         nil)

       (common-client-test-util/run-app-fixture
         conn-context
         :echo-rest
         (mock-echo-system/create-system)
         mock-echo-system/start
         mock-echo-system/stop)

       (common-client-test-util/run-app-fixture
         conn-context
         :metadata-db
         (assoc (create-mdb-system) :queue-broker queue-broker)
         mdb-system/start
         mdb-system/stop)])))

(defn reset-fixture
  "Test fixture that resets the application before each test and creates providers and users listed.
  provider-map should be a map of provider guids to provider ids. usernames should be a list of usernames
  that exist in URS. The password for each username will be username + \"pass\"."
  ([]
   (reset-fixture nil))
  ([provider-map]
   (reset-fixture provider-map nil))
  ([provider-map usernames]
   (fn [f]
     (mock-echo-client/reset (conn-context))
     (mdb/reset (conn-context))
     (ac/reset (conn-context))
     (doseq [[provider-guid provider-id] provider-map]
       (mdb/create-provider (assoc (conn-context) :token (config/echo-system-token))
                            {:provider-id provider-id}))
     (e/create-providers (conn-context) provider-map)

     (when (seq usernames)
       (mock-urs-client/create-users (conn-context) (for [username usernames]
                                                      {:username username
                                                       :password (str username "pass")})))

     ;; TODO Temporarily granting all admin. Remove this when implementing  CMR-2133, CMR-2134
     (e/grant-all-admin (conn-context))

     (f))))

(defn grant-all-group-fixture
  "Returns a test fixture function which grants all users the ability to create and modify groups for given provider guids."
  [provider-guids]
  (fn [f]
    (e/grant-system-group-permissions-to-all (conn-context))
    (doseq [provider-guid provider-guids]
      (e/grant-provider-group-permissions-to-all (conn-context) provider-guid))
    (f)))