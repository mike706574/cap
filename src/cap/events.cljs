(ns cap.events
  (:require [ajax.core :as ajax]
            [cemerick.url :as url]
            [clojure.spec.alpha :as s]
            [re-frame.core :as rf]
            [re-frame.interceptor :refer [assoc-effect ->interceptor]]
            [taoensso.timbre :as log]))

(s/def :cap/category (s/or :nil nil? :keyword keyword?))

(defmulti db-status :cap/status)

(defmethod db-status :ok [_]
  (s/keys :req [:cap/status
                :cap/category]))

(s/def :cap/error map?)
(s/def :cap/context string?)

(defmethod db-status :error [_]
  (s/keys :req [:cap/status
                :cap/context
                :cap/error]))

(s/def :cap/db (s/multi-spec db-status :cap/status))

(defn check-and-throw
  "throw an exception if db doesn't match the spec"
  [a-spec db]
  (when-not (s/valid? a-spec db)
    (cljs.pprint/pprint db)
    (throw (ex-info (str "spec check failed: " (s/explain-str a-spec db)) {}))))

(defn spec-interceptor
  [spec f]
  (->interceptor
   :id :spec
   :after (fn spec-validation
            [context]
            (let [db (get-in context [:effects :db])]
              (if (s/valid? spec db)
                context
                (let [updated-context (-> context
                                          (assoc-effect :db db)
                                          (assoc :queue #queue []))]
                  (println (str "spec check failed: " (s/explain-str spec db)) {})
                  (cljs.pprint/pprint (dissoc updated-context :queue :stack
                                              ))
                  updated-context))))))

(def my-spec-interceptor
  (spec-interceptor
   :cap/db
   (fn [db event data]
     (merge db {:cap/status :error
                :cap/context (str " handling event " event)
                :cap/error data}))))

(def interceptors [my-spec-interceptor rf/debug])

(def token-url "http://localhost:8001/api/tokens")

(rf/reg-event-fx
 :fetch-token
 interceptors
 (fn [{db :db} _]
   {:http-xhrio {:method :post
                 :uri token-url
                 :params {:bottle/username "mike"
                          :bottle/password "rocket"}
                 :format (ajax/transit-request-format)
                 :response-format (ajax/raw-response-format)
                 :on-success [:token-success]
                 :on-failure [:token-failure]}}))

(rf/reg-event-db
 :token-success
 (fn [db [_ token]]
   (log/debug "Retrieved token:" token)
   (assoc db :cap/token token)))

(rf/reg-event-db
 :token-failure
 (fn [db [_ failure]]
   {:cap/status :error
    :cap/context "fetching token."
    :cap/error failure}))

(rf/reg-event-db
 :set-category
 interceptors
 (fn [db [_ category]]
   (assoc db :cap/category category)))

(rf/reg-event-fx
 :fetch-events
 interceptors
 (fn [{db :db} _]
   (let [category (:cap/category db)]
     {:db (assoc db :events [])})))

(rf/reg-event-fx
 :boot
 interceptors
 (fn [{db :db} _]
   (let [url (-> js/window .-location .-href url/url)
         category (keyword (get-in url [:query "category"]))]
     {:db {:cap/status :booting
           :cap/category (if-let [existing-category (:cap/category db)]
                           existing-category
                           category)}
      :async-flow {:first-dispatch [:fetch-token]
                   :rules [{:when :seen-any-of? :events [:token-failure] :halt? true}]}})))
